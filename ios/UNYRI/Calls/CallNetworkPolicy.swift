import Foundation

// Pure, platform-free policies for 1:1 calls on real networks: which media
// profile a network gets, how a dropped media path is recovered (a selected
// pair that never carries the voice included) and when it moves to the
// relay, when outgoing video pauses, where the bandwidth estimate restarts,
// when the media diagnostics are taken, how long TURN credentials are
// trusted and who may learn this device's IP address. No WebRTC, Network or
// UIKit types, so all of it is
// contract-tested with swiftc (ios/Tests/CallNetworkContracts).

// MARK: - Settings

/// "Use less data for calls" (Telegram's wording and choices). Never still
/// follows the network (the ladder below); On cellular uses the saving profile
/// on cellular and metered networks; Always uses it everywhere.
enum CallDataSaving: String, CaseIterable, Equatable {
    case never, cellular, always

    static let storageKey = "callDataSaving.v1"
    /// The two "Call quality" choices this setting replaces (Auto / Save data / High).
    static let legacyVideoKey = "callVideoQuality"
    static let legacyAudioKey = "callAudioQuality"

    /// The stored choice; otherwise the old pair: "Save data" for either medium
    /// asked for less data, Auto and High both meant "follow the network".
    static func resolved(stored: String?, legacyVideo: String?, legacyAudio: String?) -> CallDataSaving {
        if let stored, let value = CallDataSaving(rawValue: stored) { return value }
        return legacyVideo == "economy" || legacyAudio == "economy" ? .always : .never
    }
}

/// "Peer-to-peer calls": who may connect to this device directly and so learn
/// its IP address. Everyone else reaches it through the TURN relay only.
enum CallPeerToPeer: String, CaseIterable, Equatable {
    case everyone, contacts, nobody

    static let storageKey = "callPeerToPeer.v1"
    static func resolved(stored: String?) -> CallPeerToPeer { stored.flatMap(Self.init(rawValue:)) ?? .everyone }
}

enum CallRelayPolicy {
    /// Relay-only ICE (`iceTransportPolicy = .relay`): this device gathers no
    /// host or server-reflexive candidates, so the other person only ever sees
    /// the relay's address. A contact is someone this account has a direct
    /// chat with; an unknown peer is never treated as one.
    static func relayOnly(_ setting: CallPeerToPeer, peerIsContact: Bool) -> Bool {
        switch setting {
        case .everyone: return false
        case .contacts: return !peerIsContact
        case .nobody: return true
        }
    }
}

// MARK: - Network path

enum CallNetworkInterface: Equatable {
    case wifi, cellular, wired, other
}

/// The facts of the current network path the call cares about (from `NWPath`).
struct CallNetworkPath: Equatable {
    var satisfied: Bool
    var interface: CallNetworkInterface?
    /// Cellular or a personal hotspot: metered.
    var isExpensive: Bool
    /// Low Data Mode.
    var isConstrained: Bool

    static let unknown = CallNetworkPath(satisfied: false, interface: nil, isExpensive: false, isConstrained: false)

    /// Wi-Fi <-> cellular (or wired): the candidates of the old path are dead
    /// or about to be, so recovery should not wait for ICE to notice. Compared
    /// against the last satisfied path: a drop that comes back on the same kind
    /// of interface is not a change, and nothing counts before the first path.
    static func interfaceChanged(from lastSatisfied: CallNetworkPath?, to path: CallNetworkPath) -> Bool {
        guard path.satisfied, let lastSatisfied, let old = lastSatisfied.interface, let new = path.interface else { return false }
        return old != new
    }
}

// MARK: - Media ladder

enum CallNetworkTier: Equatable {
    case wifi, cellular, saving
}

/// Everything a network tier decides about the media of a call.
struct CallMediaProfile: Equatable {
    let tier: CallNetworkTier
    let width: Int
    let height: Int
    let fps: Int
    let maxVideoBitrate: Int
    /// Where the bandwidth estimate starts (and restarts, `CallBitrateSeedPolicy`).
    /// With audio and packet overhead taken off, the video target stays above
    /// libwebrtc's size-drop threshold for the row's frame size (300 kbps over
    /// 320x240, 500 kbps over 640x480), so the first frames go out at the
    /// row's size. It may sit above `maxVideoBitrate`: it is the whole link's,
    /// and the caps still bound what is sent.
    let startBitrate: Int
    let audioMinBitrate: Int
    let audioMaxBitrate: Int
    /// Ask the other side for Opus DTX (`usedtx=1`): silence costs almost nothing.
    let requestsDtx: Bool
    /// Put Opus RED (redundant audio) first; negotiated only if both sides keep it.
    let prefersRed: Bool
}

enum CallMediaLadder {
    static let wifi = CallMediaProfile(tier: .wifi, width: 1280, height: 720, fps: 30, maxVideoBitrate: 2_000_000,
                                       startBitrate: 800_000, audioMinBitrate: 16_000, audioMaxBitrate: 32_000,
                                       requestsDtx: false, prefersRed: true)
    /// 30 fps like the capture: 24 would drop one captured frame in five
    /// (33/33/33/33/67 ms). The cap keeps the data cost.
    static let cellular = CallMediaProfile(tier: .cellular, width: 960, height: 540, fps: 30, maxVideoBitrate: 1_000_000,
                                           startBitrate: 650_000, audioMinBitrate: 16_000, audioMaxBitrate: 32_000,
                                           requestsDtx: true, prefersRed: true)
    /// Low Data Mode or "Use less data". RED would double the audio data, so it
    /// is left out of the negotiation altogether. The start (400 kbps) leaves
    /// video its whole 350 kbps cap; data use is bounded by the cap.
    static let saving = CallMediaProfile(tier: .saving, width: 640, height: 360, fps: 15, maxVideoBitrate: 350_000,
                                         startBitrate: 400_000, audioMinBitrate: 16_000, audioMaxBitrate: 20_000,
                                         requestsDtx: true, prefersRed: false)

    static func tier(for path: CallNetworkPath, saving: CallDataSaving) -> CallNetworkTier {
        if saving == .always || path.isConstrained { return .saving }
        // Before the first path report the middle row is the safe guess.
        guard path.satisfied, let interface = path.interface else { return saving == .cellular ? .saving : .cellular }
        if interface == .cellular || path.isExpensive { return saving == .cellular ? .saving : .cellular }
        return .wifi
    }

    static func profile(for tier: CallNetworkTier) -> CallMediaProfile {
        switch tier {
        case .wifi: return wifi
        case .cellular: return cellular
        case .saving: return saving
        }
    }

    static func profile(for path: CallNetworkPath, saving: CallDataSaving) -> CallMediaProfile {
        profile(for: tier(for: path, saving: saving))
    }

    /// The camera format is chosen once per call for the best row this call can
    /// reach, so moving to a better network never restarts the camera; lower
    /// rows only scale the frames down. With Always, the best row is the saving
    /// one, and the camera does not capture (and mirror) frames nobody sends.
    static func captureProfile(saving: CallDataSaving) -> CallMediaProfile {
        saving == .always ? Self.saving : wifi
    }
}

// MARK: - Recovery of a dropped media path

/// The voice transport's ICE identity. Session attributes are inherited by
/// the audio section; attributes of other media sections do not belong to it.
private struct CallAudioIceCredentials: Equatable {
    let ufrag: String
    let password: String

