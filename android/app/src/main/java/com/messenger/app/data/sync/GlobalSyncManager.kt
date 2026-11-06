package com.messenger.app.data.sync

import android.util.Log
import com.messenger.app.data.api.SyncSource
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "GlobalSyncManager"
private const val SYNC_PAGE_LIMIT = 100
enum class SyncPhase { IDLE, RUNNING, FAILED }

/**
 * The "correctness path": drives `GET /sync` (the device-scoped, durable
 * cursor over `messages.sequence`) forward through [MessageIngestionPipeline]
 * until fully caught up. Triggered on login/app-start and on every WebSocket
 * reconnect - the WS fast path only has to deliver messages *while
 * connected*; this is what guarantees nothing is permanently lost across
 * network loss, force-stop, reboot, or backend unavailability.
 *
 * Overlapping requests are coalesced into one trailing pass. A reconnect must
 * not be discarded while an older request is about to fail or finish.
 */
class GlobalSyncManager(
    private val syncApi: SyncSource,
    private val ingestionPipeline: MessageIngestor,
    private val authRepository: AuthRepository,
    private val tokenStore: SyncCursorStore,
    private val clearHistory: suspend (Long, Long, Int) -> Boolean = { _, _, _ -> false },
) {
    var beforeSync: suspend (String, Int) -> Boolean = { _, _ -> true }
    private val mutex = Mutex()
    private val _phase = kotlinx.coroutines.flow.MutableStateFlow(SyncPhase.IDLE)
    val phase: kotlinx.coroutines.flow.StateFlow<SyncPhase> = _phase

    private val requests = java.util.concurrent.atomic.AtomicLong()

    suspend fun runCatchUpSync() {
        requests.incrementAndGet()
        while (true) {
            if (!mutex.tryLock()) return
            val observed = requests.get()
            _phase.value = SyncPhase.RUNNING
            try {
                _phase.value = if (runOnePass()) SyncPhase.IDLE else SyncPhase.FAILED
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                _phase.value = SyncPhase.IDLE
                throw cancel
            } catch (error: Exception) {
                _phase.value = SyncPhase.FAILED
                Log.w(TAG, "sync unavailable: ${error::class.simpleName}")
            } finally {
                mutex.unlock()
            }
            if (requests.get() == observed) return
        }
    }

    /** Explicit fail-closed barrier for restoration: never import stale history
     * when the latest server clear/delete watermarks could not be obtained. */
    suspend fun awaitHistoryRestoreSync() = mutex.withLock {
        check(runOnePass()) { "Current server history state unavailable" }
    }

    private suspend fun runOnePass(): Boolean {
        val epoch = authRepository.currentSessionEpoch()
        val token = authRepository.currentAccessToken() ?: return false
        if (!beforeSync(token, epoch)) return false
        var after = tokenStore.lastSyncedSequence()
        while (true) {
            val response = try {
                syncApi.sync(token, after, limit = SYNC_PAGE_LIMIT)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Log.w(TAG, "sync page failed: ${e::class.simpleName}")
                return false
            }
            if (epoch != authRepository.currentSessionEpoch()) return false
            try {
                response.historyClearedBeforeMs?.let { if (!clearHistory(it, response.historyClearedThroughSequence ?: return false, epoch)) return false }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (e: Exception) { Log.w(TAG, "history clear failed: ${e::class.simpleName}"); return false }
            if (epoch != authRepository.currentSessionEpoch()) return false
            if (!ingestionPipeline.deleteChats(response.deletedChatIds)) return false
            val result = ingestionPipeline.ingest(response.messages)
            if (!result.advancedFully) return false // session changed mid-sync; abort silently
            val next = maxOf(response.nextSequence, result.highestSequence)
            if (response.hasMore && next <= after) return false
            after = next
            if (!response.hasMore) break
        }
        if (epoch != authRepository.currentSessionEpoch()) return false
        ingestionPipeline.recoverRetainedAfterSync()
        return true
    }
}
