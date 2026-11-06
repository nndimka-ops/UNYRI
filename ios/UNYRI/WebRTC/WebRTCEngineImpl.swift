import AVFoundation
import Combine
import Foundation
import WebRTC
import OSLog

/// Concrete WebRTC engine (mirror of Android's `WebRtcEngineImpl`). Audio and
/// video share ONE `PeerConnection`; local capture is a single
/// `RTCCameraVideoCapturer` whose format is chosen by the pure
/// `CaptureFormatSelector` (1280x720@30, front camera first — Android parity).
@MainActor
final class WebRTCEngineImpl: NSObject, WebRTCEngine {
    let events = PassthroughSubject<WebRtcEvent, Never>()
    private let cameraLogger = Logger(subsystem: "com.unyri.calls", category: "Camera")

    private var factory: RTCPeerConnectionFactory?
    private var peerConnection: RTCPeerConnection?
    private var audioSource: RTCAudioSource?
    private var localAudioTrack: RTCAudioTrack?
    private var videoSource: RTCVideoSource?
    private var localVideoTrack: RTCVideoTrack?
    private var remoteVideoTrack: RTCVideoTrack?
    private var videoCapturer: RTCCameraVideoCapturer?
    private var localVideoSenderId: String?
    private var captureRunning = false
    private var videoLimits = CallQuality.auto.video
    private var audioBitrate = CallQuality.auto.audioBitrate
    private var cameraGeneration: UInt64 = 0
    private var cameraStartPending = false
    private var mediaKind: CallMediaKind = .audio
    private var cameraPosition: CameraPosition = .front
    // Receiving the peer's video must not depend on permission to use this
    // device's camera. This is set while negotiating an audio-to-video upgrade.
    private var videoReceiveRequested = false
    private var selectedFormat: AVCaptureDevice.Format?
    private var localRendererWrapper: FirstFrameVideoRenderer?
    private var remoteRendererWrapper: FirstFrameVideoRenderer?
    private var pipRendererWrapper: FirstFrameVideoRenderer?
    private var routeChangeObserver: NSObjectProtocol?
    private var captureObservers = [NSObjectProtocol]()

    private static let localAudioTrackId = "messenger-audio-0"
    private static let localVideoTrackId = "messenger-video-0"
    private static let streamId = "messenger-stream-0"

    // MARK: - Lifecycle

    func initialize(iceServers: [CallICEServer], mediaKind: CallMediaKind) {
        dispose()
        videoLimits = CallQuality.parse(UserDefaults.standard.string(forKey: CallQuality.videoKey)).video
        audioBitrate = CallQuality.parse(UserDefaults.standard.string(forKey: CallQuality.audioKey)).audioBitrate
        self.mediaKind = mediaKind
        videoReceiveRequested = mediaKind == .video

        let factory = RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
        self.factory = factory

        let config = RTCConfiguration()
        config.iceServers = iceServers.map { server in
            RTCIceServer(urlStrings: server.urls, username: server.username, credential: server.credential)
        }
        config.iceTransportPolicy = .all
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let pc = factory.peerConnection(with: config, constraints: constraints, delegate: self) else {
            return
        }
        self.peerConnection = pc

        // Local audio track (always present for a call).
        let source = factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
        self.audioSource = source
        let track = factory.audioTrack(with: source, trackId: Self.localAudioTrackId)
        self.localAudioTrack = track
        pc.add(track, streamIds: [Self.streamId])

        // Video call: camera starts as soon as media setup begins (Android
        // `setupLocalVideo` runs inside `initialize` for CallMediaKind.VIDEO),
        // never at CallScreen open / call creation.
        if mediaKind == .video {
            _ = startLocalVideo()
        }

        observeAudioRouteChanges()
    }

