import AVFoundation
import Combine
import Foundation
import UIKit
import WebRTC
import OSLog

/// One peer-connection factory for the app's lifetime: its threads and audio
/// device module serve every call. Creating it per call, on the main thread at
/// "Connecting…", was a visible hitch (B7). It is built off the main thread on
/// first use, normally while the call still rings (`prepare()`). An idle
/// factory holds no audio session: the audio unit runs only while a call does.
private enum CallPeerConnectionFactory {
    static let shared = RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(),
                                                 decoderFactory: RTCDefaultVideoDecoderFactory())
    /// `close()` blocks its caller until WebRTC's threads finish tearing the
    /// connection down; at hang-up that used to stall the main thread.
    static let closeQueue = DispatchQueue(label: "com.unyri.calls.close", qos: .userInitiated)
}

/// Concrete WebRTC engine (mirror of Android's `WebRtcEngineImpl`). Audio and
/// video share ONE `PeerConnection`; local capture is a single
/// `RTCCameraVideoCapturer` whose format is chosen by the pure
/// `CaptureFormatSelector` (1280x720@30, front camera first — Android parity).
@MainActor
final class WebRTCEngineImpl: NSObject, WebRTCEngine {
    /// Energy counters of the remote audio receiver, polled ~7 times a second
    /// by the halo. The receiver is kept from negotiation (no transceiver list
    /// per tick), and the request leaves the main thread: every call into the
    /// peer connection is a synchronous hop to WebRTC's signaling thread, and
    /// the receiver-filtered report is still collected in full by WebRTC.
    func remoteAudioEnergySample() async -> CallAudioEnergySample? {
        guard let connection = peerConnection, let receiver = remoteAudioReceiver else { return nil }
        let request = StatsRequest(value: (connection, receiver))
        return await withCheckedContinuation { continuation in
            statsQueue.async {
                request.value.0.statistics(for: request.value.1) { report in
                    continuation.resume(returning: Self.audioEnergy(in: report))
                }
            }
        }
    }

    /// One full report for the connection-quality monitor (~1 Hz while connected).
    func statsSample() async -> CallStatsSample? {
        checkBlackVideoDiagnostics()
        guard let connection = peerConnection else { return nil }
        let request = StatsRequest(value: connection)
        let sample: CallStatsSample = await withCheckedContinuation { continuation in
            statsQueue.async {
                request.value.statistics { report in
                    continuation.resume(returning: Self.qualitySample(from: report))
                }
            }
        }
        if peerConnection === connection { noteFirstAudioPlayed(sample) }
        return sample
    }

    /// The peer connection proxies every call to its signaling thread, so a
    /// stats request is safe from this queue. Only the request crosses over.
    private struct StatsRequest<Value>: @unchecked Sendable {
        let value: Value
    }

    /// Re-read once per negotiation step and when ICE first connects, never
    /// per poll. Renegotiation keeps the audio m-line, but a replaced or
    /// stopped transceiver is dropped here. Both kinds from one read, and
    /// they carry the first-packet watcher (phase 144): every read wraps each
    /// receiver anew, a new wrapper takes the receiver's single first-packet
    /// observer over and any wrapper clears it when released. So the old
    /// wrappers go first, and only the kept wrappers of the last read before
    /// media flows hear the first packet.
    private func refreshRemoteReceivers(_ pc: RTCPeerConnection) {
        remoteAudioReceiver = nil
        remoteVideoReceiver = nil
        let live = pc.transceivers.filter { !$0.isStopped }
        remoteAudioReceiver = live.first(where: { $0.mediaType == .audio })?.receiver
        remoteVideoReceiver = live.first(where: { $0.mediaType == .video })?.receiver
        remoteAudioReceiver?.delegate = receiverWatcher
        remoteVideoReceiver?.delegate = receiverWatcher
    }

    nonisolated private static func audioEnergy(in report: RTCStatisticsReport) -> CallAudioEnergySample? {
        for stats in report.statistics.values where stats.type == "inbound-rtp" && kind(stats) == "audio" {
            guard let energy = number(stats, "totalAudioEnergy"),
                  let duration = number(stats, "totalSamplesDuration") else { continue }
            return CallAudioEnergySample(totalAudioEnergy: energy, totalSamplesDuration: duration,
                                         bytesReceived: number(stats, "bytesReceived") ?? 0)
        }
        return nil
    }

    nonisolated private static func qualitySample(from report: RTCStatisticsReport) -> CallStatsSample {
        let all = report.statistics
        var sample = CallStatsSample(timestamp: Date().timeIntervalSinceReferenceDate)
        sample.thermalState = Double(ProcessInfo.processInfo.thermalState.rawValue)
        func codec(_ stats: RTCStatistics?) -> String? {
            guard let id = stats?.values["codecId"] as? String,
                  let mime = all[id]?.values["mimeType"] as? String else { return nil }
            return mime.split(separator: "/").last.map(String.init) ?? mime
        }
        func codecCode(_ stats: RTCStatistics) -> Double? {
            guard let id = stats.values["codecId"] as? String, let codec = all[id] else { return nil }
            return CallMediaDiagnosticsPolicy.videoCodecCode(mimeType: codec.values["mimeType"] as? String,
                                                             fmtp: codec.values["sdpFmtpLine"] as? String)
        }
        /// Key frames asked for: PLI plus FIR (rare, older peers).
        func keyFrameRequests(_ stats: RTCStatistics) -> Double? {
            let pli = number(stats, "pliCount"), fir = number(stats, "firCount")
            guard pli != nil || fir != nil else { return nil }
            return (pli ?? 0) + (fir ?? 0)
        }
        // Selected pair: the transport names it; otherwise the nominated, succeeded pair.
        var pair: RTCStatistics?
        let transport = all.values.first(where: { $0.type == "transport" })
        if let id = transport?.values["selectedCandidatePairId"] as? String {
            pair = all[id]
        }
        if let transport {
            sample.transportPairChanges = number(transport, "selectedCandidatePairChanges")
            if let dtls = transport.values["dtlsState"] as? String { sample.dtlsConnected = dtls == "connected" }
        }
        if pair == nil {
            pair = all.values.first {
                $0.type == "candidate-pair" && ($0.values["state"] as? String) == "succeeded"
                    && (($0.values["nominated"] as? NSNumber)?.boolValue ?? false)
            }
        }
        if let pair {
            sample.pairId = pair.id
            sample.rttSeconds = number(pair, "currentRoundTripTime")
            // Totals: the grade averages every STUN response in its window
            // instead of repeating the last one for seconds.
            sample.totalRoundTripTime = number(pair, "totalRoundTripTime")
            sample.responsesReceived = number(pair, "responsesReceived")
            sample.availableOutgoingBitrate = number(pair, "availableOutgoingBitrate")
            sample.pairBytesSent = number(pair, "bytesSent")
            sample.pairBytesReceived = number(pair, "bytesReceived")
            let local = (pair.values["localCandidateId"] as? String).flatMap { all[$0] }
            let remote = (pair.values["remoteCandidateId"] as? String).flatMap { all[$0] }
            sample.localCandidateType = local?.values["candidateType"] as? String
            sample.remoteCandidateType = remote?.values["candidateType"] as? String
            sample.localRelayProtocol = local?.values["relayProtocol"] as? String
            sample.localProtocol = local?.values["protocol"] as? String
            sample.localNetworkType = (local?.values["networkType"] as? String)
                ?? (local?.values["networkAdapterType"] as? String)
            // ICE diagnostics. libwebrtc counts our checks before the first
            // response in `requestsSent` and every consent check after it in
            // `consentRequestsSent`: together, every check sent on the pair.
            sample.pairRequestsReceived = number(pair, "requestsReceived")
            if let beforeResponse = number(pair, "requestsSent") {
                sample.pairRequestsSent = beforeResponse + (number(pair, "consentRequestsSent") ?? 0)
            }
            sample.pairResponsesSent = number(pair, "responsesSent")
            sample.pairPacketsDiscardedOnSend = number(pair, "packetsDiscardedOnSend")
            sample.pairSinceLastPacketMs = CallIceDiagnostics.sinceLastPacketMs(
                number(pair, "lastPacketReceivedTimestamp"),
                clocksMs: [pair.timestamp_us / 1000, ProcessInfo.processInfo.systemUptime * 1000])
            // Only the family is kept, never the address.
            sample.localAddressFamily = CallIceDiagnostics.addressFamily(
                (local?.values["address"] as? String) ?? (local?.values["ip"] as? String))
            sample.remoteAddressFamily = CallIceDiagnostics.addressFamily(
                (remote?.values["address"] as? String) ?? (remote?.values["ip"] as? String))
        }
        for stats in all.values {
            switch (stats.type, kind(stats)) {
            case ("inbound-rtp", "audio"):
                sample.audioBytesReceived = number(stats, "bytesReceived")
                sample.audioPacketsReceived = number(stats, "packetsReceived")
                sample.audioPacketsLost = number(stats, "packetsLost")
                sample.audioJitterSeconds = number(stats, "jitter")
                sample.concealedSamples = number(stats, "concealedSamples")
                sample.silentConcealedSamples = number(stats, "silentConcealedSamples")
                sample.totalSamplesReceived = number(stats, "totalSamplesReceived")
                sample.audioCodec = codec(stats)
                // Playout delay: cumulative, divided over a window.
                sample.audioJitterBufferDelay = number(stats, "jitterBufferDelay")
                sample.audioJitterBufferTargetDelay = number(stats, "jitterBufferTargetDelay")
                sample.audioJitterBufferEmitted = number(stats, "jitterBufferEmittedCount")
                sample.audioJitterBufferMinimumDelay = number(stats, "jitterBufferMinimumDelay")
                sample.audioRemovedSamplesForAcceleration = number(stats, "removedSamplesForAcceleration")
            case ("remote-inbound-rtp", "audio"):
                sample.outgoingFractionLost = number(stats, "fractionLost")
                // Counted against our packets sent: a DTX report of a dozen
                // packets must not read as 8 % loss.
                sample.outgoingPacketsLost = number(stats, "packetsLost")
                sample.remoteRttSeconds = number(stats, "roundTripTime")
            case ("remote-inbound-rtp", "video"):
                sample.videoRemoteRttSeconds = number(stats, "roundTripTime")
                sample.videoRemoteRttMeasurements = number(stats, "roundTripTimeMeasurements")
            case ("outbound-rtp", "audio"):
                sample.outAudioPacketsSent = number(stats, "packetsSent")
            case ("inbound-rtp", "video"):
                sample.videoFramesPerSecond = number(stats, "framesPerSecond")
                sample.videoFreezeCount = number(stats, "freezeCount")
                // Counters, not `framesPerSecond` (omitted at 0 fps): a
                // stall shows as decoded frames that stop growing.
                sample.videoFramesDecoded = number(stats, "framesDecoded")
                sample.videoTotalFreezesDuration = number(stats, "totalFreezesDuration")
                sample.videoFrameWidth = number(stats, "frameWidth")
                sample.videoFrameHeight = number(stats, "frameHeight")
                sample.videoBytesReceived = number(stats, "bytesReceived")
                sample.videoCodec = codec(stats)
                sample.videoJitterBufferDelay = number(stats, "jitterBufferDelay")
                sample.videoJitterBufferTargetDelay = number(stats, "jitterBufferTargetDelay")
                sample.videoJitterBufferEmitted = number(stats, "jitterBufferEmittedCount")
                sample.videoTotalProcessingDelay = number(stats, "totalProcessingDelay")
                sample.videoCodecCode = codecCode(stats)
                sample.videoTotalDecodeTime = number(stats, "totalDecodeTime")
                sample.videoFramesDropped = number(stats, "framesDropped")
                sample.videoKeyFrameRequestsSent = keyFrameRequests(stats)
                sample.videoTotalInterFrameDelay = number(stats, "totalInterFrameDelay")
                sample.videoTotalSquaredInterFrameDelay = number(stats, "totalSquaredInterFrameDelay")
            case ("outbound-rtp", "video"):
                sample.outVideoFramesEncoded = number(stats, "framesEncoded")
                sample.outVideoFramesPerSecond = number(stats, "framesPerSecond")
                sample.outVideoFrameWidth = number(stats, "frameWidth")
                sample.outVideoFrameHeight = number(stats, "frameHeight")
                sample.outVideoBytesSent = number(stats, "bytesSent")
                sample.qualityLimitationReason = stats.values["qualityLimitationReason"] as? String
                sample.targetBitrate = number(stats, "targetBitrate")
                sample.outVideoCodec = codec(stats)
                sample.outVideoCodecCode = codecCode(stats)
                sample.outVideoQpSum = number(stats, "qpSum")
                sample.outVideoTotalEncodeTime = number(stats, "totalEncodeTime")
                sample.outVideoKeyFramesEncoded = number(stats, "keyFramesEncoded")
                sample.outVideoKeyFrameRequests = keyFrameRequests(stats)
                sample.outVideoBandwidthLimitedSeconds =
                    ((stats.values["qualityLimitationDurations"] as? NSDictionary)?["bandwidth"] as? NSNumber)?.doubleValue
                sample.outVideoResolutionChanges = number(stats, "qualityLimitationResolutionChanges")
                sample.outVideoPacketSendDelay = number(stats, "totalPacketSendDelay")
                sample.outVideoPacketsSent = number(stats, "packetsSent")
                sample.outVideoRetransmittedBytes = number(stats, "retransmittedBytesSent")
            case ("media-source", "video"):
                sample.cameraWidth = number(stats, "width")
                sample.cameraHeight = number(stats, "height")
                sample.cameraFps = number(stats, "framesPerSecond")
            default:
                break
            }
        }
        return sample
    }

