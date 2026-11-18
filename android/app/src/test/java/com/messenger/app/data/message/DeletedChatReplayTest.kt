package com.messenger.app.data.message

import com.messenger.app.data.api.*
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.sync.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DeletedChatReplayTest {
    @Test fun `repeated sync deletion lists do no cleanup and cannot cancel a live send`() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody("""{"messages":[],"next_sequence":0,"has_more":false,"deleted_chat_ids":["old","old"]}""")) }
            val client = OkHttpClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val dao = InMemoryMessageDao()
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                var cancelled = false
                val outbox = MessageOutboxProcessor(dao, scope, { 0L }, { "me" }) {
                    started.complete(Unit)
                    try { finish.await(); dao.updateStatus(it.clientMessageId, "sent") }
                    catch (e: CancellationException) { cancelled = true; throw e }
                }
                val tombstones = mutableSetOf<String>()
                val deletions = PendingChatDeletions()
                var cleanups = 0
                val ingestor = object : MessageIngestor {
                    override suspend fun deleteChats(chatIds: List<String>) = deletions.apply(chatIds, { it in tombstones }) { ids ->
                        outbox.cancelActiveAttempt()
                        cleanups++ // uploads, workspace, attachment reads and purge all follow this gate
                        tombstones += ids
                        true
                    }
                    override suspend fun ingest(dtos: List<MessageDto>, advanceCursor: Boolean) = MessageIngestor.IngestResult(true, 0)
                    override suspend fun recoverRetainedAfterSync() = Unit
                }
                val manager = GlobalSyncManager(SyncApi(client, Json { ignoreUnknownKeys = true }, server.url("/").toString().trimEnd('/')),
                    ingestor, FakeAuthRepository(), InMemorySyncCursorStore())
                manager.runCatchUpSync()
                dao.upsert(MessageEntity("new", null, "live", "me", "text", null, "2026-11-14T00:00:00Z", "pending"))
                val sending = async { outbox.flush() }
                withTimeout(2000) { started.await() }
                repeat(2) { manager.runCatchUpSync() }
                assertEquals(1, cleanups)
                assertFalse(cancelled)
                finish.complete(Unit)
                withTimeout(2000) { sending.await() }
                assertEquals("sent", dao.findByClientMessageId("new")!!.status)
                assertEquals(3, server.requestCount)
            } finally { scope.cancel(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `failed cleanup is retried and concurrent replays serialize on the durable marker`() = runBlocking {
        val deletions = PendingChatDeletions()
        val tombstones = mutableSetOf<String>()
        var attempts = 0
        suspend fun apply() = deletions.apply(listOf("chat"), { it in tombstones }) { ids ->
            attempts++
            if (attempts == 1) error("Cleanup interrupted before tombstone")
            yield()
            tombstones += ids
            true
        }
        assertFailsWith<IllegalStateException> { apply() }
        coroutineScope { repeat(10) { launch { assertTrue(apply()) } } }
        assertEquals(2, attempts)
    }
}