    init?(sdp: String) {
        var ufrag: String?, password: String?
        var inMedia = false, inAudio = false
        for rawLine in sdp.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("m=") {
                if inAudio { break }
                let fields = line.split(separator: " ")
                inMedia = true
                inAudio = fields.first == "m=audio" && fields.count > 1 && fields[1] != "0"
            } else if !inMedia || inAudio {
                if line.hasPrefix("a=ice-ufrag:") { ufrag = String(line.dropFirst("a=ice-ufrag:".count)) }
                if line.hasPrefix("a=ice-pwd:") { password = String(line.dropFirst("a=ice-pwd:".count)) }
            }
        }
        guard inAudio, let ufrag, !ufrag.isEmpty, let password, !password.isEmpty else { return nil }
        self.ufrag = ufrag
        self.password = password
    }
}

/// When to restart ICE on an established call, when to resend an unanswered
/// offer and when to give up. Pure: the controller feeds it events with a
/// monotonic clock and executes the action `next(now:)` returns.
///
/// - `disconnected` (libwebrtc's legacy state: at least 5 unanswered checks
///   and 5 s without a response) does not heal by itself once the other pairs
///   are pruned, while a restart heals it in tens of milliseconds: the caller
///   restarts at once (`disconnectGrace`), toward a plain peer after
///   `plainPeerDisconnectGrace`. ICE `failed` (caller) restarts at once, and
///   so does a Wi-Fi <-> cellular switch (either side) when the peer
///   announced that it handles restart offers in any phase.
/// - Never a second offer while one is unanswered (B3): an unanswered offer is
///   resent as is, so whichever answer arrives belongs to it.
/// - The callee restarts too, but lets the caller go first: its own offers
///   come only after a longer pause in which the caller sent nothing, and
///   only to a caller that announced it resolves crossing offers (the
///   current Android app and older iOS builds do not; toward them the
///   callee waits for the caller, as before).
/// - Everything ends `budget` seconds after the first trouble, under the
///   server's 40 s liveness window (keepalives stop with the network).
struct CallRecoveryPolicy: Equatable {
    enum Role: Equatable { case caller, callee }

    enum Action: Equatable {
        case idle
        case wait(until: TimeInterval)
        /// Fresh TURN credentials if the ones in use are not, then a new
        /// ICE-restart offer (through the relay when `CallRelayFallbackPolicy`
        /// says so).
        case restart
        /// The same, still unanswered offer again (B3).
        case resendOffer
        /// Media is back but our offer was never answered: roll it back.
        case abandonOffer
        case fail
    }

    /// The caller's pause after `disconnected` toward a peer that announced it
    /// resolves crossing offers: none. ICE has already waited 5 s for an
    /// answer, and every second more is frozen media.
    static let disconnectGrace: TimeInterval = 0
    /// Toward a plain peer (the current Android app, older iOS builds) the
    /// caller still waits: that peer may notice the loss up to a check
    /// interval later, and one still connected on its side takes a restart
    /// offer for a video renegotiation and can hide the picture.
    static let plainPeerDisconnectGrace: TimeInterval = 3
    static let calleeFirstOfferDelay: TimeInterval = 6
    /// Added to the callee's backoff, so a caller that is still restarting
    /// always goes first and the two offers do not cross.
    static let calleeYield: TimeInterval = 4
    static let budget: TimeInterval = 30
    static let answerTimeout: TimeInterval = 5
    static let backoff: [TimeInterval] = [4, 8, 12]

    var role: Role = .caller
    /// The peer announced it resolves crossing offers (perfect negotiation)
    /// and takes restart offers in any phase. Without it a callee never offers
    /// (an older client could end the call when a callee offer crossed its own)
    /// and nobody restarts before ICE reports trouble.
    var peerResolvesGlare = false
    private var mayOffer: Bool { role == .caller || peerResolvesGlare }
    private(set) var startedAt: TimeInterval?
    private(set) var mediaUp = true
    /// Restart rounds in this recovery, ours or the peer's.
    private(set) var negotiations = 0
    private(set) var lastNegotiationAt: TimeInterval?
    private(set) var urgent = false
    /// An urgent restart that came while a non-restart offer of ours was out
    /// (B3: one offer at a time). It goes out once that offer resolves,
    /// instead of ending with the recovery its answer settles.
    private(set) var restartAfterOffer = false
    /// Our offer awaiting its answer (any kind). Tracked outside recovery too:
    /// a recovery that starts with an offer outstanding resends it instead of
    /// making another one.
    private(set) var offerPendingSince: TimeInterval?
    private(set) var pendingOfferIsRestart = false
    /// Accepted peer offers whose async answer effects have not finished.
    /// In particular, glare rollback must not wake a local restart between
    /// applying the peer's offer and sending its answer.
    private var pendingPeerAnswers = 0
    private var pendingPeerIceCredentials: CallAudioIceCredentials?
    private var remoteIceCredentials: CallAudioIceCredentials?
    /// This recovery was started by a dead pair alone (`mediaStalled`), with
    /// media up: a consent loss that follows begins the outage afresh.
    private var stallOnly = false

    var isActive: Bool { startedAt != nil }

    /// A restart round of this recovery (ours or the peer's) already went
    /// through and media is still down: that restart did not heal the path.
    var earlierRoundFailed: Bool { isActive && !mediaUp && negotiations > 0 }

    init(role: Role = .caller, peerResolvesGlare: Bool = false) {
        self.role = role
        self.peerResolvesGlare = peerResolvesGlare
    }

    private mutating func begin(at now: TimeInterval) {
        guard startedAt == nil else { return }
        startedAt = now
        negotiations = 0
        lastNegotiationAt = nil
        urgent = false
        restartAfterOffer = false
    }

    private mutating func settleIfDone() {
        guard mediaUp, offerPendingSince == nil, pendingPeerAnswers == 0,
              !urgent, !restartAfterOffer else { return }
        startedAt = nil
        negotiations = 0
        lastNegotiationAt = nil
        urgent = false
        restartAfterOffer = false
        stallOnly = false
    }

    /// Restart now, or once the non-restart offer that is out resolves. A
    /// restart offer that is out already does the job.
    private mutating func wantRestart() {
        if offerPendingSince == nil { urgent = true }
        else if !pendingOfferIsRestart { restartAfterOffer = true }
    }

    /// Media goes down during a recovery a dead pair started: the outage
    /// begins now. Its budget runs from the loss, and the dead-pair round is
    /// not one of its rounds (never a failed round for the relay fallback).
    /// An offer still out is kept; its answer starts the usual backoff.
    private mutating func reanchorStall(at now: TimeInterval) {
        guard stallOnly else { return }
        stallOnly = false
        startedAt = now
        negotiations = 0
        lastNegotiationAt = nil
    }

    mutating func mediaDisconnected(at now: TimeInterval) {
        reanchorStall(at: now)
        begin(at: now)
        mediaUp = false
    }

    /// ICE `failed`: nothing heals by itself any more. The caller restarts at
    /// once; the callee keeps its schedule (the caller is about to offer).
    mutating func mediaFailed(at now: TimeInterval) {
        reanchorStall(at: now)
        begin(at: now)
        mediaUp = false
        if role == .caller { wantRestart() }
    }

