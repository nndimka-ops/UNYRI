package com.messenger.app.data.websocket

import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.domain.repository.AuthRepository
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** Audit android-017: a full event buffer used to drop messages and call signals while the
 *  socket stayed CONNECTED, so no catch-up ran. Overflow must now force a reconnect. */
class SocketEventOverflowTest {
    @Test fun `event buffer overflow reconnects instead of dropping events`(): Unit = runBlocking {
        val server = MockWebServer()
        val flood = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Paced, not a burst: BoundedFrameSource drops a connection above 120 frames per second.
                Thread {
                    repeat(512) { i -> // more than EVENT_BUFFER (256)
                        if (!webSocket.send("""{"type":"receipt.updated","chat_id":"c","user_id":"u","delivered_sequence":$i,"read_sequence":0}""")) return@Thread
                        Thread.sleep(10) // 100 frames per second, under the 120 per second burst guard
                    }
                }.start()
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(flood))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.start()
        val auth = object : AuthRepository by FakeAuthRepository() {
            override fun currentSessionEpoch() = 1
            override suspend fun currentAccessToken(): String = "token"
        }
        val client = MessagingSocketClient(OkHttpClient(), server.url("/ws").toString().replaceFirst("http", "ws"), auth, Json)
        val gate = CompletableDeferred<Unit>()
        val firstEvent = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.Default) { client.events.collect { firstEvent.complete(Unit); gate.await() } }
        try {
            client.start()
            val first = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            assertNotNull(first, "the client connects")
            withTimeout(5000) { firstEvent.await() } // the flood reached the collector over the first connection
            val second = withContext(Dispatchers.IO) { server.takeRequest(15, TimeUnit.SECONDS) }
            assertNotNull(second, "a full buffer drops the connection so the reconnect catch-up runs")
        } finally {
            gate.complete(Unit); collector.cancel(); client.stop(); server.shutdown()
        }
    }
}
