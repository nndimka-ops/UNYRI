package com.messenger.app.data.websocket

import com.messenger.app.data.api.ServerInputLimitException
import com.messenger.app.data.api.ServerInputLimits
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.messenger.app.data.api.ApiJson
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.domain.repository.SocketConnectionState
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BoundedWebSocketTest {
    @Test fun `fake WS server cannot send deep lists oversized fields or unlimited invalidations`() = runBlocking {
        val attacks = listOf(
            "{\"type\":\"unknown\",\"data\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}",
            "{\"type\":\"unknown\",\"data\":[" + List(4097) { "0" }.joinToString(",") + "]}",
            "{\"type\":\"unknown\",\"data\":\"" + "a".repeat(512 * 1024 + 1) + "\"}",
            "{\"type\":\"privacy.changed\"}",
        )
        for (attack in attacks) MockWebServer().use { server ->
            val remote = CompletableDeferred<WebSocket>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { remote.complete(webSocket) }
            }))
            val client = OkHttpClient()
            val socket = MessagingSocketClient(client, server.url("/ws").toString(), FakeAuthRepository(), ApiJson)
            val opened = CompletableDeferred<Unit>()
            val callbacks = java.util.concurrent.atomic.AtomicInteger()
            socket.onPrivacyChanged = { callbacks.incrementAndGet(); opened.complete(Unit) }
            try {
                socket.start()
                withTimeout(3000) {
                    opened.await()
                    val peer = remote.await()
                    repeat(if (attack == attacks.last()) 30 else 1) { peer.send(attack) }
                    socket.connectionState.first { it == SocketConnectionState.DISCONNECTED }
                }
                assertTrue(callbacks.get() <= 13) // Opening + at most twelve remote hints.
                socket.stop()
                assertEquals(SocketConnectionState.DISCONNECTED, socket.connectionState.value)
            } finally { socket.stop(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `frame length rejects at header before advertised payload is allocated`() {
        val bytes = Buffer().writeByte(0x81).writeByte(127).writeLong(Long.MAX_VALUE)
        val source = BoundedFrameSource(bytes)
        assertFailsWith<ServerInputLimitException> { source.read(Buffer(), 8192) }
        assertEquals(0L, bytes.size)
    }

    @Test fun `fragmented messages cannot evade the cumulative size bound`() {
        val wire = Buffer()
        repeat(2) { part ->
            wire.writeByte(if (part == 0) 1 else 0x80).writeByte(127).writeLong(600_000)
                .write(ByteArray(600_000))
        }
        val source = BoundedFrameSource(wire)
        val sink = Buffer()
        assertFailsWith<ServerInputLimitException> { while (source.read(sink, 8192) != -1L) sink.clear() }
        assertEquals(600_000L, wire.size) // Second payload never consumed.
    }

    @Test fun `compressed frames huge control frames and fragment floods are rejected`() {
        assertFailsWith<java.net.ProtocolException> { BoundedFrameSource(Buffer().writeByte(0xc1).writeByte(0)).read(Buffer(), 1) }
        assertFailsWith<ServerInputLimitException> {
            BoundedFrameSource(Buffer().writeByte(0x89).writeByte(126).writeShort(126)).read(Buffer(), 8192)
        }
        val bytes = Buffer()
        repeat(129) { bytes.writeByte(if (it == 0) 1 else 0).writeByte(0) }
        val source = BoundedFrameSource(bytes)
        assertFailsWith<ServerInputLimitException> { while (source.read(Buffer(), 8192) != -1L) { } }
    }

    @Test fun `real upgraded HTTP socket rejects oversized header without waiting for body`() = runBlocking {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val release = CountDownLatch(1)
            val worker = Thread {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        var key = ""
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Sec-WebSocket-Key:", true)) key = line.substringAfter(':').trim()
                        }
                        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                        val output = socket.getOutputStream()
                        output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                        output.write(Buffer().writeByte(0x81).writeByte(127).writeLong(ServerInputLimits.WS_BYTES + 1L).readByteArray())
                        output.flush()
                        release.await(5, TimeUnit.SECONDS)
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
            val failed = CompletableDeferred<Throwable>()
            val client = OkHttpClient()
            val socket = client.newBoundedWebSocket(Request.Builder().url("http://127.0.0.1:${server.localPort}/ws").build(),
                object : WebSocketListener() {
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failed.complete(t) }
                })
            try {
                assertIs<ServerInputLimitException>(withTimeout(2000) { failed.await() })
                assertEquals(1L, release.count)
            } finally { socket.cancel(); release.countDown(); worker.join(1000); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `bounded transport still exchanges messages and closes without a lingering worker`() = runBlocking {
        MockWebServer().use { server ->
            val received = CompletableDeferred<String>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("{\"type\":\"PONG\"}") }
                override fun onMessage(webSocket: WebSocket, text: String) { received.complete(text) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            }))
            val client = OkHttpClient()
            val incoming = CompletableDeferred<String>()
            val closed = CompletableDeferred<Unit>()
            val socket = client.newBoundedWebSocket(Request.Builder().url(server.url("/ws")).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("{\"type\":\"PING\"}") }
                override fun onMessage(webSocket: WebSocket, text: String) { incoming.complete(text) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
            })
            try {
                withTimeout(3000) {
                    assertEquals("{\"type\":\"PONG\"}", incoming.await())
                    assertEquals("{\"type\":\"PING\"}", received.await())
                    assertTrue(socket.close(1000, "done"))
                    closed.await()
                }
            } finally { socket.cancel(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `invalidation floods stay bounded across reconnect and reset for another account`() {
        var now = 0L
        val budget = InvalidationBudget { now }
        repeat(12) { assertTrue(budget.accept(1)) }
        repeat(1000) { assertFalse(budget.accept(1)) }
        assertTrue(budget.accept(2))
        now += 31_000_000_000L
        assertTrue(budget.accept(2))
    }
}