    func dispose() {
        stopLocalVideo()
        detachLocalRenderer(localRendererWrapper?.delegateView)
        detachRemoteRenderer(remoteRendererWrapper?.delegateView)
        detachPictureInPictureRenderer(pipRendererWrapper?.delegateView)
        remoteVideoTrack = nil
        localAudioTrack = nil
        audioSource = nil
        peerConnection?.close()
        peerConnection = nil
        factory = nil
        localVideoSenderId = nil
        videoReceiveRequested = false
        if let routeChangeObserver {
            NotificationCenter.default.removeObserver(routeChangeObserver)
            self.routeChangeObserver = nil
        }
    }

    // MARK: - SDP

    func createOffer() async throws -> String {
        guard let pc = peerConnection else { throw EngineError.notInitialized }
        try applyVideoNegotiationIntent(pc)
        let constraints = RTCMediaConstraints(mandatoryConstraints: offerConstraints, optionalConstraints: nil)
        let sdp = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<RTCSessionDescription, Error>) in
            pc.offer(for: constraints) { sdp, error in
                if let sdp { continuation.resume(returning: sdp) }
                else { continuation.resume(throwing: error ?? EngineError.failed) }
            }
        }
        guard peerConnection === pc else { throw EngineError.notInitialized }
        try await setLocal(sdp)
        return sdp.sdp
    }

    func createAnswer() async throws -> String {
        guard let pc = peerConnection else { throw EngineError.notInitialized }
        try applyVideoNegotiationIntent(pc)
        let constraints = RTCMediaConstraints(mandatoryConstraints: answerConstraints, optionalConstraints: nil)
        let sdp = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<RTCSessionDescription, Error>) in
            pc.answer(for: constraints) { sdp, error in
                if let sdp { continuation.resume(returning: sdp) }
                else { continuation.resume(throwing: error ?? EngineError.failed) }
            }
        }
        guard peerConnection === pc else { throw EngineError.notInitialized }
        try await setLocal(sdp)
        return sdp.sdp
    }

    private func setLocal(_ sdp: RTCSessionDescription) async throws {
        guard let pc = peerConnection else { throw EngineError.notInitialized }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            pc.setLocalDescription(sdp) { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: ()) }
            }
        }
        guard peerConnection === pc else { throw EngineError.notInitialized }
        applyQualityLimits()
    }

    func setRemoteDescription(type: String, sdp: String) async throws {
        guard let pc = peerConnection else { throw EngineError.notInitialized }
        let description = RTCSessionDescription(type: type == "offer" ? .offer : .answer, sdp: sdp)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            pc.setRemoteDescription(description) { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: ()) }
            }
        }
        guard peerConnection === pc else { throw EngineError.notInitialized }
        applyQualityLimits()
    }

    func addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        guard let pc = peerConnection else { return }
        let index = Int32(sdpMLineIndex ?? 0)
        pc.add(RTCIceCandidate(sdp: candidate, sdpMLineIndex: index, sdpMid: sdpMid))
    }

    // MARK: - Controls

    func restartIce() {
        // Official API (Android `PeerConnection.restartIce()` parity): marks
        // the next offer with fresh ICE credentials; new candidates flow via
        // the usual LocalIceCandidate events. No SDP renegotiation is forced.
        peerConnection?.restartIce()
    }

    func setMuted(_ muted: Bool) {
        localAudioTrack?.isEnabled = !muted
    }

    func setSpeakerOn(_ on: Bool) {
        let session = AVAudioSession.sharedInstance()
        do {
            try UnyriCallAudioSession.configure(outputOverride: on ? .speaker : .none)
            // CallKit activates the audio session; route changes must not
            // compete with its activation/deactivation lifecycle.
            if !RTCAudioSession.sharedInstance().useManualAudio { try session.setActive(true) }
        } catch {
            // Never leave the UI claiming a route that the OS did not apply.
        }
        events.send(.audioRouteChanged(currentAudioRoute()))
    }

    // MARK: - Local video

    /// Creates the local camera track and starts capture (Android
    /// `setupLocalVideo`). Returns `false` when there is no usable camera —
    /// the call then degrades to audio-only, never crashes.
    func startLocalVideo(capture: Bool = true) -> Bool {
        if capture && AVCaptureDevice.authorizationStatus(for: .video) != .authorized { return false }
        if localVideoRunning() { return true }
        guard let pc = peerConnection, let factory else { return false }

        // Front camera first (Android parity), then back, else fail.
        let device = cameraDevice(.front) ?? cameraDevice(.back)
        guard let device else { return false }

        let formats = Self.formatInfos(for: device)
        guard let index = CaptureFormatSelector.bestIndex(in: formats, width: videoLimits.width, height: videoLimits.height, fps: Double(videoLimits.fps)), index < device.formats.count else { return false }
        let format = device.formats[index]

        let source = factory.videoSource()
        source.adaptOutputFormat(toWidth: Int32(videoLimits.width), height: Int32(videoLimits.height), fps: Int32(videoLimits.fps))
        let capturer = RTCCameraVideoCapturer(delegate: source)
        if capturer.captureSession.isMultitaskingCameraAccessSupported {
            capturer.captureSession.isMultitaskingCameraAccessEnabled = true
        }
        let track = factory.videoTrack(with: source, trackId: Self.localVideoTrackId)
        track.isEnabled = capture

        self.videoSource = source
        self.videoCapturer = capturer
        cameraGeneration &+= 1
        for (notification, interrupted) in [(AVCaptureSession.wasInterruptedNotification, true),
                                            (AVCaptureSession.interruptionEndedNotification, false)] {
            let token = NotificationCenter.default.addObserver(forName: notification, object: capturer.captureSession, queue: .main) { [weak self, weak capturer] notification in
                let reason = (notification.userInfo?[AVCaptureSessionInterruptionReasonKey] as? NSNumber)?.intValue ?? 0
                Task { @MainActor in
                    guard let self, let capturer, self.videoCapturer === capturer else { return }
                    self.cameraLogger.info("Capture interrupted=\(interrupted) reason=\(reason)")
                    self.localRendererWrapper?.reset()
                    self.events.send(.localCaptureInterrupted(interrupted))
                }
            }
            captureObservers.append(token)
        }
        captureObservers.append(NotificationCenter.default.addObserver(
            forName: AVCaptureSession.runtimeErrorNotification, object: capturer.captureSession, queue: .main
        ) { [weak self, weak capturer] _ in
            Task { @MainActor in
                guard let self, let capturer, self.videoCapturer === capturer, self.captureRunning else { return }
                self.cameraStartPending = false
                self.events.send(.localCameraFailed(cameraGeneration: self.cameraGeneration))
            }
        })
        self.localVideoTrack = track
        self.cameraPosition = device.position == .front ? .front : .back
        self.selectedFormat = format

        do { try bindLocalVideoTrack(track, to: pc) }
        catch { stopLocalVideo(); return false }
        applyQualityLimits()
        if let wrapper = localRendererWrapper { track.add(wrapper) }

        if capture { startCamera(capturer, device: device, format: format) }
        return true
    }

    func setVideoEnabled(_ enabled: Bool) {
        if !enabled {
            cameraGeneration &+= 1
            cameraStartPending = false
            localVideoTrack?.isEnabled = false
            if captureRunning { videoCapturer?.stopCapture() }
            captureRunning = false
        } else {
            if !captureRunning, let capturer = videoCapturer, let device = currentCameraDevice(), let format = selectedFormat {
                localRendererWrapper?.reset()
                startCamera(capturer, device: device, format: format)
            }
            localVideoTrack?.isEnabled = true
        }
    }

    func switchCamera() {
        guard captureRunning, !cameraStartPending, let capturer = videoCapturer else { return }
        let newPosition = cameraPosition.toggled
        guard let device = cameraDevice(newPosition) else { return }

        let formats = Self.formatInfos(for: device)
        guard let index = CaptureFormatSelector.bestIndex(in: formats, width: videoLimits.width, height: videoLimits.height, fps: Double(videoLimits.fps)), index < device.formats.count else { return }
        let format = device.formats[index]

        startCamera(capturer, device: device, format: format)
    }

    private func startCamera(_ capturer: RTCCameraVideoCapturer, device: AVCaptureDevice, format: AVCaptureDevice.Format) {
        cameraGeneration &+= 1
        let expectedCamera = cameraGeneration
        cameraStartPending = true
        captureRunning = true
        let frontFacing = device.position == .front
        let supportedFps = format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? Double(videoLimits.fps)
        let fps = max(1, min(videoLimits.fps, Int(supportedFps)))
        capturer.startCapture(with: device, format: format, fps: fps) { [weak self, weak capturer] error in
            Task { @MainActor in
                guard let self, let capturer, self.videoCapturer === capturer,
                      self.cameraGeneration == expectedCamera, self.captureRunning else { return }
                self.cameraStartPending = false
                if error != nil {
                    self.events.send(.localCameraFailed(cameraGeneration: expectedCamera))
                } else {
                    // Camera inputs are installed by RTCCameraVideoCapturer
                    // during startCapture; capability may be unavailable before that.
                    if capturer.captureSession.isMultitaskingCameraAccessSupported {
                        capturer.captureSession.isMultitaskingCameraAccessEnabled = true
                    }
                    self.cameraLogger.info("Camera started; multitasking supported=\(capturer.captureSession.isMultitaskingCameraAccessSupported) enabled=\(capturer.captureSession.isMultitaskingCameraAccessEnabled)")
                    self.cameraPosition = frontFacing ? .front : .back
                    self.selectedFormat = format
                    self.events.send(.localCameraFacingChanged(cameraGeneration: expectedCamera, isFrontFacing: frontFacing))
                }
            }
        }
    }

    func isCurrentCameraGeneration(_ generation: UInt64) -> Bool {
        videoCapturer != nil && cameraGeneration == generation
    }

    func localCameraIsFrontFacing() -> Bool { cameraPosition == .front }

    private func applyQualityLimits() {
        for sender in peerConnection?.senders ?? [] {
            guard let kind = sender.track?.kind, kind == "audio" || kind == "video" else { continue }
            let parameters = sender.parameters
            guard !parameters.encodings.isEmpty else { continue }
            for encoding in parameters.encodings {
                encoding.maxBitrateBps = NSNumber(value: kind == "video" ? videoLimits.bitrate : audioBitrate)
                if kind == "video" { encoding.maxFramerate = NSNumber(value: videoLimits.fps) }
            }
            if kind == "video" { parameters.degradationPreference = NSNumber(value: RTCDegradationPreference.balanced.rawValue) }
            sender.parameters = parameters
        }
    }


    /// Stops capture and removes the video track from the PeerConnection
    /// (Android `stopLocalVideo`). Unlike `setVideoEnabled(false)` this drops
    /// the video m-line on the next renegotiation.
    func stopLocalVideo() {
        cameraGeneration &+= 1
        cameraStartPending = false
        captureObservers.forEach { NotificationCenter.default.removeObserver($0) }
        captureObservers.removeAll()
        let pc = peerConnection
        if let pc, let id = localVideoSenderId,
           let sender = pc.transceivers.first(where: { $0.sender.senderId == id })?.sender {
            pc.removeTrack(sender)
            sender.track = nil
        }
        let track = localVideoTrack
        if captureRunning { videoCapturer?.stopCapture() }
        captureRunning = false
        videoCapturer = nil
        if let wrapper = localRendererWrapper, let track { track.remove(wrapper) }
        localVideoTrack = nil
        videoSource = nil
        selectedFormat = nil
    }

    func setVideoNegotiationEnabled(_ enabled: Bool) {
        videoReceiveRequested = enabled
    }

    private func applyVideoNegotiationIntent(_ pc: RTCPeerConnection) throws {
        for transceiver in pc.transceivers where transceiver.mediaType == .video && !transceiver.isStopped {
            var error: NSError?
            transceiver.setDirection(videoReceiveRequested ? .sendRecv : .inactive, error: &error)
            if let error { throw error }
        }
    }

    // Sender IDs survive Objective-C wrapper replacement and local-track teardown.
    // Rebind instead of adding another video m-section on each later upgrade.
    private func bindLocalVideoTrack(_ track: RTCVideoTrack, to pc: RTCPeerConnection) throws {
        if let id = localVideoSenderId,
           let owned = pc.transceivers.first(where: { $0.sender.senderId == id && !$0.isStopped }) {
            owned.sender.track = track
            owned.sender.streamIds = [Self.streamId]
        } else {
            guard let sender = pc.add(track, streamIds: [Self.streamId]) else { throw EngineError.failed }
            localVideoSenderId = sender.senderId
        }
    }

    func hasMultipleCameras() -> Bool { cameraDevice(.front) != nil && cameraDevice(.back) != nil }

    func localVideoRunning() -> Bool { localVideoTrack != nil }

    // MARK: - Renderers

    func attachLocalRenderer(_ renderer: Any?) {
        guard let view = renderer as? RTCMTLVideoView else { return }
        if localRendererWrapper?.delegateView === view { return }
        if let old = localRendererWrapper { localVideoTrack?.remove(old) }
        let wrapper = FirstFrameVideoRenderer(delegate: view) { [weak self, weak view] in
            guard let self, let view, self.localRendererWrapper?.delegateView === view else { return }
            self.events.send(.localFirstFrame)
        }
        localRendererWrapper = wrapper
        localVideoTrack?.add(wrapper)
    }

    func attachRemoteRenderer(_ renderer: Any?) {
        guard let view = renderer as? RTCMTLVideoView else { return }
        if remoteRendererWrapper?.delegateView === view { return }
        if let old = remoteRendererWrapper { remoteVideoTrack?.remove(old) }
        let wrapper = FirstFrameVideoRenderer(delegate: view) { [weak self, weak view] in
            guard let self, let view, self.remoteRendererWrapper?.delegateView === view else { return }
            self.events.send(.remoteFirstFrame)
        }
        remoteRendererWrapper = wrapper
        remoteVideoTrack?.add(wrapper)
    }

    func detachLocalRenderer(_ renderer: Any?) {
        guard let view = renderer as? RTCMTLVideoView else { return }
        guard localRendererWrapper?.delegateView === view else { return } // stale — never touch a newer one
        if let wrapper = localRendererWrapper { localVideoTrack?.remove(wrapper) }
        localRendererWrapper = nil
    }

    func detachRemoteRenderer(_ renderer: Any?) {
        guard let view = renderer as? RTCMTLVideoView else { return }
        guard remoteRendererWrapper?.delegateView === view else { return }
        if let wrapper = remoteRendererWrapper { remoteVideoTrack?.remove(wrapper) }
        remoteRendererWrapper = nil
    }

    func attachPictureInPictureRenderer(_ renderer: Any?) {
        guard let view = renderer as? RTCVideoRenderer else { return }
        if pipRendererWrapper?.delegateView === view { return }
        if let old = pipRendererWrapper { remoteVideoTrack?.remove(old) }
        let wrapper = FirstFrameVideoRenderer(delegate: view) { [weak self, weak view] in
            guard let self, let view, self.pipRendererWrapper?.delegateView === view else { return }
            self.events.send(.remoteFirstFrame)
        }
        pipRendererWrapper = wrapper
        remoteVideoTrack?.add(wrapper)
    }

    func detachPictureInPictureRenderer(_ renderer: Any?) {
        guard let view = renderer as? RTCVideoRenderer, pipRendererWrapper?.delegateView === view else { return }
        if let wrapper = pipRendererWrapper { remoteVideoTrack?.remove(wrapper) }
        pipRendererWrapper = nil
    }

    func expectRemoteVideoFrame() {
        pipRendererWrapper?.reset()
        remoteRendererWrapper?.reset()
    }

    // MARK: - Audio routes

    /// The output routes the current device actually supports (Android
    /// `availableAudioRoutes` parity): earpiece and speaker are always listed;
    /// Bluetooth and a wired headset are listed only while connected, so the
    /// picker never offers an output that cannot be selected.
    func availableAudioRoutes() -> [AudioRoute] {
        let session = AVAudioSession.sharedInstance()
        var routes: [AudioRoute] = [
            AudioRoute(kind: .earpiece, label: "Phone"),
            AudioRoute(kind: .speaker, label: "Speaker"),
        ]
        let outputs = session.currentRoute.outputs
        if let bt = outputs.first(where: { Self.isBluetooth($0.portType) }) {
            routes.append(AudioRoute(kind: .bluetooth, label: bt.portName.isEmpty ? "Bluetooth" : bt.portName))
        }
        if let wired = outputs.first(where: { Self.isWired($0.portType) }) {
            routes.append(AudioRoute(kind: .wiredHeadset, label: wired.portName.isEmpty ? "Headphones" : wired.portName))
        }
        return routes
    }

    /// iOS cannot force an arbitrary route the way Android can: `.speaker` is
    /// the only explicit override; every other choice returns to the system
    /// route (which already honours a connected Bluetooth/headset device).
    func setAudioRoute(_ route: AudioRoute) {
        switch route.kind {
        case .speaker: setSpeakerOn(true)
        case .earpiece, .bluetooth, .wiredHeadset: setSpeakerOn(false)
        }
    }

    func currentAudioRoute() -> AudioRoute {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        for output in outputs {
            if Self.isBluetooth(output.portType) {
                return AudioRoute(kind: .bluetooth, label: output.portName.isEmpty ? "Bluetooth" : output.portName)
            }
            if Self.isWired(output.portType) {
                return AudioRoute(kind: .wiredHeadset, label: output.portName.isEmpty ? "Headphones" : output.portName)
            }
            if output.portType == .builtInSpeaker {
                return AudioRoute(kind: .speaker, label: "Speaker")
            }
        }
        return AudioRoute(kind: .earpiece, label: "Phone")
    }

    private func observeAudioRouteChanges() {
        if let routeChangeObserver {
            NotificationCenter.default.removeObserver(routeChangeObserver)
        }
        routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.events.send(.audioRouteChanged(self.currentAudioRoute()))
            }
        }
    }

    private static func isBluetooth(_ port: AVAudioSession.Port) -> Bool {
        port == .bluetoothHFP || port == .bluetoothA2DP || port == .bluetoothLE
    }

    private static func isWired(_ port: AVAudioSession.Port) -> Bool {
        port == .headphones || port == .headsetMic
    }

    // MARK: - Helpers

    private var offerConstraints: [String: String] {
        [kRTCMediaConstraintsOfferToReceiveAudio: kRTCMediaConstraintsValueTrue,
         kRTCMediaConstraintsOfferToReceiveVideo: videoReceiveRequested ? kRTCMediaConstraintsValueTrue : kRTCMediaConstraintsValueFalse]
    }
    private var answerConstraints: [String: String] {
        offerConstraints
    }

    private func currentCameraDevice() -> AVCaptureDevice? {
        cameraDevice(cameraPosition)
    }

    private func cameraDevice(_ position: CameraPosition) -> AVCaptureDevice? {
        let avPosition: AVCaptureDevice.Position = position == .front ? .front : .back
        return Self.cameraDevice(avPosition)
    }

    private static func cameraDevice(_ position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera],
            mediaType: .video,
            position: position
        )
        return discovery.devices.first
    }

    private static func formatInfos(for device: AVCaptureDevice) -> [CaptureFormatInfo] {
        device.formats.map { format in
            let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            let maxFps = format.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max() ?? 0
            return CaptureFormatInfo(width: Int(dimensions.width), height: Int(dimensions.height), maxFps: maxFps)
        }
    }

    enum EngineError: Error { case notInitialized, failed }
}