    nonisolated private static func kind(_ stats: RTCStatistics) -> String? {
        (stats.values["kind"] as? String) ?? (stats.values["mediaType"] as? String)
    }

    nonisolated private static func number(_ stats: RTCStatistics, _ key: String) -> Double? {
        (stats.values[key] as? NSNumber)?.doubleValue
    }

    let events = PassthroughSubject<WebRtcEvent, Never>()
    private let cameraLogger = Logger(subsystem: "com.unyri.calls", category: "Camera")

    private var peerConnection: RTCPeerConnection?
    private var audioSource: RTCAudioSource?
    private var localAudioTrack: RTCAudioTrack?
    private var videoSource: RTCVideoSource?
    private var localVideoTrack: RTCVideoTrack?
    private var remoteVideoTrack: RTCVideoTrack?
    /// The peer's streams that carry `remoteVideoTrack`: its own video stream,
    /// or the one stream an older peer sends for both tracks.
    private var remoteVideoStreamIds = Set<String>()
    private var videoCapturer: RTCCameraVideoCapturer?
    private var localVideoSenderId: String?
    private var captureRunning = false
    /// The ladder row the call sends with (changes live with the network).
    private var profile = CallMediaLadder.cellular
    /// The row the camera format was chosen for; frames are only scaled down from it.
    private var captureProfile = CallMediaLadder.wifi
    /// False while the link cannot carry video (the encoder stops, capture does not).
    private var videoSendActive = true
    private var cameraGeneration: UInt64 = 0
    private var cameraStartPending = false
    private var mediaKind: CallMediaKind = .audio
    private var cameraPosition: CameraPosition = .front
    private let orientationOwner = UUID()
    deinit {
        let owner = orientationOwner
        Task { @MainActor in CallDeviceOrientation.shared.capturing(false, owner: owner) }
    }
    private var cameraMirror: CallFrontCameraMirror?
    // Receiving the peer's video must not depend on permission to use this
    // device's camera. This is set while negotiating an audio-to-video upgrade.
    private var videoReceiveRequested = false
    private var selectedFormat: AVCaptureDevice.Format?
    /// The device the capturer last started successfully; the torch lives on it.
    private var activeCameraDevice: AVCaptureDevice?
    /// Watches that device's torch availability (heat takes it away).
    private var torchAvailability: NSKeyValueObservation?
    /// The capture session is interrupted (background, another app, system
    /// pressure): the camera is not ours for now, and neither is its torch.
    private var captureInterrupted = false
    /// Every self view and peer view on screen gets its track, each keyed by its own view.
    /// SwiftUI can keep an old view alive beside its replacement during a layout change
    /// (the audio stage's camera preview becoming the video stage's tile): with one shared
    /// "last attach wins" slot, the old view attaching last and then detaching left the new
    /// view without frames for the rest of the call (device, 13 Nov: black self view while
    /// the camera kept sending). Views attach and detach only themselves.
    private var localRendererWrappers: [ObjectIdentifier: FirstFrameVideoRenderer] = [:]
    private var remoteRendererWrappers: [ObjectIdentifier: FirstFrameVideoRenderer] = [:]
    private var pipRendererWrapper: FirstFrameVideoRenderer?
    private var captureObservers = [NSObjectProtocol]()
    private var remoteAudioReceiver: RTCRtpReceiver?
    private var remoteVideoReceiver: RTCRtpReceiver?
    /// The receivers' delegate (they hold it weakly), for the connection's life.
    private var receiverWatcher: CallRtpReceiverWatcher?
    /// Candidate types of the selected pair, from WebRTC's selected-pair
    /// callback (always, not only for diagnostics): the relay fallback asks
    /// whether a lost pair was a direct one.
    private var selectedPairTypes: (local: String?, remote: String?)?
    /// This connection gathered a relay candidate of its own (a TURN
    /// allocation worked): the relay fallback switches only then.
    private var relayCandidateSeen = false
    private let statsQueue = DispatchQueue(label: "com.unyri.calls.stats", qos: .userInitiated)
    // ICE detail diagnostics of the current connection (`call_state`
    // operation 5), reset with it.
    /// When ICE first connected (uptime): local candidates are recorded from
    /// then on, with the time since.
    private var iceConnectedAt: TimeInterval?
    /// Local username fragments in the order they appeared, each with when
    /// its first candidate came: the index is the ICE generation (0 the
    /// first, +1 per restart). The fragment itself is never recorded.
    private var localGenerations: [(ufrag: String, since: TimeInterval)] = []
    /// Events recorded per capped kind.
    private var iceDetailCounts: [IceDetailCap: Int] = [:]
    /// When the engine was initialized for this call (uptime).
    private var initializedAt: TimeInterval?
    /// Kinds whose first RTP packet came (true video), the peer's voice
    /// played out, and the audio sections last applied each way (phases
    /// 144, 145 and 148).
    private var firstPackets = Set<Bool>()
    private var firstAudioPlayed = false
    private var audioNegotiation: (local: CallAudioNegotiation?, remote: CallAudioNegotiation?) = (nil, nil)

    /// The ICE detail kinds capped per call (`CallIceDiagnostics.*Cap`): a
    /// flapping network must not push the rest of the report out, and a
    /// gathering's candidates must not push out the later ones.
    private enum IceDetailCap: Hashable {
        case gatheringCandidate, laterCandidate, removal, pairSwitch, connectionState, negotiation

        var limit: Int {
            switch self {
            case .gatheringCandidate: return CallIceDiagnostics.gatheringCandidateCap
            case .laterCandidate: return CallIceDiagnostics.candidateCap
            case .removal: return CallIceDiagnostics.removalCap
            case .pairSwitch: return CallIceDiagnostics.switchCap
            case .connectionState: return CallIceDiagnostics.connectionStateCap
            case .negotiation: return CallIceDiagnostics.negotiationCap
            }
        }
    }

