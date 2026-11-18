package com.messenger.app.data.sync

import com.messenger.app.data.api.SyncResponseDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * Exercises [GlobalSyncManager]'s own orchestration - pagination until
 * `has_more=false`, the [kotlinx.coroutines.sync.Mutex.tryLock]-based
 * single-in-flight-run guarantee, session-change abort, and graceful
 * handling of a failed page fetch - all with a [FakeMessageIngestor] so
 * these tests are independent of real decryption/Room.
 */
class GlobalSyncManagerTest {
    @Test fun forgedFutureClearDoesNotSuppressFutureMessages() = runBlocking {
        val cursor = InMemorySyncCursorStore().apply { saveLastSyncedSequence(4) }
        val source = FakeSyncSource().apply { responder = { _, _ -> SyncResponseDto(listOf(testMessageDto("next", sequence = 5)), 5, false,
            historyClearedBeforeMs = 1234, historyClearedThroughSequence = Long.MAX_VALUE) } }
        val ingestor = FakeMessageIngestor(); var clears = 0; var warnings = 0
        val manager = GlobalSyncManager(source, ingestor, FakeAuthRepository(), cursor, clearHistory = { _, _, _ -> clears++; true }, rejectedHistoryClear = { warnings++ })
        repeat(2) { manager.runCatchUpSync() }
        assertEquals(0, clears); assertEquals(1, warnings); assertEquals(2, ingestor.ingestedBatches.size)
        assertEquals(SyncPhase.IDLE, manager.phase.value)
    }

    @Test fun `restore barrier fails closed when current server deletions cannot load`() = runBlocking {
        val (manager, h) = harness()
        h.syncSource.responder = { _, _ -> error("offline") }
        assertTrue(runCatching { manager.awaitHistoryRestoreSync() }.isFailure)
        h.syncSource.responder = { _, _ -> SyncResponseDto(emptyList(),0,false) }
        manager.beforeSync = { _, _ -> false }
        assertTrue(runCatching { manager.awaitHistoryRestoreSync() }.isFailure)
        manager.beforeSync = { _, _ -> true }
        manager.awaitHistoryRestoreSync()
        h.syncSource.responder = { _, _ -> SyncResponseDto(emptyList(),0,true) }
        assertTrue(runCatching { manager.awaitHistoryRestoreSync() }.isFailure)
    }
    @Test fun `connection header sees failed catchup and clears it only after success`() = runBlocking {
        val (manager, h) = harness()
        h.syncSource.responder = { _, _ ->
            assertEquals(SyncPhase.RUNNING, manager.phase.value)
            error("offline")
        }
        manager.runCatchUpSync()
        assertEquals(SyncPhase.FAILED, manager.phase.value)
        h.syncSource.responder = { _, _ -> SyncResponseDto(emptyList(), 0, false) }
        manager.runCatchUpSync()
        assertEquals(SyncPhase.IDLE, manager.phase.value)
    }
    @Test fun `offline history clear is applied on empty sync before ingest and retried`() = runBlocking {
        val source = FakeSyncSource().apply { responder = { _, _ -> SyncResponseDto(emptyList(), 900, false, historyClearedBeforeMs = 1234, historyClearedThroughSequence = 5) } }
        val ingestor = FakeMessageIngestor()
        var attempts = 0
        val manager = GlobalSyncManager(source, ingestor, FakeAuthRepository(), InMemorySyncCursorStore().apply { saveLastSyncedSequence(5) },
            clearHistory = { cutoff, sequence, _ -> assertEquals(5L, sequence); assertEquals(1234L, cutoff); attempts++; attempts > 1 })
        manager.runCatchUpSync()
        assertTrue(ingestor.ingestedBatches.isEmpty())
        manager.runCatchUpSync()
        assertEquals(2, attempts)
        assertEquals(1, ingestor.ingestedBatches.size)
    }

