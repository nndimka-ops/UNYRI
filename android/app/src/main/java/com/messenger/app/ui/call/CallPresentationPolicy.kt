package com.messenger.app.ui.call

import com.messenger.app.data.call.AudioRouteKind
import com.messenger.app.domain.call.*

/** Presentation rules only. Never changes the negotiated call or camera intent. */
object CallPresentationPolicy {
    data class VideoControls(val callId: String, val localVideoEnabled: Boolean, val isMuted: Boolean, val audioRoute: AudioRouteKind)
    const val VIDEO_CONTROL_COUNT = 4
    const val TOP_BAR_HEIGHT = 56f
    const val TOP_SCRIM_HEIGHT = 150f
    const val BOTTOM_SCRIM_HEIGHT = 200f
    const val FLIP_CIRCLE = 30f
    const val FLIP_TARGET = 44f
    const val RECONNECT_DELAY_MS = 1_200L

    fun connected(state: CallUiState): CallUiState.Connected? = when (state) {
        is CallUiState.Connected -> state
        is CallUiState.Reconnecting -> connected(state.since)
        else -> null
    }

    fun isVideoCall(state: CallUiState): Boolean = connected(state)?.let { it.videoMode == VideoMode.VIDEO } ?: when (state) {
        is CallUiState.OutgoingRinging -> state.call.mediaKind == com.messenger.app.domain.model.CallMediaKind.VIDEO
        is CallUiState.IncomingRinging -> state.call.mediaKind == com.messenger.app.domain.model.CallMediaKind.VIDEO
        is CallUiState.Connecting -> state.call.mediaKind == com.messenger.app.domain.model.CallMediaKind.VIDEO
        else -> false
    }

    fun videoControls(state: CallUiState): VideoControls? {
        if (!isVideoCall(state)) return null
        return when (state) {
            is CallUiState.OutgoingRinging -> VideoControls(state.call.callId, state.localVideoEnabled, state.isMuted, state.audioRoute)
            is CallUiState.Connecting -> VideoControls(state.call.callId, state.localVideoEnabled, state.isMuted, state.audioRoute)
            else -> connected(state)?.let { VideoControls(it.call.callId, it.localVideoEnabled, it.isMuted, it.audioRoute) }
        }
    }

    fun cameraTarget(requestedId: String, live: CallUiState): Boolean? =
        videoControls(live)?.takeIf { it.callId == requestedId }?.let { !it.localVideoEnabled }

    fun controlsVisible(available: Boolean, terminal: Boolean): Boolean = available && !terminal

    fun videoUpgradeVisible(state: CallUiState): Boolean = (state as? CallUiState.Connected)?.let {
        it.videoMode == VideoMode.AUDIO &&
            (it.videoUpgrade == VideoUpgradeState.Idle || it.videoUpgrade == VideoUpgradeState.Requesting)
    } == true

    fun flipVisible(state: CallUiState, multipleCameras: Boolean, chrome: Boolean = true): Boolean =
        connected(state)?.let { chrome && multipleCameras && it.videoMode == VideoMode.VIDEO &&
            it.localVideoEnabled && it.localCameraState == LocalCameraState.ON } == true

    fun canFlip(requestedId: String, live: CallUiState, multipleCameras: Boolean): Boolean =
        connected(live)?.call?.callId == requestedId && flipVisible(live, multipleCameras)

    fun torchVisible(state: CallUiState, available: Boolean, chrome: Boolean = true): Boolean =
        connected(state)?.let { chrome && available && !it.localCameraIsFrontFacing &&
            it.videoMode == VideoMode.VIDEO && it.localVideoEnabled && it.localCameraState == LocalCameraState.ON } == true

    fun canTorch(requestedId: String, live: CallUiState, available: Boolean): Boolean =
        connected(live)?.call?.callId == requestedId && torchVisible(live, available)

    fun anyPicture(state: CallUiState.Connected, keptRemoteFrame: Boolean = false): Boolean =
        state.remoteVideoState == RemoteVideoState.ON || keptRemoteFrame ||
            (state.localVideoEnabled && state.localCameraState == LocalCameraState.ON)

    fun rendererVisibility(foreground: Boolean, visible: Boolean, mini: Boolean, pip: Boolean,
        localRunning: Boolean, remoteRunning: Boolean = true, started: Boolean = foreground): Pair<Boolean, Boolean> {
        // PiP is visible while its Activity is PAUSED (STARTED), but not STOPPED.
        val stage = visible && (foreground || (pip && started))
        return (foreground && visible && !mini && !pip && localRunning) to (stage && remoteRunning)
    }

    fun outputHighlighted(route: AudioRouteKind, video: Boolean, hasEarpiece: Boolean): Boolean = when (route) {
        AudioRouteKind.SPEAKER -> !video && hasEarpiece
        AudioRouteKind.EARPIECE -> video
        AudioRouteKind.BLUETOOTH, AudioRouteKind.WIRED_HEADSET -> false
    }

    fun assumedOutput(video: Boolean, hasEarpiece: Boolean): AudioRouteKind =
        if (video || !hasEarpiece) AudioRouteKind.SPEAKER else AudioRouteKind.EARPIECE

    fun controlsPerRow(width: Float): Int = if (
        CallLayoutPolicy.trayWidth(width) - 2 * CallLayoutPolicy.trayPadding(width) <
        4 * CallLayoutPolicy.CONTROL_TOUCH_MIN + 3 * CallLayoutPolicy.controlSpacing(width, 4)
    ) 2 else 4

    fun capsuleVisible(chrome: Boolean, alert: Boolean, ended: Boolean = false): Boolean = chrome || (alert && !ended)
    fun sheetEnabled(state: CallUiState): Boolean = state !is CallUiState.Ended && state !is CallUiState.Failed && state !is CallUiState.Idle
    fun verificationCardVisible(verifiedCommitment: Boolean, code: List<Int>?): Boolean =
        verifiedCommitment && code?.size == 4 && code.all { it in 0..63 }
}
