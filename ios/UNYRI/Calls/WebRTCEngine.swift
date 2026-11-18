import Combine
import Foundation

/// Engine-level events the controller reduces over (Android `WebRtcEvent`).
enum WebRtcEvent: Equatable {
    case localIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)
    case connectionStateChanged(PeerConnectionState)
    case remoteVideoEnabled(Bool)
    case remoteFirstFrame
    case localCaptureInterrupted(Bool)
    case localFirstFrame
    case localCameraFailed(cameraGeneration: UInt64)
    case localCameraFacingChanged(cameraGeneration: UInt64, isFrontFacing: Bool)
    /// The running camera's torch changed outside a camera start, stop or
    /// switch: the capture was interrupted or resumed, or the device took the
    /// torch away (heat) or gave it back. The controller reads it again.
    case localTorchChanged(cameraGeneration: UInt64)
    case audioRouteChanged(AudioRoute)
}

/// What a call negotiates and sends, decided by the controller from the
/// network, the user's settings and the peer: relay-only ICE for privacy, the
/// ladder row to send with, and the row the camera captures for.
struct CallEngineOptions: Equatable {
    var relayOnly = false
    var profile: CallMediaProfile = CallMediaLadder.cellular
    var captureProfile: CallMediaProfile = CallMediaLadder.wifi
}

/// Abstraction over the real WebRTC `PeerConnection` lifecycle (mirror of
/// Android's `WebRtcEngine`). `CallController` never touches `WebRTC` types
/// directly, keeping it unit-testable with a fake engine.
@MainActor
protocol WebRTCEngine: AnyObject {
    var events: PassthroughSubject<WebRtcEvent, Never> { get }

