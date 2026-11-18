package com.messenger.app.data.sync

import com.messenger.app.data.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import kotlin.test.*

class HostileSyncTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun page(sequence: Long, more: Boolean = true, empty: Boolean = false) = SyncResponseDto(
        if (empty) emptyList() else listOf(testMessageDto(id = "m$sequence", sequence = sequence)), sequence, more)

    @Test fun `account switching cancels actual stalled HTTP immediately`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient()
            try {
                val idle = CompletableDeferred<Unit>()
                client.dispatcher.idleCallback = Runnable { idle.complete(Unit) }
                val auth = FakeAuthRepository()
                val manager = GlobalSyncManager(SyncApi(client, json, server.url("/").toString().trimEnd('/')),
                    FakeMessageIngestor(), auth, InMemorySyncCursorStore())
                val work = launch { manager.runCatchUpSync() }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) })
                auth.switchSession()
                withTimeout(1500) { work.join(); idle.await() }
                assertEquals(0, client.dispatcher.runningCallsCount())
            } finally { client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `empty advancing repeated and decreasing hostile pages stop then back off`() = runBlocking {
        val encoded = listOf(page(1, empty = true), page(0)).map { json.encodeToString(SyncResponseDto.serializer(), it) }
        val negativeWire = json.encodeToString(SyncResponseDto.serializer(),page(0)).replace("\"sequence\":0", "\"sequence\":-1").replace("\"next_sequence\":0", "\"next_sequence\":-1")
        for (response in encoded + negativeWire) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                val client = OkHttpClient()
                try {
                    var now = 1L
                    val ingestor = FakeMessageIngestor()
                    val manager = GlobalSyncManager(SyncApi(client, json, server.url("/").toString().trimEnd('/')),
                        ingestor, FakeAuthRepository(), InMemorySyncCursorStore(), nanoTime = { now })
                    manager.runCatchUpSync()
                    repeat(100) { manager.runCatchUpSync() }
                    assertEquals(1, server.requestCount)
                    assertTrue(ingestor.ingestedBatches.isEmpty())
                    assertEquals(SyncPhase.FAILED, manager.phase.value)
                    now += 31_000_000_000L
                    server.enqueue(MockResponse().setBody(json.encodeToString(SyncResponseDto.serializer(), page(0, false, true))))
                    manager.runCatchUpSync()
                    assertEquals(SyncPhase.IDLE, manager.phase.value)
                } finally { client.dispatcher.executorService.shutdown() }
            }
        }
    }

    @Test fun `endlessly increasing server cursors have a total page budget`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val after = request.requestUrl!!.queryParameter("after_sequence")!!.toLong()
                    return MockResponse().setBody(json.encodeToString(SyncResponseDto.serializer(), page(after + 1)))
                }
            }
            val client = OkHttpClient()
            try {
                val manager = GlobalSyncManager(SyncApi(client, json, server.url("/").toString().trimEnd('/')),
                    FakeMessageIngestor(), FakeAuthRepository(), InMemorySyncCursorStore(), maxPages = 3)
                manager.runCatchUpSync()
                repeat(100) { manager.runCatchUpSync() }
                assertEquals(3, server.requestCount)
                assertEquals(SyncPhase.FAILED, manager.phase.value)
            } finally { client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `elapsed budget stops fast pages and session change resets the backoff`() = runBlocking {
        var now = 0L
        val auth = FakeAuthRepository()
        val source = FakeSyncSource().apply { responder = { after, _ -> now += 20_000_000_000L; page(after + 1) } }
        val manager = GlobalSyncManager(source, FakeMessageIngestor(), auth, InMemorySyncCursorStore(), nanoTime = { now })
        manager.runCatchUpSync()
        assertEquals(2, source.calls.size)
        manager.runCatchUpSync()
        assertEquals(2, source.calls.size)
        auth.switchSession()
        source.responder = { _, _ -> page(0, false, true) }
        manager.runCatchUpSync()
        assertEquals(3, source.calls.size)
    }

    @Test fun `a stalled fake server is cancelled on deadline external cancellation and account switch`() = runBlocking {
        for (mode in listOf("deadline", "cancel", "account")) {
            val auth = FakeAuthRepository()
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val source = FakeSyncSource().apply { responder = { _, _ ->
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            } }
            val manager = GlobalSyncManager(source, FakeMessageIngestor(), auth, InMemorySyncCursorStore(),
                maxDurationMs = if (mode == "deadline") 100 else 30_000)
            val work = launch { manager.runCatchUpSync() }
            started.await()
            if (mode == "cancel") work.cancel()
            if (mode == "account") auth.switchSession()
            withTimeout(1500) { cancelled.await(); work.join() }
            assertNotEquals(SyncPhase.RUNNING, manager.phase.value)
        }
    }

    @Test fun `continuous overlapping invalidations cannot keep a trailing pass alive forever`() = runBlocking {
        lateinit var manager: GlobalSyncManager
        val source = FakeSyncSource().apply { responder = { _, _ ->
            repeat(100) { manager.runCatchUpSync() }
            page(0, false, true)
        } }
        manager = GlobalSyncManager(source, FakeMessageIngestor(), FakeAuthRepository(), InMemorySyncCursorStore())
        manager.runCatchUpSync()
        assertEquals(2, source.calls.size)
        manager.runCatchUpSync()
        assertEquals(2, source.calls.size)
    }
}
