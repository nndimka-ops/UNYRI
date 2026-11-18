package com.messenger.app.data.websocket

import com.messenger.app.data.api.MessageDto
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Authenticated realtime transport for message/receipt events. Reconnects
 *  with exponential backoff (1s, 2s, 4s, ... capped at 30s) on any drop,
 *  never a tight infinite loop, and always fetches the CURRENT access token
 *  fresh on every (re)connect attempt (via [AuthRepository.currentAccessToken],
 *  which itself transparently refreshes) - never holds onto a stale JWT.
 *  Postgres remains the source of truth: a missed event here is always
 *  recoverable via `/sync`/`GET /chats`, so a dropped connection is
 *  degraded realtime service, not data loss. */
class MessagingSocketClient(
    private val client: OkHttpClient,
    private val wsUrl: String,
    private val authRepository: AuthRepository,
    private val json: Json,
) {
    var onAppearanceChanged: () -> Unit = {}
    var onPrivacyChanged: () -> Unit = {}
    var onAccountBlocksChanged: () -> Unit = {}
    var onContactsChanged: (String?, Boolean?) -> Unit = { _, _ -> }
    var onProfileChanged: (String, Long) -> Unit = { _, _ -> }
    val presence = RealtimePresence(::sendPresence, {
        com.messenger.app.data.api.ServerEndpoint.current() + "|" + authRepository.currentSessionEpoch()
    })
    private val scope = CoroutineScope(SupervisorJob())
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var attempt = 0
    @Volatile private var stopped = true
    private val lock = Any()
    @Volatile private var generation = 0L
    @Volatile private var accountEpoch = -1
    private var connectJob: Job? = null
    private val invalidations = InvalidationBudget()
    private var openedAt = 0L

    private val _connectionState = MutableStateFlow(SocketConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SocketConnectionState> = _connectionState
    private val pingWaiters = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()

    /** Optional foreground UI measurement; normal keepalive is unchanged. */
    suspend fun measurePing(): Long? {
        val id = java.util.UUID.randomUUID().toString()
        val reply = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ticket = generation
        val started = android.os.SystemClock.elapsedRealtime()
        pingWaiters[id] = reply
        try {
            val sent = synchronized(lock) {
                isCurrent(ticket) && connectionState.value == SocketConnectionState.CONNECTED &&
                    webSocket?.send("{\"type\":\"PING\",\"request_id\":\"$id\"}") == true
            }
            if (!sent || kotlinx.coroutines.withTimeoutOrNull(5000) { reply.await(); true } != true) return null
            return if (isCurrent(ticket)) (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(1) else null
        } finally { pingWaiters.remove(id); reply.cancel() }
    }

    private val _events = MutableSharedFlow<IncomingSocketEvent>(extraBufferCapacity = EVENT_BUFFER)
    /** Messages, receipts and call signals are never dropped silently (audit android-017).
     *  When a slow collector fills the buffer the connection is dropped on purpose: the
     *  reconnect runs the global catch-up sync and the call reconciliation, which recover
     *  everything the server still holds. */
    private fun deliver(webSocket: WebSocket, event: IncomingSocketEvent) {
        if (_events.tryEmit(event)) return
        synchronized(lock) { attempt = 0 }
        webSocket.cancel()
    }
    val events: SharedFlow<IncomingSocketEvent> = _events

    val draftFrames = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val _activity = MutableStateFlow<Map<String, Pair<String, Long>>>(emptyMap())
    val activity: StateFlow<Map<String, Pair<String, Long>>> = _activity
    fun start() = synchronized(lock) {
        val epoch = authRepository.currentSessionEpoch()
        if (!stopped && accountEpoch == epoch) return@synchronized
        stop()
        accountEpoch = epoch
        stopped = false
        attempt = 0
        connect()
    }
    fun reconnectIfNeeded() = synchronized(lock) {
        if (!stopped && connectionState.value != SocketConnectionState.CONNECTED) { stop(); start() }
    }

    fun stop() = synchronized(lock) {
        sendRaw("{\"type\":\"presence.heartbeat\",\"active\":false}")
        presence.setConnected(false)
        stopped = true
        generation++
        _activity.value = emptyMap()
        connectJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.cancel()
        webSocket = null
        _connectionState.value = SocketConnectionState.DISCONNECTED
    }

    /** Sends a pre-serialized JSON frame (used for Этап J call.* signaling -
     *  CallSignalingClient builds the payload, this class owns the one real
     *  socket). Returns false if there is currently no live connection -
     *  the caller decides what "couldn't send" means for its own retry/
     *  state-machine policy, this class doesn't queue or retry frames. */
    fun sendRaw(payload: String): Boolean = synchronized(lock) {
        if (stopped || accountEpoch != authRepository.currentSessionEpoch()) false
        else webSocket?.send(payload) ?: false
    }

    private fun sendPresence(payload: String, owner: String): Boolean = synchronized(lock) {
        if (owner != com.messenger.app.data.api.ServerEndpoint.current() + "|" + authRepository.currentSessionEpoch()) false
        else sendRaw(payload)
    }

    private fun isCurrent(ticket: Long): Boolean = !stopped && ticket == generation &&
        accountEpoch == authRepository.currentSessionEpoch()

    private fun connect(): Unit = synchronized(lock) {
        if (stopped || accountEpoch != authRepository.currentSessionEpoch()) return@synchronized
        val ticket = ++generation
        _connectionState.value = SocketConnectionState.CONNECTING
        connectJob = scope.launch {
            try {
                val token = authRepository.currentAccessToken()
                synchronized(lock) {
                    if (!isCurrent(ticket)) return@synchronized
                    if (token == null) { scheduleReconnect(); return@synchronized }
                    val request = Request.Builder().url(com.messenger.app.data.api.ServerEndpoint.websocket(wsUrl))
                        .header("Authorization", "Bearer $token")
                        .header("X-Unyri-Capabilities", "call-ring-ack").build()
                    webSocket = client.newBoundedWebSocket(request, listener(ticket))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) { if (isCurrent(ticket)) scheduleReconnect() }
            }
        }
    }

    private fun scheduleReconnect() {
        if (stopped || accountEpoch != authRepository.currentSessionEpoch()) return
        presence.setConnected(false)
        _connectionState.value = SocketConnectionState.DISCONNECTED
        reconnectJob?.cancel()
        val delayMs = (BASE_DELAY_MS * (1L shl attempt.coerceAtMost(MAX_BACKOFF_SHIFT))).coerceAtMost(MAX_DELAY_MS)
        attempt = (attempt + 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        reconnectJob = scope.launch { delay(delayMs); connect() }
    }

    private fun listener(ticket: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (!isCurrent(ticket)) { webSocket.cancel(); return@synchronized }
            openedAt = System.nanoTime()
            _connectionState.value = SocketConnectionState.CONNECTED
            presence.setConnected(true)
            onAppearanceChanged()
            onPrivacyChanged()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(ticket)) return
            runCatching {
                com.messenger.app.data.api.ServerInputLimits.validateJson(text,
                    com.messenger.app.data.api.ServerInputLimits.WS_BYTES, 512 * 1024)
                synchronized(lock) {
                    if (!isCurrent(ticket)) return
                    if (System.nanoTime() - openedAt >= 30_000_000_000L) attempt = 0
                }
                val element = json.parseToJsonElement(text).jsonObject
                val type = element["type"]?.jsonPrimitive?.contentOrNull
                if (type in setOf("privacy.changed", "appearance.changed", "contacts.changed", "profile.changed",
                        "chat.management_changed", "call.history_changed", "chat.deleted") &&
                    !invalidations.accept(accountEpoch)) {
                    synchronized(lock) { attempt = MAX_BACKOFF_SHIFT }
                    webSocket.cancel()
                    return
                }
                when (type) {
                    "direct.signal", "direct_transfer.signal" -> {
                        val id = element["transfer_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        if (com.messenger.app.data.directtransfer.canonicalUuid(id)) synchronized(lock) {
                            if (isCurrent(ticket)) deliver(webSocket, IncomingSocketEvent.DirectTransferSignal(id, accountEpoch))
                        }
                    }
                    "PONG" -> element["request_id"]?.jsonPrimitive?.contentOrNull?.let { pingWaiters[it]?.complete(Unit) }
                    "presence.snapshot" -> synchronized(lock) { if (isCurrent(ticket)) presence.accept(element) }
                    "privacy.changed" -> { if (isCurrent(ticket)) onPrivacyChanged() }
                    "appearance.changed" -> { if (isCurrent(ticket)) onAppearanceChanged() }
                    "contacts.changed" -> {
                        if (isCurrent(ticket)) {
                            val hint = parseContactsChanged(element)
                            onContactsChanged(hint?.userId, hint?.isContact)
                        }
                    }
                    "profile.changed" -> parseSelfProfileChanged(element)?.let { hint ->
                        if (isCurrent(ticket)) onProfileChanged(hint.userId, hint.version)
                    }
                    "chat.draft" -> { if (text.length <= 32768) draftFrames.tryEmit(text) }
                    "chat.activity" -> {
                        val chat = element["chat_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val activity = element["activity"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        if (activity in setOf("typing", "recording", "uploading", "idle")) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            _activity.value = _activity.value.filterValues { now - it.second < 8000 }.entries.toList().takeLast(127).associate { it.toPair() } + (chat to (activity to now))
                        }
                    }
                    "chat.management_changed" -> {
                        if (isCurrent(ticket)) { onAccountBlocksChanged(); deliver(webSocket, IncomingSocketEvent.ChatManagementChanged) }
                    }
                    "chat.deleted" -> {
                        val chatId = element["chat_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        deliver(webSocket, IncomingSocketEvent.ChatDeleted(chatId))
                    }
                    "message.new", "message.sent" -> {
                        val messageJson = element["message"]?.jsonObject ?: return@runCatching
                        val dto = json.decodeFromJsonElement<MessageDto>(messageJson)
                        deliver(webSocket, IncomingSocketEvent.NewMessage(dto))
                    }
                    "receipt.updated" -> {
                        val chatId = element["chat_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val userId = element["user_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val delivered = element["delivered_sequence"]?.jsonPrimitive?.longOrNull ?: 0L
                        val read = element["read_sequence"]?.jsonPrimitive?.longOrNull ?: 0L
                        deliver(webSocket, IncomingSocketEvent.ReceiptUpdate(chatId, userId, delivered, read))
                    }
                    "call.ringing" -> {
                        val callId = element["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val callerUserId = element["caller_user_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val mediaKind = element["media_kind"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        deliver(webSocket, IncomingSocketEvent.CallRinging(callId, callerUserId, mediaKind))
                    }
                    "call.offer" -> {
                        val callId = element["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val sdp = element["sdp"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        deliver(webSocket, IncomingSocketEvent.CallOffer(callId, sdp))
                    }
                    "call.answer" -> {
                        val callId = element["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val sdp = element["sdp"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        deliver(webSocket, IncomingSocketEvent.CallAnswer(callId, sdp))
                    }
                    "call.ice" -> {
                        val callId = element["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val candidate = element["candidate"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val sdpMid = element["sdp_mid"]?.jsonPrimitive?.contentOrNull
                        val sdpMLineIndex = element["sdp_m_line_index"]?.jsonPrimitive?.intOrNull
                        if (element["sdp_m_line_index"]?.toString()?.let { it != "null" } == true && (element["sdp_m_line_index"]?.jsonPrimitive?.isString == true || sdpMLineIndex == null || sdpMLineIndex !in 0..16)) return@runCatching
                        deliver(webSocket, IncomingSocketEvent.CallIce(callId, candidate, sdpMid, sdpMLineIndex))
                    }
                    "call.peer_ringing" -> {
                        val callId = element["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        deliver(webSocket, IncomingSocketEvent.CallPeerRinging(callId))
                    }
                    "call.accepted" -> {
                        val callId = element["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        deliver(webSocket, IncomingSocketEvent.CallAccepted(callId))
                    }
                    "call.answered_elsewhere" -> {
                        val callId = element["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        deliver(webSocket, IncomingSocketEvent.CallAnsweredElsewhere(callId))
                    }
                    "call.history_changed" -> { deliver(webSocket, IncomingSocketEvent.CallHistoryChanged) }
                    "call.ended" -> {
                        val callId = element["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val reason = element["reason"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        deliver(webSocket, IncomingSocketEvent.CallEnded(callId, reason))
                    }
                    "call.video_upgrade_request" -> {
                        val callId = element["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        deliver(webSocket, IncomingSocketEvent.CallVideoUpgradeRequest(callId))
                    }
                    "call.video_upgrade_accept" -> {
                        val callId = element["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        deliver(webSocket, IncomingSocketEvent.CallVideoUpgradeAccept(callId))
                    }
                    "call.video_upgrade_reject" -> {
                        val callId = element["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        deliver(webSocket, IncomingSocketEvent.CallVideoUpgradeReject(callId))
                    }
                    "call.video_upgrade_cancel" -> {
                        val callId = element["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        deliver(webSocket, IncomingSocketEvent.CallVideoUpgradeCancel(callId))
                    }
                    "call.video_state" -> {
                        val callId = element["call_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                        val enabled = element["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                        deliver(webSocket, IncomingSocketEvent.CallVideoState(callId, enabled))
                    }
                    else -> Unit
                }
            }.onFailure { webSocket.cancel() }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { webSocket.cancel() }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (isCurrent(ticket)) scheduleReconnect()
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            if (isCurrent(ticket)) scheduleReconnect()
        }
    }

    private companion object {
        const val EVENT_BUFFER = 256
        const val BASE_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 30_000L
        const val MAX_BACKOFF_SHIFT = 5
    }
}