    @Test fun `chat tombstones are applied before message ingestion`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = FakeSyncSource().apply { responder = { _, _ -> SyncResponseDto(emptyList(), 0, false, listOf("deleted")) } }
        val ingestor = object : MessageIngestor {
            override suspend fun recoverRetainedAfterSync() { calls.add("recover") }
            override suspend fun deleteChats(chatIds: List<String>): Boolean {
                assertEquals(listOf("deleted"), chatIds); calls.add("delete"); return true
            }
            override suspend fun ingest(dtos: List<com.messenger.app.data.api.MessageDto>, advanceCursor: Boolean): MessageIngestor.IngestResult {
                calls.add("ingest"); return MessageIngestor.IngestResult(true, 0)
            }
        }
        GlobalSyncManager(source, ingestor, FakeAuthRepository(), InMemorySyncCursorStore()).runCatchUpSync()
        assertEquals(listOf("delete", "ingest", "recover"), calls)
    }


    private fun harness(
        auth: FakeAuthRepository = FakeAuthRepository(),
        syncSource: FakeSyncSource = FakeSyncSource(),
        ingestor: FakeMessageIngestor = FakeMessageIngestor(),
        cursorStore: InMemorySyncCursorStore = InMemorySyncCursorStore(),
    ) = GlobalSyncManager(syncSource, ingestor, auth, cursorStore) to Harness(auth, syncSource, ingestor, cursorStore)

    private data class Harness(
        val auth: FakeAuthRepository,
        val syncSource: FakeSyncSource,
        val ingestor: FakeMessageIngestor,
        val cursorStore: InMemorySyncCursorStore,
    )

    @Test
    fun `single page with no more data completes in one round trip`() = runBlocking {
        val (manager, h) = harness()
        h.syncSource.responder = { after, _ -> SyncResponseDto(listOf(testMessageDto(id = "m1", sequence = 1)), 1, false) }

        manager.runCatchUpSync()

        assertEquals(1, h.syncSource.calls.size)
        assertEquals(0L, h.syncSource.calls.first().first) // started from the durable cursor (0)
        assertEquals(1, h.ingestor.ingestedBatches.size)
    }

    @Test
    fun `pages through until has_more is false`() = runBlocking {
        val (manager, h) = harness()
        var page = 0
        h.syncSource.responder = { after, _ ->
            page++
            when (page) {
                1 -> SyncResponseDto(listOf(testMessageDto(id = "m1", sequence = 100)), 100, true)
                2 -> SyncResponseDto(listOf(testMessageDto(id = "m2", sequence = 200)), 200, true)
                else -> SyncResponseDto(listOf(testMessageDto(id = "m3", sequence = 300)), 300, false)
            }
        }

        manager.runCatchUpSync()

        assertEquals(3, h.syncSource.calls.size)
        assertEquals(listOf(0L, 100L, 200L), h.syncSource.calls.map { it.first })
        assertEquals(3, h.ingestor.ingestedBatches.size)
    }

    @Test
    fun `a session change reported by the ingestor aborts the loop early`() = runBlocking {
        val (manager, h) = harness()
        h.syncSource.responder = { after, _ -> SyncResponseDto(listOf(testMessageDto(id = "m1", sequence = 1)), 1, true) }
        h.ingestor.results.addLast(MessageIngestor.IngestResult(advancedFully = false, highestSequence = 1))

        manager.runCatchUpSync()

        // Only the first page is ever fetched - never continues paginating
        // once a session change was detected mid-sync.
        assertEquals(1, h.syncSource.calls.size)
    }

    @Test
    fun `a failed page fetch stops gracefully without crashing`() = runBlocking {
        val (manager, h) = harness()
        h.syncSource.thrower = { throw java.io.IOException("network down") }

        manager.runCatchUpSync() // must not throw

        assertTrue(h.ingestor.ingestedBatches.isEmpty())
    }

    @Test
    fun `not authenticated never calls sync`() = runBlocking {
        val auth = FakeAuthRepository().apply { setToken(null) }
        val (manager, h) = harness(auth = auth)

        manager.runCatchUpSync()

        assertTrue(h.syncSource.calls.isEmpty())
    }

    @Test
    fun `a reconnect during an in-flight request schedules a trailing pass`() = runBlocking {
        val (manager, h) = harness()
        val firstPageStarted = CompletableDeferred<Unit>()
        val releaseFirstPage = CompletableDeferred<Unit>()
        var callCount = 0
        h.syncSource.responder = { _, _ ->
            callCount++
            if (callCount == 1) {
                firstPageStarted.complete(Unit)
                releaseFirstPage.await()
            }
            SyncResponseDto(emptyList(), 0, false)
        }

        val firstRun = async { manager.runCatchUpSync() }
        firstPageStarted.await()
        val secondRun = async { manager.runCatchUpSync() } // coalesced: returns immediately, but requests another pass

        secondRun.await()
        releaseFirstPage.complete(Unit)
        firstRun.await()

        assertEquals(2, callCount)
    }

    @Test
    fun `reconnect is retained when the previous network request fails`() = runBlocking {
        val (manager, h) = harness()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        h.syncSource.responder = { after, _ ->
            calls++
            if (calls == 1) {
                started.complete(Unit)
                release.await()
                throw java.io.IOException("connection lost")
            }
            SyncResponseDto(emptyList(), after, false)
        }
        val old = async { manager.runCatchUpSync() }
        started.await()
        repeat(10) { manager.runCatchUpSync() }
        release.complete(Unit)
        old.await()
        assertEquals(2, calls) // Ten notifications coalesce to one recovery pass.
    }

    @Test
    fun `resumes from the durable cursor, not from zero`() = runBlocking {
        val (manager, h) = harness(cursorStore = InMemorySyncCursorStore(initial = 42L))
        h.syncSource.responder = { after, _ -> SyncResponseDto(emptyList(), after, false) }

        manager.runCatchUpSync()

        assertEquals(42L, h.syncSource.calls.first().first)
    }
}
