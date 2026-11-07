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
    case audioRouteChanged(AudioRoute)
}

/// Abstraction over the real WebRTC `PeerConnection` lifecycle (mirror of
/// Android's `WebRtcEngine`). `CallController` never touches `WebRTC` types
/// directly, keeping it unit-testable with a fake engine.
@MainActor
protocol WebRTCEngine: AnyObject {
    var events: PassthroughSubject<WebRtcEvent, Never> { get }

    /// (Re)creates the factory/connection for a fresh call. Called once per
    /// call before any other method.
    func initialize(iceServers: [CallICEServer], mediaKind: CallMediaKind)
    func createOffer() async throws -> String
    func createAnswer() async throws -> String
    func setRemoteDescription(type: String, sdp: String) async throws
    func addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)

    func restartIce()
    func setMuted(_ muted: Bool)
    func setSpeakerOn(_ on: Bool)
    func setVideoEnabled(_ enabled: Bool)
    func switchCamera()
    func isCurrentCameraGeneration(_ generation: UInt64) -> Bool
    func localCameraIsFrontFacing() -> Bool
    func startLocalVideo(capture: Bool) -> Bool
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
    func dispose()
}

extension WebRTCEngine {
    func setVideoNegotiationEnabled(_ enabled: Bool) {}
    func isCurrentCameraGeneration(_ generation: UInt64) -> Bool { false }
    func localCameraIsFrontFacing() -> Bool { true }
    func attachPictureInPictureRenderer(_ renderer: Any?) {}
    func detachPictureInPictureRenderer(_ renderer: Any?) {}
    func startLocalVideo() -> Bool { startLocalVideo(capture: true) }
}