    /// Creates the connection for a fresh call. Called once per call before
    /// any other method (the factory is shared and outlives calls).
    func initialize(iceServers: [CallICEServer], mediaKind: CallMediaKind, options: CallEngineOptions)
    /// Builds the shared factory off the main thread ahead of the first call.
    func prepare()
    /// A new ladder row mid-call (network change, "Use less data"): bitrate
    /// caps, frame size and rate apply at once; DTX and RED from the next
    /// negotiation. `seedBitrate` (`CallBitrateSeedPolicy`, bps): where the
    /// bandwidth estimate restarts, set before the new encodings; nil keeps it.
    func applyMediaProfile(_ profile: CallMediaProfile, seedBitrate: Int?)
    /// Back to the last stable description, dropping a pending local (or
    /// remote) offer. A no-op when nothing is pending.
    func rollbackLocalDescription() async
    /// Encode and send the camera, or stop sending while the link cannot carry
    /// video (the capture and the local preview keep running). `seedBitrate`
    /// (`CallBitrateSeedPolicy`, bps): where the bandwidth estimate restarts
    /// when sending turns on, set before the encoding turns active; nil keeps
    /// the estimate.
    func setVideoSendActive(_ active: Bool, seedBitrate: Int?)
    func createOffer() async throws -> String
    func createAnswer() async throws -> String
    func setRemoteDescription(type: String, sdp: String) async throws
    func addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)

    func restartIce()
    /// Fresh TURN credentials for an established connection (they expire after
    /// the server TTL; a restart after that could not allocate relays).
    func updateIceServers(_ servers: [CallICEServer])
    /// The relay fallback (`CallRelayFallbackPolicy`): relay-only ICE (true)
    /// or every candidate type again (false), with the current servers,
    /// effective from the next ICE generation, so the restart that follows
    /// gathers with it while the current pair is left alone. True when the
    /// connection uses that policy now (already, or applied); false, with
    /// nothing changed, without a connection, without a TURN server (for
    /// relay-only) or when WebRTC refuses it.
    func setRelayOnly(_ relayOnly: Bool) -> Bool
    /// This connection has gathered a relay candidate of its own: a TURN
    /// allocation worked from here (a TURN URL alone proves nothing).
    func relayCandidateGathered() -> Bool
    /// The candidate types (`host`, `srflx`, `prflx`, `relay`) of the selected
    /// pair as WebRTC last reported it; nil before the first selection.
    func selectedCandidateTypes() -> (local: String?, remote: String?)?
    /// The description we last applied asks the peer for Opus DTX
    /// (`usedtx=1`), so its voice may pause by design. It follows the last
    /// negotiation, not the current ladder row. Nil before any description.
    func localAudioRequestsDtx() -> Bool?
    func setMuted(_ muted: Bool)
    func setSpeakerOn(_ on: Bool)
    /// Re-enabling capture seeds the estimate before the camera can send its
    /// first frames; nil preserves the estimate.
    func setVideoEnabled(_ enabled: Bool, seedBitrate: Int?)
    func switchCamera()
    func isCurrentCameraGeneration(_ generation: UInt64) -> Bool
    func localCameraIsFrontFacing() -> Bool
    /// The running camera has a torch that can light now (the back camera on
    /// iPhone); false while the camera is off, still starting or interrupted,
    /// and while the device has taken the torch away (heat). A change outside
    /// a camera start, stop or switch arrives as `.localTorchChanged`.
    func localCameraHasTorch() -> Bool
    /// Lights or darkens the running camera's torch. True when it is lit
    /// afterwards; false without a torch, while it is unavailable (heat) or
    /// on failure. Every camera stop, switch or restart darkens it.
    func setTorch(_ on: Bool) -> Bool
    /// Creates the camera track and binds it to the connection; `capture`
    /// starts the camera too. `seedBitrate` (`CallBitrateSeedPolicy`, bps):
    /// where the bandwidth estimate restarts, set before the encoder gets its
    /// first frames; nil keeps it.
    func startLocalVideo(capture: Bool, seedBitrate: Int?) -> Bool
    func stopLocalVideo()
    func setVideoNegotiationEnabled(_ enabled: Bool)
    func hasMultipleCameras() -> Bool
    func availableAudioRoutes() -> [AudioRoute]
    func setAudioRoute(_ route: AudioRoute)
    func currentAudioRoute() -> AudioRoute

    func attachLocalRenderer(_ renderer: Any?)
    func attachRemoteRenderer(_ renderer: Any?)
    func detachLocalRenderer(_ renderer: Any?)
    func attachPictureInPictureRenderer(_ renderer: Any?)
    func detachPictureInPictureRenderer(_ renderer: Any?)
    func detachRemoteRenderer(_ renderer: Any?)

    func localVideoRunning() -> Bool
    func expectRemoteVideoFrame()
    /// The own preview reports its next frame again (`.localFirstFrame`): the
    /// reducer is waiting for proof that the camera runs.
    func expectLocalVideoFrame()
    func dispose()

    /// Cumulative energy counters of the remote audio receiver (the voice halo
    /// polls these ~7 times a second). A protocol requirement, not an
    /// extension default: `CallController` holds the protocol type, and an
    /// extension-only method would dispatch statically to a stub returning nil.
    func remoteAudioEnergySample() async -> CallAudioEnergySample?
    /// One full stats report flattened for the connection-quality grade (~1 Hz).
    func statsSample() async -> CallStatsSample?
    /// DTLS certificate fingerprints of the current local and remote
    /// descriptions (the call's verification code). Read on demand only.
    func dtlsFingerprints() -> CallDtlsFingerprints?
}

extension WebRTCEngine {
    func initialize(iceServers: [CallICEServer], mediaKind: CallMediaKind) {
        initialize(iceServers: iceServers, mediaKind: mediaKind, options: CallEngineOptions())
    }
    func updateIceServers(_ servers: [CallICEServer]) {}
    func setRelayOnly(_ relayOnly: Bool) -> Bool { false }
    func relayCandidateGathered() -> Bool { false }
    func selectedCandidateTypes() -> (local: String?, remote: String?)? { nil }
    func localAudioRequestsDtx() -> Bool? { nil }
    func setVideoNegotiationEnabled(_ enabled: Bool) {}
    func isCurrentCameraGeneration(_ generation: UInt64) -> Bool { false }
    func localCameraIsFrontFacing() -> Bool { true }
    func attachPictureInPictureRenderer(_ renderer: Any?) {}
    func detachPictureInPictureRenderer(_ renderer: Any?) {}
    func setVideoSendActive(_ active: Bool) { setVideoSendActive(active, seedBitrate: nil) }
    func setVideoEnabled(_ enabled: Bool) { setVideoEnabled(enabled, seedBitrate: nil) }
    func dtlsFingerprints() -> CallDtlsFingerprints? { nil }
}