    /// Wi-Fi <-> cellular on this device: restart now, even while ICE still
    /// reports connected over the path that is going away. Only toward a peer
    /// that announced it handles such offers: a plain peer (the current Android
    /// app, older iOS builds) still connected on its side treats a restart offer
    /// as a video renegotiation and can hide the picture for the rest of the
    /// call. Toward it, recovery starts when ICE reports trouble, as before.
    mutating func interfaceChanged(at now: TimeInterval) {
        guard peerResolvesGlare else { return }
        begin(at: now)
        wantRestart()
    }

    /// The selected pair carries none of the peer's voice, or refuses our own
    /// sends, although ICE says connected (`CallDeadPairPolicy`): the caller
    /// restarts now, as after a network switch. Only toward a peer that
    /// resolves crossing offers, and never from the callee, so offers do not
    /// cross: the caller sees the dead pair too, as the peer's silence or as
    /// its own refused sends. Media stays up, so this is one urgent restart
    /// that never fails the call and is never a failed round
    /// (`earlierRoundFailed`) for the relay fallback; a consent loss that
    /// follows starts the outage afresh (`reanchorStall`). True when this
    /// side acts on it (after the answer to an offer that is out, B3).
    @discardableResult
    mutating func mediaStalled(at now: TimeInterval) -> Bool {
        guard peerResolvesGlare, role == .caller else { return false }
        if startedAt == nil { stallOnly = true }
        begin(at: now)
        wantRestart()
        return true
    }

    mutating func mediaConnected(at now: TimeInterval) {
        mediaUp = true
        urgent = false
        restartAfterOffer = false
        settleIfDone()
    }

    /// The reducer started a local offer (B3: there is at most one). An
    /// urgent restart that had not gone out yet waits for this offer.
    mutating func offerRequested(at now: TimeInterval, restart: Bool) {
        offerPendingSince = now
        pendingOfferIsRestart = restart
        guard restart else {
            if urgent {
                urgent = false
                restartAfterOffer = true
            }
            return
        }
        restartAfterOffer = false
        negotiations += 1
        lastNegotiationAt = now
        urgent = false
    }

    /// The offer went out (first send or resend): the answer timer restarts.
    mutating func offerSent(at now: TimeInterval) {
        if offerPendingSince != nil { offerPendingSince = now }
    }

    /// Answered, rolled back or failed. A restart held back by this offer
    /// becomes due, within the budget, after any peer answer goes out (an
    /// offer abandoned at the deadline takes it along).
    mutating func offerResolved(at now: TimeInterval) {
        if pendingOfferIsRestart && isActive { lastNegotiationAt = now }
        let restartNow = restartAfterOffer && (startedAt.map { now < $0 + Self.budget } ?? false)
        restartAfterOffer = false
        offerPendingSince = nil
        pendingOfferIsRestart = false
        if restartNow { urgent = true } else { settleIfDone() }
    }

    /// Only successfully applied remote descriptions become the comparison
    /// point for a later offer. Missing credentials cannot prove a restart.
    mutating func remoteDescriptionApplied(sdp: String) {
        remoteIceCredentials = CallAudioIceCredentials(sdp: sdp)
    }

    /// Called synchronously when the reducer accepts an offer, before it
    /// resolves our crossing offer or any async engine work can yield. A peer
    /// ICE restart covers existing intent; a video-only offer does not. A new
    /// outage/switch while the answer is being made can still request recovery.
    mutating func peerOfferReceived(sdp: String) {
        let previous = pendingPeerAnswers > 0 ? pendingPeerIceCredentials : remoteIceCredentials
        let offered = CallAudioIceCredentials(sdp: sdp)
        pendingPeerAnswers += 1
        // Compare queued offers with the newest accepted offer, so a resend
        // before setRemote completes cannot swallow a newer local switch.
        pendingPeerIceCredentials = offered
        if let previous, let offered,
           offered.ufrag != previous.ufrag, offered.password != previous.password {
            urgent = false
            restartAfterOffer = false
        }
    }

    /// We answered the peer's offer. Re-plan only after the answer goes out:
    /// any restart intent that its ICE credentials did not cover is kept.
    mutating func peerNegotiated(at now: TimeInterval) {
        finishPeerAnswer(at: now, succeeded: true)
    }

    /// Either applying the offer or making its answer failed. Release only
    /// this operation; other accepted offers still own their answer slots.
    mutating func peerAnswerFailed(at now: TimeInterval) {
        finishPeerAnswer(at: now, succeeded: false)
    }

    private mutating func finishPeerAnswer(at now: TimeInterval, succeeded: Bool) {
        pendingPeerAnswers = max(0, pendingPeerAnswers - 1)
        if pendingPeerAnswers == 0 { pendingPeerIceCredentials = nil }
        guard isActive else { return }
        if succeeded {
            negotiations += 1
            lastNegotiationAt = now
        }
        if let started = startedAt, now >= started + Self.budget {
            urgent = false
            restartAfterOffer = false
        }
        settleIfDone()
    }

    /// A restart could not start (another negotiation holds the connection).
    mutating func restartDeferred(at now: TimeInterval) {
        urgent = false
        if isActive { lastNegotiationAt = now }
        settleIfDone()
    }

    func next(now: TimeInterval) -> Action {
        guard let started = startedAt else { return .idle }
        let deadline = started + Self.budget
        if pendingPeerAnswers > 0 {
            if mediaUp { return .idle }
            return now >= deadline ? .fail : .wait(until: deadline)
        }
        if let sent = offerPendingSince {
            if now >= deadline { return mediaUp ? .abandonOffer : .fail }
            let resendAt = sent + Self.answerTimeout
            return now >= resendAt ? .resendOffer : .wait(until: min(resendAt, deadline))
        }
        if mediaUp { return urgent && mayOffer ? .restart : .idle }
        if now >= deadline { return .fail }
        guard mayOffer else { return .wait(until: deadline) }
        let due = nextRestart(after: started)
        if due >= deadline { return .wait(until: deadline) }
        return now >= due ? .restart : .wait(until: due)
    }

    private func nextRestart(after started: TimeInterval) -> TimeInterval {
        if urgent { return started }
        guard let last = lastNegotiationAt else {
            switch role {
            case .caller: return started + (peerResolvesGlare ? Self.disconnectGrace : Self.plainPeerDisconnectGrace)
            case .callee: return started + Self.calleeFirstOfferDelay
            }
        }
        let step = Self.backoff[min(max(negotiations - 1, 0), Self.backoff.count - 1)]
        return last + step + (role == .callee ? Self.calleeYield : 0)
    }
}

// MARK: - A pair that never carries the voice

/// A selected pair that never carries the voice: ICE and DTLS are up, yet
/// almost nothing arrives (report 12 Nov: 278-408 bytes in 6 s, 301 packets
/// refused by one phone's own socket) until ICE's consent expires 5-6 s
/// later. Without DTX (not asked for on Wi-Fi) the peer's Opus sends about 50
/// packets a second, silence and mute included, so fewer than
/// `minimumPackets` in the first `deadAfter` seconds of a DTLS-connected
/// pair is a dead pair. So is a pair whose socket refused `minimumPackets` of
/// our own sends (`packetsDiscardedOnSend`) in that time without any growth
/// in its sent bytes, even while the peer's voice arrives: loss in our
/// direction only, which the peer hears as silence and this side sees only
/// there. The clock starts at the pair's
/// first read with DTLS connected (no voice can arrive before). Watched only
/// for `watchFor` seconds: a pair that carried the voice and then stops
/// (CallKit hold, an interruption) is left to ICE. A new pair that comes
/// while the peer is silent (our network switches during its hold) is not
/// judged on that silence either: only a pair selected while the voice was
/// arriving when the outage began (within `arrivingWithin`), or before any
/// voice came in this call (its first pairs). With no preceding outage, the
/// pair selection itself is the boundary; the pair that replaces a dead one
/// also remembers voice arriving at its report. DTX (`voiceExpected` is false)
/// exempts only the peer's silence; our refused sends still count. At
/// most `maximumPerCall` a call, so a peer whose voice never starts (its
/// audio session never came up) cannot restart the call over and over.
struct CallDeadPairPolicy: Equatable {
    static let deadAfter: TimeInterval = 3
    static let watchFor: TimeInterval = 10
    static let minimumPackets = 10.0
    static let maximumPerCall = 2
    static let arrivingWithin: TimeInterval = 2

