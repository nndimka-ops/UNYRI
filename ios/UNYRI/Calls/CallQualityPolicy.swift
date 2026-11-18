import Foundation

// Pure, platform-free call measurement policy: the voice level behind the
// avatar halo and the connection grade behind the signal bars. No WebRTC or
// UIKit types, so both are contract-tested on macOS CI.

// MARK: - Voice level

/// Cumulative counters of the remote audio receiver (`inbound-rtp`, kind audio).
struct CallAudioEnergySample: Equatable {
    var totalAudioEnergy: Double
    var totalSamplesDuration: Double
    var bytesReceived: Double
}

/// Turns receiver energy counters into a 0…1 voice level. Energy deltas
/// average everything heard between two polls, so short syllables between
/// reads are not lost (a single `audioLevel` read is a ~100 ms peak).
struct CallVoiceLevelEstimator {
    static let gateDb = -55.0
    static let rangeDb = 40.0
    static let attack = 0.5
    static let release = 0.12
    /// Poll period of the halo meter (~7 Hz).
    static let pollIntervalNs: UInt64 = 140_000_000

    private var last: CallAudioEnergySample?
    private(set) var level: Double = 0

    /// Silence: no meter state survives a pause, an interruption or a new call.
    mutating func reset() {
        last = nil
        level = 0
    }

    /// One poll. `nil` means the level is forced to zero (not connected, call
    /// audio inactive, app in the background, no receiver yet).
    @discardableResult
    mutating func update(_ sample: CallAudioEnergySample?) -> Double {
        guard let sample else { reset(); return 0 }
        defer { self.last = sample }
        guard let previous = self.last else { level = 0; return 0 }
        let energy = sample.totalAudioEnergy - previous.totalAudioEnergy
        let duration = sample.totalSamplesDuration - previous.totalSamplesDuration
        // Counters went backwards (new receiver) or no new audio bytes: silence.
        guard energy >= 0, duration >= 0, sample.bytesReceived > previous.bytesReceived else {
            level = 0
            return 0
        }
        let target = Self.normalizedLevel(energy: energy, duration: duration)
        let alpha = target > level ? Self.attack : Self.release
        level += (target - level) * alpha
        if level < 0.01 { level = 0 }
        return level
    }

    /// RMS of the interval in dBFS, gated at -55 dBFS and mapped over 40 dB.
    static func normalizedLevel(energy: Double, duration: Double) -> Double {
        let rms = (max(0, energy) / max(duration, 0.001)).squareRoot()
        let db = 20 * log10(max(rms, 0.000_01))
        return min(1, max(0, (db - gateDb) / rangeDb))
    }

    /// Reduce Motion halo: a few still steps instead of a moving value.
    static func step(_ level: Double) -> Int {
        level < 0.15 ? 0 : level < 0.45 ? 1 : level < 0.75 ? 2 : 3
    }
}

// MARK: - Connection quality

enum CallQualityGrade: Int, Comparable, Equatable, CaseIterable {
    case excellent = 0, good, weak, poor

    static func < (lhs: CallQualityGrade, rhs: CallQualityGrade) -> Bool { lhs.rawValue < rhs.rawValue }

    /// Signal bars shown for this grade (out of 4).
    var bars: Int {
        switch self {
        case .excellent: return 4
        case .good: return 3
        case .weak: return 2
        case .poor: return 1
        }
    }
}

/// How the media path runs: peer to peer, or through the TURN relay.
enum CallMediaRoute: Equatable {
    case direct
    case relay(transport: String?)

    var isRelay: Bool { if case .relay = self { return true }; return false }

    /// `host`/`srflx`/`prflx` candidates are direct; a relay on either side is
    /// via the server. The relay transport is only known for our own side.
    static func from(localType: String?, remoteType: String?, localRelayProtocol: String?) -> CallMediaRoute? {
        guard let localType, let remoteType else { return nil }
        if localType == "relay" { return .relay(transport: localRelayProtocol?.lowercased()) }
        if remoteType == "relay" { return .relay(transport: nil) }
        return .direct
    }
}

/// What the bars and their words show. Published only on change.
struct CallQualityIndicator: Equatable {
    enum Level: Equatable {
        /// Weak, poor or down: the only times the call screen shows the bars.
        var hasProblem: Bool {
            switch self {
            case .reconnecting: return true
            case .graded(let grade): return grade >= .weak
            case .measuring: return false
            }
        }
        /// No grade yet: the first seconds of a call, after the app was away
        /// and after a reconnect. Four dim bars, never a stale grade.
        case measuring
        case graded(CallQualityGrade)
        /// Media is down, shown after the same ~1.2 s everywhere.
        /// `waitingForNetwork`: the device itself has no network.
        case reconnecting(waitingForNetwork: Bool)
    }

    var level: Level = .measuring
    var route: CallMediaRoute?
    /// "Peer-to-peer calls" made this call relay-only: the route line says the
    /// IP address is hidden. Known from the start of the call.
    var privateRelay = false
    /// A weak or poor grade has held `CallQualityPresentation.captionAfter`:
    /// only then is it said in words (a short dip moves the bars only).
    var settled = false

    var grade: CallQualityGrade? {
        if case .graded(let grade) = level { return grade }
        return nil
    }

    var isReconnecting: Bool {
        if case .reconnecting = level { return true }
        return false
    }

    /// The words beside or under the bars (a localization key), if any.
    var caption: String? {
        switch level {
        case .reconnecting(let waiting): return waiting ? "Waiting for network…" : "Reconnecting…"
        case .graded(.weak) where settled: return "Weak connection"
        case .graded(.poor) where settled: return "Poor connection"
        default: return nil
        }
    }
}

/// Timings of the indicator, shared by the call screen and the call banner.
enum CallQualityPresentation {
    /// "Reconnecting…" (and its bars) shows once a drop lasts this long.
    static let reconnectingAfter: TimeInterval = 1.2
    /// Weak and poor are said in words once the grade has held this long.
    static let captionAfter: TimeInterval = 4
    /// The weak text in the video layout fades out after this long; poor stays.
    static let weakCaptionFor: TimeInterval = 5
}

