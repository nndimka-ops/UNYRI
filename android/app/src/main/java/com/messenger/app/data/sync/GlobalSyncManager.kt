package com.messenger.app.data.sync

import android.util.Log
import com.messenger.app.data.api.SyncSource
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

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
    private val nanoTime: () -> Long = System::nanoTime,
    private val maxPages: Int = 20,
    private val maxDurationMs: Long = 30_000,
    private val rejectedHistoryClear: () -> Unit = { Log.w(TAG, "Rejected history clear beyond local sync cursor"); Unit },
    private val failedStage: (String) -> Unit = { Log.w(TAG, "Sync paused: $it"); Unit },
) {
    var beforeSync: suspend (String, Int) -> Boolean = { _, _ -> true }
    private val mutex = Mutex()
    private val _phase = kotlinx.coroutines.flow.MutableStateFlow(SyncPhase.IDLE)
    val phase: kotlinx.coroutines.flow.StateFlow<SyncPhase> = _phase

    private val requests = java.util.concurrent.atomic.AtomicLong()
    private var rejectedClearEpoch = -1
    private var budgetEpoch = -1
    private var blockedUntil = 0L

    private fun admitted(): Boolean {
        val epoch = authRepository.currentSessionEpoch()
        if (budgetEpoch != epoch) { budgetEpoch = epoch; blockedUntil = 0 }
        return nanoTime() >= blockedUntil
    }
    private fun backOff() { blockedUntil = nanoTime() + 30_000_000_000L }
    private class SessionChanged : kotlinx.coroutines.CancellationException("Sync session changed")

    private suspend fun boundedPass(): Boolean? = coroutineScope {
        val epoch = authRepository.currentSessionEpoch()
        val work = async { withTimeoutOrNull(maxDurationMs) { runOnePass() } }
        val owner = launch {
            authRepository.authState.first { authRepository.currentSessionEpoch() != epoch }
            work.cancel(SessionChanged())
        }
        try { work.await() } catch (_: SessionChanged) { false } finally { owner.cancel() }
    }

    suspend fun runCatchUpSync() {
        requests.incrementAndGet()
        var passes = 0
        while (true) {
            if (!mutex.tryLock()) return
            val observed = requests.get()
            val epoch = authRepository.currentSessionEpoch()
            try {
                // A demand-driven cooldown is not a newly failed sync pass.
                if (!admitted()) return
                _phase.value = SyncPhase.RUNNING
                val completed = boundedPass()
                if (completed == null) { backOff(); failedStage("duration_budget") }
                _phase.value = if (completed == true || epoch != authRepository.currentSessionEpoch()) SyncPhase.IDLE else SyncPhase.FAILED
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                _phase.value = SyncPhase.IDLE
                throw cancel
            } catch (error: Exception) {
                _phase.value = if (epoch != authRepository.currentSessionEpoch()) SyncPhase.IDLE else SyncPhase.FAILED
                Log.w(TAG, "sync unavailable: ${error::class.simpleName}")
            } finally {
                mutex.unlock()
            }
            if (requests.get() == observed) return
            if (++passes == 2) { backOff(); return }
        }
    }

    /** Explicit fail-closed barrier for restoration: never import stale history
     * when the latest server clear/delete watermarks could not be obtained. */
    suspend fun awaitHistoryRestoreSync() = mutex.withLock {
        check(admitted()) { "Current server history state unavailable" }
        val epoch = authRepository.currentSessionEpoch()
        _phase.value = SyncPhase.RUNNING
        try {
            val completed = boundedPass()
            if (completed == null) { backOff(); failedStage("duration_budget") }
            _phase.value = if (completed == true || epoch != authRepository.currentSessionEpoch()) SyncPhase.IDLE else SyncPhase.FAILED
            check(completed == true) { "Current server history state unavailable" }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            _phase.value = SyncPhase.IDLE
            throw cancel
        } catch (error: Exception) {
            _phase.value = if (epoch != authRepository.currentSessionEpoch()) SyncPhase.IDLE else SyncPhase.FAILED
            throw error
        }
    }

    private suspend fun runOnePass(): Boolean {
        val epoch = authRepository.currentSessionEpoch()
        ingestionPipeline.recoverCommittedReceives()
        if (epoch != authRepository.currentSessionEpoch()) return false
        val token = authRepository.currentAccessToken() ?: return fail("authentication")
        if (!beforeSync(token, epoch)) return fail("before_sync")
        var after = tokenStore.lastSyncedSequence()
        val started = nanoTime()
        var pages = 0
        var records = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (epoch != authRepository.currentSessionEpoch()) return false
            if (++pages > maxPages || nanoTime() - started >= maxDurationMs * 1_000_000) {
                backOff(); return fail("page_budget")
            }
            val response = try {
                syncApi.sync(token, after, limit = SYNC_PAGE_LIMIT)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Log.w(TAG, "sync page failed: ${e::class.simpleName}")
                if (e is com.messenger.app.data.api.HostileWireException || e is kotlinx.serialization.SerializationException) backOff()
                return fail("fetch_page:${e::class.simpleName}")
            }
            if (epoch != authRepository.currentSessionEpoch()) return false
            records += response.messages.size
            // Require progress in the server cursor itself, before any local side effects.
            if (response.messages.size > SYNC_PAGE_LIMIT || records > SYNC_PAGE_LIMIT * maxPages ||
                response.nextSequence < after ||
                (response.hasMore && (response.messages.isEmpty() || response.nextSequence <= after))) {
                backOff(); return fail("page_progress")
            }
            try {
                response.historyClearedBeforeMs?.let { cutoff ->
                    val through = response.historyClearedThroughSequence ?: return fail("history_clear_sequence")
                    if (through > tokenStore.lastSyncedSequence()) {
                        if (rejectedClearEpoch != epoch) {
                            rejectedClearEpoch = epoch
                            rejectedHistoryClear()
                        }
                    } else if (!clearHistory(cutoff, through, epoch)) return fail("history_clear")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (e: Exception) { return fail("history_clear:${e::class.simpleName}") }
            if (epoch != authRepository.currentSessionEpoch()) return false
            if (!ingestionPipeline.deleteChats(response.deletedChatIds)) return fail("delete_chats")
            if (epoch != authRepository.currentSessionEpoch()) return false
            val result = ingestionPipeline.ingest(response.messages)
            if (!result.advancedFully) {
                if (epoch != authRepository.currentSessionEpoch()) return false
                return fail("ingestion")
            }
            val next = maxOf(response.nextSequence, result.highestSequence)
            if (response.hasMore && next <= after) return fail("cursor_progress")
            after = next
            if (!response.hasMore) break
            yield()
        }
        if (epoch != authRepository.currentSessionEpoch()) return false
        ingestionPipeline.recoverRetainedAfterSync()
        currentCoroutineContext().ensureActive()
        if (epoch != authRepository.currentSessionEpoch()) return false
        return true
    }

    private fun fail(stage: String): Boolean { failedStage(stage); return false }
}
