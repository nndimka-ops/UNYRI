package com.messenger.app.data.api

import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.message.MessageOutboxProcessor
import com.messenger.app.data.sync.InMemoryMessageDao
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MessagesApiCancellationTest {
    private class StalledServer(private val response: String? = null) : AutoCloseable {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val headersReceived = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var socket: Socket? = null
        val worker = Thread {
            try {
                socket = server.accept()
                val reader = socket!!.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                headersReceived.countDown()
                response?.let { socket!!.getOutputStream().apply { write(it.toByteArray()); flush() } }
                release.await(10, TimeUnit.SECONDS)
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val url get() = "http://127.0.0.1:${server.localPort}"
        override fun close() { release.countDown(); socket?.close(); server.close(); worker.join(1000) }
    }

    @Test fun `paused chat clear cancels actual stalled HTTP without waiting for read timeout`() = runBlocking {
        StalledServer().use { server ->
            val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val api = MessagesApi(client, Json { ignoreUnknownKeys = true }, server.url)
                val dao = InMemoryMessageDao()
                dao.upsert(MessageEntity("old", null, "chat", "me", "old", null, "2026-10-10T00:00:00Z", "pending"))
                val processor = MessageOutboxProcessor(dao, appScope, { 0L }, { "me" }) {
                    api.sendMessage("token", "chat", it.clientMessageId, emptyList())
                }
                val sending = async { processor.flush() }
                assertTrue(withContext(Dispatchers.IO) { server.headersReceived.await(3, TimeUnit.SECONDS) })
                withTimeout(1500) {
                    processor.withDeliveryPaused { dao.hideLocally("old") }
                    sending.await()
                }
                assertTrue(dao.findByClientMessageId("old")!!.hiddenLocally)
                assertEquals(1L, server.release.count) // Server has still not answered.
            } finally { appScope.coroutineContext[Job]!!.cancelAndJoin(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `API error response preserves structured classification`() = runBlocking {
        val body = "{\"error\":{\"code\":\"validation_error\",\"message\":\"Rejected\"}}"
        StalledServer("HTTP/1.1 400 Bad Request\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").use { server ->
            val client = OkHttpClient()
            try {
                val error = assertFailsWith<AuthApiException.Api> { MessagesApi(client, Json, server.url).sendMessage("token", "chat", "id", emptyList()) }
                assertEquals("validation_error", error.code)
            } finally { client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `outbox auth refresh chat lookup and prekey preparation cancel promptly`() = runBlocking {
        for (dependency in listOf("auth", "chat", "keys")) {
            StalledServer().use { server ->
                val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
                val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val dao = InMemoryMessageDao()
                    dao.upsert(MessageEntity("old", null, "chat", "me", "old", null, "2026-10-10T00:00:00Z", "pending"))
                    var sent = false
                    val processor = MessageOutboxProcessor(dao, appScope, { 0L }, { "me" }, prepareAttempt = {
                        when (dependency) {
                            "auth" -> AuthApi(client, Json, server.url).refresh(RefreshRequestDto("refresh", "request"))
                            "chat" -> ChatsApi(client, Json, server.url).getChat("token", "chat")
                            else -> KeysApi(client, Json, server.url).getBundles("token", "peer")
                        }
                    }, attemptSend = { sent = true })
                    val preparing = async { processor.flush() }
                    assertTrue(withContext(Dispatchers.IO) { server.headersReceived.await(3, TimeUnit.SECONDS) }, dependency)
                    withTimeout(1500) { processor.withDeliveryPaused { dao.hideLocally("old") }; preparing.await() }
                    assertFalse(sent, dependency)
                    assertTrue(dao.findByClientMessageId("old")!!.hiddenLocally, dependency)
                } finally { appScope.coroutineContext[Job]!!.cancelAndJoin(); client.dispatcher.executorService.shutdown() }
            }
        }
    }
}
