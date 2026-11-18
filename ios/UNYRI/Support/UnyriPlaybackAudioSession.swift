import AVFoundation
import CallKit
import Combine
import os

/// The single owner of the shared `.playback` audio session: the media viewer and round
/// video notes (voice messages once they adopt it). Every UNYRI audio session mutation
/// outside WebRTC's own activation runs on one serial queue, call configuration included
/// (`callConfiguration`), so a check and the mutation it guards can never interleave with
/// another owner's or with a call taking the session over.
///
/// Nothing here blocks the main thread for playback: `setCategory` and `setActive` are IPC
/// calls to the audio server that take hundreds of milliseconds on a device, more when the
/// route changes from recording to playback. A player starts muted at once and becomes
/// audible when `acquire` reports `.active`.
enum UnyriPlaybackAudioSession {
    enum Owner: String { case viewer, videoNote, voice }
    enum Grant { case active, refused, superseded }
    /// The claimant, set at once by `acquire` while the session may still be activating.
    @MainActor private(set) static var owner: Owner?
    /// The claimant whose activation finished: its player may be audible.
    @MainActor private(set) static var activeOwner: Owner?
    @MainActor private static var mode = AVAudioSession.Mode.default
    /// Every UNYRI-initiated audio session mutation runs here.
    static let queue = DispatchQueue(label: "unyri.audio.playback-session", qos: .userInitiated)
    private static let log = Logger(subsystem: "com.unyri.media", category: "audio")
    /// A short pause keeps the session: resuming within this window needs no new
    /// activation, and no deactivation is left behind to stop the resumed player.
    private static let releaseGrace: DispatchTimeInterval = .milliseconds(800)

    /// Claim and call generations, readable from the audio queue. A queued mutation runs
    /// only while its claim is still the latest and no call configured the session since.
    private final class Generations: @unchecked Sendable {
        private let lock = NSLock()
        private var claim: UInt64 = 0
        private var call: UInt64 = 0
        func nextClaim() -> UInt64 { lock.lock(); defer { lock.unlock() }; claim &+= 1; return claim }
        func currentClaim() -> UInt64 { lock.lock(); defer { lock.unlock() }; return claim }
        func nextCall() { lock.lock(); call &+= 1; lock.unlock() }
        func currentCall() -> UInt64 { lock.lock(); defer { lock.unlock() }; return call }
    }
    private static let generations = Generations()
    @MainActor private static var observingReset = false
    /// The current claimant's completion: a takeover (a recorder, a call, a room) tells it
    /// `.refused` so its player stops or mutes instead of waiting for a grant forever.
    @MainActor private static var grantHandler: ((Grant) -> Void)?

    @MainActor static func isActive(_ owner: Owner) -> Bool { activeOwner == owner && self.owner == owner }

