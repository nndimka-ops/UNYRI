package com.messenger.app.data.call

import com.messenger.app.data.api.CallDto
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.ui.calls.chatTimeline
import kotlin.test.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class CallHistoryTest {
    @Test fun `clear survives restart filters stale pages and rejects stale account epoch`() = kotlinx.coroutines.runBlocking {
        val root = java.nio.file.Files.createTempDirectory("coco-call-clear-").toFile()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            val auth = com.messenger.app.data.sync.FakeAuthRepository("alice")
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val old = call("old")
            val fresh = call("new", "2026-09-12T10:00:00Z")
            val body = "{\"visible_since\":\"1970-01-01T00:00:00Z\",\"items\":[" + json.encodeToString(CallDto.serializer(),old) + "," + json.encodeToString(CallDto.serializer(),fresh) + "],\"next_cursor\":null}"
            val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body((if (chain.request().url.encodedPath.endsWith("/cleared")) "[]" else if (chain.request().url.encodedPath.endsWith("/deletions")) "{\"items\":[],\"has_more\":false}" else body).toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = CallApiImpl(client,json,"https://test.invalid",auth)
            val repository = CallHistoryRepository(root,"https://test.invalid",api,auth,scope)
            repository.refresh()
            assertEquals(2,repository.calls.value.size)
            val boundary = java.time.Instant.parse("2026-09-12T00:00:00Z").toEpochMilli()
            assertFalse(repository.applyHistoryClear(boundary,auth.currentSessionEpoch()+1))
            assertEquals(2,repository.calls.value.size)
            assertTrue(repository.applyHistoryClear(boundary,auth.currentSessionEpoch()))
            assertEquals(listOf("new"),repository.calls.value.map { it.id })
            repository.refresh()
            assertEquals(listOf("new"),repository.calls.value.map { it.id })
            val reopened = CallHistoryRepository(root,"https://test.invalid",api,auth,scope)
            reopened.refresh()
            assertEquals(listOf("new"),reopened.calls.value.map { it.id })
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); root.deleteRecursively() }
    }

    @Test fun `server restore flag cannot reveal old calls including offline reopening`() = kotlinx.coroutines.runBlocking {
        val root = java.nio.file.Files.createTempDirectory("coco-call-privacy-").toFile()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            val auth = com.messenger.app.data.sync.FakeAuthRepository("alice")
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            var restored = false
            val old = call("old")
            val fresh = call("new", "2026-09-12T10:00:00Z")
            val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                val page = com.messenger.app.data.api.CallHistoryPage(listOf(old, fresh, old), historyRestored = restored,
                    visibleSince = "2026-09-12T00:00:00Z")
                val body = if (chain.request().url.encodedPath.endsWith("/cleared")) "[]" else if (chain.request().url.encodedPath.endsWith("/deletions")) "{\"items\":[],\"has_more\":false}"
                    else json.encodeToString(com.messenger.app.data.api.CallHistoryPage.serializer(), page)
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = CallApiImpl(client,json,"https://test.invalid",auth)
            val repo = CallHistoryRepository(root,"https://test.invalid",api,auth,scope)
            repo.refresh()
            assertEquals(listOf("new"),repo.calls.value.map { it.id })
            restored = true
            repo.refresh()
            assertEquals(listOf("new"),repo.calls.value.map { it.id })
            val offline = CallHistoryRepository(root,"https://test.invalid",api,auth,scope)
            kotlinx.coroutines.withTimeout(3000) { while (offline.calls.value.size != 1) kotlinx.coroutines.delay(10) }
            assertEquals(listOf("new"),offline.calls.value.map { it.id })
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); root.deleteRecursively() }
    }

    private fun call(id: String = "c", time: String = "2026-09-11T10:00:00Z") =
        CallDto(id, "alice", "bob", "audio", "ended", "missed", createdAt = time, endedAt = "2026-09-11T10:01:00Z")

    @Test fun `missed call differs by participant and ringing time is not duration`() {
        assertEquals(CallHistoryOutcome.MISSED, callOutcome(call(), "bob"))
        assertEquals(CallHistoryOutcome.CANCELLED, callOutcome(call(), "alice"))
        assertNull(callDurationSeconds(call()))
    }
    @Test fun `connected calls have direction and only connected duration`() {
        val connected = call().copy(connectedAt = "2026-09-11T10:00:20Z", endReason = "hangup")
        assertEquals(CallHistoryOutcome.OUTGOING, callOutcome(connected, "alice"))
        assertEquals(CallHistoryOutcome.INCOMING, callOutcome(connected, "bob"))
        assertEquals(40L, callDurationSeconds(connected))
        assertEquals(0L, callDurationSeconds(connected.copy(endedAt = "2026-09-11T10:00:00Z")))
    }
    @Test fun `rejected and failed calls are not missed`() {
        assertEquals(CallHistoryOutcome.REJECTED, callOutcome(call().copy(endReason = "rejected"), "bob"))
        assertEquals(CallHistoryOutcome.FAILED, callOutcome(call().copy(endReason = "timeout"), "bob"))
    }
    @Test fun `timeline deduplicates calls and orders restored messages by event time`() {
        fun message(id: String, time: String) = Message(id, id, "chat", "alice", id, 1, time, MessageStatus.SENT)
        val rows = chatTimeline(listOf(message("first", "2026-09-11T09:59:00Z"), message("second", "2026-09-11T10:02:00Z")), listOf(call(), call()))
        assertEquals(listOf("first", "call:c", "second"), rows.map { it.key })
        val skewed = chatTimeline(listOf(message("first", "2026-09-11T10:02:00Z"), message("second", "2026-09-11T09:59:00Z")), listOf(call()))
        assertEquals(listOf("second", "call:c", "first"), skewed.map { it.key })
    }
}