/// Wraps an `RTCMTLVideoView` so the engine can detect the first actual
/// rendered frame (Android `FirstFrameSink` parity): "camera on" is a
/// signaling fact, the first frame is proof.
final class FirstFrameVideoRenderer: NSObject, RTCVideoRenderer {
    private weak var delegate: RTCVideoRenderer?
    private let onFirstFrame: () -> Void
    private var fired = false
    private var generation: UInt64 = 0
    private let frameLock = NSLock()

    /// The wrapped `RTCMTLVideoView` (weak — owned by SwiftUI).
    weak var delegateView: RTCVideoRenderer?

    init(delegate: RTCVideoRenderer, onFirstFrame: @escaping () -> Void) {
        self.delegate = delegate
        self.delegateView = delegate
        self.onFirstFrame = onFirstFrame
        super.init()
    }

    func reset() { frameLock.lock(); generation &+= 1; fired = false; frameLock.unlock() }

    private func isCurrent(_ expected: UInt64) -> Bool {
        frameLock.lock()
        defer { frameLock.unlock() }
        return generation == expected
    }

    func setSize(_ size: CGSize) { delegate?.setSize(size) }

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard frame != nil else { delegate?.renderFrame(nil); return }
        frameLock.lock()
        let isFirst = !fired
        let expected = generation
        fired = true
        frameLock.unlock()
        if isFirst {
            Task { @MainActor in
                guard self.isCurrent(expected) else { return }
                self.onFirstFrame()
            }
        }
        delegate?.renderFrame(frame)
    }
}

