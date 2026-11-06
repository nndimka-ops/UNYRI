package com.messenger.app.data.websocket

import com.messenger.app.domain.model.ConnectionState
import com.messenger.app.domain.repository.ConnectionRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Stage 1 implementation: connects to the backend `/ws` endpoint and
 * exchanges a trivial PING/PONG JSON protocol. Message parsing, reconnect
 * with backoff, and the full protocol will be added in later stages.
 */
class WebSocketConnectionRepository(
    private val serverUrl: String
) : ConnectionRepository {

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _log = MutableStateFlow<List<String>>(emptyList())
    override val log: StateFlow<List<String>> = _log

    override fun connect() {
        _connectionState.value = ConnectionState.Connecting
        val request = Request.Builder().url(com.messenger.app.data.api.ServerEndpoint.websocket(serverUrl)).build()
        webSocket = client.newWebSocket(request, listener)
    }

    override fun sendPing() {
        val sent = webSocket?.send("""{"type":"PING"}""") ?: false
        appendLog(if (sent) "-> PING" else "failed to send PING: not connected")
    }

    override fun disconnect() {
        webSocket?.close(1000, "client closed")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun appendLog(line: String) {
        _log.update { it + line }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connectionState.value = ConnectionState.Connected
            appendLog("connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            appendLog("<- $text")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _connectionState.value = ConnectionState.Disconnected
            appendLog("closed: $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _connectionState.value = ConnectionState.Failed(t.message ?: "unknown error")
            appendLog("error: ${t.message}")
        }
    }
}
