package com.messenger.app.data.websocket

import com.messenger.app.data.api.MessageDto

/** Realtime events this account's WebSocket connection can push - either a
 *  message envelope (fast path for [com.messenger.app.data.sync.MessageIngestionPipeline])
 *  or a receipt watermark update (fast path for chat-list/receipt UI). Both
 *  are always independently recoverable via HTTP (`/sync`, `GET /chats`) if
 *  ever missed - this is purely the low-latency notification channel. */
sealed interface IncomingSocketEvent {
    data object ChatManagementChanged : IncomingSocketEvent
    data class ChatDeleted(val chatId: String) : IncomingSocketEvent
    data class NewMessage(val message: MessageDto) : IncomingSocketEvent
    data class ReceiptUpdate(
        val chatId: String,
        val userId: String,
        val deliveredSequence: Long,
        val readSequence: Long,
    ) : IncomingSocketEvent

    // --- Этап J: call signaling frames (opaque SDP/ICE relay + state
    // notifications) - same authenticated socket, logically separate
    // subsystem (see CallSignalingClient, which filters these out of the
    // shared event stream for CallController). ---
    data object CallHistoryChanged : IncomingSocketEvent
    data class CallRinging(val callId: String, val callerUserId: String, val mediaKind: String) : IncomingSocketEvent
    data class CallOffer(val callId: String, val sdp: String) : IncomingSocketEvent
    data class CallAnswer(val callId: String, val sdp: String) : IncomingSocketEvent
    data class CallIce(
        val callId: String,
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int?,
    ) : IncomingSocketEvent
    data class CallAccepted(val callId: String) : IncomingSocketEvent
    data class CallAnsweredElsewhere(val callId: String) : IncomingSocketEvent
    data class CallEnded(val callId: String, val reason: String) : IncomingSocketEvent

    // --- N.1A: audio->video upgrade frames (transient, same call) ---
    data class CallVideoUpgradeRequest(val callId: String) : IncomingSocketEvent
    data class CallVideoUpgradeAccept(val callId: String) : IncomingSocketEvent
    data class CallVideoUpgradeReject(val callId: String) : IncomingSocketEvent
    data class CallVideoUpgradeCancel(val callId: String) : IncomingSocketEvent
    data class CallVideoState(val callId: String, val enabled: Boolean) : IncomingSocketEvent
}