/// One full `getStats` read flattened into the numbers the grader needs.
/// Counters are cumulative; the grader works on deltas between samples.
struct CallStatsSample: Equatable {
    var timestamp: TimeInterval
    // Selected candidate pair
    var pairId: String?
    /// The last STUN response only (the stats sheet); the grade uses the totals.
    var rttSeconds: Double?
    var totalRoundTripTime: Double?
    var responsesReceived: Double?
    var availableOutgoingBitrate: Double?
    var pairBytesSent: Double?
    var pairBytesReceived: Double?
    var localCandidateType: String?
    var remoteCandidateType: String?
    var localRelayProtocol: String?
    var localProtocol: String?
    /// The local candidate's interface (`wifi`, `cellular`, …), for diagnostics.
    var localNetworkType: String?
    // STUN traffic of the selected pair, for the ICE diagnostics: the peer's
    // checks that reached us, every check we sent (connectivity and consent),
    // our answers to the peer's checks, and packets the socket refused to send.
    var pairRequestsReceived: Double?
    var pairRequestsSent: Double?
    var pairResponsesSent: Double?
    var pairPacketsDiscardedOnSend: Double?
    /// Milliseconds since media last arrived on the pair, as of this read
    /// (`CallIceDiagnostics.sinceLastPacketMs`).
    var pairSinceLastPacketMs: Double?
    /// The IP family of the pair's candidates (1 IPv4, 2 IPv6), never the
    /// address itself (`CallIceDiagnostics.addressFamily`).
    var localAddressFamily: Double?
    var remoteAddressFamily: Double?
    // Transport
    /// How many times the transport's selected pair changed so far.
    var transportPairChanges: Double?
    var dtlsConnected: Bool?
    // Incoming audio (inbound-rtp)
    var audioBytesReceived: Double?
    var audioPacketsReceived: Double?
    var audioPacketsLost: Double?
    var audioJitterSeconds: Double?
    var concealedSamples: Double?
    /// Concealed samples of silence (DTX comfort noise): not heard as glitches.
    var silentConcealedSamples: Double?
    var totalSamplesReceived: Double?
    var audioCodec: String?
    /// Playout delay, cumulative: the seconds each emitted sample spent in
    /// the jitter buffer, the delay it aimed for (lip-sync raises it toward
    /// the video's), and how many samples it emitted.
    var audioJitterBufferDelay: Double?
    var audioJitterBufferTargetDelay: Double?
    var audioJitterBufferEmitted: Double?
    /// The jitter buffer's own target, cumulative like the target delay,
    /// without lip-sync and playout-delay constraints: the target minus it
    /// is the delay lip-sync adds to the voice.
    var audioJitterBufferMinimumDelay: Double?
    /// Samples dropped to catch up (accelerate), cumulative.
    var audioRemovedSamplesForAcceleration: Double?
    // Our outgoing audio as the peer reports it (remote-inbound-rtp)
    var outgoingFractionLost: Double?
    /// Cumulative, from the same receiver reports (about one per 5 s).
    var outgoingPacketsLost: Double?
    var remoteRttSeconds: Double?
    /// Our video as the peer reports it: the last RTCP round trip and how
    /// many were measured so far (about one a second while video flows; the
    /// voice's come about every 5 s).
    var videoRemoteRttSeconds: Double?
    var videoRemoteRttMeasurements: Double?
    /// Our outgoing audio (outbound-rtp): how many packets the reports cover.
    var outAudioPacketsSent: Double?
    // Incoming video
    var videoFramesPerSecond: Double?
    var videoFreezeCount: Double?
    var videoFramesDecoded: Double?
    var videoTotalFreezesDuration: Double?
    var videoFrameWidth: Double?
    var videoFrameHeight: Double?
    var videoBytesReceived: Double?
    var videoCodec: String?
    /// The same jitter buffer counters for frames, and the seconds from each
    /// decoded frame's first packet to its decode (jitter buffer included).
    var videoJitterBufferDelay: Double?
    var videoJitterBufferTargetDelay: Double?
    var videoJitterBufferEmitted: Double?
    var videoTotalProcessingDelay: Double?
    /// Its codec as a diagnostics code (`CallMediaDiagnosticsPolicy.videoCodecCode`),
    /// seconds spent decoding, frames dropped before decode, key frames this
    /// side asked for (PLI + FIR sent), and the sum and the sum of squares of
    /// the gaps between decoded frames, in seconds.
    var videoCodecCode: Double?
    var videoTotalDecodeTime: Double?
    var videoFramesDropped: Double?
    var videoKeyFrameRequestsSent: Double?
    var videoTotalInterFrameDelay: Double?
    var videoTotalSquaredInterFrameDelay: Double?
    // Outgoing video
    var outVideoFramesEncoded: Double?
    var outVideoFramesPerSecond: Double?
    var outVideoFrameWidth: Double?
    var outVideoFrameHeight: Double?
    var outVideoBytesSent: Double?
    var qualityLimitationReason: String?
    var targetBitrate: Double?
    var outVideoCodec: String?
    var outVideoCodecCode: Double?
    /// Encoder counters: the QP sum of the encoded frames, seconds spent
    /// encoding, key frames encoded, key frames the peer asked for (PLI + FIR
    /// received), seconds held back by bandwidth, resolution changes for a
    /// quality limitation, seconds packets waited in the pacer and the
    /// packets sent, and bytes retransmitted.
    var outVideoQpSum: Double?
    var outVideoTotalEncodeTime: Double?
    var outVideoKeyFramesEncoded: Double?
    var outVideoKeyFrameRequests: Double?
    var outVideoBandwidthLimitedSeconds: Double?
    var outVideoResolutionChanges: Double?
    var outVideoPacketSendDelay: Double?
    var outVideoPacketsSent: Double?
    var outVideoRetransmittedBytes: Double?
    // The camera as it captures (media-source)
    var cameraWidth: Double?
    var cameraHeight: Double?
    var cameraFps: Double?
    /// `ProcessInfo.thermalState` at the read: nominal 0, fair 1, serious 2,
    /// critical 3.
    var thermalState: Double?

    init(timestamp: TimeInterval) { self.timestamp = timestamp }
}

