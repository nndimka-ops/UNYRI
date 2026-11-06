package com.messenger.app.domain.call

import com.messenger.app.data.call.AudioRouteKind
import com.messenger.app.domain.model.CallEndReason
import com.messenger.app.domain.model.CallInfo

/** Client-side call state machine (Этап J п.29) - a strict superset of the
 *  backend's DB state machine: [Reconnecting] and [Failed] are client-local
 *  concepts the backend never tracks (it is not a media proxy and cannot
 *  observe PeerConnection health itself). UI never touches PeerConnection
 *  directly - it only ever observes this via [CallController.state]. */
sealed interface CallUiState {
    data object Idle : CallUiState
    data class OutgoingRinging(val call: CallInfo, val isMuted: Boolean = false, val audioRoute: AudioRouteKind = AudioRouteKind.EARPIECE) : CallUiState
    data class IncomingRinging(val call: CallInfo) : CallUiState
    data class Connecting(val call: CallInfo, val isMuted: Boolean = false, val audioRoute: AudioRouteKind = AudioRouteKind.EARPIECE) : CallUiState
    data class Connected(
        val call: CallInfo,
        val connectedAtElapsedRealtimeMs: Long,
        val isMuted: Boolean = false,
        val isSpeakerOn: Boolean = false,
        val localVideoEnabled: Boolean = false,
        val remoteVideoEnabled: Boolean = false,
        val videoMode: VideoMode = VideoMode.AUDIO,
        val videoUpgrade: VideoUpgradeState = VideoUpgradeState.Idle,
        val notice: CallNotice? = null,
        val remoteVideoState: RemoteVideoState = RemoteVideoState.OFF,
        val localCameraState: LocalCameraState = LocalCameraState.OFF,
        val audioRoute: AudioRouteKind = AudioRouteKind.EARPIECE,
        val localCameraIsFrontFacing: Boolean = true,
    ) : CallUiState
    data class Reconnecting(val call: CallInfo, val since: CallUiState) : CallUiState
    data class Ended(val call: CallInfo, val reason: CallEndReason) : CallUiState
    data class Failed(val call: CallInfo?, val reason: CallFailureReason) : CallUiState
}

/** Whether the call is currently negotiated as audio-only or video-capable
 *  (N.1A). Distinct from [CallUiState.Connected.localVideoEnabled] - a
 *  VIDEO call can have the camera switched off and remain video-capable. */
enum class VideoMode { AUDIO, VIDEO }

/** Tri-state remote video presentation. A signaling "camera on" is NOT proof
 *  a frame is actually being received - the UI must never hide the avatar
 *  placeholder on a signaling boolean alone. */
enum class RemoteVideoState {
    /** Peer's camera is off (signaling said so). */
    OFF,

    /** Peer says camera is on but no remote frame has been rendered yet. */
    WAITING,

    /** A real remote frame was rendered (first-frame evidence). */
    ON,
}

/** Local camera state. `localVideoEnabled=true` is a user intent, not proof
 *  the camera is producing frames - the actual capture path is tracked
 *  separately. */
enum class LocalCameraState {
    /** Camera off. */
    OFF,

    /** Camera was turned on; waiting for the first captured frame. */
    STARTING,

    /** First local frame rendered - the camera is live. */
    ON,

    /** Capture failed (no camera / permission / in use) - audio continues. */
    FAILED,
}

/** Typed, localizable reason for a failed call start - never a raw exception
 *  or API message (code-review 8). */
enum class CallFailureReason { GENERIC, NETWORK, DEVICE_RESTRICTED, BUSY }

/** Feedback for a rejected NEW action; never changes the conversation already running. */
enum class CallStartBlockedReason { ROOM, CALL, CAPTURE }

/** Explicit N.1A audio->video upgrade state machine - no scattered Boolean
 *  flags. A single upgrade request is in flight at a time. */
sealed interface VideoUpgradeState {
    /** No upgrade in progress. */
    data object Idle : VideoUpgradeState

    /** I requested video; waiting for the peer to accept/reject/timeout. */
    data object Requesting : VideoUpgradeState

    /** The peer requested video; waiting for my accept/reject. */
    data object IncomingRequest : VideoUpgradeState

    /** Accept exchanged; WebRTC renegotiation in flight. */
    data object Upgrading : VideoUpgradeState

    /** The peer rejected my request (transient, shown as a notice). */
    data object Rejected : VideoUpgradeState

    /** Renegotiation or capture failed; audio continues. */
    data object Failed : VideoUpgradeState
}

/** Transient, localized feedback shown on the connected call screen - never
 *  a terminal call state (the audio call continues underneath). */
enum class CallNotice {
    VIDEO_REQUEST_DECLINED,
    VIDEO_REQUEST_TIMEOUT,
    VIDEO_FAILED,
    CAMERA_DENIED,
    CAMERA_FAILED,
}