// MARK: - RTCPeerConnectionDelegate

extension WebRTCEngineImpl: RTCPeerConnectionDelegate {
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {
        Task { @MainActor in
            guard self.peerConnection === peerConnection,
                  let track = transceiver.receiver.track as? RTCVideoTrack else { return }
            self.acceptRemoteVideo(track)
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {
        Task { @MainActor in
            guard self.peerConnection === peerConnection,
                  let track = rtpReceiver.track as? RTCVideoTrack else { return }
            self.acceptRemoteVideo(track)
        }
    }

    private func acceptRemoteVideo(_ track: RTCVideoTrack) {
        guard remoteVideoTrack !== track else { return }
        if let wrapper = remoteRendererWrapper {
            remoteVideoTrack?.remove(wrapper)
            wrapper.reset()
            track.add(wrapper)
        }
        if let wrapper = pipRendererWrapper {
            remoteVideoTrack?.remove(wrapper)
            wrapper.reset()
            track.add(wrapper)
        }
        remoteVideoTrack = track
        events.send(.remoteVideoEnabled(true))
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            if let videoTrack = stream.videoTracks.first {
                self.acceptRemoteVideo(videoTrack)
            }
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            if self.remoteVideoTrack != nil {
                if let wrapper = self.remoteRendererWrapper {
                    self.remoteVideoTrack?.remove(wrapper)
                    wrapper.reset()
                }
                if let wrapper = self.pipRendererWrapper {
                    self.remoteVideoTrack?.remove(wrapper)
                    wrapper.reset()
                }
                self.remoteVideoTrack = nil
                self.events.send(.remoteVideoEnabled(false))
            }
        }
    }

    nonisolated func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            let mapped: PeerConnectionState
            switch newState {
            case .new: mapped = .new
            case .checking: mapped = .checking
            case .connected, .completed: mapped = .connected
            case .disconnected: mapped = .disconnected
            case .failed: mapped = .failed
            case .closed: mapped = .closed
            case .count: mapped = .closed
            @unknown default: mapped = .closed
            }
            self.events.send(.connectionStateChanged(mapped))
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            self.events.send(.localIceCandidate(candidate: candidate.sdp, sdpMid: candidate.sdpMid, sdpMLineIndex: Int(candidate.sdpMLineIndex)))
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}
