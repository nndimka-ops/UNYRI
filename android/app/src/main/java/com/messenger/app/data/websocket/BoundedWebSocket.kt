@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.messenger.app.data.websocket

import com.messenger.app.data.api.ServerInputLimitException
import com.messenger.app.data.api.ServerInputLimits
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.internal.connection.RealCall
import okhttp3.internal.ws.WebSocketReader
import okhttp3.internal.ws.WebSocketWriter
import okio.*
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.ProtocolException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** OkHttp 4.12's public WebSocket API only delivers already allocated messages.
 * Keep its HTTP/TLS handshake and RFC reader/writer, but guard the upgraded source
 * BEFORE the reader buffers payloads. Compression is deliberately not negotiated.
 * This small adapter depends on the pinned OkHttp internals; upgrade with its tests. */
internal class BoundedWebSocket(
    client: OkHttpClient,
    private val request: Request,
    private val listener: WebSocketListener,
) : WebSocket {
    private data class Outgoing(val opcode: Int, val bytes: ByteString)
    private val random = SecureRandom()
    private val key = ByteArray(16).apply { random.nextBytes(this) }.toByteString().base64()
    private val http = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .eventListener(EventListener.NONE).build()
    private val call = RealCall(http, request.newBuilder()
        .header("Upgrade", "websocket").header("Connection", "Upgrade")
        .header("Sec-WebSocket-Key", key).header("Sec-WebSocket-Version", "13")
        .removeHeader("Sec-WebSocket-Extensions").build(), forWebSocket = true)
    private val done = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outgoing = Channel<Outgoing>(64)
    private var queued = 0L
    private var closing = false
    private var streams: okhttp3.internal.ws.RealWebSocket.Streams? = null

    fun connect(): WebSocket {
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = fail(e, null)
            override fun onResponse(call: Call, response: Response) {
                val exchange = response.exchange
                try {
                    val accept = (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encodeUtf8().sha1().base64()
                    if (response.code != 101 || !"websocket".equals(response.header("Upgrade"), true) ||
                        !"Upgrade".equals(response.header("Connection"), true) ||
                        response.header("Sec-WebSocket-Accept") != accept ||
                        response.header("Sec-WebSocket-Extensions") != null || exchange == null) {
                        throw ProtocolException("Invalid WebSocket upgrade")
                    }
                    val opened = exchange.newWebSocketStreams()
                    synchronized(this@BoundedWebSocket) {
                        if (done.get()) { opened.close(); return }
                        streams = opened
                    }
                    val writer = WebSocketWriter(true, opened.sink, random, false, false, Long.MAX_VALUE)
                    scope.launch {
                        try {
                            for (frame in outgoing) {
                                when (frame.opcode) {
                                    8 -> {
                                        val code = ((frame.bytes[0].toInt() and 255) shl 8) or (frame.bytes[1].toInt() and 255)
                                        val reason = frame.bytes.substring(2).utf8()
                                        writer.writeClose(code, frame.bytes.substring(2))
                                        if (done.compareAndSet(false, true)) listener.onClosed(this@BoundedWebSocket, code, reason)
                                        shutdown(); return@launch
                                    }
                                    10 -> writer.writePong(frame.bytes)
                                    else -> writer.writeMessageFrame(frame.opcode, frame.bytes)
                                }
                                synchronized(this@BoundedWebSocket) { queued -= frame.bytes.size }
                            }
                        } catch (e: Exception) { fail(e, null) }
                        finally { writer.close() }
                    }
                    val reader = WebSocketReader(true, BoundedFrameSource(opened.source).buffer(),
                        object : WebSocketReader.FrameCallback {
                            override fun onReadMessage(text: String) = listener.onMessage(this@BoundedWebSocket, text)
                            override fun onReadMessage(bytes: ByteString) = listener.onMessage(this@BoundedWebSocket, bytes)
                            override fun onReadPing(payload: ByteString) { if (!enqueue(10, payload)) throw ServerInputLimitException() }
                            override fun onReadPong(payload: ByteString) = Unit
                            override fun onReadClose(code: Int, reason: String) {
                                listener.onClosing(this@BoundedWebSocket, code, reason)
                                close(if (code == 1005) 1000 else code, reason)
                            }
                        }, false, false)
                    reader.use {
                        listener.onOpen(this@BoundedWebSocket, response)
                        while (!done.get() && !isClosing()) it.processNextFrame()
                    }
                } catch (e: Exception) {
                    fail(e, response)
                    if (streams == null) { response.close(); exchange?.webSocketUpgradeFailed() }
                }
            }
        })
        return this
    }

    @Synchronized private fun isClosing() = closing
    @Synchronized private fun enqueue(opcode: Int, bytes: ByteString): Boolean {
        if (done.get() || closing || bytes.size > ServerInputLimits.WS_BYTES ||
            queued + bytes.size > ServerInputLimits.WS_BYTES) return false
        if (!outgoing.trySend(Outgoing(opcode, bytes)).isSuccess) return false
        queued += bytes.size
        return true
    }
    override fun request() = request
    @Synchronized override fun queueSize() = queued
    override fun send(text: String) = text.length <= ServerInputLimits.WS_BYTES && enqueue(1, text.encodeUtf8())
    override fun send(bytes: ByteString) = enqueue(2, bytes)
    @Synchronized override fun close(code: Int, reason: String?): Boolean {
        require(code in 1000..4999 && code !in listOf(1004, 1005, 1006, 1015))
        val bytes = (reason ?: "").encodeUtf8()
        require(bytes.size <= 123)
        val payload = Buffer().writeShort(code).write(bytes).readByteString()
        if (!enqueue(8, payload)) { cancel(); return false }
        closing = true
        return true
    }
    override fun cancel() = fail(IOException("WebSocket cancelled"), null)
    private fun fail(error: Exception, response: Response?) {
        if (done.compareAndSet(false, true)) {
            try { listener.onFailure(this, error, response) } finally { shutdown() }
        }
    }
    private fun shutdown() {
        call.cancel()
        outgoing.close()
        synchronized(this) { streams?.close(); streams = null; queued = 0 }
        scope.cancel()
    }
}