    /// Claims `.playback` for `owner` and returns at once. `completion` runs on the main
    /// actor: `.active` once the session is configured and active and `owner` still holds
    /// the claim, `.refused` when a call holds the audio or the system said no, and
    /// `.superseded` when a release or a newer claim came first (ignore the result then).
    @MainActor static func acquire(_ owner: Owner, mode: AVAudioSession.Mode,
                                   completion: @escaping @MainActor (Grant) -> Void) {
        observeResetIfNeeded()
        // A UNYRI call or room (or one starting) owns the audio: playback stays muted.
        if NonCallCaptureOwnership.callsBusy() {
            if self.owner == owner { self.owner = nil; activeOwner = nil; grantHandler = nil; _ = generations.nextClaim() }
            completion(.refused)
            return
        }
        if self.owner == owner, activeOwner == owner, self.mode == mode {
            // Already configured and active for this owner (switching notes): nothing to do.
            grantHandler = completion
            completion(.active)
            return
        }
        let claim = generations.nextClaim()
        let call = generations.currentCall()
        self.owner = owner
        self.mode = mode
        activeOwner = nil
        grantHandler = completion
        log.debug("acquire \(owner.rawValue, privacy: .public)")
        queue.async {
            // Superseded before it ran (released by an interruption, a newer claim): touch nothing.
            guard generations.currentClaim() == claim else {
                DispatchQueue.main.async { MainActor.assumeIsolated { completion(.superseded) } }
                return
            }
            let started = DispatchTime.now().uptimeNanoseconds
            var failure: NSError?
            if generations.currentCall() != call {
                // A call configured the session after this claim: it is the call's now.
                failure = NSError(domain: "UNYRIAudio", code: 1)
            } else {
                do {
                    let session = AVAudioSession.sharedInstance()
                    if session.category != .playback || session.mode != mode {
                        try session.setCategory(.playback, mode: mode)
                    }
                    try session.setActive(true)
                } catch {
                    failure = error as NSError
                }
            }
            let elapsed = Double(DispatchTime.now().uptimeNanoseconds - started) / 1_000_000
            PerformanceRecorder.shared.record(.videoReady, ["operation": 20, "duration_ms": elapsed,
                "success": failure == nil ? 1 : 0, "reason": owner.metric])
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    // A call or room took the session after this claim: refused, not granted.
                    if generations.currentClaim() == claim, self.owner == owner, generations.currentCall() != call {
                        self.owner = nil
                        activeOwner = nil
                        grantHandler = nil
                        completion(.refused)
                        return
                    }
                    guard generations.currentClaim() == claim, self.owner == owner else {
                        // Superseded while activating: if nobody holds the session now, give
                        // it back so other apps' audio is not left suppressed.
                        if failure == nil, self.owner == nil {
                            deactivate(ifStill: .playback, mode: mode, label: owner.rawValue,
                                       reason: owner.metric, after: .now())
                        }
                        completion(.superseded)
                        return
                    }
                    if let failure {
                        self.owner = nil
                        grantHandler = nil
                        log.error("acquire \(owner.rawValue, privacy: .public) failed code=\(failure.code, privacy: .public)")
                        completion(.refused)
                    } else {
                        activeOwner = owner
                        completion(.active)
                    }
                }
            }
        }
    }

    /// Gives the session back so Music or a podcast can resume. A no-op unless `owner`
    /// holds it. `deactivate: false` only forgets ownership: after an interruption or a
    /// media-services reset the system has already deactivated the session.
    @MainActor static func release(_ owner: Owner, deactivate: Bool = true) {
        guard self.owner == owner else { return }
        self.owner = nil
        activeOwner = nil
        grantHandler = nil
        _ = generations.nextClaim()
        guard deactivate else {
            log.debug("release \(owner.rawValue, privacy: .public) skipped: system")
            return
        }
        // A call or a capture owns the route now: deactivating would cut it off.
        guard !NonCallCaptureOwnership.isBusy, !NonCallCaptureOwnership.callsBusy() else {
            log.debug("release \(owner.rawValue, privacy: .public) skipped: capture")
            return
        }
        // `Self.`: the `deactivate` parameter shadows the method name here. The grace
        // window lets a quick resume keep the session (any new claim cancels this).
        Self.deactivate(ifStill: .playback, mode: mode, label: owner.rawValue, reason: owner.metric,
                        after: .now() + releaseGrace)
    }

    /// Deactivates off the main thread, only while the session is still configured as
    /// given, no newer claim was made and no call configured the session since this
    /// request: then it belongs to someone else. Used by the recorders and the voice
    /// player too, so all UNYRI mutations stay in order.
    static func deactivate(ifStill category: AVAudioSession.Category, mode: AVAudioSession.Mode,
                           label: String, reason: Double, after deadline: DispatchTime = .now()) {
        let claim = generations.currentClaim()
        let call = generations.currentCall()
        queue.asyncAfter(deadline: deadline) {
            guard generations.currentClaim() == claim, generations.currentCall() == call else {
                log.debug("release \(label, privacy: .public) skipped: superseded")
                return
            }
            let session = AVAudioSession.sharedInstance()
            guard session.category == category, session.mode == mode else {
                log.debug("release \(label, privacy: .public) skipped: category")
                return
            }
            let started = DispatchTime.now().uptimeNanoseconds
            var ok = true
            do {
                try session.setActive(false, options: .notifyOthersOnDeactivation)
                log.debug("release \(label, privacy: .public) ok")
            } catch {
                ok = false
                log.error("release \(label, privacy: .public) failed code=\((error as NSError).code, privacy: .public)")
            }
            let elapsed = Double(DispatchTime.now().uptimeNanoseconds - started) / 1_000_000
            PerformanceRecorder.shared.record(.videoReady, ["operation": 21, "duration_ms": elapsed,
                "success": ok ? 1 : 0, "reason": reason])
        }
    }

    /// A recorder or the voice player claims the session synchronously (their start is
    /// already gated on the main thread). The playback claimant is told `.refused` first,
    /// so it stops or mutes even if this start then fails; a newer claim also cancels any
    /// playback deactivation still waiting in its grace window. The work runs in order.
    @MainActor static func synchronousClaim<T>(_ work: () throws -> T) rethrows -> T {
        revokePlayback()
        _ = generations.nextClaim()
        return try queue.sync(execute: work)
    }

    /// Calls configure the session through the same queue. Playback work still queued is
    /// invalidated before this waits (so it skips its IPC), and the playback claimant is
    /// told `.refused`: a note or video can never switch a call's route back to playback,
    /// deactivate it, or be unmuted under it.
    static func callConfiguration<T>(_ work: () throws -> T) rethrows -> T {
        generations.nextCall()
        if Thread.isMainThread {
            MainActor.assumeIsolated { revokePlayback() }
        } else {
            DispatchQueue.main.async { MainActor.assumeIsolated { revokePlayback() } }
        }
        return try queue.sync {
            generations.nextCall()
            return try work()
        }
    }

    /// A conference room configures the session itself (LiveKit): invalidate queued
    /// playback work and stop the playback claimant before it does.
    @MainActor static func externalTakeover() {
        generations.nextCall()
        revokePlayback()
    }

    @MainActor private static func revokePlayback() {
        guard owner != nil else { return }
        let handler = grantHandler
        owner = nil
        activeOwner = nil
        grantHandler = nil
        _ = generations.nextClaim()
        handler?(.refused)
    }

    /// The media server restarted: the session and every player are gone. Forget the
    /// grant so the next claim configures the session again.
    @MainActor private static func observeResetIfNeeded() {
        guard !observingReset else { return }
        observingReset = true
        NotificationCenter.default.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification,
                                               object: nil, queue: .main) { _ in
            MainActor.assumeIsolated {
                owner = nil
                activeOwner = nil
                grantHandler = nil
                _ = generations.nextClaim()
            }
        }
    }
}

private extension UnyriPlaybackAudioSession.Owner {
    /// Diagnostics code (no names in reports).
    var metric: Double {
        switch self { case .viewer: return 1; case .videoNote: return 2; case .voice: return 3 }
    }
}

/// Calls outside UNYRI (cellular, FaceTime, other VoIP apps) plus UNYRI's own CallKit
/// calls. Event-driven, no polling; started by the first player that needs it.
@MainActor
final class UnyriSystemCallMonitor: NSObject, ObservableObject, CXCallObserverDelegate {
    static let shared = UnyriSystemCallMonitor()
    @Published private(set) var active = false
    private var observer: CXCallObserver?

    func start() {
        guard observer == nil else { return }
        let observer = CXCallObserver()
        observer.setDelegate(self, queue: .main)
        self.observer = observer
        active = observer.calls.contains { !$0.hasEnded }
    }

    nonisolated func callObserver(_ callObserver: CXCallObserver, callChanged call: CXCall) {
        let value = callObserver.calls.contains { !$0.hasEnded }
        // The delegate queue is .main.
        MainActor.assumeIsolated { if active != value { active = value } }
    }
}
