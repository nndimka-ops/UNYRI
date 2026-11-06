package com.messenger.app.data.websocket

import android.content.Context
import com.messenger.app.data.signal.SignalSessionManager
import com.messenger.app.data.signal.EncryptedEnvelope
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.util.Base64

/** Memory-only snapshots. Signal authenticates one random AES key per stream,
 * not every keystroke, so offline peers do not accumulate skipped Signal keys. */
class LiveDrafts(context: Context, private val auth: AuthRepository,
    private val socket: MessagingSocketClient, private val signal: SignalSessionManager) {
    private val prefs = context.getSharedPreferences("chat-privacy", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sending = Mutex()
    private val _peerText = MutableStateFlow("")
    val peerText: StateFlow<String> = _peerText
    @Volatile private var chat: String? = null
    @Volatile private var peer: String? = null
    private var contextGeneration = 0L
    private var epoch = auth.currentSessionEpoch()
    private var stream = ""
    private var key: ByteArray? = null
    private var envelopes: List<EncryptedEnvelope> = emptyList()
    private var revision = 0L
    private var desiredText = ""
    private var sentAt = 0L
    private var receivedAt = 0L
    private data class Inbound(val key: ByteArray, var revision: Long = 0)
    private val latestTimestamp = linkedMapOf<String, Long>()
    private val inbound = linkedMapOf<String, Inbound>()
    private fun enabled() = prefs.getBoolean("live-draft", false)
    init {
        scope.launch { socket.connectionState.collect { state ->
            if (state != SocketConnectionState.CONNECTED) {
                contextGeneration++; key = null; envelopes = emptyList(); desiredText = ""; _peerText.value = ""; sentAt = 0
            }
        } }

        scope.launch { socket.draftFrames.collect { frame -> try { receive(frame) } catch (_: Exception) { /* no payload diagnostics */ } } }
        scope.launch { while (isActive) { delay(500); if (System.currentTimeMillis() - receivedAt > 8000 || socket.connectionState.value != SocketConnectionState.CONNECTED) _peerText.value = "" } }
        scope.launch { auth.authState.collect { if (auth.currentSessionEpoch() != epoch) { chat = null; peer = null; key = null; envelopes = emptyList(); inbound.clear(); latestTimestamp.clear(); _peerText.value = ""; epoch = auth.currentSessionEpoch() } } }
    }
    fun open(chatId: String, userId: String) {
        if (chat != chatId || peer != userId) { contextGeneration++; chat = chatId; peer = userId; key = null; envelopes = emptyList(); _peerText.value = ""; sentAt = 0 }
    }
    fun close(chatId: String) { if (chat == chatId) { runCatching { emit(chatId, "") }; contextGeneration++; desiredText = ""; chat = null; peer = null; key = null; envelopes = emptyList(); _peerText.value = "" } }
    fun update(text: String) {
        val targetChat = chat ?: return
        val targetPeer = peer ?: return
        val ticket = epoch
        val contextTicket = contextGeneration
        desiredText = if (enabled()) text.take(2000) else ""
        val snapshot = desiredText
        if (sending.isLocked) return
        val now = System.currentTimeMillis()
        if (socket.connectionState.value != SocketConnectionState.CONNECTED || now - sentAt < 1000) return
        if (snapshot.isEmpty() && key == null) return
        sentAt = now
        scope.launch { sending.withLock {
            try {
                if (ticket != auth.currentSessionEpoch() || contextTicket != contextGeneration) return@withLock
                if (chat != targetChat || peer != targetPeer) return@withLock
                if (key == null) {
                    if (!enabled() || snapshot.isEmpty()) return@withLock
                    val token = auth.currentAccessToken() ?: return@withLock
                    if (ticket != auth.currentSessionEpoch() || contextTicket != contextGeneration) return@withLock
                    val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    val id = UUID.randomUUID().toString()
                    val wrapped = JSONObject().put("purpose", "coco.live-draft.v1").put("chat", targetChat).put("stream", id).put("key", b64(fresh)).toString()
                    val recipients = signal.buildEnvelopesForUser(targetPeer, token, wrapped.toByteArray(Charsets.UTF_8))
                    if (ticket != auth.currentSessionEpoch() || contextTicket != contextGeneration || chat != targetChat || !enabled()) return@withLock
                    key = fresh; stream = id; envelopes = recipients; revision = 0
                }
                emit(targetChat, if (enabled()) desiredText else "")
            } catch (_: Exception) { /* best effort; never queued or written to logs */ }
        } }
    }
    private fun emit(targetChat: String, text: String) {
        if (key == null) return
        revision++
        val aad = "$targetChat|$stream|$revision".toByteArray(Charsets.UTF_8)
        val body = JSONObject().put("text", text).put("at", System.currentTimeMillis()).toString().toByteArray(Charsets.UTF_8)
        val sealed = LiveDraftCipher.seal(key ?: return, body, aad)
        val list = JSONArray()
        envelopes.forEach { list.put(JSONObject().put("recipient_device_id", it.recipientDeviceId).put("message_type", it.messageType).put("ciphertext", it.ciphertext)) }
        socket.sendRaw(JSONObject().put("type", "chat.draft").put("chat_id", targetChat).put("stream", stream).put("revision", revision).put("payload", b64(sealed)).put("envelopes", list).toString())
    }
    private suspend fun receive(raw: String) {
        val frame = JSONObject(raw)
        val target = chat ?: return
        val sender = peer ?: return
        val ticket = epoch
        if (ticket != auth.currentSessionEpoch()) return
        val contextTicket = contextGeneration
        if (frame.getString("chat_id") != target || frame.getString("user_id") != sender) return
        val id = frame.getString("stream")
        val cacheId = "$sender|${frame.getString("device_id")}|$id"
        var entry = inbound[cacheId]
        if (entry == null) {
            val envelope = frame.getJSONObject("envelope")
            val token = auth.currentAccessToken() ?: return
            // Drain native/identity writes before logout clears account data.
            val plaintext = auth.withSessionWrite(ticket) {
                if (contextTicket != contextGeneration) return@withSessionWrite null
                val device = signal.resolveSignalDeviceId(sender, frame.getString("device_id"), token)
                if (ticket != auth.currentSessionEpoch() || contextTicket != contextGeneration) return@withSessionWrite null
                signal.decrypt(sender, device, envelope.getInt("message_type"), envelope.getString("ciphertext"))
            } ?: return
            if (ticket != auth.currentSessionEpoch() || contextTicket != contextGeneration) return
            val wrapped = JSONObject(String(plaintext, Charsets.UTF_8))
            if (wrapped.optString("purpose") != "coco.live-draft.v1" || wrapped.optString("chat") != target || wrapped.optString("stream") != id) return
            val bytes = Base64.getDecoder().decode(wrapped.getString("key")); if (bytes.size != 32) return
            entry = Inbound(bytes)
            if (inbound.size >= 32) inbound.remove(inbound.keys.first())
            inbound[cacheId] = entry
        }
        val rev = frame.getLong("revision"); if (rev <= entry.revision) return
        val sealed = Base64.getDecoder().decode(frame.getString("payload")); if (sealed.size !in 28..16384) return
        val body = JSONObject(String(LiveDraftCipher.open(entry.key, sealed, "$target|$id|$rev".toByteArray(Charsets.UTF_8)), Charsets.UTF_8))
        entry.revision = rev
        val timestamp = body.getLong("at")
        if (kotlin.math.abs(System.currentTimeMillis() - timestamp) > 15000) return
        val displayKey = "$target|$sender"
        if (timestamp < (latestTimestamp[displayKey] ?: 0)) return
        if (ticket != auth.currentSessionEpoch() || contextTicket != contextGeneration || chat != target || peer != sender) return
        if (latestTimestamp.size >= 32) latestTimestamp.remove(latestTimestamp.keys.first())
        latestTimestamp[displayKey] = timestamp
        receivedAt = System.currentTimeMillis(); _peerText.value = body.getString("text").take(2000)
    }
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