/// What a few seconds of samples measured; also what the QA stats sheet lists.
struct CallQualityMetrics: Equatable {
    var rttMs: Double?
    var incomingLoss: Double?
    var outgoingLoss: Double?
    var jitterMs: Double?
    /// Audible concealment: concealed samples that were not silence.
    var concealment: Double?
    var incomingKbps: Double?
    var outgoingKbps: Double?
    var availableOutgoingKbps: Double?
    var videoInFps: Double?
    var videoOutFps: Double?
    var qualityLimitationReason: String?
    var audioCodec: String?
    /// Each direction's video codec (they may differ: each side encodes with
    /// the first codec the other one listed), and as diagnostics codes.
    var videoInCodec: String?
    var videoOutCodec: String?
    var videoInCodecCode: Double?
    var videoOutCodecCode: Double?
    var videoInResolution: String?
    var videoOutResolution: String?
    /// The pictures' sizes as numbers (the diagnostics): ours encoded, the
    /// peer's decoded.
    var videoOutWidth: Double?
    var videoOutHeight: Double?
    var videoInWidth: Double?
    var videoInHeight: Double?
    /// Receive-side delay over the window, in ms: how long the peer's voice
    /// waited in the jitter buffer on average and the delay it aimed for,
    /// and for the peer's picture the time from a frame's first packet to
    /// its decode (the jitter buffer's alone without that counter) and its
    /// buffer's target.
    var audioPlayoutDelayMs: Double?
    var audioTargetDelayMs: Double?
    var videoPlayoutDelayMs: Double?
    var videoTargetDelayMs: Double?
    /// The delay lip-sync adds to the voice (its target minus the jitter
    /// buffer's own), in ms: about 0 when audio and video sync apart.
    var avSyncDelayMs: Double?
    /// Our audio packets a second (50 is 20 ms packets; DTX silence sends a
    /// few), the peer's that reached us or were lost, and the share of the
    /// peer's samples dropped to catch up, in per mille.
    var audioPacketsOutPerSecond: Double?
    var audioPacketsInPerSecond: Double?
    var audioAcceleratedPerMille: Double?
    /// The selected pair's STUN round trip alone (`rttMs` falls back to RTCP
    /// without one) and the RTCP one of the peer's receiver reports, in ms.
    var stunRttMs: Double?
    var rtcpRttMs: Double?
    // Our encoder over the window (diagnostics phase 121, the stats sheet):
    // mean QP and encode ms of the frames encoded, its target in kbps, ms
    // held back by bandwidth, resolution changes, key frames encoded and
    // asked for by the peer, ms each packet waited in the pacer, and bytes
    // retransmitted; the camera's capture size and rate.
    var videoOutQp: Double?
    var videoEncodeMs: Double?
    var videoOutTargetKbps: Double?
    var videoBandwidthLimitedMs: Double?
    var videoResolutionChanges: Double?
    var videoKeyFramesEncoded: Double?
    var videoKeyFrameRequestsIn: Double?
    var videoPacerMs: Double?
    var videoRetransmittedBytes: Double?
    var cameraResolution: String?
    var cameraFps: Double?
    // The peer's picture over the window (phase 122): decode ms per frame,
    // frames dropped, key frames this side asked for, and the standard
    // deviation of the gaps between decoded frames in ms.
    var videoDecodeMs: Double?
    var videoFramesDropped: Double?
    var videoKeyFrameRequestsOut: Double?
    var videoInterFrameStddevMs: Double?
    /// `ProcessInfo.thermalState` (`CallStatsSample.thermalState`).
    var thermalState: Double?
    var route: CallMediaRoute?
    var localProtocol: String?
    /// The selected pair's candidate types and our interface (the stats
    /// sheet's route line and the ICE diagnostics).
    var localCandidateType: String?
    var remoteCandidateType: String?
    var localNetworkType: String?
    var receivingAudio = true
    // Graded only (not on the stats sheet).
    /// No audio bytes for more than `silentSecondsForPoor`.
    var audioSilent = false
    /// The peer's picture, while it is shown: frames decoded per second and
    /// the share of the time it stood frozen.
    var decodedFps: Double?
    var freezeFraction: Double?
    /// The shown picture got no new frame for `stallSecondsForPoor` while the
    /// audio kept coming.
    var videoStalled = false
    /// The upload estimate once bandwidth has held our video back for
    /// `bandwidthLimitedSeconds`; nil while the encoder is not held back.
    var uploadLimitedKbps: Double?

    /// Our audio packet time in ms, while at least `minimumPacketRate`
    /// packets a second go out (DTX silence sends about 2.5).
    var audioPacketTimeMs: Double? {
        audioPacketsOutPerSecond.flatMap { $0 >= Self.minimumPacketRate ? 1000 / $0 : nil }
    }
    static let minimumPacketRate = 8.0
}

/// What the screen shows of the media, so the grade only judges what is
/// there (the fps of a camera that is off is not a frozen picture). The
/// grader adds what the counters prove: frames that are decoded or encoded.
struct CallQualityContext: Equatable {
    /// The own camera runs.
    var sendingVideo = false
    /// The peer's live picture is on screen: a freeze is seen.
    var receivingVideo = false
    /// The peer's camera is on (its picture may still be on its way).
    var peerVideoEnabled = false
    /// Media is down: nothing measured now says anything about the link after.
    var reconnecting = false
}

/// The connection grade: counters over a window of a few samples (never a
/// single second's ratio), worst metric wins, quick to fall and slow to rise.
struct CallQualityGrader {
    /// Samples in the window (about 4 s of deltas at 1 Hz).
    static let windowSize = 5
    /// The first grade comes from a full window, at the start and after a
    /// shown reconnect or gap.
    static let firstGradeAfter = windowSize
    static let degradeAfter = 2
    static let improveAfter = 5
    /// A shown level stays at least this long.
    static let minimumShown: TimeInterval = 3
    /// Samples further apart (the app was away): measure again from scratch.
    static let sampleGap: TimeInterval = 3
    static let silentSecondsForPoor: TimeInterval = 2
    static let stallSecondsForPoor: TimeInterval = 2
    static let bandwidthLimitedSeconds: TimeInterval = 3
    /// Decoded or encoded frames this recent prove the video flows.
    static let recentFrames: TimeInterval = 3
    /// Fewer packets in the window (DTX silence) say nothing about loss.
    static let minimumLossPackets = 40.0
    /// A better level is reached only 15 % inside it, so a value sitting on a
    /// threshold does not flip the bars.
    static let exitBand = 0.85

    private var window: [CallStatsSample] = []
    private var silentSince: TimeInterval?
    private var bandwidthLimitedSince: TimeInterval?
    private var lastDecodedAt: TimeInterval?
    private var lastEncodedAt: TimeInterval?
    /// The peer's video payload last grew (packets of its picture arrive).
    private var lastVideoBytesAt: TimeInterval?
    private var videoSince: TimeInterval?
    private var worseStreak = 0
    private var betterStreak = 0
    private(set) var grade: CallQualityGrade?
    /// When the shown grade was set (the caption waits for it to hold).
    private(set) var gradeSince: TimeInterval?
    private(set) var metrics = CallQualityMetrics()

    mutating func reset() { self = CallQualityGrader() }