    private var pairId: String?
    private var watchedSince: TimeInterval?
    /// The inbound voice packets counted at the watch's start (the counter
    /// spans every pair of the call).
    private var packetsAtStart = 0.0
    /// The pair's refused sends at the watch's start (the pair's own counter).
    private var refusedAtStart = 0.0
    /// Bytes successfully sent on this pair at the same watch boundary.
    /// Missing/reset counters cannot prove that our sends stopped.
    private var bytesSentAtStart: Double?
    /// The arrival state at the first notice of an outage, or the judgment of
    /// a pair just reported dead, kept until the next pair or renewed voice
    /// on the same pair.
    private var outageJudgesSilence: Bool?
    /// The peer's silence counts against this pair (`arrivingWithin`).
    private var judgesSilence = true
    private var settled = false
    /// Voice counter when this pair was reported. A few more packets are not
    /// renewed voice; `minimumPackets` after the report end the carry-over.
    /// The pair stays settled, so it can never spend another report itself.
    private var voiceAtReport: Double?
    /// The voice counter at the previous read, and when it last grew.
    private var lastVoiceTotal: Double?
    private var voiceGrewAt: TimeInterval?
    /// Dead pairs reported in this call.
    private(set) var reported = 0
    /// The voice packets of the watched pair, our sends it refused and how
    /// long it has been watched, as of the last read (the diagnostic).
    private(set) var packets = 0.0
    private(set) var refused = 0.0
    private(set) var watched: TimeInterval = 0
    /// The last dead pair was judged on our refused sends (not the silence).
    private(set) var sendsRefused = false

    /// Remember the first outage notice, before a replacement pair can take
    /// several seconds to connect. Later ICE/path notices belong to the same
    /// outage and must not replace its pre-outage arrival state.
    mutating func noteOutage(at now: TimeInterval) {
        guard outageJudgesSilence == nil else { return }
        outageJudgesSilence = (lastVoiceTotal ?? 0) == 0
            || (voiceGrewAt.map { now - $0 <= Self.arrivingWithin } ?? false)
    }

    /// One stats read while the call is connected; true once for a dead pair.
    /// `voiceExpected`: the description we last applied asked the peer for no
    /// DTX (what it encodes with, not our current row), so its voice never
    /// pauses by design and its silence may be judged. Refused sends need a
    /// known, non-reset byte counter proving that no sends succeeded.
    mutating func update(now: TimeInterval, pairId: String?, dtlsConnected: Bool?, audioPacketsReceived: Double?,
                         packetsDiscardedOnSend: Double? = nil, pairBytesSent: Double? = nil,
                         voiceExpected: Bool = true) -> Bool {
        guard let pairId else { return false }
        let total = audioPacketsReceived ?? lastVoiceTotal ?? 0
        let heardBefore = (lastVoiceTotal ?? 0) > 0
        if pairId == self.pairId, let last = lastVoiceTotal, total < last, voiceAtReport != nil {
            // A stats counter reset starts renewal evidence again; it must
            // neither manufacture renewed voice nor leave the old floor stuck.
            voiceAtReport = total
        }
        if let last = lastVoiceTotal, total > last {
            voiceGrewAt = now
            if pairId == self.pairId {
                if voiceAtReport.map({ total - $0 >= Self.minimumPackets }) ?? true {
                    outageJudgesSilence = nil
                    voiceAtReport = nil
                }
            }
        }
        lastVoiceTotal = total
        if pairId != self.pairId {
            self.pairId = pairId
            watchedSince = nil
            packetsAtStart = 0
            refusedAtStart = 0
            bytesSentAtStart = nil
            settled = false
            voiceAtReport = nil
            judgesSilence = outageJudgesSilence
                ?? (!heardBefore || (voiceGrewAt.map { now - $0 <= Self.arrivingWithin } ?? false))
            outageJudgesSilence = nil
            packets = 0
            refused = 0
            watched = 0
        }
        // DTX may silence the peer; it cannot explain our own socket refusing
        // every send, so only the inbound-silence test is exempt.
        guard !settled, dtlsConnected == true else { return false }
        let discarded = packetsDiscardedOnSend ?? 0
        guard let since = watchedSince else {
            watchedSince = now
            packetsAtStart = total
            refusedAtStart = discarded
            bytesSentAtStart = pairBytesSent
            return false
        }
        packets = max(0, total - packetsAtStart)
        refused = max(0, discarded - refusedAtStart)
        watched = now - since
        if watched > Self.watchFor {
            settled = true
            return false
        }
        guard watched >= Self.deadAfter else { return false }
        settled = true
        let silent = voiceExpected && judgesSilence && packets < Self.minimumPackets
        let sendsStopped = bytesSentAtStart.map { pairBytesSent == $0 } ?? false
        let refusing = refused >= Self.minimumPackets && sendsStopped
        guard silent || refusing, reported < Self.maximumPerCall else { return false }
        reported += 1
        sendsRefused = refusing
        // Preserve both its original judgment and voice that resumed before
        // a refused-send verdict. A trickle after the report cannot erase it.
        voiceAtReport = total
        outageJudgesSilence = judgesSilence
            || (voiceGrewAt.map { now - $0 <= Self.arrivingWithin } ?? false)
        return true
    }
}

// MARK: - Falling back to the relay

/// On some networks (a shared fast Wi-Fi) the selected direct pair loses
/// consent: no answer to its checks a few seconds after connect, or later on
/// a flowing link in one direction. A restart heals it in tens of
/// milliseconds, but each loss first freezes media for the 5 s ICE waits,
/// and a restart with every candidate type picks the same kind of pair again.
/// So the second consent loss of a direct pair in one call, or a restart on a
/// direct path that did not bring media back, moves the call to the TURN
/// relay: relay-only ICE (`iceTransportPolicy = .relay`, the current servers
/// kept), then the restart offer. Only once this connection has gathered a
/// relay candidate of its own, which proves a TURN allocation works from
/// here (a TURN URL alone does not: its port may be blocked, the server's
/// quota or relay ports used up); otherwise the normal restart. The relay
/// stays for the rest of the call unless a restart round over it does not
/// bring media back: the next restart then goes back to every candidate type
/// (a superset of the relay pairs, so never worse) and the call stays there.
/// The side that makes the restart offer switches; the other one just
/// answers an ordinary restart offer, and with a relay at the far end its own
/// pair is no longer direct, so it never switches as well. Pure: the
/// controller reports ICE and network events and asks before every restart
/// offer.
struct CallRelayFallbackPolicy: Equatable {
    enum Reason: Equatable {
        /// The second consent loss of a direct pair in this call.
        case repeatedLoss
        /// A restart round on a direct path did not bring media back.
        case restartFailed
    }

