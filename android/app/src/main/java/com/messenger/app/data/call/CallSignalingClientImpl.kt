package com.messenger.app.data.call

import com.messenger.app.data.websocket.IncomingSocketEvent
import com.messenger.app.data.websocket.MessagingSocketClient
import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Filters [MessagingSocketClient.events] down to just the call.* frames
 *  and builds outgoing call.* JSON frames - the one real socket connection
 *  is still owned entirely by [MessagingSocketClient]. */
class CallSignalingClientImpl(
    private val socketClient: MessagingSocketClient,
    private val json: Json,
) : CallSignalingClient {

    override val events: Flow<CallSignalingEvent> = socketClient.events.mapNotNull { event ->
        when (event) {
            is IncomingSocketEvent.CallRinging -> CallSignalingEvent.Ringing(event.callId, event.callerUserId, event.mediaKind)
            is IncomingSocketEvent.CallOffer -> CallSignalingEvent.Offer(event.callId, event.sdp)
            is IncomingSocketEvent.CallAnswer -> CallSignalingEvent.Answer(event.callId, event.sdp)
            is IncomingSocketEvent.CallIce -> CallSignalingEvent.Ice(event.callId, event.candidate, event.sdpMid, event.sdpMLineIndex)
            is IncomingSocketEvent.CallAccepted -> CallSignalingEvent.Accepted(event.callId)
            is IncomingSocketEvent.CallAnsweredElsewhere -> CallSignalingEvent.AnsweredElsewhere(event.callId)
            is IncomingSocketEvent.CallEnded -> CallSignalingEvent.Ended(event.callId, event.reason)
            is IncomingSocketEvent.CallVideoUpgradeRequest -> CallSignalingEvent.VideoUpgradeRequest(event.callId)
            is IncomingSocketEvent.CallVideoUpgradeAccept -> CallSignalingEvent.VideoUpgradeAccept(event.callId)
            is IncomingSocketEvent.CallVideoUpgradeReject -> CallSignalingEvent.VideoUpgradeReject(event.callId)
            is IncomingSocketEvent.CallVideoUpgradeCancel -> CallSignalingEvent.VideoUpgradeCancel(event.callId)
            is IncomingSocketEvent.CallVideoState -> CallSignalingEvent.VideoState(event.callId, event.enabled)
            else -> null
        }
    }

    override val connectionState: Flow<SocketConnectionState> = socketClient.connectionState

    override fun sendOffer(callId: String, sdp: String): Boolean =
        send(buildJsonObject { put("type", "call.offer"); put("call_id", callId); put("sdp", sdp) })

    override fun sendAnswer(callId: String, sdp: String): Boolean =
        send(buildJsonObject { put("type", "call.answer"); put("call_id", callId); put("sdp", sdp) })

    override fun sendIce(callId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?): Boolean =
        send(
            buildJsonObject {
                put("type", "call.ice")
                put("call_id", callId)
                put("candidate", candidate)
                put("sdp_mid", sdpMid)
                put("sdp_m_line_index", sdpMLineIndex)
            },
        )

    override fun sendVideoUpgradeRequest(callId: String): Boolean =
        send(buildJsonObject { put("type", "call.video_upgrade_request"); put("call_id", callId) })

    override fun sendVideoUpgradeAccept(callId: String): Boolean =
        send(buildJsonObject { put("type", "call.video_upgrade_accept"); put("call_id", callId) })

    override fun sendVideoUpgradeReject(callId: String): Boolean =
        send(buildJsonObject { put("type", "call.video_upgrade_reject"); put("call_id", callId) })

    override fun sendVideoUpgradeCancel(callId: String): Boolean =
        send(buildJsonObject { put("type", "call.video_upgrade_cancel"); put("call_id", callId) })

    override fun sendVideoState(callId: String, enabled: Boolean): Boolean =
        send(buildJsonObject { put("type", "call.video_state"); put("call_id", callId); put("enabled", enabled) })

    override fun sendKeepAlive(callId: String): Boolean =
        send(buildJsonObject { put("type", "call.keep_alive"); put("call_id", callId) })

    private fun send(payload: kotlinx.serialization.json.JsonObject): Boolean =
        socketClient.sendRaw(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload))
}
