package com.messenger.app.data.call

import com.messenger.app.data.api.IceServerDto
import com.messenger.app.domain.model.CallMediaKind
import kotlinx.coroutines.flow.Flow

/** Abstraction over the real WebRTC `PeerConnection` lifecycle (Этап J
 *  п.30) - [CallController] never touches `livekit.org.webrtc.*` directly, only
 *  this interface. Real implementation: [WebRtcEngineImpl] (native WebRTC,
 *  Android-only, cannot run in plain JVM unit tests). Test double: a fake
 *  implementing this same interface with no native code, used by
 *  CallControllerTest to verify the ICE-queueing/state-machine logic in
 *  [CallController] without ever touching real media. */
interface WebRtcEngine {
    fun dtlsFingerprints(): Pair<String, String>? = null
    val localOrientation: kotlinx.coroutines.flow.StateFlow<CallFrameOrientation> get() = noLocalCallOrientation
    val remoteGeometry: kotlinx.coroutines.flow.StateFlow<CallRemoteGeometry> get() = noRemoteCallGeometry
    val events: Flow<WebRtcEvent>
    /** Cold, screen-owned receiver-only meter; defaults to silence for non-native engines. */
    fun remoteVoiceLevels(): Flow<Float> = kotlinx.coroutines.flow.flowOf(0f)
    /** Keep sinks/EGL attached, but stop drawing surfaces the UI cannot show. */
    fun setRendererVisibility(localVisible: Boolean, remoteVisible: Boolean) {}

    /** Acquire communication routing without opening microphone/camera. */
    fun prepareCallAudio() {}

    /** Synchronous, account-fenced Contacts membership for the next initialize. */
    fun setPeerContact(isContact: Boolean) {}

    /** Read-only preflight before acquiring audio, camera or a native connection. */
    fun relayUnavailable(iceServers: List<IceServerDto>, peerIsContact: Boolean): Boolean = false

    /** (Re)creates the underlying `PeerConnectionFactory`/`PeerConnection`
     *  for a fresh call - must be called exactly once per call, before any
     *  other method. */
    fun initialize(iceServers: List<IceServerDto>, mediaKind: CallMediaKind)
    /** Negotiate video without opening the camera when its pre-media intent is off. */
    fun initialize(iceServers: List<IceServerDto>, mediaKind: CallMediaKind, captureVideo: Boolean) {
        initialize(iceServers, mediaKind)
        if (mediaKind == CallMediaKind.VIDEO && !captureVideo) setVideoEnabled(false)
    }
    suspend fun createOffer(): String
    suspend fun createOffer(isCurrent: () -> Boolean): String {
        check(isCurrent()) { "Offer superseded" }
        return createOffer().also { check(isCurrent()) { "Offer superseded" } }
    }
    suspend fun createAnswer(): String
    suspend fun createAnswer(isCurrent: () -> Boolean): String {
        check(isCurrent()) { "Answer superseded" }
        return createAnswer().also { check(isCurrent()) { "Answer superseded" } }
    }
    suspend fun setRemoteDescription(type: String, sdp: String)
    suspend fun setRemoteDescription(type: String, sdp: String, isCurrent: () -> Boolean) {
        check(isCurrent()) { "Description superseded" }
        setRemoteDescription(type, sdp)
        check(isCurrent()) { "Description superseded" }
    }
    suspend fun rollbackLocalDescription() {}
    /** A queued rollback must still belong to this negotiation when the native lock opens. */
    suspend fun rollbackLocalDescription(isCurrent: () -> Boolean) {
        if (isCurrent()) rollbackLocalDescription()
    }
    fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)

    /** Standard WebRTC ICE restart (Этап J.1-6) - re-gathers ICE credentials
     *  and candidates and re-runs connectivity checks, so a transient network
     *  drop can recover without tearing down the PeerConnection. New
     *  candidates are delivered through the usual [WebRtcEvent.LocalIceCandidate]. */
    fun restartIce()

    fun setMuted(muted: Boolean)
    fun setSpeakerOn(on: Boolean)
    fun setVideoEnabled(enabled: Boolean)
    /** Encoding-only congestion pause. Camera intent/capture stays owned by the user. */
    fun setVideoSendActive(active: Boolean) {}
    fun currentMediaProfile(): CallMediaProfile = CallMediaLadder.cellular
    fun setDiagnosticsRecording(recording: Boolean) {}
    val torchState: kotlinx.coroutines.flow.StateFlow<CallTorchState> get() = noCallTorch
    fun setTorch(on: Boolean) {}
    /** The capture owner, not the delayed UI snapshot, decides the next light state. */
    fun toggleTorch() {}
    fun switchCamera()
    fun isCurrentCameraGeneration(generation: Long): Boolean = false
    fun localCameraIsFrontFacing(): Boolean = true

    /** N.1A audio->video upgrade: creates the local camera track (if not
     *  already running) and adds it to the PeerConnection so a subsequent
     *  [createOffer] negotiates a video m-line. Returns false when the
     *  device has no usable camera or CAMERA permission is missing - the
     *  call must then stay audio-only, never crash. */
    // capture=false prepares a disabled track for receiving a peer upgrade;
    // it must not open the local camera or require its permission.
    fun startLocalVideo(capture: Boolean = true): Boolean

    /** N.1A video->audio: stops capture, removes the video track from the
     *  PeerConnection and disposes it. Unlike [setVideoEnabled](false) this
     *  actually removes the video m-line on the next renegotiation. */
    fun stopLocalVideo()

    /** Explicit negotiated video intent, independent of local camera cleanup.
     * Applied before every offer/answer, including ICE restart. */
    fun setVideoNegotiationEnabled(enabled: Boolean) {}

    /** Whether the device has more than one camera (controls Flip visibility). */
    fun hasMultipleCameras(): Boolean

    /** Available output routes right now (Этап J.1 п.13/14) - only routes
     *  an actual device supports are ever returned (e.g. no BLUETOOTH entry
     *  if nothing is paired/connected). */
    fun availableAudioRoutes(): List<AudioRoute>
    fun setAudioRoute(route: AudioRoute)
    fun currentAudioRoute(): AudioRoute

    /** `renderer` is a `livekit.org.webrtc.SurfaceViewRenderer` in the real
     *  implementation - typed `Any?` here so this interface (and therefore
     *  [com.messenger.app.domain.call.CallController]) never has a compile
     *  dependency on `livekit.org.webrtc.*`, keeping it usable from a plain JVM
     *  test with a fake engine. */
    fun attachLocalRenderer(renderer: Any?)
    fun attachRemoteRenderer(renderer: Any?)

    /** Detaches the local preview / remote video renderer ONLY if [renderer]
     *  is the renderer currently attached - an old DisposableEffect disposal
     *  must never detach a newer renderer (background/rotation black-screen
     *  race). Removes the `VideoTrack` sink and releases the renderer. Safe
     *  to call multiple times or when nothing is attached. */
    fun detachLocalRenderer(renderer: Any?)
    fun detachRemoteRenderer(renderer: Any?)

    /** Re-initializes the currently-attached renderers' EGL surfaces after
     *  the Activity resumes from background. Some devices (MIUI) destroy the
     *  SurfaceView surface while backgrounded and SurfaceViewRenderer alone
     *  does not always recover - this nudges it back without touching the
     *  PeerConnection or the running camera capture. */
    fun reinitRenderers()

    /** Re-arm first-frame evidence when the peer restarts its camera. */
    fun expectRemoteVideoFrame() {}

    /** Whether the local camera video track was actually set up. `false` when
     *  the call is AUDIO-only, the device has no camera, or CAMERA permission
     *  is denied (the call must then behave as audio-only, never crash). */
    fun localVideoRunning(): Boolean

    /** Releases the PeerConnection/tracks/camera/audio focus - safe to call
     *  multiple times or when never initialized. */
    fun dispose()

    /** Full snapshot for the call's single 1 Hz policy/diagnostics read.
     * Never includes full SDP, IP addresses, or credentials. */
    suspend fun getStats(): WebRtcCallStats?
}