    static let directLossesBeforeRelay = 2
    /// A loss this soon after a Wi-Fi <-> cellular switch or losing the
    /// network is the network's doing (ICE notices about 5 s later), not the
    /// pair's, and is not counted.
    static let networkChangeWindow: TimeInterval = 10

    /// Relay-only ICE: from the start ("Peer-to-peer calls"), or since the
    /// fallback. Nothing more to switch, and losses are not counted.
    private(set) var relayOnly: Bool
    /// The relay-only ICE is the fallback's, not the privacy setting's: one
    /// that does not work may be undone.
    private(set) var relayByFallback = false
    /// The fallback went back to every candidate type; it never switches
    /// again in this call.
    private(set) var reverted = false
    /// Consent losses of a direct pair in this call.
    private(set) var directLosses = 0
    /// The current outage began with a counted loss of a direct pair.
    private(set) var outageOnDirectPath = false
    /// A restart round of the current outage on a direct path did not bring
    /// media back. Ends with the outage.
    private(set) var restartFailedOnDirectPath = false
    /// A restart round over the fallback's relay did not bring media back.
    /// Ends with the outage.
    private(set) var relayRoundFailed = false
    private var networkChangedAt: TimeInterval?

    init(relayOnly: Bool = false) {
        self.relayOnly = relayOnly
    }

    /// The candidate type (`host`, `srflx`, `prflx`, `relay`) of a candidate
    /// line (`candidate:<foundation> <component> <transport> <priority>
    /// <address> <port> typ <type> ...`, with or without `a=`); nil otherwise.
    static func candidateType(sdp: String) -> String? {
        var text = Substring(sdp.trimmingCharacters(in: .whitespacesAndNewlines))
        if text.hasPrefix("a=") { text = text.dropFirst(2) }
        guard text.hasPrefix("candidate:") else { return nil }
        let fields = text.split(separator: " ")
        guard fields.count >= 8, fields[6] == "typ" else { return nil }
        return fields[7].lowercased()
    }

    /// A direct pair: neither end is a relay (host, srflx or prflx on both).
    /// An unknown end is not direct, so a pair never reported is not counted.
    static func isDirect(localType: String?, remoteType: String?) -> Bool {
        let direct: Set<String> = ["host", "srflx", "prflx"]
        guard let localType, let remoteType else { return false }
        return direct.contains(localType) && direct.contains(remoteType)
    }

    /// Whether any of the ICE server URLs is a TURN server (`turn:`/`turns:`).
    static func hasTurnServer(_ urls: [String]) -> Bool {
        urls.contains { url in
            let scheme = url.lowercased()
            return scheme.hasPrefix("turn:") || scheme.hasPrefix("turns:")
        }
    }

    /// Wi-Fi <-> cellular, or no network at all. An outage under way becomes
    /// the network's too: its failed restarts say nothing about the direct
    /// pair. A failed round over the fallback's relay still goes back to
    /// every candidate type: that is never worse, while a relay the new
    /// network cannot reach would hold the call until it fails.
    mutating func networkChanged(at now: TimeInterval) {
        networkChangedAt = now
        outageOnDirectPath = false
        restartFailedOnDirectPath = false
    }

    /// ICE went from connected to disconnected or failed on an established
    /// call: the selected pair lost consent, and a new outage begins.
    /// `direct`: that pair, as WebRTC last reported it (`isDirect`).
    mutating func consentLost(direct: Bool, at now: TimeInterval) {
        let network = networkChangedAt.map { now >= $0 && now - $0 < Self.networkChangeWindow } ?? false
        endOutage()
        outageOnDirectPath = direct && !relayOnly && !network
        if outageOnDirectPath { directLosses += 1 }
    }

    /// ICE connected again: the outage is over.
    mutating func mediaRestored() {
        endOutage()
    }

    /// What the failed restart rounds of an outage found holds for that
    /// outage only: a restart after media came back (in a later outage, or
    /// on a network switch while connected) never acts on it.
    private mutating func endOutage() {
        outageOnDirectPath = false
        restartFailedOnDirectPath = false
        relayRoundFailed = false
    }

    /// A restart round of the current outage went through and media is still
    /// down (`CallRecoveryPolicy.earlierRoundFailed`).
    mutating func restartFailed() {
        if relayOnly {
            if relayByFallback { relayRoundFailed = true }
        } else if outageOnDirectPath {
            restartFailedOnDirectPath = true
        }
    }

    /// Why the restart offer about to be made should go through the relay;
    /// nil for the normal restart. `relayGathered`: this connection has
    /// gathered a relay candidate of its own, so a TURN allocation works
    /// from here. `pairDirect`: the selected pair is still a direct one (once
    /// the peer went through the relay, this side need not).
    func relayReason(relayGathered: Bool, pairDirect: Bool) -> Reason? {
        guard !relayOnly, !reverted, relayGathered, pairDirect else { return nil }
        if directLosses >= Self.directLossesBeforeRelay { return .repeatedLoss }
        if outageOnDirectPath && restartFailedOnDirectPath { return .restartFailed }
        return nil
    }

    /// The restart offer about to be made should go back to every candidate
    /// type: a round over the fallback's relay did not bring media back (the
    /// allocation may fail now, or the relay went away). Never for a call
    /// that is relay-only from the start.
    var backToAllDue: Bool { relayOnly && relayByFallback && relayRoundFailed }

    /// The connection is relay-only now, for the rest of the call unless a
    /// round over the relay fails (`backToAllDue`).
    mutating func relayApplied() {
        relayOnly = true
        relayByFallback = true
        endOutage()
    }

    /// The connection gathers every candidate type again, for the rest of
    /// the call: no second switch.
    mutating func relayReverted() {
        relayOnly = false
        relayByFallback = false
        reverted = true
        endOutage()
    }
}

// MARK: - Pausing outgoing video on a starved link

/// Video that cannot get ~150 kbps only freezes and starves the voice. After
/// 5 s below that, while the encoder reports that bandwidth holds it back and
/// the link shows congestion (a round trip of 400 ms or more, or 6 % or more
/// of our audio lost: the grader's weak steps), the encoder stops (the peer
/// is told the camera is off) and retries later, longer after each failed
/// try. Our loss comes only with the peer's receiver reports, 1 to 7.5 s
/// apart, and a window without a new one reads none, so a loss stays
/// evidence for `lossEvidenceFor`. A low estimate alone never pauses: on a
/// quiet LAN pair (report 12 Nov: 5 ms, no loss) it only follows an encoder
/// that sends little.
/// Nothing pauses within `resumeGrace` of a resume, a camera start or a
/// capture change, so a restarted encoder can climb from the estimate it
/// starts with instead of being judged on the one the pause left behind. A
/// network change retries at once: the bandwidth estimate starts over on the
/// new path.
struct CallVideoPausePolicy: Equatable {
    enum Decision: Equatable { case pause, resume }