    /// A drop long enough to be shown is over: measuring again, so no tail of
    /// the drop stays on screen. The monitor calls it only for a drop that was
    /// shown; a hidden blip keeps the bars as they were.
    mutating func restartMeasuring() {
        clearGrade()
        restartWindow()
    }

    /// Feeds one stats sample (about once a second); returns the displayed grade.
    @discardableResult
    mutating func update(_ sample: CallStatsSample, context: CallQualityContext) -> CallQualityGrade? {
        let now = sample.timestamp
        if let last = window.last, now <= last.timestamp { return grade }
        if context.reconnecting {
            // Counters across a drop say nothing about the link after it, so
            // the window starts over. The grade stays: only a drop that was
            // shown ends in measuring (`restartMeasuring`, the monitor's call),
            // or a blip nobody saw would dim the bars for seconds.
            restartWindow()
            metrics = Self.measure([sample], videoFrom: nil)
            return grade
        }
        if let last = window.last {
            if now - last.timestamp > Self.sampleGap {
                clearGrade()
                restartWindow()
            } else if last.pairId != sample.pairId {
                // A new route: its counters start over; the grade stays.
                restartWindow()
            }
        }
        let previous = window.last
        window.append(sample)
        if window.count > Self.windowSize { window.removeFirst(window.count - Self.windowSize) }

        if let previous {
            if (Self.delta(previous.audioBytesReceived, sample.audioBytesReceived) ?? 0) > 0 { silentSince = nil }
            else if silentSince == nil { silentSince = previous.timestamp }
            if (Self.delta(previous.videoFramesDecoded, sample.videoFramesDecoded) ?? 0) > 0 { lastDecodedAt = now }
            if (Self.delta(previous.videoBytesReceived, sample.videoBytesReceived) ?? 0) > 0 { lastVideoBytesAt = now }
            if (Self.delta(previous.outVideoFramesEncoded, sample.outVideoFramesEncoded) ?? 0) > 0 { lastEncodedAt = now }
        }
        if sample.qualityLimitationReason == "bandwidth" {
            if bandwidthLimitedSince == nil { bandwidthLimitedSince = now }
        } else {
            bandwidthLimitedSince = nil
        }
        // The counters vouch for video the screen does not show yet; a peer
        // camera that is off never counts (its last frames are not a freeze).
        let decodedRecently = lastDecodedAt.map { now - $0 <= Self.recentFrames } ?? false
        let encodedRecently = lastEncodedAt.map { now - $0 <= Self.recentFrames } ?? false
        // The peer's picture is judged only while its packets arrive (within
        // the stall window, so a choppy stream still counts). Packets with no
        // frame out of them are the link or a lost key frame; packets that
        // stop while the voice keeps coming are the sender stopping (a paused
        // or backgrounded encoder), not the link, so they never read as poor.
        let videoArriving = sample.videoBytesReceived == nil
            || (lastVideoBytesAt.map { now - $0 < Self.stallSecondsForPoor } ?? false)
        let receivingVideo = (context.receivingVideo || (context.peerVideoEnabled && decodedRecently)) && videoArriving
        let sendingVideo = context.sendingVideo || encodedRecently
        if receivingVideo { if videoSince == nil { videoSince = now } } else { videoSince = nil }

        var m = Self.measure(window, videoFrom: receivingVideo ? videoSince : nil)
        m.receivingAudio = silentSince == nil
        m.audioSilent = silentSince.map { now - $0 > Self.silentSecondsForPoor } ?? false
        if context.receivingVideo, let since = videoSince, sample.videoFramesDecoded != nil, m.receivingAudio {
            m.videoStalled = now - max(lastDecodedAt ?? since, since) >= Self.stallSecondsForPoor
        }
        if sendingVideo, let since = bandwidthLimitedSince, now - since >= Self.bandwidthLimitedSeconds {
            m.uploadLimitedKbps = m.availableOutgoingKbps
        }
        metrics = m
        guard window.count >= 2 else { return grade }
        apply(Self.grade(m, current: grade), now: now)
        return grade
    }

    private mutating func clearGrade() {
        grade = nil
        gradeSince = nil
        worseStreak = 0
        betterStreak = 0
    }

    private mutating func restartWindow() {
        window.removeAll()
        silentSince = nil
        bandwidthLimitedSince = nil
        videoSince = nil
    }

    /// The first grade comes from a full window; then down after `degradeAfter`
    /// worse samples and straight up to the measured grade after `improveAfter`
    /// better ones (0.4.11's rule), never sooner than `minimumShown`.
    private mutating func apply(_ raw: CallQualityGrade, now: TimeInterval) {
        guard let current = grade else {
            if window.count >= Self.firstGradeAfter { show(raw, at: now) }
            return
        }
        let held = now - (gradeSince ?? now) >= Self.minimumShown
        if raw > current {
            betterStreak = 0
            worseStreak += 1
            if worseStreak >= Self.degradeAfter && held { show(raw, at: now) }
        } else if raw < current {
            worseStreak = 0
            betterStreak += 1
            if betterStreak >= Self.improveAfter && held { show(raw, at: now); betterStreak = 0 }
        } else {
            worseStreak = 0
            betterStreak = 0
        }
    }

    private mutating func show(_ value: CallQualityGrade, at now: TimeInterval) {
        grade = value
        gradeSince = now
        worseStreak = 0
    }

    /// Worst metric wins; a missing metric does not count. `current` is the
    /// shown grade: a better level needs the value 15 % inside it.
    static func grade(_ m: CallQualityMetrics, current: CallQualityGrade? = nil) -> CallQualityGrade {
        var worst = CallQualityGrade.excellent
        func consider(_ grade: CallQualityGrade?) { if let grade, grade > worst { worst = grade } }
        /// Lower is better: below `t.0` excellent, `t.1` good, `t.2` weak, else poor.
        func lower(_ value: Double?, _ t: (Double, Double, Double)) -> CallQualityGrade? {
            value.map { level($0, t, current: current) }
        }
        /// Higher is better: from `t.0` excellent, `t.1` good, `t.2` weak, else poor.
        func higher(_ value: Double?, _ t: (Double, Double, Double)) -> CallQualityGrade? {
            value.map { level(-$0, (-t.0, -t.1, -t.2), current: current) }
        }
        consider(lower(m.rttMs, (250, 400, 700)))
        consider(lower(m.concealment.map { $0 * 100 }, (2, 5, 12)))
        // Loss only where concealment cannot tell what was heard (RED and
        // FEC repair much of it), and our outgoing audio, of which only the
        // peer's loss reports come back.
        if m.concealment == nil { consider(lower(m.incomingLoss.map { $0 * 100 }, (3, 6, 12))) }
        consider(lower(m.outgoingLoss.map { $0 * 100 }, (3, 6, 12)))
        consider(lower(m.jitterMs, (40, 80, 150)))
        // The peer's picture: neutral to 15, 24 and 30 fps senders.
        consider(lower(m.freezeFraction.map { $0 * 100 }, (10, 10, 30)))
        consider(higher(m.decodedFps, (10, 5, -.infinity)))
        // Our upload, only while bandwidth really holds the encoder back.
        consider(higher(m.uploadLimitedKbps, (.infinity, 400, 150)))
        if m.audioSilent || m.videoStalled { consider(.poor) }
        return worst
    }