private val noLocalCallOrientation = kotlinx.coroutines.flow.MutableStateFlow(CallFrameOrientation())
private val noRemoteCallGeometry = kotlinx.coroutines.flow.MutableStateFlow(CallRemoteGeometry())

enum class PeerConnectionState { NEW, CHECKING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** The four output route kinds a device can offer (Этап J.1-7). */
enum class AudioRouteKind { EARPIECE, SPEAKER, BLUETOOTH, WIRED_HEADSET }

/** An actual, selectable output route. [label] is the human-readable device
 *  name (e.g. "Pixel Buds" for a Bluetooth headset) - always non-empty, with
 *  a sensible fallback when the real device name isn't available/permitted. */
data class AudioRoute(val kind: AudioRouteKind, val label: String, val deviceId: Int? = null)

sealed interface WebRtcEvent {
    data class VideoSendPausedChanged(val paused: Boolean) : WebRtcEvent
    data class DeadPairDetected(val sendsRefused: Boolean, val packets: Long, val refused: Long, val watchedMs: Long) : WebRtcEvent
    data object NetworkInterfaceChanged : WebRtcEvent
    data class Diagnostics(val metrics: Map<String, Double>) : WebRtcEvent
    data class LocalCameraFailed(val cameraGeneration: Long) : WebRtcEvent
    data class LocalCameraFacingChanged(val cameraGeneration: Long, val isFrontFacing: Boolean) : WebRtcEvent
    data class LocalIceCandidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : WebRtcEvent
    data class ConnectionStateChanged(val state: PeerConnectionState) : WebRtcEvent

    /** Remote peer's signaling toggled their camera (their `setVideoEnabled`).
     *  This is NOT proof a frame is being received - see [RemoteFirstFrame]. */
    data class RemoteVideoEnabled(val enabled: Boolean) : WebRtcEvent

    /** The remote renderer actually rendered a first frame - proof the remote
     *  video path is live (drives RemoteVideoState.ON). */
    data object RemoteFirstFrame : WebRtcEvent

    /** The local renderer actually rendered a first captured frame - proof
     *  the local camera is producing video (drives LocalCameraState.ON). */
    data object LocalFirstFrame : WebRtcEvent

    /** A hardware output route changed (Bluetooth/wired headset connected or
     *  disconnected) while a call is active - the UI must re-read the current
     *  route without waiting for an unrelated state change. */
    data class AudioRouteChanged(val route: AudioRoute) : WebRtcEvent
}