    static let pauseBelowKbps = 150.0
    static let pauseAfter: TimeInterval = 5
    static let retryAfter: [TimeInterval] = [10, 20, 40, 60]
    /// No pause this long after a resume, a camera start, a capture change
    /// or a network change.
    static let resumeGrace: TimeInterval = 15
    /// Congestion evidence: the grader's weak steps for the round trip and
    /// our outgoing loss (`CallQualityGrader.grade`).
    static let congestedRttMs = 400.0
    static let congestedLoss = 0.06
    /// A loss at `congestedLoss` stays evidence this long: past the longest
    /// gap between two of the peer's audio receiver reports (libwebrtc's 5 s
    /// interval, randomized to 7.5 s).
    static let lossEvidenceFor: TimeInterval = 8
    /// Healthy this long after a retry: the next pause starts the backoff over.
    static let healthyResetAfter: TimeInterval = 30
    /// Samples further apart than this (the app was away, sampling stopped):
    /// what came before says nothing about the link now.
    static let sampleGap: TimeInterval = 3

    private(set) var paused = false
    /// Pauses in the current backoff (1: the first, retried after 10 s).
    private(set) var failedTries = 0
    private var lowSince: TimeInterval?
    private var healthySince: TimeInterval?
    private var retryAt: TimeInterval?
    private var lastSampleAt: TimeInterval?
    private var graceUntil: TimeInterval?
    /// The last sample with our loss at `congestedLoss` or more.
    private var lossEvidenceAt: TimeInterval?

    /// Not sending video any more: the pause (if any) ends with it.
    mutating func reset() -> Decision? {
        let wasPaused = paused
        self = CallVideoPausePolicy()
        return wasPaused ? .resume : nil
    }

    /// The estimate starts over on the new path: a pause ends at once, and
    /// the new path gets the grace.
    mutating func networkChanged(at now: TimeInterval) -> Decision? {
        failedTries = 0
        lowSince = nil
        healthySince = nil
        lossEvidenceAt = nil
        graceUntil = now + Self.resumeGrace
        guard paused else { return nil }
        paused = false
        retryAt = nil
        return .resume
    }

    /// The camera stopped or started again (another app took it, the app went
    /// away): the windows start over, so a resumed camera is never paused for
    /// seconds that measured a stopped one, and it gets the grace.
    mutating func captureChanged(at now: TimeInterval) {
        lowSince = nil
        healthySince = nil
        lossEvidenceAt = nil
        graceUntil = now + Self.resumeGrace
    }

    /// One stats sample (about once a second). `availableKbps` is nil when
    /// there is no usable estimate (reconnecting, app in the background);
    /// such a sample breaks the windows like a sampling gap.
    /// `limitedByBandwidth`: the encoder reports `qualityLimitationReason ==
    /// "bandwidth"`. Only then is a low estimate the link's fault: an encoder
    /// that sends little (a static scene, just resumed) lets the estimate sink
    /// with it, and that must never pause video or cycle pause and resume.
    /// libwebrtc keeps reporting "bandwidth" while an earlier restriction
    /// holds, so the link must also show congestion: `rttMs` (the window's
    /// round trip) at `congestedRttMs` or more, or `outgoingLoss` (0...1, the
    /// peer's reports on our audio) at `congestedLoss` or more within
    /// `lossEvidenceFor`; nil is no evidence. `sending`: the camera is on in a
    /// video call. Its intent, not proof of frames: a camera that is still
    /// starting has an encoder that bandwidth does not hold back, so it can
    /// never be paused; its first sample starts the grace.
    /// `captureInterrupted`: another app has the camera or the app is away.
    /// Nothing goes out, but the camera comes back by itself, so a pause and
    /// its backoff stay (the peer keeps the avatar) and the windows start
    /// over when it does.
    mutating func update(now: TimeInterval, availableKbps: Double?, limitedByBandwidth: Bool,
                         rttMs: Double?, outgoingLoss: Double?, sending: Bool,
                         captureInterrupted: Bool) -> Decision? {
        guard sending else { return reset() }
        if lastSampleAt == nil { graceUntil = now + Self.resumeGrace }
        guard !captureInterrupted else {
            captureChanged(at: now)
            return nil
        }
        if let last = lastSampleAt, now - last > Self.sampleGap { captureChanged(at: now) }
        lastSampleAt = now
        guard let kbps = availableKbps else {
            // No estimate (reconnecting): the windows start over, so the first
            // sample after it never pauses on starvation from before.
            lowSince = nil
            healthySince = nil
            return nil
        }
        if paused {
            guard now >= (retryAt ?? now) else { return nil }
            paused = false
            retryAt = nil
            lowSince = nil
            healthySince = nil
            lossEvidenceAt = nil
            graceUntil = now + Self.resumeGrace
            return .resume
        }
        if (outgoingLoss ?? 0) >= Self.congestedLoss { lossEvidenceAt = now }
        if kbps < Self.pauseBelowKbps {
            healthySince = nil
            let lossy = lossEvidenceAt.map { now - $0 <= Self.lossEvidenceFor } ?? false
            let congested = (rttMs ?? 0) >= Self.congestedRttMs || lossy
            guard limitedByBandwidth, congested, now >= (graceUntil ?? now) else { lowSince = nil; return nil }
            let since = lowSince ?? now
            lowSince = since
            guard now - since >= Self.pauseAfter else { return nil }
            paused = true
            lowSince = nil
            retryAt = now + Self.retryAfter[min(failedTries, Self.retryAfter.count - 1)]
            failedTries += 1
            return .pause
        }
        lowSince = nil
        let since = healthySince ?? now
        healthySince = since
        if now - since >= Self.healthyResetAfter { failedTries = 0 }
        return nil
    }
}

// MARK: - Where the bandwidth estimate restarts

/// libwebrtc drops the first frames of an encoder that starts or resumes,
/// one size step each (up to four: 1280x720 down to 320x180), while its
/// target is under 300 kbps for frames over 320x240 or under 500 kbps for
/// frames over 640x480; turning an encoding active again re-arms that, and
/// afterwards only the QP scaler climbs back. So the estimate is seeded at
/// the row's start (`CallMediaProfile.startBitrate`) before the encoding
/// turns active, but only where that cannot burst into a link that is short:
/// - a new path or row (network or tier change): always, the old estimate
///   measured another link;
/// - a camera starting or being turned back on mid-call: only on a clean
///   link (round trip under the pause policy's congestion line, our audio
///   loss under 2 %, a missing loss
///   is no evidence) whose estimate is below the row start (never downward:
///   a seed resets a better estimate too);
/// - the pause policy's timed retry: never. It resumes on a link measured
///   starved (held back by bandwidth, with congestion), and with video off
///   the round trip and loss recover by themselves, so they cannot vouch for
///   it; a seed would burst into it and starve the voice at every retry. It
///   resumes from the estimate the pause left behind.
enum CallBitrateSeedPolicy {
    enum Cause: Equatable { case newPath, cameraStart, retry }

    /// What the last stats read measured (`CallQualityMetrics`); nil fields
    /// were not measured.
    struct Link: Equatable {
        var rttMs: Double?
        var outgoingLoss: Double?
        var availableKbps: Double?
    }

    static let cleanRttMs = CallVideoPausePolicy.congestedRttMs
    static let cleanLoss = 0.02

    /// The bitrate to seed in bps, nil for none. `link`: the last read,
    /// nil when there is no recent one.
    static func seed(_ cause: Cause, profile: CallMediaProfile, link: Link?) -> Int? {
        switch cause {
        case .newPath:
            return profile.startBitrate
        case .retry:
            return nil
        case .cameraStart:
            guard let link, let rtt = link.rttMs, rtt < cleanRttMs, (link.outgoingLoss ?? 0) < cleanLoss,
                  let kbps = link.availableKbps, kbps * 1000 < Double(profile.startBitrate) else { return nil }
            return profile.startBitrate
        }
    }
}