    /// Lower is better: below `t.0` excellent, `t.1` good, `t.2` weak, else poor.
    private static func level(_ value: Double, _ t: (Double, Double, Double), current: CallQualityGrade?) -> CallQualityGrade {
        let raw = rate(value, t)
        guard let current, raw < current else { return raw }
        // Leaving a level for a better one needs the margin (a negated
        // higher-is-better value shrinks toward zero instead).
        let margin = value < 0 ? value * exitBand : value / exitBand
        return min(current, rate(margin, t))
    }

    private static func rate(_ value: Double, _ t: (Double, Double, Double)) -> CallQualityGrade {
        value < t.0 ? .excellent : value < t.1 ? .good : value < t.2 ? .weak : .poor
    }

    /// Everything over the window: ratios from counter deltas across it,
    /// never a mean of per-second ratios. `videoFrom`: the peer's picture is
    /// judged only over the seconds it has been shown.
    private static func measure(_ window: [CallStatsSample], videoFrom: TimeInterval?) -> CallQualityMetrics {
        guard let last = window.last else { return CallQualityMetrics() }
        var m = staticMetrics(last)
        guard let first = window.first, last.timestamp > first.timestamp else { return m }
        let span = last.timestamp - first.timestamp
        if let responses = delta(first.responsesReceived, last.responsesReceived), responses > 0,
           let total = delta(first.totalRoundTripTime, last.totalRoundTripTime) {
            m.rttMs = total / responses * 1000
            m.stunRttMs = m.rttMs
        } else if let rtt = median(window.compactMap { $0.rttSeconds ?? $0.remoteRttSeconds }) {
            m.rttMs = rtt * 1000
            m.stunRttMs = median(window.compactMap(\.rttSeconds)).map { $0 * 1000 }
        }
        // RTCP: the video's reports while they still come (a stopped video
        // keeps its last one), else the voice's.
        let videoReports = delta(first.videoRemoteRttMeasurements, last.videoRemoteRttMeasurements) ?? 0
        m.rtcpRttMs = ((videoReports > 0 ? last.videoRemoteRttSeconds : nil) ?? last.remoteRttSeconds).map { $0 * 1000 }
        if let lost = delta(first.audioPacketsLost, last.audioPacketsLost),
           let received = delta(first.audioPacketsReceived, last.audioPacketsReceived),
           lost + received >= minimumLossPackets {
            m.incomingLoss = ratio(lost, lost + received)
        }
        // Our outgoing audio, from the peer's receiver reports. One report
        // covers about 5 s and repeats until the next; in DTX silence (muted,
        // listening) that is a dozen packets, where one lost reads 8 %. So
        // only a window that sent enough packets counts, and never above the
        // reported losses against what we sent in it: a report from a silence
        // that just ended does not speak for the speech after it.
        if let sent = delta(first.outAudioPacketsSent, last.outAudioPacketsSent), sent >= minimumLossPackets {
            let reported = last.outgoingFractionLost.map { max(0, min(1, $0)) }
            let counted = delta(first.outgoingPacketsLost, last.outgoingPacketsLost).map { min(1, $0 / sent) }
            m.outgoingLoss = [reported, counted].compactMap { $0 }.min()
        }
        if let concealed = delta(first.concealedSamples, last.concealedSamples),
           let samples = delta(first.totalSamplesReceived, last.totalSamplesReceived) {
            let silent = delta(first.silentConcealedSamples, last.silentConcealedSamples) ?? 0
            m.concealment = ratio(max(0, concealed - silent), samples)
        }
        let jitter = window.compactMap(\.audioJitterSeconds)
        if !jitter.isEmpty { m.jitterMs = jitter.reduce(0, +) / Double(jitter.count) * 1000 }
        m.incomingKbps = delta(first.pairBytesReceived, last.pairBytesReceived).map { $0 * 8 / span / 1000 }
        m.outgoingKbps = delta(first.pairBytesSent, last.pairBytesSent).map { $0 * 8 / span / 1000 }
        let audioEmitted = delta(first.audioJitterBufferEmitted, last.audioJitterBufferEmitted)
        m.audioPlayoutDelayMs = meanMs(delta(first.audioJitterBufferDelay, last.audioJitterBufferDelay), audioEmitted)
        m.audioTargetDelayMs = meanMs(delta(first.audioJitterBufferTargetDelay, last.audioJitterBufferTargetDelay), audioEmitted)
        let videoEmitted = delta(first.videoJitterBufferEmitted, last.videoJitterBufferEmitted)
        m.videoPlayoutDelayMs = meanMs(delta(first.videoTotalProcessingDelay, last.videoTotalProcessingDelay),
                                       delta(first.videoFramesDecoded, last.videoFramesDecoded))
            ?? meanMs(delta(first.videoJitterBufferDelay, last.videoJitterBufferDelay), videoEmitted)
        m.videoTargetDelayMs = meanMs(delta(first.videoJitterBufferTargetDelay, last.videoJitterBufferTargetDelay), videoEmitted)
        if let target = m.audioTargetDelayMs,
           let minimum = meanMs(delta(first.audioJitterBufferMinimumDelay, last.audioJitterBufferMinimumDelay), audioEmitted) {
            m.avSyncDelayMs = max(0, target - minimum)
        }
        m.audioPacketsOutPerSecond = delta(first.outAudioPacketsSent, last.outAudioPacketsSent).map { $0 / span }
        if let received = delta(first.audioPacketsReceived, last.audioPacketsReceived) {
            // packetsLost may go down (late duplicates): then only what arrived.
            m.audioPacketsInPerSecond = (received + (delta(first.audioPacketsLost, last.audioPacketsLost) ?? 0)) / span
        }
        m.audioAcceleratedPerMille = perUnit(delta(first.audioRemovedSamplesForAcceleration, last.audioRemovedSamplesForAcceleration),
                                             delta(first.totalSamplesReceived, last.totalSamplesReceived)).map { $0 * 1000 }
        let encoded = delta(first.outVideoFramesEncoded, last.outVideoFramesEncoded)
        m.videoOutQp = perUnit(delta(first.outVideoQpSum, last.outVideoQpSum), encoded)
        m.videoEncodeMs = meanMs(delta(first.outVideoTotalEncodeTime, last.outVideoTotalEncodeTime), encoded)
        m.videoBandwidthLimitedMs = delta(first.outVideoBandwidthLimitedSeconds, last.outVideoBandwidthLimitedSeconds).map { $0 * 1000 }
        m.videoResolutionChanges = delta(first.outVideoResolutionChanges, last.outVideoResolutionChanges)
        m.videoKeyFramesEncoded = delta(first.outVideoKeyFramesEncoded, last.outVideoKeyFramesEncoded)
        m.videoKeyFrameRequestsIn = delta(first.outVideoKeyFrameRequests, last.outVideoKeyFrameRequests)
        m.videoPacerMs = meanMs(delta(first.outVideoPacketSendDelay, last.outVideoPacketSendDelay),
                                delta(first.outVideoPacketsSent, last.outVideoPacketsSent))
        m.videoRetransmittedBytes = delta(first.outVideoRetransmittedBytes, last.outVideoRetransmittedBytes)
        let decoded = delta(first.videoFramesDecoded, last.videoFramesDecoded)
        m.videoDecodeMs = meanMs(delta(first.videoTotalDecodeTime, last.videoTotalDecodeTime), decoded)
        m.videoFramesDropped = delta(first.videoFramesDropped, last.videoFramesDropped)
        m.videoKeyFrameRequestsOut = delta(first.videoKeyFrameRequestsSent, last.videoKeyFrameRequestsSent)
        m.videoInterFrameStddevMs = stddevMs(sum: delta(first.videoTotalInterFrameDelay, last.videoTotalInterFrameDelay),
                                             squares: delta(first.videoTotalSquaredInterFrameDelay, last.videoTotalSquaredInterFrameDelay),
                                             count: decoded)
        if let videoFrom, let start = window.first(where: { $0.timestamp >= videoFrom }),
           last.timestamp - start.timestamp >= 2 {
            let seconds = last.timestamp - start.timestamp
            m.decodedFps = delta(start.videoFramesDecoded, last.videoFramesDecoded).map { $0 / seconds }
            m.freezeFraction = delta(start.videoTotalFreezesDuration, last.videoTotalFreezesDuration).map { min(1, $0 / seconds) }
        }
        return m
    }