    private static let localAudioTrackId = "messenger-audio-0"
    private static let localVideoTrackId = "messenger-video-0"
    /// Audio and video in streams of their own: the receiver syncs the tracks
    /// of one stream (libwebrtc's `sync_group` is the first stream id) by
    /// delaying the voice toward late video. A peer that sends one shared
    /// stream (older builds, Android) is received as before.
    private static let localAudioStreamId = "messenger-audio-stream-0"
    private static let localVideoStreamId = "messenger-video-stream-0"

    // MARK: - Lifecycle

    nonisolated func prepare() {
        DispatchQueue.global(qos: .userInitiated).async { _ = CallPeerConnectionFactory.shared }
    }

    func initialize(iceServers: [CallICEServer], mediaKind: CallMediaKind, options: CallEngineOptions) {
        dispose()
        resetIceDiagnostics()
        initializedAt = ProcessInfo.processInfo.systemUptime
        profile = options.profile
        captureProfile = options.captureProfile
        videoSendActive = true
        self.mediaKind = mediaKind
        videoReceiveRequested = mediaKind == .video

        let factory = CallPeerConnectionFactory.shared

        let config = RTCConfiguration()
        config.iceServers = iceServers.map { server in
            RTCIceServer(urlStrings: server.urls, username: server.username, credential: server.credential)
        }
        // Relay only: no host or server-reflexive candidate is ever gathered,
        // so the peer learns the relay's address, never this device's.
        config.iceTransportPolicy = options.relayOnly ? .relay : .all
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually
        config.rtcpAudioReportIntervalMs = 1000

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let pc = factory.peerConnection(with: config, constraints: constraints, delegate: self) else {
            return
        }
        self.peerConnection = pc
        receiverWatcher = CallRtpReceiverWatcher { [weak self, weak pc] video in
            Task { @MainActor in
                guard let self, let pc, self.peerConnection === pc else { return }
                self.noteFirstPacket(video: video)
            }
        }
        // The estimate starts at the row's start bitrate instead of ~300 kbps,
        // so video is sharp within seconds instead of ramping up slowly.
        seedEstimate(profile.startBitrate)

        // Local audio track (always present for a call).
        let source = factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
        self.audioSource = source
        let track = factory.audioTrack(with: source, trackId: Self.localAudioTrackId)
        self.localAudioTrack = track
        pc.add(track, streamIds: [Self.localAudioStreamId])

        // Video call: camera starts as soon as media setup begins (Android
        // `setupLocalVideo` runs inside `initialize` for CallMediaKind.VIDEO),
        // never at CallScreen open / call creation. Seeded just above.
        if mediaKind == .video {
            _ = startLocalVideo(capture: options.cameraEnabled, seedBitrate: nil)
        }
        // Routes are observed once, by CallKitCoordinator (the system
        // route-change notification); the engine no longer reports them too.
    }

    func dispose() {
        stopLocalVideo()
        // The renderer views stay registered: the views own that lifecycle
        // (attach when shown, detach when dismantled or backgrounded). A video
        // call's screen mounts them while it is still ringing, before
        // `initialize` (which disposes first) creates this call's tracks.
        for wrapper in remoteRendererWrappers.values { remoteVideoTrack?.remove(wrapper) }
        if let wrapper = pipRendererWrapper { remoteVideoTrack?.remove(wrapper) }
        for wrapper in localRendererWrappers.values { wrapper.reset() }
        for wrapper in remoteRendererWrappers.values { wrapper.reset() }
        pipRendererWrapper?.reset()
        remoteVideoTrack = nil
        remoteVideoStreamIds.removeAll()
        remoteAudioReceiver = nil
        remoteVideoReceiver = nil
        receiverWatcher = nil
        localAudioTrack = nil
        audioSource = nil
        // Callbacks of the closing connection no longer match `peerConnection`
        // and are dropped; the close itself runs off the main thread.
        if let closing = peerConnection {
            peerConnection = nil
            let request = StatsRequest(value: closing)
            CallPeerConnectionFactory.closeQueue.async { request.value.close() }
        }
        localVideoSenderId = nil
        videoReceiveRequested = false
        selectedPairTypes = nil
        relayCandidateSeen = false
        resetIceDiagnostics()
    }

    private func resetIceDiagnostics() {
        iceConnectedAt = nil
        localGenerations.removeAll()
        iceDetailCounts.removeAll()
        initializedAt = nil
        firstPackets.removeAll()
        firstAudioPlayed = false
        audioNegotiation = (nil, nil)
    }

    // MARK: - SDP