// MARK: - Media diagnostics

/// When the media diagnostics (`call_state` operation 2) are taken: the
/// outgoing picture's encoded size at each step (phase 117, at most
/// `sizeStepCap` a call) and the receive-side playout delay (phase 119)
/// every `playoutEvery` seconds while connected (`status` 0, at most
/// `playoutCap` a call) and `afterCameraOn` seconds after the own camera
/// turns on (`status` 1 and 2, at most `cameraCap` a call); the video sender
/// (121) and the receiver and transport (122) beside each of those. The
/// camera is followed on every read, diagnostics on or off (`noteCamera`),
/// so the marks are timed from the camera, and marks that came due while
/// nothing was recorded are skipped. Also the audio path once, on the call's
/// first connected read (phase 147 `status` 3, `audioPathDue`). Driven by
/// the 1 Hz stats reads the call already makes: no timer of its own.
struct CallMediaDiagnosticsPolicy: Equatable {
    static let sizeStepCap = 20
    static let playoutEvery: TimeInterval = 10
    static let playoutCap = 30
    static let afterCameraOn: [TimeInterval] = [3, 8]
    static let cameraCap = 10

    private var lastWidth: Double?
    private var lastHeight: Double?
    private(set) var sizeSteps = 0
    private var periodicFrom: TimeInterval?
    private(set) var periodicRecords = 0
    private var cameraWasOn = false
    private var cameraOnAt: TimeInterval?
    /// Marks of `afterCameraOn` done (or skipped) since the camera turned on.
    private var cameraMarksDone = 0
    private(set) var cameraRecords = 0
    private(set) var audioPathTaken = false

    /// What holds the encoder back (`qualityLimitationReason`): none 0, cpu
    /// 1, bandwidth 2, anything else 3; nil when not reported.
    static func limitationCode(_ reason: String?) -> Double? {
        switch reason {
        case nil: return nil
        case "none": return 0
        case "cpu": return 1
        case "bandwidth": return 2
        default: return 3
        }
    }

    /// A video codec as one number: H.264 Constrained High 1, Constrained
    /// Baseline 2, any other H.264 profile 6, each × 100 plus the level_idc
    /// of its `profile-level-id` (31 is level 3.1, 41 is 4.1, 52 is 5.2; 600
    /// without one); VP8 300, VP9 400, AV1 500, anything else 0; nil without
    /// a codec. `mimeType` and `fmtp` are the codec stats' `mimeType` and
    /// `sdpFmtpLine`.
    static func videoCodecCode(mimeType: String?, fmtp: String?) -> Double? {
        guard let mimeType else { return nil }
        switch (mimeType.split(separator: "/").last.map(String.init) ?? mimeType).lowercased() {
        case "vp8": return 300
        case "vp9": return 400
        case "av1", "av1x": return 500
        case "h264": break
        default: return 0
        }
        let parameters = (fmtp ?? "").split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
        guard let field = parameters.first(where: { $0.hasPrefix("profile-level-id=") }),
              let hex = field.split(separator: "=").last, hex.count == 6, let id = Int(hex, radix: 16) else { return 600 }
        let profile = id >> 16, flags = (id >> 8) & 0xff, level = id & 0xff
        let code: Int
        switch profile {
        case 0x64 where flags == 0x0c: code = 1
        case 0x42 where flags & 0x40 != 0, 0x4d where flags & 0x80 != 0, 0x58 where flags & 0xc0 == 0xc0: code = 2
        default: code = 6
        }
        return Double(code * 100 + min(level, 99))
    }

    /// The encoded size of one read; true for a step to record: the call's
    /// first size, or a different one. A read without a size (no outgoing
    /// video) changes nothing.
    mutating func sizeStep(width: Double?, height: Double?) -> Bool {
        guard let width, let height, width != lastWidth || height != lastHeight else { return false }
        lastWidth = width
        lastHeight = height
        guard sizeSteps < Self.sizeStepCap else { return false }
        sizeSteps += 1
        return true
    }

    /// Every read, diagnostics on or off: the own camera's intent. Its turning
    /// on starts the marks; while nothing is recorded (`recording` false) the
    /// marks that come due are passed, so diagnostics turned on later never
    /// take a mark long after the camera started.
    mutating func noteCamera(_ cameraOn: Bool, at now: TimeInterval, recording: Bool) {
        if cameraOn != cameraWasOn {
            cameraWasOn = cameraOn
            cameraOnAt = cameraOn ? now : nil
            cameraMarksDone = 0
        }
        if !recording, let on = cameraOnAt, let due = Self.afterCameraOn.lastIndex(where: { now - on >= $0 }) {
            cameraMarksDone = max(cameraMarksDone, due + 1)
        }
    }

    /// One recorded read: true once a call, on the first connected one, for
    /// the audio path (phase 147 `status` 3): WebRTC has configured the
    /// audio session by then, which it may not have at CallKit's activation.
    mutating func audioPathDue(connected: Bool) -> Bool {
        guard connected, !audioPathTaken else { return false }
        audioPathTaken = true
        return true
    }

    /// One recorded read, after `noteCamera`: the playout record due now as
    /// its `status` (0 periodic, 1 and 2 the marks after the camera turned
    /// on), nil for none. `measured`: the read has a playout delay; a record
    /// that is due waits for one. One record per read; a later camera mark
    /// that is already due replaces the earlier one.
    mutating func playout(now: TimeInterval, connected: Bool, measured: Bool) -> Double? {
        guard connected else { return nil }
        if periodicFrom == nil { periodicFrom = now }
        guard measured else { return nil }
        if let on = cameraOnAt, cameraRecords < Self.cameraCap,
           let due = Self.afterCameraOn.lastIndex(where: { now - on >= $0 }), due >= cameraMarksDone {
            cameraMarksDone = due + 1
            cameraRecords += 1
            return Double(due + 1)
        }
        if let from = periodicFrom, now - from >= Self.playoutEvery, periodicRecords < Self.playoutCap {
            periodicFrom = now
            periodicRecords += 1
            return 0
        }
        return nil
    }
}

// MARK: - TURN credentials

/// `GET /calls/ice-servers` returns credentials valid for `ttl_seconds`
/// (default 1200). An established relay outlives them, but a new allocation
/// (an ICE restart) needs unexpired ones: they are refreshed at half the TTL
/// during a call, and before a restart only when the ones the connection runs
/// with are no longer fresh (`refreshBeforeRestart`), and cached until then.
enum CallIceCredentialPolicy {
    /// A server without the field (older deployment) issues one-hour credentials.
    static let defaultTTL: TimeInterval = 3600
    /// Never refresh more often than every 30 s, whatever the server says.
    static let minimumTTL: TimeInterval = 60
    /// How long a restart waits for fresh credentials on a network that may
    /// still be switching before it goes ahead with the current ones.
    static let restartRefreshTimeout: TimeInterval = 1.5
    /// A failed refresh is retried this much later (never a tight loop).
    static let retryAfterFailure: TimeInterval = 60

    static func ttl(_ seconds: Int?) -> TimeInterval {
        guard let seconds, seconds > 0 else { return defaultTTL }
        return max(minimumTTL, TimeInterval(seconds))
    }

