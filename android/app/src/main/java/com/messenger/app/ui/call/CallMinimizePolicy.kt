package com.messenger.app.ui.call

import com.messenger.app.domain.call.CallUiState

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