    func createOffer() async throws -> String {
        guard let pc = peerConnection else { throw EngineError.notInitialized }
        try applyVideoNegotiationIntent(pc)
        applyAudioCodecPreferences(pc)
        applyVideoCodecPreferences(pc)
        let constraints = RTCMediaConstraints(mandatoryConstraints: offerConstraints, optionalConstraints: nil)
        let sdp = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<RTCSessionDescription, Error>) in
            pc.offer(for: constraints) { sdp, error in
                if let sdp { continuation.resume(returning: sdp) }
                else { continuation.resume(throwing: error ?? EngineError.failed) }
            }
        }
        guard peerConnection === pc else { throw EngineError.notInitialized }
        return try await setLocalNegotiated(sdp)
    }

    func createAnswer() async throws -> String {
        guard let pc = peerConnection else { throw EngineError.notInitialized }
        try applyVideoNegotiationIntent(pc)
        applyAudioCodecPreferences(pc)
        applyVideoCodecPreferences(pc)
        let constraints = RTCMediaConstraints(mandatoryConstraints: answerConstraints, optionalConstraints: nil)
        let sdp = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<RTCSessionDescription, Error>) in
            pc.answer(for: constraints) { sdp, error in
                if let sdp { continuation.resume(returning: sdp) }
                else { continuation.resume(throwing: error ?? EngineError.failed) }
            }
        }
        guard peerConnection === pc else { throw EngineError.notInitialized }
        return try await setLocalNegotiated(sdp)
    }

    /// Sets our description with the row's DTX request (`usedtx=1` on Opus,
    /// cellular and saving rows). Plain SDP negotiation: a peer that ignores
    /// the parameter (the current Android app) just keeps sending as before.
    /// If WebRTC ever refuses the edited text, the untouched one still
    /// connects the call. Returns exactly what was set, which is what is sent.
    private func setLocalNegotiated(_ created: RTCSessionDescription) async throws -> String {
        if profile.requestsDtx {
            let edited = CallSdpMunger.requestingOpusDtx(created.sdp)
            if edited != created.sdp {
                do {
                    try await setLocal(RTCSessionDescription(type: created.type, sdp: edited))
                    return edited
                } catch EngineError.notInitialized {
                    throw EngineError.notInitialized
                } catch {
                    cameraLogger.error("DTX request refused by WebRTC: \(error.localizedDescription, privacy: .public)")
                }
            }
        }
        try await setLocal(created)
        return created.sdp
    }

    /// Opus RED first (redundant audio rides out 10-20 % loss) or, when saving
    /// data, left out altogether. Set on our transceiver before every offer and
    /// answer, so RED is used only when both sides list it: an answer keeps
    /// only codecs the offer had, and a side that leaves RED out excludes it
    /// for both directions.
    private func applyAudioCodecPreferences(_ pc: RTCPeerConnection) {
        guard let transceiver = pc.transceivers.first(where: { $0.mediaType == .audio && !$0.isStopped }) else { return }
        let codecs = CallPeerConnectionFactory.shared.rtpReceiverCapabilities(forKind: kRTCMediaStreamTrackKindAudio).codecs
        let order = CallAudioCodecPreference.order(codecs.map(\.name), preferRed: profile.prefersRed)
        guard !order.isEmpty else { return }
        do { try transceiver.setCodecPreferences(order.map { codecs[$0] }, error: ()) }
        catch { cameraLogger.error("Audio codec preferences refused: \(error.localizedDescription, privacy: .public)") }
    }

    /// Hardware H.264 first in both directions, VP8 and the rest kept
    /// (`CallVideoCodecPreference`, B-047). On every video transceiver before
    /// every offer and answer; a refusal leaves WebRTC's own order.
    private func applyVideoCodecPreferences(_ pc: RTCPeerConnection) {
        let transceivers = pc.transceivers.filter { $0.mediaType == .video && !$0.isStopped }
        guard !transceivers.isEmpty else { return }
        let codecs = CallPeerConnectionFactory.shared.rtpReceiverCapabilities(forKind: kRTCMediaStreamTrackKindVideo).codecs
        let order = CallVideoCodecPreference.order(codecs.map { (name: $0.name, profileLevelId: $0.parameters["profile-level-id"]) })
        guard !order.isEmpty else { return }
        let preferred = order.map { codecs[$0] }
        for transceiver in transceivers {
            do { try transceiver.setCodecPreferences(preferred, error: ()) }
            catch { cameraLogger.error("Video codec preferences refused: \(error.localizedDescription, privacy: .public)") }
        }
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
        applyEncodings()
        refreshRemoteReceivers(pc)
        noteAudioNegotiation(sdp.sdp, local: true)
    }

    func rollbackLocalDescription() async {
        guard let pc = peerConnection, pc.signalingState != .stable, pc.signalingState != .closed else { return }
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                pc.setLocalDescription(RTCSessionDescription(type: .rollback, sdp: "")) { error in
                    if let error { continuation.resume(throwing: error) }
                    else { continuation.resume(returning: ()) }
                }
            }
        } catch {
            cameraLogger.error("Rollback failed: \(error.localizedDescription, privacy: .public)")
        }
        guard peerConnection === pc else { return }
        // Ours is the stable description again (`localAudioRequestsDtx`).
        audioNegotiation.local = pc.localDescription.flatMap { CallAudioNegotiation.parse($0.sdp) }
        applyEncodings()
        refreshRemoteReceivers(pc)
    }

    func setRemoteDescription(type: String, sdp: String) async throws {
        guard let pc = peerConnection else { throw EngineError.notInitialized }
        let description = RTCSessionDescription(type: type == "offer" ? .offer : .answer, sdp: RemoteIcePolicy.filterSDP(sdp, relayOnly: pc.configuration.iceTransportPolicy == .relay))
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            pc.setRemoteDescription(description) { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: ()) }
            }
        }
        guard peerConnection === pc else { throw EngineError.notInitialized }
        applyEncodings()
        refreshRemoteReceivers(pc)
        noteAudioNegotiation(sdp, local: false)
    }

    func addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        guard let pc = peerConnection else { return }
        guard RemoteIcePolicy.allows(candidate, relayOnly: pc.configuration.iceTransportPolicy == .relay) else { return }
        guard let index = try? HostileInteger.iceIndex(sdpMLineIndex ?? 0) else { return }
        pc.add(RTCIceCandidate(sdp: candidate, sdpMLineIndex: index, sdpMid: sdpMid))
    }

    // MARK: - Controls

    func restartIce() {
        // Official API (Android `PeerConnection.restartIce()` parity): marks
        // the next offer with fresh ICE credentials; new candidates flow via
        // the usual LocalIceCandidate events. No SDP renegotiation is forced.
        peerConnection?.restartIce()
    }

    func updateIceServers(_ servers: [CallICEServer]) {
        guard let pc = peerConnection, !servers.isEmpty else { return }
        let config = pc.configuration
        config.iceServers = servers.map { RTCIceServer(urlStrings: $0.urls, username: $0.username, credential: $0.credential) }
        let applied = pc.setConfiguration(config)
        // Phase 143: whether WebRTC took the new servers (`success`), how many.
        recordIceDetail(143, ["success": applied ? 1 : 0, "items": Double(servers.count)])
    }

    /// To relay-only, the candidate filter applies to the ICE sessions that
    /// start after it (a live session keeps the candidates it has), so the
    /// selected pair stays until the restart that follows replaces it with
    /// relay candidates; back to every type, the restart that follows gathers
    /// them all again. The servers, credentials included, are the
    /// connection's own; later credential refreshes start from this
    /// configuration and keep it.
    func setRelayOnly(_ relayOnly: Bool) -> Bool {
        guard let pc = peerConnection else { return false }
        let config = pc.configuration
        if relayOnly {
            if config.iceTransportPolicy == .relay { return true }
            guard CallRelayFallbackPolicy.hasTurnServer(config.iceServers.flatMap(\.urlStrings)) else { return false }
            config.iceTransportPolicy = .relay
        } else {
            if config.iceTransportPolicy == .all { return true }
            config.iceTransportPolicy = .all
        }
        return pc.setConfiguration(config)
    }

    func relayCandidateGathered() -> Bool { relayCandidateSeen }

    func selectedCandidateTypes() -> (local: String?, remote: String?)? { selectedPairTypes }

    func localAudioRequestsDtx() -> Bool? { audioNegotiation.local?.dtx }

    func setMuted(_ muted: Bool) {
        localAudioTrack?.isEnabled = !muted
    }

    func setSpeakerOn(_ on: Bool) {
        let session = AVAudioSession.sharedInstance()
        do {
            try UnyriCallAudioSession.configure(outputOverride: on ? AVAudioSession.PortOverride.speaker : AVAudioSession.PortOverride.none)
            // CallKit activates the audio session; route changes must not
            // compete with its activation/deactivation lifecycle.
            if !RTCAudioSession.sharedInstance().useManualAudio { try session.setActive(true) }
        } catch {
            // Nothing changed, so no notification follows: never leave the
            // UI claiming a route that the OS did not apply.
            events.send(.audioRouteChanged(currentAudioRoute()))
        }
        // No synchronous route report here: iOS applies the override later, so
        // reading the route now published the old one and the speaker button
        // flickered. The route-change notification is the only source.
    }

    // MARK: - Local video

    /// Creates the local camera track and starts capture (Android
    /// `setupLocalVideo`). Returns `false` when there is no usable camera —
    /// the call then degrades to audio-only, never crashes.
    func startLocalVideo(capture: Bool, seedBitrate: Int?) -> Bool {
        if capture && AVCaptureDevice.authorizationStatus(for: .video) != .authorized { return false }
        if localVideoRunning() { return true }
        guard let pc = peerConnection else { return false }
        let factory = CallPeerConnectionFactory.shared

        // Front camera first (Android parity), then back, else fail.
        let device = cameraDevice(.front) ?? cameraDevice(.back)
        guard let device else { return false }

        let formats = Self.formatInfos(for: device)
        guard let index = CaptureFormatSelector.bestIndex(in: formats, width: captureProfile.width, height: captureProfile.height, fps: Double(captureProfile.fps)), index < device.formats.count else { return false }
        let format = device.formats[index]

        let source = factory.videoSource()
        adaptOutputFormat(source)
        // Front-camera frames are mirrored before encoding (as in rooms): the peer
        // sees this person exactly like their own self-view.
        let mirror = CallFrontCameraMirror(source: source, orientation: CallDeviceOrientation.shared.frames)
        self.cameraMirror = mirror
        let capturer = RTCCameraVideoCapturer(delegate: mirror)
        if capturer.captureSession.isMultitaskingCameraAccessSupported {
            capturer.captureSession.isMultitaskingCameraAccessEnabled = true
        }
        let track = factory.videoTrack(with: source, trackId: Self.localVideoTrackId)
        track.isEnabled = capture

        self.videoSource = source
        self.videoCapturer = capturer
        captureInterrupted = false
        cameraGeneration &+= 1
        for (notification, interrupted) in [(AVCaptureSession.wasInterruptedNotification, true),
                                            (AVCaptureSession.interruptionEndedNotification, false)] {
            let token = NotificationCenter.default.addObserver(forName: notification, object: capturer.captureSession, queue: .main) { [weak self, weak capturer] notification in
                let reason = (notification.userInfo?[AVCaptureSessionInterruptionReasonKey] as? NSNumber)?.intValue ?? 0
                Task { @MainActor in
                    guard let self, let capturer, self.videoCapturer === capturer else { return }
                    self.cameraLogger.info("Capture interrupted=\(interrupted) reason=\(reason)")
                    for wrapper in self.localRendererWrappers.values { wrapper.reset() }
                    self.captureInterrupted = interrupted
                    CallDeviceOrientation.shared.capturing(self.captureRunning && !interrupted, owner: self.orientationOwner)
                    // The camera left us (background, pressure): its torch goes
                    // dark rather than stay lit behind the app, and stays dark
                    // when the camera returns. Another app's camera is theirs.
                    if interrupted, let device = self.activeCameraDevice,
                       AVCaptureSession.InterruptionReason(rawValue: reason) != .videoDeviceInUseByAnotherClient {
                        self.torchOff(device)
                    }
                    self.events.send(.localCaptureInterrupted(interrupted))
                    self.torchChanged()
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
                CallDeviceOrientation.shared.capturing(false, owner: self.orientationOwner)
                self.events.send(.localCameraFailed(cameraGeneration: self.cameraGeneration))
            }
        })
        self.localVideoTrack = track
        self.cameraPosition = device.position == .front ? .front : .back
        mirror.setFront(device.position == .front)
        self.selectedFormat = format

        // The seed first: the new encoder's first frames are judged on the
        // target it has then (libwebrtc's initial size drop).
        seedEstimate(seedBitrate)
        do { try bindLocalVideoTrack(track, to: pc) }
        catch { stopLocalVideo(); return false }
        applyEncodings()
        for wrapper in localRendererWrappers.values { track.add(wrapper) }

        if capture { startCamera(capturer, device: device, format: format) }
        return true
    }

    func setVideoEnabled(_ enabled: Bool, seedBitrate: Int?) {
        if !enabled {
            cameraGeneration &+= 1
            cameraStartPending = false
            localVideoTrack?.isEnabled = false
            darkenTorch()
            if captureRunning { videoCapturer?.stopCapture() }
            captureRunning = false
            CallDeviceOrientation.shared.capturing(false, owner: orientationOwner)
        } else {
            // Apply before capture restarts or the retained track is enabled.
            seedEstimate(seedBitrate)
            if !captureRunning, let capturer = videoCapturer, let device = currentCameraDevice(), let format = selectedFormat {
                for wrapper in localRendererWrappers.values { wrapper.reset() }
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
        guard let index = CaptureFormatSelector.bestIndex(in: formats, width: captureProfile.width, height: captureProfile.height, fps: Double(captureProfile.fps)), index < device.formats.count else { return }
        let format = device.formats[index]

        startCamera(capturer, device: device, format: format)
    }

    private func startCamera(_ capturer: RTCCameraVideoCapturer, device: AVCaptureDevice, format: AVCaptureDevice.Format) {
        // A switch or restart never leaves the old camera's torch lit; the
        // controller relights it for the new camera when asked.
        darkenTorch()
        cameraGeneration &+= 1
        let expectedCamera = cameraGeneration
        cameraStartPending = true
        captureRunning = true
        let frontFacing = device.position == .front
        // Keep the last rendered frame while inputs change. Neither old-camera
        // frames with a new tag nor early new-camera frames with an old tag escape.
        cameraMirror?.beginCameraChange()
        CallDeviceOrientation.shared.capturing(true, owner: orientationOwner)
        let supportedFps = format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? Double(captureProfile.fps)
        let fps = max(1, min(captureProfile.fps, Int(supportedFps)))
        capturer.startCapture(with: device, format: format, fps: fps) { [weak self, weak capturer] error in
            Task { @MainActor in
                guard let self, let capturer, self.videoCapturer === capturer,
                      self.cameraGeneration == expectedCamera, self.captureRunning else { return }
                self.cameraStartPending = false
                if error != nil {
                    CallDeviceOrientation.shared.capturing(false, owner: self.orientationOwner)
                    self.events.send(.localCameraFailed(cameraGeneration: expectedCamera))
                } else {
                    self.activeCameraDevice = device
                    self.observeTorch(device)
                    // Camera inputs are installed by RTCCameraVideoCapturer
                    // during startCapture; capability may be unavailable before that.
                    if capturer.captureSession.isMultitaskingCameraAccessSupported {
                        capturer.captureSession.isMultitaskingCameraAccessEnabled = true
                    }
                    self.cameraLogger.info("Camera started; multitasking supported=\(capturer.captureSession.isMultitaskingCameraAccessSupported) enabled=\(capturer.captureSession.isMultitaskingCameraAccessEnabled)")
                    self.cameraPosition = frontFacing ? .front : .back
                    self.selectedFormat = format
                    self.cameraMirror?.setFront(frontFacing)
                    self.events.send(.localCameraFacingChanged(cameraGeneration: expectedCamera, isFrontFacing: frontFacing))
                }
            }
        }
    }

    func isCurrentCameraGeneration(_ generation: UInt64) -> Bool {
        videoCapturer != nil && cameraGeneration == generation
    }

    func localCameraIsFrontFacing() -> Bool { cameraPosition == .front }

    /// Only a torch that can light now: not while the session is interrupted
    /// or the device has taken it away (heat), so the button never shows for
    /// a tap that would do nothing.
    func localCameraHasTorch() -> Bool {
        guard captureRunning, !cameraStartPending, !captureInterrupted, let device = activeCameraDevice else { return false }
        return device.hasTorch && device.isTorchAvailable
    }

    /// On the main actor like every camera call here. The capturer locks the
    /// device only inside its own start; the torch is touched only between a
    /// start's completion and the next stop or start, which darken it first,
    /// so the two never wait on each other.
    func setTorch(_ on: Bool) -> Bool {
        guard captureRunning, !cameraStartPending, !captureInterrupted,
              let device = activeCameraDevice, device.hasTorch else { return false }
        if on && !device.isTorchAvailable { return false }
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            if on {
                // The highest level the device allows now (it drops when hot).
                try device.setTorchModeOn(level: AVCaptureDevice.maxAvailableTorchLevel)
            } else {
                device.torchMode = .off
            }
        } catch {
            cameraLogger.error("Torch \(on ? "on" : "off") failed: \(error.localizedDescription, privacy: .public)")
            // Unchanged: report what it still is.
            return device.torchMode == .on
        }
        return on
    }

    /// Before any capture stop, switch or restart; forgets the device.
    private func darkenTorch() {
        torchAvailability = nil
        if let device = activeCameraDevice { torchOff(device) }
        activeCameraDevice = nil
    }

    /// Darkens the torch; the device stays the running camera.
    private func torchOff(_ device: AVCaptureDevice) {
        guard device.hasTorch, device.torchMode != .off else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = .off
            device.unlockForConfiguration()
        } catch {
            cameraLogger.error("Torch off failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Heat can take the torch away (it goes dark) and give it back later.
    private func observeTorch(_ device: AVCaptureDevice) {
        torchAvailability = nil
        guard device.hasTorch else { return }
        let id = ObjectIdentifier(device)
        torchAvailability = device.observe(\.isTorchAvailable, options: [.new]) { @Sendable [weak self] _, _ in
            // KVO calls back on AVFoundation's thread.
            Task { @MainActor in
                guard let self, let current = self.activeCameraDevice, ObjectIdentifier(current) == id else { return }
                self.torchChanged()
            }
        }
    }

    /// The running camera's torch changed outside a start, stop or switch
    /// (an interruption, heat): `.localTorchChanged` makes the controller read
    /// availability again and clear its lit state when the torch goes dark.
    private func torchChanged() {
        guard captureRunning, !cameraStartPending, activeCameraDevice != nil else { return }
        events.send(.localTorchChanged(cameraGeneration: cameraGeneration))
    }

    func applyMediaProfile(_ profile: CallMediaProfile, seedBitrate: Int?) {
        self.profile = profile
        guard peerConnection != nil else { return }
        // The seed first, so the new row's encodings start from it.
        seedEstimate(seedBitrate)
        if let videoSource { adaptOutputFormat(videoSource) }
        applyEncodings()
    }

    func setVideoSendActive(_ active: Bool, seedBitrate: Int?) {
        guard videoSendActive != active else { return }
        videoSendActive = active
        // The seed first: turning the encoding active re-arms libwebrtc's
        // initial size drop, which judges the first frames on the target it
        // has then (under 300 kbps they drop to 320x180).
        if active { seedEstimate(seedBitrate) }
        applyEncodings()
    }

    /// Restarts the bandwidth estimate at `bps` (`CallBitrateSeedPolicy`);
    /// nil keeps it. Blocking on WebRTC's worker thread, so encodings applied
    /// after it already see the new target.
    private func seedEstimate(_ bps: Int?) {
        guard let bps, let pc = peerConnection else { return }
        _ = pc.setBweMinBitrateBps(nil, currentBitrateBps: NSNumber(value: bps), maxBitrateBps: nil)
    }

    /// Frame size and rate the encoder gets: the current row, never above what
    /// the camera captures (lower rows only scale down).
    private func adaptOutputFormat(_ source: RTCVideoSource) {
        source.adaptOutputFormat(toWidth: Int32(min(profile.width, captureProfile.width)),
                                 height: Int32(min(profile.height, captureProfile.height)),
                                 fps: Int32(min(profile.fps, captureProfile.fps)))
    }

    /// Limits are ceilings; congestion control may always go lower.
    /// - Audio: 16 kbps minimum, the row's maximum, high network priority and
    ///   a larger share than video when the link is short, adaptive packet
    ///   time (fewer packet headers at low bitrates).
    /// - Video: the row's cap and frame rate, `maintainFramerate` (a smaller
    ///   picture rather than a stutter), off while the link cannot carry it.
    private func applyEncodings() {
        for sender in peerConnection?.senders ?? [] {
            guard let kind = sender.track?.kind, kind == "audio" || kind == "video" else { continue }
            let parameters = sender.parameters
            guard !parameters.encodings.isEmpty else { continue }
            for encoding in parameters.encodings {
                if kind == "video" {
                    encoding.maxBitrateBps = NSNumber(value: profile.maxVideoBitrate)
                    encoding.maxFramerate = NSNumber(value: profile.fps)
                    encoding.isActive = videoSendActive
                } else {
                    encoding.minBitrateBps = NSNumber(value: profile.audioMinBitrate)
                    encoding.maxBitrateBps = NSNumber(value: profile.audioMaxBitrate)
                    encoding.networkPriority = .high
                    encoding.bitratePriority = 4
                    encoding.adaptiveAudioPacketTime = true
                }
            }
            if kind == "video" { parameters.degradationPreference = NSNumber(value: RTCDegradationPreference.maintainFramerate.rawValue) }
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
        darkenTorch()
        if captureRunning { videoCapturer?.stopCapture() }
        captureRunning = false
        CallDeviceOrientation.shared.capturing(false, owner: orientationOwner)
        videoCapturer = nil
        captureInterrupted = false
        if let track { for wrapper in localRendererWrappers.values { track.remove(wrapper) } }
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
            owned.sender.streamIds = [Self.localVideoStreamId]
        } else {
            guard let sender = pc.add(track, streamIds: [Self.localVideoStreamId]) else { throw EngineError.failed }
            localVideoSenderId = sender.senderId
        }
    }

    func hasMultipleCameras() -> Bool { cameraDevice(.front) != nil && cameraDevice(.back) != nil }

    func localVideoRunning() -> Bool { localVideoTrack != nil }

    // MARK: - Renderers

    func attachLocalRenderer(_ renderer: Any?) {
        guard let view = renderer as? RTCMTLVideoView else { return }
        guard let wrapper = Self.register(view, in: &localRendererWrappers, track: localVideoTrack, make: { view in
            FirstFrameVideoRenderer(delegate: view) { [weak self, weak view] frame in
                guard let self, let view, self.localRendererWrappers[ObjectIdentifier(view)]?.delegateView === view else { return }
                self.recordFirstFrame(phase: 130, frame, renderer: view, trackEnabled: self.localVideoTrack?.isEnabled)
                if frame.isFirst { self.events.send(.localFirstFrame) }
            }
        }) else { return }
        localVideoTrack?.add(wrapper)
    }

    func attachRemoteRenderer(_ renderer: Any?) {
        guard let view = renderer as? RTCMTLVideoView else { return }
        guard let wrapper = Self.register(view, in: &remoteRendererWrappers, track: remoteVideoTrack, make: { view in
            FirstFrameVideoRenderer(delegate: view) { [weak self, weak view] frame in
                guard let self, let view, self.remoteRendererWrappers[ObjectIdentifier(view)]?.delegateView === view else { return }
                self.recordFirstFrame(phase: 131, frame, renderer: view)
                if frame.isFirst { self.events.send(.remoteFirstFrame) }
            }
        }) else { return }
        remoteVideoTrack?.add(wrapper)
    }

    /// A new wrapper for `view`, or nil when that view already has one. Entries whose view
    /// is gone (deallocated without a detach) are dropped first, so a new view that reuses
    /// a freed view's address is never mistaken for it.
    private static func register(_ view: RTCMTLVideoView, in wrappers: inout [ObjectIdentifier: FirstFrameVideoRenderer],
                                 track: RTCVideoTrack?, make: (RTCMTLVideoView) -> FirstFrameVideoRenderer) -> FirstFrameVideoRenderer? {
        for (key, wrapper) in wrappers where wrapper.delegateView == nil {
            track?.remove(wrapper)
            wrappers[key] = nil
        }
        let key = ObjectIdentifier(view)
        if let existing = wrappers[key] {
            if existing.delegateView === view { return nil }
            track?.remove(existing)
        }
        let wrapper = make(view)
        wrappers[key] = wrapper
        return wrapper
    }

    func detachLocalRenderer(_ renderer: Any?) {
        guard let view = renderer as? RTCMTLVideoView,
              localRendererWrappers[ObjectIdentifier(view)]?.delegateView === view,
              let wrapper = localRendererWrappers.removeValue(forKey: ObjectIdentifier(view)) else { return }
        localVideoTrack?.remove(wrapper)
    }

    func detachRemoteRenderer(_ renderer: Any?) {
        guard let view = renderer as? RTCMTLVideoView,
              remoteRendererWrappers[ObjectIdentifier(view)]?.delegateView === view,
              let wrapper = remoteRendererWrappers.removeValue(forKey: ObjectIdentifier(view)) else { return }
        remoteVideoTrack?.remove(wrapper)
    }

    func attachPictureInPictureRenderer(_ renderer: Any?) {
        guard let view = renderer as? RTCVideoRenderer else { return }
        if pipRendererWrapper?.delegateView === view { return }
        if let old = pipRendererWrapper { remoteVideoTrack?.remove(old) }
        let wrapper = FirstFrameVideoRenderer(delegate: view) { [weak self, weak view] frame in
            guard let self, let view, self.pipRendererWrapper?.delegateView === view else { return }
            self.recordFirstFrame(phase: 132, frame, renderer: view)
            if frame.isFirst { self.events.send(.remoteFirstFrame) }
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
        for wrapper in remoteRendererWrappers.values { wrapper.reset() }
    }

    func expectLocalVideoFrame() {
        for wrapper in localRendererWrappers.values { wrapper.reset() }
    }

    /// A renderer got its first frame since it was armed (`call_state`,
    /// operation 4; phase 130 self view, 131 the peer on screen, 132 PiP), or
    /// the later sample `FirstFrameVideoRenderer.laterFrameAfter` after it
    /// (phase + 3: 133, 134, 135): its size, the mean luma in `items` (about
    /// 16 or 0: black frames), `frames` received since arming, `gap_ms` since
    /// the first frame (later sample only), `status` 1 when the renderer is a
    /// view in a window with a size (the PiP renderer is not a view: 0), and
    /// for the self view `count` 1 when the camera track is enabled. Dark
    /// then normal, dark throughout, and dark then stalled (no later sample)
    /// read apart; with `status`, black frames tell apart from a view that
    /// does not present and from frames reaching the wrong or a hidden view.
    private func recordFirstFrame(phase: Double, _ frame: FirstFrameInfo, renderer: Any, trackEnabled: Bool? = nil) {
        guard CallDiagnostics.isEnabled else { return }
        var onScreen = false
        if let view = renderer as? UIView { onScreen = view.window != nil && !view.bounds.isEmpty }
        var enabled: Double?
        if let trackEnabled { enabled = trackEnabled ? 1 : 0 }
        let values: [String: Double?] = [
            "width": Double(frame.width),
            "height": Double(frame.height),
            "items": frame.luma,
            "frames": Double(frame.frames),
            "gap_ms": frame.isFirst ? nil : frame.sinceFirst * 1000,
            "status": onScreen ? 1 : 0,
            "count": enabled,
        ]
        CallDiagnostics.record(.firstFrame, phase: frame.isFirst ? phase : phase + 3, values)
    }

    /// Reuses the call's existing stats reads; no renderer timer or watchdog.
    private func checkBlackVideoDiagnostics() {
        let enabled = DiagnosticReportPreferences.automatic
        let now = ProcessInfo.processInfo.systemUptime
        let localLive = enabled && captureRunning && !captureInterrupted && localVideoTrack?.isEnabled == true
        let peerLive = enabled && videoReceiveRequested && remoteVideoTrack?.isEnabled == true
        for wrapper in localRendererWrappers.values { wrapper.checkBlackVideo(local: true, live: localLive, at: now) }
        for wrapper in remoteRendererWrappers.values { wrapper.checkBlackVideo(local: false, live: peerLive, at: now) }
        pipRendererWrapper?.checkBlackVideo(local: false, live: peerLive, at: now)
    }

    // MARK: - ICE detail diagnostics

    /// One ICE detail event (`call_state` operation 5), only with diagnostics
    /// on. A capped kind stops after its limit of events per call.
    private func recordIceDetail(_ phase: Double, capped: IceDetailCap? = nil, _ values: [String: Double?] = [:]) {
        guard CallDiagnostics.isEnabled else { return }
        if let capped {
            let used = iceDetailCounts[capped, default: 0]
            guard used < capped.limit else { return }
            iceDetailCounts[capped] = used + 1
        }
        CallDiagnostics.record(.iceDetail, phase: phase, values)
    }

    /// Phase 144: the first RTP packet of a kind in this call (WebRTC's
    /// first-packet signal per receiver, `CallRtpReceiverWatcher`): `status`
    /// 1 audio, 2 video, `duration_ms` the time since ICE first connected
    /// (left out when the packet came first), `gap_ms` since the engine was
    /// initialized for the call. Once per kind and call.
    private func noteFirstPacket(video: Bool) {
        guard firstPackets.insert(video).inserted else { return }
        let now = ProcessInfo.processInfo.systemUptime
        recordIceDetail(144, [
            "status": video ? 2 : 1,
            "duration_ms": iceConnectedAt.map { (now - $0) * 1000 },
            "gap_ms": initializedAt.map { (now - $0) * 1000 },
        ])
    }

    /// Phase 145: the first stats read (about 1 Hz) on which the peer's voice
    /// was played out (`jitterBufferEmittedCount` above 0): `duration_ms` the
    /// time since ICE first connected, `gap_ms` since the engine was
    /// initialized, `budget_ms` the mean jitter-buffer delay so far. Once a
    /// call, whether diagnostics are on then or not.
    private func noteFirstAudioPlayed(_ sample: CallStatsSample) {
        guard !firstAudioPlayed, let emitted = sample.audioJitterBufferEmitted, emitted > 0 else { return }
        firstAudioPlayed = true
        let now = ProcessInfo.processInfo.systemUptime
        recordIceDetail(145, [
            "duration_ms": iceConnectedAt.map { (now - $0) * 1000 },
            "gap_ms": initializedAt.map { (now - $0) * 1000 },
            "budget_ms": sample.audioJitterBufferDelay.map { $0 / emitted * 1000 },
        ])
    }

    /// Phase 148: the audio negotiation after each applied description
    /// (`CallAudioNegotiation`): RED, in-band FEC and DTX each way. Both
    /// sides are followed with diagnostics off too (one parse per
    /// description), so a later record knows the other side.
    private func noteAudioNegotiation(_ sdp: String, local: Bool) {
        let parsed = CallAudioNegotiation.parse(sdp)
        if local { audioNegotiation.local = parsed } else { audioNegotiation.remote = parsed }
        recordIceDetail(148, capped: .negotiation, CallAudioNegotiation.diagnostics(
            local: audioNegotiation.local, remote: audioNegotiation.remote, appliedLocal: local))
    }

    /// The ICE generation of a local username fragment (its index in order
    /// of appearance) and when that generation's first candidate came; a new
    /// one is appended at `now` (bounded; nil past the bound).
    private func localGeneration(of ufrag: String?, adding: Bool, at now: TimeInterval) -> (index: Int, since: TimeInterval)? {
        guard let ufrag else { return nil }
        if let index = localGenerations.firstIndex(where: { $0.ufrag == ufrag }) {
            return (index, localGenerations[index].since)
        }
        guard adding, localGenerations.count < 16 else { return nil }
        localGenerations.append((ufrag, now))
        return (localGenerations.count - 1, now)
    }

    /// Phase 141: a local candidate gathered after ICE first connected.
    /// That alone is not the network changing under the call: with continual
    /// gathering the first gathering is still going when a LAN pair connects
    /// (the callee starts it at the answer, and srflx, relay and TCP
    /// candidates come after the host ones), and a restart gathers anew. Only
    /// a candidate that comes after its generation's gathering
    /// (`CallIceDiagnostics.isGathering`) is a network appearing or a
    /// re-enumeration. `status` its type, `direction` its IP family, `count`
    /// its network cost, `items` its ICE generation, `duration_ms` the time
    /// since ICE first connected, `gap_ms` the time since its generation's
    /// first candidate. The generation is tracked from the first candidate
    /// on; a gathering's candidates and later ones are capped apart.
    private func noteLocalCandidate(_ sdp: String) {
        let now = ProcessInfo.processInfo.systemUptime
        let candidate = CallIceDiagnostics.candidate(sdp: sdp)
        let generation = localGeneration(of: candidate?.ufrag, adding: true, at: now)
        guard let connectedAt = iceConnectedAt, let candidate else { return }
        let sinceGeneration = generation.map { now - $0.since }
        let gathering = CallIceDiagnostics.isGathering(sinceGenerationStart: sinceGeneration)
        recordIceDetail(141, capped: gathering ? .gatheringCandidate : .laterCandidate, [
            "status": candidate.type,
            "direction": candidate.family,
            "count": candidate.networkCost,
            "items": generation.map { Double($0.index) },
            "duration_ms": (now - connectedAt) * 1000,
            "gap_ms": sinceGeneration.map { $0 * 1000 },
        ])
    }

    /// Phase 142: local candidates WebRTC withdrew (their network went away):
    /// `count` how many, the first one's type, family and generation, and
    /// `duration_ms` the time since ICE first connected (none before).
    private func noteRemovedCandidates(_ sdps: [String]) {
        guard let first = sdps.first else { return }
        let now = ProcessInfo.processInfo.systemUptime
        let candidate = CallIceDiagnostics.candidate(sdp: first)
        recordIceDetail(142, capped: .removal, [
            "count": Double(sdps.count),
            "status": candidate?.type,
            "direction": candidate?.family,
            "items": localGeneration(of: candidate?.ufrag, adding: false, at: now).map { Double($0.index) },
            "duration_ms": iceConnectedAt.map { (now - $0) * 1000 },
        ])
    }

    /// Phases 150-159: the selected pair switched, 150 + the reason
    /// (`CallIceDiagnostics.switchReason`): `status` local × 10 + remote
    /// candidate type, `direction` local × 10 + remote IP family, `count` the
    /// local network cost, `items` the remote one (when the peer signaled it).
    private func noteSelectedPairChange(localSdp: String, remoteSdp: String, reason: String) {
        let local = CallIceDiagnostics.candidate(sdp: localSdp)
        let remote = CallIceDiagnostics.candidate(sdp: remoteSdp)
        recordIceDetail(150 + CallIceDiagnostics.switchReason(reason), capped: .pairSwitch, [
            "status": (local?.type ?? 0) * 10 + (remote?.type ?? 0),
            "direction": (local?.family ?? 0) * 10 + (remote?.family ?? 0),
            "count": local?.networkCost,
            "items": remote?.networkCost,
        ])
    }

    // MARK: - Audio routes

    /// The output routes the current device actually supports (Android
    /// `availableAudioRoutes` parity). Read when the route menu opens, never
    /// while rendering. The receiver exists only on iPhone; every connected
    /// Bluetooth device is listed (not just the first); accessories stay listed
    /// while audio is on the speaker. Every accessory carries its port uid: two
    /// headsets with the same name are two routes.
    func availableAudioRoutes() -> [AudioRoute] {
        let session = AVAudioSession.sharedInstance()
        var routes: [AudioRoute] = []
        if UIDevice.current.userInterfaceIdiom == .phone {
            routes.append(AudioRoute(kind: .earpiece, label: "iPhone"))
        }
        routes.append(AudioRoute(kind: .speaker, label: "Speaker"))
        let outputs = session.currentRoute.outputs
        let inputs = session.availableInputs ?? []
        var bluetooth = Set<String>()
        for input in inputs where input.portType == .bluetoothHFP {
            guard bluetooth.insert(AudioRoute.deviceID(input.uid)).inserted else { continue }
            routes.append(AudioRoute(kind: .bluetooth, label: input.portName.isEmpty ? "Bluetooth" : input.portName, uid: input.uid))
        }
        // An output-only device (A2DP/LE) that has no HFP microphone entry; a
        // headset already listed by its microphone is the same device.
        for output in outputs where Self.isBluetooth(output.portType) {
            guard bluetooth.insert(AudioRoute.deviceID(output.uid)).inserted else { continue }
            routes.append(AudioRoute(kind: .bluetooth, label: output.portName.isEmpty ? "Bluetooth" : output.portName, uid: output.uid))
        }
        if let wired = outputs.first(where: { Self.isWired($0.portType) }) {
            routes.append(AudioRoute(kind: .wiredHeadset, label: wired.portName.isEmpty ? "Headphones" : wired.portName, uid: wired.uid))
        } else if let wired = inputs.first(where: { $0.portType == .headsetMic }) {
            routes.append(AudioRoute(kind: .wiredHeadset, label: wired.portName.isEmpty ? "Headphones" : wired.portName, uid: wired.uid))
        }
        return routes
    }

    /// iOS cannot force an arbitrary route the way Android can: `.speaker` is
    /// the only explicit override; every other choice returns to the system
    /// route (which already honours a connected Bluetooth/headset device).
    func setAudioRoute(_ route: AudioRoute) {
        // The preferred input decides the receiver route on iOS: the built-in mic
        // means the earpiece even with a headset connected ("Phone" used to keep
        // audio on Bluetooth), a Bluetooth HFP input means that headset.
        let session = AVAudioSession.sharedInstance()
        let inputs = session.availableInputs ?? []
        switch route.kind {
        case .speaker:
            setSpeakerOn(true)
        case .earpiece:
            setSpeakerOn(false)
            try? session.setPreferredInput(inputs.first { $0.portType == .builtInMic })
        case .bluetooth:
            setSpeakerOn(false)
            // Exactly the chosen headset: its own microphone port, or another
            // profile port of the same device. Never "the first headset": with
            // two connected that sent audio to the wrong one. An output-only
            // device has no input to prefer; iOS then routes to it itself.
            let headsets = inputs.filter { $0.portType == .bluetoothHFP }
            let target: AVAudioSessionPortDescription?
            if let uid = route.uid {
                target = headsets.first { $0.uid == uid }
                    ?? headsets.first { AudioRoute.deviceID($0.uid) == AudioRoute.deviceID(uid) }
            } else {
                target = headsets.count == 1 ? headsets.first : nil
            }
            try? session.setPreferredInput(target)
        case .wiredHeadset:
            setSpeakerOn(false)
            try? session.setPreferredInput(inputs.first { $0.portType == .headsetMic })
        }
    }

    func currentAudioRoute() -> AudioRoute {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        for output in outputs {
            if Self.isBluetooth(output.portType) {
                return AudioRoute(kind: .bluetooth, label: output.portName.isEmpty ? "Bluetooth" : output.portName, uid: output.uid)
            }
            if Self.isWired(output.portType) {
                return AudioRoute(kind: .wiredHeadset, label: output.portName.isEmpty ? "Headphones" : output.portName, uid: output.uid)
            }
            if output.portType == .builtInSpeaker {
                return AudioRoute(kind: .speaker, label: "Speaker")
            }
        }
        return AudioRoute(kind: .earpiece, label: "iPhone")
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
            let subtype = CMFormatDescriptionGetMediaSubType(format.formatDescription)
            return CaptureFormatInfo(width: Int(dimensions.width), height: Int(dimensions.height), maxFps: maxFps,
                                     isBinned: format.isVideoBinned,
                                     isFullRange420: subtype == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)
        }
    }

    enum EngineError: Error { case notInitialized, failed }
}

/// A frame a renderer got since it was (re-)armed, for diagnostics: the
/// first one, or the later one taken once the picture has had time to settle
/// (a camera's first frame is often dark while its exposure adjusts). Its
/// size and the mean luma of its Y plane (nil when the buffer is not an NV12
/// pixel buffer); a luma near 16 (video range) or 0 is a black frame.
struct FirstFrameInfo: Sendable {
    let width: Int32
    let height: Int32
    let luma: Double?
    /// The first frame since arming (false: the later sample).
    let isFirst: Bool
    /// Frames received since arming, this one included.
    let frames: Int
    /// Seconds since the first frame (0 for the first one).
    let sinceFirst: TimeInterval
}

/// Wraps an `RTCMTLVideoView` so the engine can detect the first actual
/// rendered frame (Android `FirstFrameSink` parity): "camera on" is a
/// signaling fact, the first frame is proof. Calls back twice per arming:
/// for the first frame, and (diagnostics only) for the first frame at least
/// `laterFrameAfter` after it.
final class FirstFrameVideoRenderer: NSObject, RTCVideoRenderer, @unchecked Sendable {
    /// Long enough for a camera's exposure to settle after it starts.
    static let laterFrameAfter: TimeInterval = 2

    private weak var delegate: RTCVideoRenderer?
    private let onFrame: (FirstFrameInfo) -> Void
    private var fired = false
    private var laterFired = false
    private var blackWindow = DiagnosticBlackVideoWindow()
    private var blackDuration: TimeInterval?
    private var lastBlackProbe: TimeInterval = 0
    private var noFrameSince: TimeInterval?
    private var blackReported = false
    private var blackProbeEnabled = false
    private var firstAt: TimeInterval = 0
    private var frames = 0
    private var generation: UInt64 = 0
    private let frameLock = NSLock()
    private var geometry: (width: Int32, height: Int32, rotation: Int)?

    /// The wrapped `RTCMTLVideoView` (weak — owned by SwiftUI).
    weak var delegateView: RTCVideoRenderer?

    /// `onFrame` runs on the main actor; only an `isFirst` frame proves the
    /// picture, the later one is for diagnostics.
    init(delegate: RTCVideoRenderer, onFrame: @escaping (FirstFrameInfo) -> Void) {
        self.delegate = delegate
        self.delegateView = delegate
        self.onFrame = onFrame
        super.init()
    }

    func reset() {
        frameLock.lock()
        generation &+= 1
        fired = false
        laterFired = false
        blackWindow = DiagnosticBlackVideoWindow(); blackDuration = nil; lastBlackProbe = 0
        noFrameSince = nil; blackReported = false
        frames = 0
        geometry = nil
        frameLock.unlock()
    }

    private func isCurrent(_ expected: UInt64) -> Bool {
        frameLock.lock()
        defer { frameLock.unlock() }
        return generation == expected
    }

    func setSize(_ size: CGSize) { delegate?.setSize(size) }

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard let frame else { delegate?.renderFrame(nil); return }
        let now = ProcessInfo.processInfo.systemUptime
        frameLock.lock()
        if blackProbeEnabled, !blackReported, now - lastBlackProbe >= 0.5 {
            lastBlackProbe = now
            blackDuration = blackWindow.observe(luma: Self.meanLuma(frame), at: now, live: true)
        }
        let isFirst = !fired
        if isFirst {
            fired = true
            firstAt = now
        }
        frames += 1
        let isLater = !isFirst && !laterFired && now - firstAt >= Self.laterFrameAfter
        if isLater { laterFired = true }
        let count = frames
        let sinceFirst = now - firstAt
        let expected = generation
        let degrees = frame.rotation.rawValue
        let geometryChanged = geometry.map { $0.width != frame.width || $0.height != frame.height || $0.rotation != degrees } ?? true
        if geometryChanged { geometry = (frame.width, frame.height, degrees) }
        frameLock.unlock()
        if geometryChanged {
            let size = CallPipGeometry.orientedSize(CGSize(width: Int(frame.width), height: Int(frame.height)), rotationDegrees: degrees)
            Task { @MainActor in
                guard self.isCurrent(expected) else { return }
                (self.delegate as? CallVideoGeometryReceiving)?.videoGeometryChanged(size, rotation: degrees)
            }
        }
        if isFirst || isLater {
            // Twice per re-arm, on the render thread, before the frame is drawn.
            let info = FirstFrameInfo(width: frame.width, height: frame.height, luma: Self.meanLuma(frame),
                                      isFirst: isFirst, frames: count, sinceFirst: isFirst ? 0 : sinceFirst)
            Task { @MainActor in
                guard self.isCurrent(expected) else { return }
                self.onFrame(info)
            }
        }
        delegate?.renderFrame(frame)
    }

    @MainActor func checkBlackVideo(local: Bool, live: Bool, at now: TimeInterval) {
        var visible = delegateView != nil
        if let view = delegateView as? UIView { visible = view.window != nil && !view.bounds.isEmpty && !view.isHidden }
        frameLock.lock()
        blackProbeEnabled = live && visible
        if !blackProbeEnabled {
            noFrameSince = nil; blackDuration = nil; blackWindow = DiagnosticBlackVideoWindow()
            blackReported = false; lastBlackProbe = 0
            frameLock.unlock(); return
        }
        if noFrameSince == nil { noFrameSince = now }
        let duration: TimeInterval? = fired ? (now - lastBlackProbe <= 1.5 ? blackDuration : nil) : now - (noFrameSince ?? now)
        let send: Bool = !blackReported && (duration ?? 0) > 3
        if send { blackReported = true }
        frameLock.unlock()
        if send { DiagnosticReportEvents.emit(.blackVideo(local: local, seconds: duration ?? 0, trackLive: true)) }
    }

    /// Mean of an 8×8 grid of the Y plane of an NV12 pixel buffer, read-only.
    private static func meanLuma(_ frame: RTCVideoFrame) -> Double? {
        guard let pixelBuffer = (frame.buffer as? RTCCVPixelBuffer)?.pixelBuffer else { return nil }
        let format = CVPixelBufferGetPixelFormatType(pixelBuffer)
        guard format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
                || format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
              CVPixelBufferGetPlaneCount(pixelBuffer) >= 2 else { return nil }
        guard CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) else { return nil }
        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
        let bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        guard width > 0, height > 0, bytesPerRow >= width else { return nil }
        let luma = base.assumingMemoryBound(to: UInt8.self)
        var sum = 0
        for row in 0..<8 {
            let y = (2 * row + 1) * height / 16
            for column in 0..<8 {
                let x = (2 * column + 1) * width / 16
                sum += Int(luma[y * bytesPerRow + x])
            }
        }
        return Double(sum) / 64
    }
}