    private static func median(_ values: [Double]) -> Double? {
        guard !values.isEmpty else { return nil }
        let sorted = values.sorted()
        let middle = sorted.count / 2
        return sorted.count % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2
    }

    private static func staticMetrics(_ s: CallStatsSample) -> CallQualityMetrics {
        var m = CallQualityMetrics()
        if let rtt = s.rttSeconds ?? s.remoteRttSeconds { m.rttMs = rtt * 1000 }
        m.jitterMs = s.audioJitterSeconds.map { $0 * 1000 }
        m.availableOutgoingKbps = s.availableOutgoingBitrate.map { $0 / 1000 }
        m.videoInFps = s.videoFramesPerSecond
        m.videoOutFps = s.outVideoFramesPerSecond
        m.qualityLimitationReason = s.qualityLimitationReason
        m.audioCodec = s.audioCodec
        m.videoInCodec = s.videoCodec
        m.videoOutCodec = s.outVideoCodec
        m.videoInCodecCode = s.videoCodecCode
        m.videoOutCodecCode = s.outVideoCodecCode
        if let w = s.videoFrameWidth, let h = s.videoFrameHeight, let width = Int(exactly: w), let height = Int(exactly: h), width > 0, height > 0 {
            m.videoInResolution = "\(width)×\(height)"
            m.videoInWidth = w
            m.videoInHeight = h
        }
        if let w = s.outVideoFrameWidth, let h = s.outVideoFrameHeight, let width = Int(exactly: w), let height = Int(exactly: h), width > 0, height > 0 {
            m.videoOutResolution = "\(width)×\(height)"
            m.videoOutWidth = w
            m.videoOutHeight = h
        }
        m.stunRttMs = s.rttSeconds.map { $0 * 1000 }
        m.rtcpRttMs = (s.remoteRttSeconds ?? s.videoRemoteRttSeconds).map { $0 * 1000 }
        m.videoOutTargetKbps = s.targetBitrate.map { $0 / 1000 }
        if let w = s.cameraWidth, let h = s.cameraHeight, let width = Int(exactly: w), let height = Int(exactly: h), width > 0, height > 0 { m.cameraResolution = "\(width)×\(height)" }
        m.cameraFps = s.cameraFps
        m.thermalState = s.thermalState
        m.route = CallMediaRoute.from(localType: s.localCandidateType, remoteType: s.remoteCandidateType,
                                      localRelayProtocol: s.localRelayProtocol)
        m.localProtocol = s.localProtocol
        m.localCandidateType = s.localCandidateType
        m.remoteCandidateType = s.remoteCandidateType
        m.localNetworkType = s.localNetworkType
        return m
    }

    private static func delta(_ a: Double?, _ b: Double?) -> Double? {
        guard let a, let b, b >= a else { return nil }
        return b - a
    }

    private static func ratio(_ part: Double?, _ total: Double?) -> Double? {
        guard let part, let total, total > 0 else { return nil }
        return max(0, min(1, part / total))
    }

    /// A cumulative delay in seconds over a count of what it covers, as the
    /// mean in ms; nil without anything counted.
    private static func meanMs(_ seconds: Double?, _ count: Double?) -> Double? {
        guard let seconds, let count, count > 0 else { return nil }
        return seconds / count * 1000
    }

    /// A cumulative sum over what it covers (QP per frame); nil without anything counted.
    private static func perUnit(_ total: Double?, _ count: Double?) -> Double? {
        guard let total, let count, count > 0 else { return nil }
        return total / count
    }

    /// The standard deviation in ms of `count` gaps from their sum and the
    /// sum of their squares, in seconds.
    private static func stddevMs(sum: Double?, squares: Double?, count: Double?) -> Double? {
        guard let sum, let squares, let count, count > 0 else { return nil }
        let mean = sum / count
        return max(0, squares / count - mean * mean).squareRoot() * 1000
    }
}

// MARK: - ICE diagnostics