/** Reads at most a header before rejecting lengths, including fragmented messages.
 * The underlying Okio source may prefetch one 8 KiB segment, never the advertised payload. */
internal class BoundedFrameSource(private val upstream: BufferedSource) : Source {
    private val header = Buffer()
    private var remaining = 0L
    private var messageBytes = 0L
    private var fragments = 0
    private var windowStart = System.nanoTime()
    private var frames = 0

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) return 0
        if (header.size == 0L && remaining == 0L) {
            if (upstream.exhausted()) return -1
            val first = upstream.readByte().toInt() and 255
            val second = upstream.readByte().toInt() and 255
            header.writeByte(first).writeByte(second)
            if (first and 0x70 != 0 || second and 0x80 != 0) throw ProtocolException("Unsupported WebSocket frame")
            val length = when (second and 127) {
                126 -> upstream.readShort().toInt().and(65535).also { header.writeShort(it) }.toLong()
                127 -> upstream.readLong().also { header.writeLong(it) }
                else -> (second and 127).toLong()
            }
            if (length < 0 || length > ServerInputLimits.WS_BYTES) throw ServerInputLimitException()
            val control = first and 8 != 0
            if (control && length > 125) throw ServerInputLimitException()
            if (!control) {
                if (first and 15 != 0) { messageBytes = 0; fragments = 0 }
                messageBytes += length
                if (messageBytes > ServerInputLimits.WS_BYTES || ++fragments > 128) throw ServerInputLimitException()
            }
            val now = System.nanoTime()
            if (now - windowStart >= 1_000_000_000L) { windowStart = now; frames = 0 }
            if (++frames > 120) throw ServerInputLimitException()
            remaining = length
        }
        if (header.size > 0) return header.read(sink, minOf(byteCount, header.size))
        val count = upstream.read(sink, minOf(byteCount, remaining))
        if (count == -1L) throw java.io.EOFException()
        remaining -= count
        return count
    }
    override fun timeout() = upstream.timeout()
    override fun close() = upstream.close()
}

internal fun OkHttpClient.newBoundedWebSocket(request: Request, listener: WebSocketListener): WebSocket =
    BoundedWebSocket(this, request, listener).connect()
