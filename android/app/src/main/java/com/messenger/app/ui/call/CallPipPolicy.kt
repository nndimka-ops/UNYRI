package com.messenger.app.ui.call

import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.call.RemoteVideoState
import com.messenger.app.domain.call.VideoMode

/** Keep the negotiated video session across ICE recovery, but conceal stale frames. */
object CallPipPolicy {
    fun videoState(state: CallUiState): CallUiState.Connected? = when (state) {
        is CallUiState.Connected -> state.takeIf { it.videoMode == VideoMode.VIDEO }
        is CallUiState.Reconnecting -> videoState(state.since)?.copy(remoteVideoState = RemoteVideoState.WAITING)
        else -> null
    }

    fun canEnter(state: CallUiState, supported: Boolean, locked: Boolean): Boolean =
        supported && !locked && videoState(state)?.remoteVideoState == RemoteVideoState.ON

    /** A window already on screen may show the safe avatar while video resumes.
     *  Entry still needs a fresh remote frame; retaining the same negotiated
     *  call must not finish its Activity on ICE recovery or camera-off. */
    fun canRemain(state: CallUiState, supported: Boolean, locked: Boolean): Boolean =
        supported && !locked && videoState(state) != null
}
