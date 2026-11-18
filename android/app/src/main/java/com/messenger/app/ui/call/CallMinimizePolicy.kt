package com.messenger.app.ui.call

import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.call.VideoMode
import com.messenger.app.domain.model.CallInfo
import com.messenger.app.domain.model.CallMediaKind
import com.messenger.app.data.call.AudioRouteKind

/** Presentation only: minimizing never cancels signaling or changes the audio route. */
object CallMinimizePolicy {
    const val HEADER_WIDTH_DP = 84f
    const val HEADER_HEIGHT_DP = 56f
    const val HEADER_INSET_DP = 12f
    const val HEADER_CLEARANCE_DP = 12f

    fun callId(state: CallUiState): String? = when (state) {
        is CallUiState.OutgoingRinging -> state.call.callId
        is CallUiState.Connecting -> state.call.callId
        is CallUiState.Connected -> state.call.callId
        is CallUiState.Reconnecting -> state.call.callId
        else -> null
    }

    fun canComplete(requested: String?, state: CallUiState): Boolean =
        requested != null && requested == callId(state)

    fun isDownwardSwipe(x: Float, y: Float): Boolean =
        x.isFinite() && y.isFinite() && y >= 64f && y > kotlin.math.abs(x) * 1.5f
}

/** Last negotiated controls, never a signaling/media owner. One call ID at a time. */
internal data class CallTerminalControls(
    val callId: String,
    val videoLayout: Boolean,
    val localVideoEnabled: Boolean,
    val isMuted: Boolean,
    val audioRoute: AudioRouteKind,
) {
    val videoControls: CallPresentationPolicy.VideoControls?
        get() = if (videoLayout) CallPresentationPolicy.VideoControls(callId, localVideoEnabled, isMuted, audioRoute) else null

    companion object {
        fun initial(call: CallInfo) = CallTerminalControls(call.callId, call.mediaKind == CallMediaKind.VIDEO,
            call.mediaKind == CallMediaKind.VIDEO, false, AudioRouteKind.EARPIECE)
    }
}

/** Ended/Failed no longer contain negotiated media mode. Keep its final presentation
 * through termination, but clear on idle and never borrow another call's controls. */
internal class CallTerminalPresentationMemory(initial: CallTerminalControls? = null) {
    var snapshot: CallTerminalControls? = initial
        private set

    fun observe(state: CallUiState): CallTerminalControls? {
        val call = when (state) {
            is CallUiState.IncomingRinging -> state.call
            is CallUiState.OutgoingRinging -> state.call
            is CallUiState.Connecting -> state.call
            is CallUiState.Connected -> state.call
            is CallUiState.Reconnecting -> state.call
            is CallUiState.Ended -> state.call
            is CallUiState.Failed -> state.call
            CallUiState.Idle -> null
        }
        if (call == null) { snapshot = null; return null }
        val previous = snapshot?.takeIf { it.callId == call.callId }
        val connected = CallPresentationPolicy.connected(state)?.takeIf { it.call.callId == call.callId }
        val next = when {
            connected != null -> CallTerminalControls(call.callId, connected.videoMode == VideoMode.VIDEO,
                connected.localVideoEnabled, connected.isMuted, connected.audioRoute)
            state is CallUiState.OutgoingRinging -> CallTerminalControls(call.callId, call.mediaKind == CallMediaKind.VIDEO,
                state.localVideoEnabled, state.isMuted, state.audioRoute)
            state is CallUiState.Connecting -> CallTerminalControls(call.callId, call.mediaKind == CallMediaKind.VIDEO,
                state.localVideoEnabled, state.isMuted, state.audioRoute)
            else -> previous ?: CallTerminalControls.initial(call)
        }
        if (next != snapshot) snapshot = next
        return snapshot
    }
}