    static func refreshAt(fetchedAt: TimeInterval, ttl: TimeInterval) -> TimeInterval {
        fetchedAt + ttl / 2
    }

    /// Wall-clock based (credentials expire by the server's clock, and the
    /// device may sleep between calls). A clock that went backwards distrusts
    /// the cache.
    static func isFresh(fetchedAt: TimeInterval, ttl: TimeInterval, now: TimeInterval) -> Bool {
        now >= fetchedAt && now < refreshAt(fetchedAt: fetchedAt, ttl: ttl)
    }

    /// A restart fetches credentials first only when the ones the connection
    /// runs with are no longer fresh, or unknown. Fresh ones still have ttl/2
    /// left (30 s at the very least), enough for a new relay allocation, so
    /// the restart does not wait behind a request.
    static func refreshBeforeRestart(engineFetchedAt: TimeInterval?, ttl: TimeInterval, now: TimeInterval) -> Bool {
        guard let engineFetchedAt else { return true }
        return !isFresh(fetchedAt: engineFetchedAt, ttl: ttl, now: now)
    }
}

// MARK: - SDP details negotiated by this client

enum CallSdpMunger {
    /// Adds `usedtx=1` to every Opus format line: asks the other side to stop
    /// sending during silence. Receiver-side and optional (RFC 7587): a peer
    /// that ignores it keeps sending as before, and every Opus decoder handles
    /// DTX. Nothing else in the description changes.
    static func requestingOpusDtx(_ sdp: String) -> String {
        let separator = sdp.contains("\r\n") ? "\r\n" : "\n"
        var lines = sdp.components(separatedBy: separator)
        var opusTypes = Set<String>()
        for line in lines where line.hasPrefix("a=rtpmap:") {
            let fields = line.dropFirst("a=rtpmap:".count).split(separator: " ", maxSplits: 1)
            guard fields.count == 2, fields[1].lowercased().hasPrefix("opus/") else { continue }
            opusTypes.insert(String(fields[0]))
        }
        guard !opusTypes.isEmpty else { return sdp }
        var withFmtp = Set<String>()
        for index in lines.indices where lines[index].hasPrefix("a=fmtp:") {
            let fields = lines[index].dropFirst("a=fmtp:".count).split(separator: " ", maxSplits: 1)
            guard let type = fields.first.map(String.init), opusTypes.contains(type) else { continue }
            withFmtp.insert(type)
            let parameters = fields.count > 1 ? String(fields[1]) : ""
            guard !parameters.split(separator: ";").contains(where: { $0.trimmingCharacters(in: .whitespaces).hasPrefix("usedtx=") }) else { continue }
            lines[index] = "a=fmtp:\(type) " + (parameters.isEmpty ? "usedtx=1" : parameters + ";usedtx=1")
        }
        // An Opus payload without any format line gets one right after its rtpmap.
        var index = lines.startIndex
        while index < lines.endIndex {
            let line = lines[index]
            if line.hasPrefix("a=rtpmap:"),
               let type = line.dropFirst("a=rtpmap:".count).split(separator: " ").first.map(String.init),
               opusTypes.contains(type), !withFmtp.contains(type) {
                lines.insert("a=fmtp:\(type) usedtx=1", at: lines.index(after: index))
                withFmtp.insert(type)
            }
            index = lines.index(after: index)
        }
        return lines.joined(separator: separator)
    }
}

/// The audio section of a description, for the negotiation diagnostic
/// (`call_state` operation 5, phase 148): whether RED is its first payload
/// type, whether its Opus format asks for in-band FEC and DTX, and how many
/// redundant copies its RED format lists. A description says what its side
/// wants to receive: the peer's says how we send, ours how the peer sends.
struct CallAudioNegotiation: Equatable {
    var redFirst = false
    var inbandFec = false
    var dtx = false
    /// Redundant encodings in RED's format line (`111/111` is 1); nil without
    /// RED or without its format line.
    var redDistance: Double?

    /// Nil without an audio section.
    static func parse(_ sdp: String) -> CallAudioNegotiation? {
        // `\r\n` is one Character: split on any line break.
        let lines = sdp.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }
        guard let start = lines.firstIndex(where: { $0.hasPrefix("m=audio ") }) else { return nil }
        let end = lines[(start + 1)...].firstIndex(where: { $0.hasPrefix("m=") }) ?? lines.endIndex
        let payloads = lines[start].split(separator: " ").dropFirst(3).map(String.init)
        var names: [String: String] = [:]
        var formats: [String: String] = [:]
        for line in lines[(start + 1)..<end] {
            for (prefix, isName) in [("a=rtpmap:", true), ("a=fmtp:", false)] where line.hasPrefix(prefix) {
                let fields = line.dropFirst(prefix.count).split(separator: " ", maxSplits: 1)
                guard fields.count == 2 else { continue }
                if isName { names[String(fields[0])] = fields[1].split(separator: "/").first.map { $0.lowercased() } }
                else { formats[String(fields[0])] = String(fields[1]) }
            }
        }
        var result = CallAudioNegotiation()
        result.redFirst = payloads.first.flatMap { names[$0] } == "red"
        if let opus = payloads.first(where: { names[$0] == "opus" }), let format = formats[opus] {
            let parameters = Set(format.split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces).lowercased() })
            result.inbandFec = parameters.contains("useinbandfec=1")
            result.dtx = parameters.contains("usedtx=1")
        }
        if let red = payloads.first(where: { names[$0] == "red" }), let format = formats[red] {
            result.redDistance = Double(max(0, format.split(separator: "/").count - 1))
        }
        return result
    }

    /// Phase 148 after a description was applied: `code` 1 ours, 2 the
    /// peer's; `status` 1 RED first in the peer's (we send RED), `direction`
    /// 1 RED first in ours (the peer sends it); `count` bits: 1 the peer asks
    /// us for in-band FEC, 2 for DTX, 4 we ask it for in-band FEC, 8 for DTX;
    /// `items` the RED redundancy in the peer's description. A side not
    /// applied yet is left out.
    static func diagnostics(local: CallAudioNegotiation?, remote: CallAudioNegotiation?, appliedLocal: Bool) -> [String: Double?] {
        var values: [String: Double?] = ["code": appliedLocal ? 1.0 : 2.0]
        if let remote {
            values["status"] = remote.redFirst ? 1.0 : 0.0
            values["items"] = remote.redDistance
        }
        if let local { values["direction"] = local.redFirst ? 1.0 : 0.0 }
        if local != nil || remote != nil {
            var bits = 0
            if remote?.inbandFec == true { bits |= 1 }
            if remote?.dtx == true { bits |= 2 }
            if local?.inbandFec == true { bits |= 4 }
            if local?.dtx == true { bits |= 8 }
            values["count"] = Double(bits)
        }
        return values
    }
}

enum CallAudioCodecPreference {
    /// Positions (into `names`) of the codecs to prefer, in order. With RED
    /// wanted it goes first; otherwise it is left out entirely, so it cannot
    /// be negotiated whatever the other side offers. Everything else keeps its
    /// order. RED is used only when both sides list it (plain SDP negotiation),
    /// so a peer without it simply gets Opus.
    static func order(_ names: [String], preferRed: Bool) -> [Int] {
        let red = names.indices.filter { names[$0].lowercased() == "red" }
        let others = names.indices.filter { names[$0].lowercased() != "red" }
        return preferRed ? red + others : others
    }
}