// MARK: - RTCPeerConnectionDelegate

extension WebRTCEngineImpl: RTCPeerConnectionDelegate {
    /// The track only crosses over: the SDK's receiver wrapper must be gone
    /// before the description's completion re-reads the receivers (a wrapper
    /// released later clears the first-packet observer, phase 144).
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {
        let track = transceiver.receiver.track as? RTCVideoTrack
        Task { @MainActor in
            guard self.peerConnection === peerConnection, let track else { return }
            self.acceptRemoteVideo(track)
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {
        let streamIds = streams.map(\.streamId)
        let track = rtpReceiver.track as? RTCVideoTrack
        Task { @MainActor in
            guard self.peerConnection === peerConnection, let track else { return }
            self.acceptRemoteVideo(track, streamIds: streamIds)
        }
    }

    /// `streamIds`: the peer's streams the track belongs to, when known.
    private func acceptRemoteVideo(_ track: RTCVideoTrack, streamIds: [String] = []) {
        if remoteVideoTrack !== track {
            for wrapper in remoteRendererWrappers.values {
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
            remoteVideoStreamIds.removeAll()
            events.send(.remoteVideoEnabled(true))
        }
        remoteVideoStreamIds.formUnion(streamIds)
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        let streamId = stream.streamId
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            if let videoTrack = stream.videoTracks.first {
                self.acceptRemoteVideo(videoTrack, streamIds: [streamId])
            }
        }
    }

    /// Only the removal of the stream that carries the peer's picture clears
    /// it: with audio and video in streams of their own, another stream going
    /// away says nothing about the video. libwebrtc removes a stream once it
    /// is empty, so the stream ids the track came with tell which one it was.
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {
        let streamId = stream.streamId
        let videoTrackIds = stream.videoTracks.map(\.trackId)
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            if let track = self.remoteVideoTrack,
               self.remoteVideoStreamIds.contains(streamId) || videoTrackIds.contains(track.trackId) {
                for wrapper in self.remoteRendererWrappers.values {
                    self.remoteVideoTrack?.remove(wrapper)
                    wrapper.reset()
                }
                if let wrapper = self.pipRendererWrapper {
                    self.remoteVideoTrack?.remove(wrapper)
                    wrapper.reset()
                }
                self.remoteVideoTrack = nil
                self.remoteVideoStreamIds.removeAll()
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
            if mapped == .connected, self.iceConnectedAt == nil {
                self.iceConnectedAt = ProcessInfo.processInfo.systemUptime
                // DTLS still has to finish before the first RTP packet: the
                // watcher is put on fresh wrappers, after every stray one of
                // the negotiation is gone.
                self.refreshRemoteReceivers(peerConnection)
            }
            self.events.send(.connectionStateChanged(mapped))
        }
    }

    /// Diagnostics only (phases 160-165): the connection's overall state,
    /// which also covers DTLS: new 0, connecting 1, connected 2,
    /// disconnected 3, failed 4, closed 5.
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            let code: Double
            switch newState {
            case .new: code = 0
            case .connecting: code = 1
            case .connected: code = 2
            case .disconnected: code = 3
            case .failed: code = 4
            case .closed: code = 5
            @unknown default: return
            }
            self.recordIceDetail(160 + code, capped: .connectionState)
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            self.events.send(.localIceCandidate(candidate: candidate.sdp, sdpMid: candidate.sdpMid, sdpMLineIndex: Int(candidate.sdpMLineIndex)))
            if CallRelayFallbackPolicy.candidateType(sdp: candidate.sdp) == "relay" { self.relayCandidateSeen = true }
            self.noteLocalCandidate(candidate.sdp)
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {
        let sdps = candidates.map(\.sdp)
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            self.noteRemovedCandidates(sdps)
        }
    }

    /// The selected candidate pair changed, and why: its candidate types for
    /// the relay fallback, the rest for diagnostics.
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChangeLocalCandidate local: RTCIceCandidate,
                                    remoteCandidate remote: RTCIceCandidate, lastReceivedMs lastDataReceivedMs: Int32,
                                    changeReason reason: String) {
        let localSdp = local.sdp
        let remoteSdp = remote.sdp
        Task { @MainActor in
            guard self.peerConnection === peerConnection else { return }
            self.selectedPairTypes = (CallRelayFallbackPolicy.candidateType(sdp: localSdp),
                                      CallRelayFallbackPolicy.candidateType(sdp: remoteSdp))
            self.noteSelectedPairChange(localSdp: localSdp, remoteSdp: remoteSdp, reason: reason)
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}


/// WebRTC's first-packet signal of a receiver (phase 144), once per kind and
/// connection. A receiver holds its delegate weakly; the engine keeps this.
/// Called on a WebRTC thread.
final class CallRtpReceiverWatcher: NSObject, RTCRtpReceiverDelegate, @unchecked Sendable {
    private let onFirstPacket: (_ video: Bool) -> Void
    init(onFirstPacket: @escaping (_ video: Bool) -> Void) { self.onFirstPacket = onFirstPacket }
    func rtpReceiver(_ rtpReceiver: RTCRtpReceiver, didReceiveFirstPacketFor mediaType: RTCRtpMediaType) {
        guard mediaType == .audio || mediaType == .video else { return }
        onFirstPacket(mediaType == .video)
    }
}

/// Sits between the camera capturer and the WebRTC video source and mirrors
/// front-camera frames (see UnyriPixelBufferMirror).
final class CallFrontCameraMirror: NSObject, RTCVideoCapturerDelegate, @unchecked Sendable {
    private weak var source: RTCVideoSource?
    private let orientation: CallOrientationStore
    private let positionLock = NSLock()
    private var front = true
    private var acceptingFrames = true
    private let mirror = UnyriPixelBufferMirror()
    init(source: RTCVideoSource, orientation: CallOrientationStore) { self.source = source; self.orientation = orientation }
    func beginCameraChange() { positionLock.lock(); acceptingFrames = false; positionLock.unlock() }
    func setFront(_ value: Bool) {
        positionLock.lock(); front = value; acceptingFrames = true; positionLock.unlock()
    }
    private func cameraFront() -> Bool? {
        positionLock.lock(); defer { positionLock.unlock() }
        return acceptingFrames ? front : nil
    }
    func capturer(_ capturer: RTCVideoCapturer, didCapture frame: RTCVideoFrame) {
        guard let source, let front = cameraFront() else { return }
        let degrees = CallOrientationPolicy.frameRotation(orientation.read(), front: front)
        let rotation = RTCVideoRotation(rawValue: degrees) ?? ._90
        var output = frame.buffer
        if front, let buffer = frame.buffer as? RTCCVPixelBuffer,
           let flipped = mirror.mirrored(buffer.pixelBuffer, quarterTurn: degrees == 90 || degrees == 270) {
            output = RTCCVPixelBuffer(pixelBuffer: flipped)
        }
        source.capturer(capturer, didCapture: RTCVideoFrame(buffer: output, rotation: rotation, timeStampNs: frame.timeStampNs))
    }
}

extension WebRTCEngineImpl {
    /// Two synchronous hops to WebRTC's signaling thread: called only while
    /// the encryption sheet still lacks the code, never per frame.
    func dtlsFingerprints() -> CallDtlsFingerprints? {
        guard let pc = peerConnection,
              let local = pc.localDescription.flatMap({ CallDtlsFingerprints.fingerprint(inSdp: $0.sdp) }),
              let remote = pc.remoteDescription.flatMap({ CallDtlsFingerprints.fingerprint(inSdp: $0.sdp) }) else { return nil }
        return CallDtlsFingerprints(local: local, remote: remote)
    }
}
