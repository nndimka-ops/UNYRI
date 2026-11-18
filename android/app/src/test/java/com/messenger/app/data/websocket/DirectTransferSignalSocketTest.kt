package com.messenger.app.data.websocket

import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DirectTransferSignalSocketTest {
    @Test fun `deployed wire type and alias deliver only validated transfer ids with owning epoch`() = runBlocking {
        MockWebServer().use { server ->
            val opened = CompletableDeferred<WebSocket>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(webSocket) }
            }))
            val auth = object : AuthRepository by FakeAuthRepository() {
                override fun currentSessionEpoch() = 7
                override suspend fun currentAccessToken() = "fixture-token"
            }
            val client = MessagingSocketClient(OkHttpClient(), server.url("/ws").toString().replaceFirst("http", "ws"), auth, Json)
            val events = mutableListOf<IncomingSocketEvent.DirectTransferSignal>()
            val received = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                client.events.filterIsInstance<IncomingSocketEvent.DirectTransferSignal>().take(2).toList(events)
            }
            try {
                client.start()
                val socket = withTimeout(5000) { opened.await() }
                socket.send("""{"type":"direct.signal","transfer_id":"not-a-uuid"}""")
                val id = "ffffffff-6666-4666-8666-fffffffffff6"
                socket.send("""{"type":"direct.signal","transfer_id":"$id"}""")
                socket.send("""{"type":"direct_transfer.signal","transfer_id":"$id"}""")
                withTimeout(5000) { received.join() }
                assertEquals(listOf(IncomingSocketEvent.DirectTransferSignal(id, 7), IncomingSocketEvent.DirectTransferSignal(id, 7)), events)
            } finally { received.cancel(); client.stop() }
        }
    }
}