/// A call's recent stats reads, for the ICE state diagnostic. libwebrtc pings
/// a stable selected pair only every 2.5 s, so one second between two reads
/// often holds no STUN response on a perfectly healthy pair: what arrived and
/// how many responses came back are measured over `minimumSpan` or more of
/// the same pair. Stats are read while the app is active, and in the
/// background only while diagnostics are on (for this history alone), so a
/// newest read older than `freshFor` says nothing about the moment of the
/// event.
struct CallIceReadHistory {
    /// The reads around an ICE event: the newest one, and the same pair's
    /// read `minimumSpan`...`maximumSpan` before it when there is one.
    struct Window: Equatable {
        var newest: CallStatsSample
        var base: CallStatsSample? = nil
        /// Seconds from `base` to `newest`.
        var span: TimeInterval? = nil
        /// Seconds from `newest` to the event.
        var age: TimeInterval = 0
    }

    /// One read a second, with room for one that came late.
    static let freshFor: TimeInterval = 2.5
    /// Two pings or more of a stable pair.
    static let minimumSpan: TimeInterval = 5
    /// Older reads no longer describe the seconds before the event.
    static let maximumSpan: TimeInterval = 12
    static let capacity = 16

    private struct Read {
        let at: TimeInterval
        let sample: CallStatsSample
    }

    private var reads: [Read] = []

    /// `uptime` is when the read arrived.
    mutating func add(_ sample: CallStatsSample, at uptime: TimeInterval) {
        reads.append(Read(at: uptime, sample: sample))
        if reads.count > Self.capacity { reads.removeFirst(reads.count - Self.capacity) }
    }

    mutating func reset() { reads.removeAll() }

    /// Nil without a read or when the newest one is stale. No `base` when the
    /// newest read has no selected pair, or no read of that same pair, with
    /// no other pair in between, lies `minimumSpan`...`maximumSpan` earlier
    /// (a new pair, the start of the call, reads that stopped or stalled).
    func window(at now: TimeInterval) -> Window? {
        guard let newest = reads.last else { return nil }
        let age = now - newest.at
        guard age >= 0, age <= Self.freshFor else { return nil }
        var result = Window(newest: newest.sample, age: age)
        guard let pair = newest.sample.pairId else { return result }
        for read in reads.dropLast().reversed() {
            let span = newest.at - read.at
            guard span <= Self.maximumSpan, read.sample.pairId == pair else { break }
            if span >= Self.minimumSpan {
                result.base = read.sample
                result.span = span
                break
            }
        }
        return result
    }
}

/// The ICE detail diagnostic (`call_state` operation 5): candidates gathered
/// (a gathering told apart from a network change, `isGathering`) or removed
/// after the call connected, selected-pair switches and why, the
/// connection's DTLS-level state, and around every ICE state change the
/// selected pair's STUN traffic in both directions. Numbers only: an address
/// is reduced to its IP family and never kept, a username fragment only
/// tells ICE generations apart and is never recorded.
enum CallIceDiagnostics {
    /// A signaled or selected candidate, as numbers.
    struct Candidate: Equatable {
        /// host 1, srflx 2, prflx 3, relay 4, anything else 0.
        var type: Double
        /// 1 IPv4, 2 IPv6; nil for a host name (mDNS `.local`) or no address.
        var family: Double?
        /// libwebrtc's network cost (Wi-Fi 10, unknown 50, cellular 250-980);
        /// nil when the candidate does not carry one (a peer-reflexive one).
        var networkCost: Double?
        /// The ICE username fragment: a new one is a new ICE generation.
        var ufrag: String?
    }

    /// Most events of one kind recorded per call: a flapping network must
    /// not push the rest of the report out, and the candidates of a gathering
    /// (the first one's tail after connect, every restart's) must not push
    /// out the later ones that show the network changing.
    static let gatheringCandidateCap = 24 // phase 141 within a gathering
    static let candidateCap = 24          // phase 141 after it
    static let removalCap = 12            // phase 142
    static let switchCap = 24             // phases 150-159
    static let connectionStateCap = 12    // phases 160-165
    static let negotiationCap = 16        // phase 148

    /// How far behind the read a pair's `lastPacketReceivedTimestamp` may lie.
    static let lastPacketHorizonMs = 600_000.0

    /// Milliseconds from the pair's `lastPacketReceivedTimestamp` to the read.
    /// libwebrtc keeps it on its monotonic clock, which is not always the
    /// report's: the first of `clocksMs` it lies 0...`lastPacketHorizonMs`
    /// behind is the one; nil when none fits or nothing arrived yet.
    static func sinceLastPacketMs(_ lastPacketMs: Double?, clocksMs: [Double]) -> Double? {
        guard let lastPacketMs, lastPacketMs > 0 else { return nil }
        for clock in clocksMs {
            let since = clock - lastPacketMs
            if since >= 0 && since <= lastPacketHorizonMs { return since }
        }
        return nil
    }

    /// How long after its ICE generation's first local candidate a candidate
    /// still belongs to that generation's gathering: host candidates come at
    /// once, srflx after a STUN round trip, relay after two to four TURN
    /// round trips (UDP, TCP, TLS), and a cellular radio may first have to
    /// wake up. With continual gathering WebRTC never says the gathering is
    /// complete, so time is the only boundary.
    static let gatheringWindow: TimeInterval = 5

    /// A local candidate whose generation's first candidate came under
    /// `gatheringWindow` ago belongs to that gathering: after a LAN pair
    /// connected, the callee's first gathering is still going (it starts at
    /// the answer), and a restart gathers anew. Later, or of an unknown
    /// generation, it is a network appearing under the call or a
    /// re-enumeration.
    static func isGathering(sinceGenerationStart: TimeInterval?) -> Bool {
        guard let sinceGenerationStart else { return false }
        return sinceGenerationStart >= 0 && sinceGenerationStart < gatheringWindow
    }

    /// The same codes as the ICE state diagnostic: host 1, srflx 2, prflx 3,
    /// relay 4, anything else 0.
    static func candidateTypeCode(_ type: String?) -> Double {
        switch type {
        case "host": return 1
        case "srflx": return 2
        case "prflx": return 3
        case "relay": return 4
        default: return 0
        }
    }

