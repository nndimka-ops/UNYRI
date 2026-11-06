package com.messenger.app.data.call

import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.flow.Flow

/** Logically separate signaling channel for opaque SDP/ICE relay (Этап J
 *  п.1) - physically reuses the existing authenticated messaging
 *  WebSocket (see [CallSignalingClientImpl]), never opens a second socket.
 *  [CallController] only ever talks to this narrow interface, never to
 *  [com.messenger.app.data.websocket.MessagingSocketClient] directly. */
interface CallSignalingClient {
    val events: Flow<CallSignalingEvent>

    /** Transport-level connection state of the underlying socket, so
     *  [com.messenger.app.domain.call.CallController] can reconcile its
     *  call state after a WS reconnect (Этап J.1-6) - a dropped signaling
     *  socket must not leave the call UI stuck in a stale state. */
    val connectionState: Flow<SocketConnectionState>

    /** Returns false if there's currently no live connection - the caller
     *  (CallController) decides what that means for its own retry/failure
     *  policy; this layer never queues or retries frames itself. */
    fun sendOffer(callId: String, sdp: String): Boolean
    fun sendAnswer(callId: String, sdp: String): Boolean
    fun sendIce(callId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?): Boolean

    /** N.1A audio->video upgrade frames (transient, same call). */
    fun sendVideoUpgradeRequest(callId: String): Boolean
    fun sendVideoUpgradeAccept(callId: String): Boolean
    fun sendVideoUpgradeReject(callId: String): Boolean
    fun sendVideoUpgradeCancel(callId: String): Boolean
    fun sendVideoState(callId: String, enabled: Boolean): Boolean

    /** N.2R call-liveness heartbeat: sent periodically while this device is
     *  actually in a call. Keeps the server's `last_activity_at` fresh so a
     *  connected call is never swept as abandoned while it is still live. */
    fun sendKeepAlive(callId: String): Boolean
}

sealed interface CallSignalingEvent {
    data class Ringing(val callId: String, val callerUserId: String, val mediaKind: String) : CallSignalingEvent
    data class Offer(val callId: String, val sdp: String) : CallSignalingEvent
    data class Answer(val callId: String, val sdp: String) : CallSignalingEvent
    data class Ice(val callId: String, val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) :
        CallSignalingEvent
    data class Accepted(val callId: String) : CallSignalingEvent
    data class AnsweredElsewhere(val callId: String) : CallSignalingEvent
    data class Ended(val callId: String, val reason: String) : CallSignalingEvent
    data class VideoUpgradeRequest(val callId: String) : CallSignalingEvent
    data class VideoUpgradeAccept(val callId: String) : CallSignalingEvent
    data class VideoUpgradeReject(val callId: String) : CallSignalingEvent
    data class VideoUpgradeCancel(val callId: String) : CallSignalingEvent
    data class VideoState(val callId: String, val enabled: Boolean) : CallSignalingEvent
}