    /// 2 for an IPv6 address, 1 for a dotted IPv4 quad, nil for a name (an
    /// mDNS `.local` host), nothing or anything else. The text is not kept.
    static func addressFamily(_ address: String?) -> Double? {
        guard let address = address?.trimmingCharacters(in: .whitespaces), !address.isEmpty else { return nil }
        if address.contains(":") { return 2 }
        let octets = address.split(separator: ".", omittingEmptySubsequences: false)
        let isQuad = octets.count == 4 && octets.allSatisfy { octet in
            !octet.isEmpty && octet.count <= 3 && octet.allSatisfy { $0 >= "0" && $0 <= "9" }
                && (Int(octet) ?? 256) <= 255
        }
        return isQuad ? 1 : nil
    }

    /// Parses `candidate:<foundation> <component> <transport> <priority>
    /// <address> <port> typ <type> [<key> <value>]...` (with or without
    /// `a=`), taking `ufrag` and `network-cost` from the key-value tail.
    static func candidate(sdp: String) -> Candidate? {
        var text = Substring(sdp.trimmingCharacters(in: .whitespacesAndNewlines))
        if text.hasPrefix("a=") { text = text.dropFirst(2) }
        guard text.hasPrefix("candidate:") else { return nil }
        let fields = text.split(separator: " ")
        guard fields.count >= 8, fields[6] == "typ" else { return nil }
        var result = Candidate(type: candidateTypeCode(String(fields[7])), family: addressFamily(String(fields[4])))
        var index = 8
        while index + 1 < fields.count {
            switch fields[index] {
            case "ufrag": result.ufrag = String(fields[index + 1])
            case "network-cost": result.networkCost = Double(fields[index + 1])
            default: break
            }
            index += 2
        }
        return result
    }

    /// libwebrtc's reason for a selected-pair switch, by keyword: nomination
    /// 1, data received 2, a new candidate pair 3, a pair's state change 4,
    /// the selected pair destroyed 5, network preference 6, remote candidate
    /// generation 7, controller recheck 8, application request 9, other 0.
    static func switchReason(_ reason: String) -> Double {
        let text = reason.lowercased()
        if text.contains("nomination") { return 1 }
        if text.contains("data received") { return 2 }
        if ["new candidate pair", "new local", "new remote", "unknown remote address"].contains(where: { text.contains($0) }) {
            return 3
        }
        if text.contains("state change") { return 4 }
        if text.contains("destroyed") { return 5 }
        if text.contains("preference") { return 6 }
        if text.contains("generation") { return 7 }
        if text.contains("recheck") { return 8 }
        if text.contains("application") { return 9 }
        return 0
    }

    /// The selected pair around an ICE state change (phase 140). From the
    /// newest read: `status` local × 10 + remote IP family, `duration_ms` the
    /// read's age, `budget_ms` its round trip (a LAN answers within ~10 ms),
    /// `visible_count` the transport's selected-pair changes so far, `success`
    /// 1 when DTLS is connected. Over `gap_ms` (only with a base read of the
    /// same pair): `bytes` all media that arrived on any pair (inbound audio
    /// and video payload), `count` the peer's checks that reached us,
    /// `frames` our answers to them, `items` the checks we sent, `late_frames`
    /// packets the socket refused to send. A counter that went backwards is
    /// left out.
    static func pairDetail(_ reads: CallIceReadHistory.Window) -> [String: Double?] {
        let newest = reads.newest
        var values: [String: Double?] = [
            "duration_ms": reads.age * 1000,
            "budget_ms": newest.rttSeconds.map { $0 * 1000 },
            "visible_count": newest.transportPairChanges,
            "success": newest.dtlsConnected.map { $0 ? 1.0 : 0.0 },
        ]
        if newest.localAddressFamily != nil || newest.remoteAddressFamily != nil {
            values["status"] = (newest.localAddressFamily ?? 0) * 10 + (newest.remoteAddressFamily ?? 0)
        }
        guard let base = reads.base, let span = reads.span else { return values }
        values["gap_ms"] = span * 1000
        values["bytes"] = delta(mediaBytes(base), mediaBytes(newest))
        values["count"] = delta(base.pairRequestsReceived, newest.pairRequestsReceived)
        values["frames"] = delta(base.pairResponsesSent, newest.pairResponsesSent)
        values["items"] = delta(base.pairRequestsSent, newest.pairRequestsSent)
        values["late_frames"] = delta(base.pairPacketsDiscardedOnSend, newest.pairPacketsDiscardedOnSend)
        return values
    }

    /// The end of a call (phase 170): `status` the reason (hangup 1, rejected
    /// 2, cancelled 3, missed 4, timeout 5, failed 6, answered elsewhere 7,
    /// remote ended 8, the failed screen 9) and `direction` who ended it: 1
    /// this device, 2 the peer or the server. The controller ends a call it
    /// hangs up, declines or cancels itself with the same event the server's
    /// end arrives as, so only it can say `endedHere`.
    static func endDetail(_ ui: CallUiState, after event: CallEvent, endedHere: Bool) -> (status: Double, direction: Double)? {
        let status: Double
        switch ui {
        case .ended(_, let reason):
            switch reason {
            case .hangup: status = 1
            case .rejected: status = 2
            case .cancelled: status = 3
            case .missed: status = 4
            case .timeout: status = 5
            case .failed: status = 6
            case .answeredElsewhere: status = 7
            case .remoteEnded: status = 8
            }
        case .failed:
            status = 9
        default:
            return nil
        }
        let remote: Bool
        switch event {
        case .remoteEnded, .remoteAnsweredElsewhere, .reconnectReconcileSucceeded: remote = !endedHere
        default: remote = false
        }
        return (status, remote ? 2 : 1)
    }

    /// Inbound media payload: audio plus video, a missing receiver counting
    /// as nothing received yet.
    private static func mediaBytes(_ sample: CallStatsSample) -> Double? {
        guard sample.audioBytesReceived != nil || sample.videoBytesReceived != nil else { return nil }
        return (sample.audioBytesReceived ?? 0) + (sample.videoBytesReceived ?? 0)
    }

    private static func delta(_ a: Double?, _ b: Double?) -> Double? {
        guard let a, let b, b >= a else { return nil }
        return b - a
    }
}

// MARK: - Proximity sensor

/// The screen turns off at the ear only during an audio call on the receiver.
/// Speaker, accessories, video and the end of the call release it.
enum CallProximityPolicy {
    static func wantsProximity(state: CallUiState, controls: ConnectedState?) -> Bool {
        guard let controls, controls.audioRoute == .earpiece else { return false }
        switch state {
        case .outgoingRinging(let call), .connecting(let call):
            return call.mediaKind == .audio
        case .connected, .reconnecting:
            return controls.videoMode == .audio && !controls.localVideoEnabled
        default:
            return false
        }
    }
}
