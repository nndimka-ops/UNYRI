import AVFoundation
import Combine
import Foundation
import UIKit

/// Single app-scoped controller owning the entire lifecycle of "the current
/// call" (mirror of Android's `CallController`, Этап J п.28). The UI observes
/// `state` only; nothing else touches the engine/API/signaling seams.
///
/// Account-scoped: created per `AccountServices`, captures the session
/// generation, and aborts every callback when it no longer matches — a stale
/// old-account event can never mutate the new account's call state.
@MainActor
final class CallController: ObservableObject {
    private var callSAS = CallSASHandshake() {
        didSet { NotificationCenter.default.post(name: Notification.Name("COCOCallVerificationChanged"), object: callSAS.callID) }
    }
    var currentSessionGeneration: UInt64 { api.currentSessionGeneration }
    var incomingCallAllowed: (String) -> Bool = { _ in true }
    var incomingPeerAllowed: (String) -> Bool = { _ in true }
    func localBlockChanged() {
        guard case .incomingRinging(let call) = state, !incomingPeerAllowed(call.remoteUserId) else { return }
        apply(.remoteEnded(callId: call.callId, reason: "rejected"), endedHere: true)
        Task { _ = try? await api.perform(.reject, callId: UUID(uuidString: call.callId) ?? UUID()) }
    }
    /// Whether this account has saved the user in Contacts. A direct chat is
    /// not membership. Unknown means no: the safe side is the relay.
    var isContact: (String) -> Bool = { _ in false }
    @Published private(set) var state: CallUiState = .idle {
        didSet {
            #if os(iOS)
            func phase(_ value: CallUiState) -> Double {
                switch value {
                case .idle: return 0
                case .outgoingRinging: return 1
                case .incomingRinging: return 2
                case .connecting: return 3
                case .connected: return 4
                case .reconnecting: return 5
                case .ended: return 6
                case .failed: return 7
                }
            }
            if phase(state) != phase(oldValue) { PerformanceRecorder.shared.record(.callState, ["phase": phase(state)]) }
            #endif
            stateDidChange()
        }
    }
    /// Connection grade and route for the status line, the video timer and
    /// the collapsed banner. Its own object: a grade change re-renders only
    /// the views that show it, never the whole call screen.
    let quality = CallQualityMonitor()
    /// When the current call first reached `.connected` — drives the in-call
    /// timer and the minimized active-call banner. Survives a transient
    /// `.reconnecting` (only set on the connecting -> connected transition).
    @Published private(set) var connectedAt: Date?
    private var diagnosticMediaProblem = false
    @Published var startBlockedMessage: String?
    @Published private(set) var ringIndicator: CallRingIndicator?
    /// Monotonic audio anchor shared with the render-server ring waves.
    @Published private(set) var ringbackStartedAt: TimeInterval?
    /// Our running camera has a torch (the back camera): the self-view shows
    /// the torch button only then (owner, 12 Nov).
    @Published private(set) var torchAvailable = false
    /// The torch is lit. Any camera stop, switch or failure darkens it.
    @Published private(set) var torchOn = false
    /// The reads of the call that rings out (`CallOutgoingReadPolicy`), from
    /// its create response; nil until a call is bound.
    private var outgoingReads: CallOutgoingReadPolicy?
    /// Sleeps until the next timed read of that call; one per call.
    private var outgoingRingingTask: Task<Void, Never>?
    private var lastRingingAnnouncement: String?
    private var ringingReports: [String: CallRingConfirmPolicy.Report] = [:]
    private var ringingConfirmations = Set<String>()
    private var ringingConfirmationOrder: [String] = []
    private var ringingConfirmationTasks: [String: Task<Void, Never>] = [:]

    /// Mute as the controls show it, in every phase of a live call (ringing
    /// out and connecting included), for the CallKit mirror (B11).
    let muteChanges = PassthroughSubject<(callId: String, muted: Bool), Never>()

    /// Called when a terminal call push must clear the incoming-call UI.
    var onDismissIncomingPush: (String) -> Void = { _ in }
    var resolveCallerName: (String) async -> String? = { _ in nil }
    /// Other native media owners reserve the same camera/microphone before
    /// asking permissions. This also blocks a new account during teardown.
    var externalMediaBusy: () -> Bool = { false }
    var externalCaptureBusy: () -> Bool = { false }
    var interruptExternalCapture: () -> Void = {}
    var awaitExternalCaptureRelease: (Date?) async throws -> Void = { _ in }
    var isPrivateMediaBusy: Bool { state.isBusy || creatingCall || acceptingCallID != nil }
    var canAcquireConferenceMedia: Bool {
        !isShutDown && !state.isBusy && !creatingCall && acceptingCallID == nil && !externalMediaBusy() && !externalCaptureBusy()
    }

    private let api: CallsRepository
    private let signaling: CallSignalingClient
    private let engine: WebRTCEngine
    private let generation: UInt64

    private var reducerState = CallReducerState() {
        didSet {
            // Mute/route while ringing or connecting live outside `state`
            // (`.outgoingRinging(call)` does not change), yet the buttons show them.
            let controlsChanged = oldValue.audioControls != reducerState.audioControls
            if controlsChanged && oldValue.ui == reducerState.ui { objectWillChange.send() }
            if let callId = reducerState.activeCallId, let muted = reducerState.audioControls?.isMuted,
               oldValue.audioControls?.isMuted != muted {
                muteChanges.send((callId, muted))
            }
            if controlsChanged || oldValue.ui != reducerState.ui {
                updateProximityMonitoring()
                // Notices live in the controls. While reconnecting they change
                // in `lastConnected` only, and `state` stays the same.
                scheduleNoticeExpiry()
            }
        }
    }
    /// The layout of the call that just ended, so its last seconds on screen
    /// stay in that layout instead of switching to the audio one.
    private(set) var endedInVideoLayout = false
    // Reuse the native call adapter's bounded terminal policy. A local decline
    // wins over late WS duplicates and a still-ringing REST recovery response.
    private var terminalCalls = CallKitReportTracker()
    private var cancellables = Set<AnyCancellable>()
    private var keepAliveTask: Task<Void, Never>?
    private var upgradeTimeoutTask: Task<Void, Never>?
    private var reconcileTask: Task<Void, Never>?
    private var incomingRecoveryTask: Task<Void, Never>?
    /// Recovery of a dropped media path (`CallRecoveryPolicy`): one loop per
    /// call, woken by every relevant event instead of polling.
    private var recovery = CallRecoveryPolicy()
    private var recoveryTask: Task<Void, Never>?
    private var recoveryWake: Task<Void, Never>?
    private var recoveryToken: UInt64 = 0
    /// The last ICE state diagnostic: its `duration_ms` is the time since the
    /// previous one of the same call.
    private var iceRecord: (callId: String, at: TimeInterval)?
    /// The offer of the established call awaiting its answer, kept so an
    /// unanswered offer is resent as is instead of replaced (B3).
    private var pendingOffer: (generation: UInt64, sdp: String)?
    /// TURN credentials, fetched ahead of the call and trusted until ttl/2.
    private var iceCache: (set: CallICEServerSet, fetchedAt: Date)?
    private var iceFetch: Task<CallICEServerSet?, Never>?
    private var credentialRefreshTask: Task<Void, Never>?
    /// The call the engine was initialized for (credentials and the ladder
    /// apply to it live).
    private var engineCallId: String?
    /// When the TURN credentials the engine runs with were fetched, and their
    /// TTL: a restart skips the refresh while they are fresh. Nil when
    /// unknown (a restart then refreshes first).
    private var engineIce: (fetchedAt: Date, ttl: TimeInterval)?
    private let network = CallNetworkMonitor.shared
    private var lastSatisfiedPath: CallNetworkPath?
    private var mediaProfile: CallMediaProfile?
    private var dataSaving: CallDataSaving
    /// Relay-only ICE for this call, decided once when the call appears so the
    /// route line and the engine always agree.
    private var relayDecision: (callId: String, relayOnly: Bool)?
    /// Moving the current call to the TURN relay after repeated consent
    /// losses of a direct pair, and back when that relay does not work
    /// (`CallRelayFallbackPolicy`), set up when the engine is initialized
    /// for the call.
    private var relayFallback = CallRelayFallbackPolicy()
    /// ICE has been connected since the last loss: the next disconnected or
    /// failed is a consent loss of the selected pair.
    private var iceUp = false
    /// The servers the engine runs with include a TURN server.
    private var engineTurnKnown = false
    private var videoPause = CallVideoPausePolicy()
    /// What the last foreground stats read measured, and when: where the
    /// bandwidth estimate restarts is judged on it (`CallBitrateSeedPolicy`).
    private var lastLink: (at: TimeInterval, link: CallBitrateSeedPolicy.Link)?
    /// A selected pair that never carries the voice (`CallDeadPairPolicy`).
    private var deadPair = CallDeadPairPolicy()
    /// When the media diagnostics of this call are taken (phases 117, 119,
    /// 121, 122 and the audio path 147).
    private var mediaDiagnostics = CallMediaDiagnosticsPolicy()
    private var reconcileGeneration: UInt64 = 0
    private var lastConnected: Bool = false
    private var isShutDown = false
    private var creatingCall = false
    private var acceptingCallID: String?
    private let effectQueue = CallEffectSerialQueue()
    private var ringbackPlayer: AVAudioPlayer?
    private var callAudioActive = false
    private var automaticSpeaker = CallAutomaticSpeakerPolicy()
    private var noticeTask: Task<Void, Never>?
    private var scheduledNotice: CallNotice?
    private var proximityMonitoring = false
    private var multipleCameras: (callId: String?, value: Bool)?
    private lazy var ringbackData = CallRingbackPolicy.waveData()

    /// Account teardown is local and synchronous: never wait for a revoked
    /// token/network request before releasing camera, microphone and timers.
    func shutdown() {
        guard !isShutDown else { return }
        isShutDown = true
        startBlockedMessage = nil
        setCallAudioActive(false)
        effectQueue.cancel()
        cancellables.removeAll()
        if automaticSpeaker.reset() { engine.setSpeakerOn(false) }
        keepAliveTask?.cancel(); keepAliveTask = nil
        upgradeTimeoutTask?.cancel(); upgradeTimeoutTask = nil
        reconcileTask?.cancel(); reconcileTask = nil
        stopIncomingRecovery()
        stopOutgoingRingingChecks()
        for task in ringingConfirmationTasks.values { task.cancel() }
        ringingConfirmationTasks.removeAll()
        ringingReports.removeAll()
        ringingConfirmations.removeAll()
        ringingConfirmationOrder.removeAll()
        ringIndicator = nil
        cancelRecovery()
        stopCredentialRefresh()
        iceFetch?.cancel(); iceFetch = nil
        iceCache = nil
        engine.dispose()
        syncTorch()
        reducerState = CallReducerState()
        state = .idle
        connectedAt = nil
        quality.update(callId: nil, active: false)
        setProximityMonitoring(false)
    }

    private static let keepAliveIntervalMs: UInt64 = 10_000
    private static let videoUpgradeTimeoutMs: UInt64 = 25_000

    init(api: CallsRepository, signaling: CallSignalingClient, engine: WebRTCEngine, generation: UInt64) {
        self.api = api
        self.signaling = signaling
        self.engine = engine
        self.generation = generation
        dataSaving = UnyriDeviceSettings.shared.callDataSaving

        signaling.events
            .sink { [weak self] event in self?.handleSignaling(event) }
            .store(in: &cancellables)
        engine.events
            .sink { [weak self] event in self?.handleEngine(event) }
            .store(in: &cancellables)
        signaling.connectionState
            .removeDuplicates()
            .sink { [weak self] connected in self?.handleConnection(connected) }
            .store(in: &cancellables)
        quality.sample = { [weak self] in
            guard let self, !self.isShutDown, self.state.isInCallMedia else { return nil }
            return await self.engine.statsSample()
        }
        quality.context = { [weak self] in self?.qualityContext ?? CallQualityContext() }
        quality.onMetrics = { [weak self] metrics in self?.handleQualityMetrics(metrics) }
        lastSatisfiedPath = network.current.satisfied ? network.current : nil
        network.paths
            .sink { [weak self] path in self?.handleNetworkPath(path) }
            .store(in: &cancellables)
        // `@Published` emits before the new value is stored: use the emitted one.
        UnyriDeviceSettings.shared.$callDataSaving
            .dropFirst()
            .removeDuplicates()
            .sink { [weak self] value in
                guard let self else { return }
                self.dataSaving = value
                self.updateMediaProfile(networkChanged: false)
            }
            .store(in: &cancellables)
    }

    /// The instant CallKit starting screen and direct starts share one policy.
    func beginOutgoingRingingIndicator() {
        guard !isShutDown, generation == apiGeneration, state == .idle else { return }
        if ringIndicator?.phase == .sending { return }
        stopOutgoingRingingChecks()
        outgoingReads = nil
        lastRingingAnnouncement = nil
        ringIndicator = nil
        reduceRinging(.started)
    }

    func clearOutgoingRingingIndicatorIfUnbound() {
        guard state == .idle, !creatingCall, outgoingReads == nil else { return }
        ringIndicator = nil
        stopOutgoingRingingChecks()
    }

    private func reduceRinging(_ event: CallRingingIndicatorPolicy.Event) {
        let wasAwaiting = ringIndicator?.awaitsConfirmation == true
        let (next, outputs) = CallRingingIndicatorPolicy.reduce(ringIndicator, event)
        if next != ringIndicator { ringIndicator = next }
        // Confirmed (or legacy) now: the re-checks end and the reads re-plan
        // to the backstop alone. A terminal phase comes with leaving
        // outgoingRinging, which stops them.
        if wasAwaiting && next?.awaitsConfirmation != true {
            stopOutgoingRingingChecks()
            if next?.phase.isTerminal == false, let callId = reducerState.activeCallId {
                startOutgoingRingingChecks(callId: callId)
            }
        }
        updateRingback()
        for output in outputs {
            switch output {
            case .haptic:
                if UIApplication.shared.applicationState == .active {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred(intensity: 0.6)
                }
            case .announce(let key):
                let text: String
                if key == "Not online yet. Waiting for them to come online" {
                    text = UnyriL10n.string("Not online yet") + ". " +
                        UnyriL10n.string("Waiting for them to come online")
                } else { text = UnyriL10n.string(key) }
                guard text != lastRingingAnnouncement else { continue }
                lastRingingAnnouncement = text
                if UIAccessibility.isVoiceOverRunning {
                    UIAccessibility.post(notification: .announcement, argument: text)
                }
            }
        }
    }

    private func stopOutgoingRingingChecks() {
        outgoingRingingTask?.cancel()
        outgoingRingingTask = nil
    }

    /// The timed reads of the call that rings out (`CallOutgoingReadPolicy`):
    /// the deadline check and bounded re-checks while the indicator awaits
    /// confirmation, then one backstop read past the server's missed sweep.
    /// The task sleeps until each read and ends when none is left.
    /// Own-network failures do not generate indicator events.
    private func startOutgoingRingingChecks(callId: String) {
        guard outgoingRingingTask == nil, let first = nextOutgoingRead(callId: callId)?.at else { return }
        outgoingRingingTask = Task { @MainActor [weak self] in
            var wake: TimeInterval? = first
            while let due = wake {
                let delay = due - ProcessInfo.processInfo.systemUptime
                if delay > 0 {
                    do { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
                    catch { return }
                }
                guard let self, !Task.isCancelled else { return }
                wake = self.issueDueOutgoingRead(callId: callId)
            }
        }
    }

    private func nextOutgoingRead(callId: String) -> CallOutgoingReadPolicy.Due? {
        guard !isShutDown, generation == apiGeneration, reducerState.activeCallId == callId,
              case .outgoingRinging = state else { return nil }
        return outgoingReads?.next(awaitingConfirmation: ringIndicator?.awaitsConfirmation == true)
    }

    /// Issues the timed read that is due, if any. Returns when the next one is
    /// due; nil when none is left.
    private func issueDueOutgoingRead(callId: String) -> TimeInterval? {
        guard let due = nextOutgoingRead(callId: callId) else { return nil }
        let now = ProcessInfo.processInfo.systemUptime
        guard now >= due.at else { return due.at }
        reconcileAfterReconnect(due.read)
        // A read that was not recorded must never spin the loop.
        guard let next = nextOutgoingRead(callId: callId), next.at > now else { return nil }
        return next.at
    }

    /// Both orderings are supported: CallKit first (push), or admitted state
    /// first (socket). A duplicate report error cannot erase recorded success.
    func incomingCallReportCompleted(callId: String, succeeded: Bool) {
        guard !isShutDown, generation == apiGeneration,
              let id = UUID(uuidString: callId)?.uuidString.lowercased() else { return }
        rememberRingingConfirmation(id)
        if succeeded || ringingReports[id] != .succeeded {
            ringingReports[id] = succeeded ? .succeeded : .failed
        }
        confirmRinging(callId: id)
    }

    private func rememberRingingConfirmation(_ callId: String) {
        guard !ringingConfirmationOrder.contains(callId) else { return }
        ringingConfirmationOrder.append(callId)
        while ringingConfirmationOrder.count > 8 {
            let old = ringingConfirmationOrder.removeFirst()
            ringingConfirmationTasks.removeValue(forKey: old)?.cancel()
            ringingReports.removeValue(forKey: old)
            ringingConfirmations.remove(old)
        }
    }

    func confirmRinging(callId: String) {
        guard !isShutDown, generation == apiGeneration,
              let id = UUID(uuidString: callId),
              CallRingConfirmPolicy.shouldSend(ui: state, callId: callId,
                  report: ringingReports[callId] ?? .pending,
                  alreadySent: ringingConfirmations.contains(callId)) else { return }
        rememberRingingConfirmation(callId)
        ringingConfirmations.insert(callId)
        ringingConfirmationTasks[callId] = Task { @MainActor [weak self] in
            defer { self?.ringingConfirmationTasks.removeValue(forKey: callId) }
            for attempt in 1...2 {
                guard let self, !Task.isCancelled, !self.isShutDown,
                      self.generation == self.apiGeneration,
                      CallIncomingRecoveryPolicy.matches(self.state, callId: callId) else { return }
                do {
                    try await self.api.confirmRinging(callId: id)
                    return
                } catch is CancellationError { return }
                catch {
                    let failure: CallRingConfirmPolicy.Failure
                    switch error {
                    case APIError.network: failure = .transport
                    case APIError.http(let status): failure = .http(status)
                    case let transport as URLError where transport.code != .cancelled: failure = .transport
                    default: failure = .other
                    }
                    guard CallRingConfirmPolicy.retry(after: failure, attempt: attempt,
                        stillRinging: CallIncomingRecoveryPolicy.matches(self.state, callId: callId)) else { return }
                    do { try await Task.sleep(nanoseconds: UInt64(CallRingConfirmPolicy.retryDelay * 1_000_000_000)) }
                    catch { return }
                }
            }
        }
    }

    // MARK: - User actions

    func startCall(remoteUserId: String, remoteDisplayName: String, mediaKind: CallMediaKind) async {
        guard !isShutDown, generation == apiGeneration else { return }
        if reportBlockedStart() { return }
        beginOutgoingRingingIndicator()
        creatingCall = true
        defer { creatingCall = false; clearOutgoingRingingIndicatorIfUnbound() }
        prepareForCall()
        do {
            // Android parity: mic (always) + camera (video) are requested
            // BEFORE the call is created, never silently started without them.
            try await CallMediaPermissions.request(for: mediaKind)
            try requireSession()
            guard state == .idle, !externalMediaBusy(), !externalCaptureBusy() else { _ = reportBlockedStart(includePending: false); return }
            let dto = try await api.create(calleeUserId: UUID(uuidString: remoteUserId) ?? UUID(), mediaKind: mediaKind)
            try requireSession()
            guard state == .idle else {
                _ = try? await api.perform(.cancel, callId: dto.id)
                return
            }
            let info = CallInfo(callId: dto.id.uuidString.lowercased(), remoteUserId: remoteUserId,
                                remoteDisplayName: remoteDisplayName, mediaKind: mediaKind, direction: .outgoing)
            startBlockedMessage = nil
            outgoingReads = CallOutgoingReadPolicy(createdAt: ProcessInfo.processInfo.systemUptime)
            reduceRinging(.created(expect: dto.peerRingingExpected == true))
            apply(.startCall(info))
            startOutgoingRingingChecks(callId: info.callId)
            // An exceptionally fast peer can accept before POST /calls has
            // returned our call id, when the WS event cannot be correlated.
            // Re-read once after binding the id; ordinary events and this
            // authoritative response converge through the same reducer path.
            reconcileAfterReconnect(.created)
        } catch is CallMediaPermissions.PermissionError {
            reduceRinging(.createFailed(busy: false))
            apply(.createFailed(.generic))
        } catch {
            guard !isShutDown, generation == apiGeneration else { return }
            let reason: CallFailureReason
            if case APIError.server(code: "conflict", message: "busy") = error { reason = .busy }
            else if (error as? APIError)?.isRateLimited == true { reason = .rateLimited }
            else if case APIError.network = error { reason = .network }
            else { reason = .generic }
            reduceRinging(.createFailed(busy: reason == .busy))
            apply(.createFailed(reason))
        }
    }

    /// CallKit's permission preflight must explain the same busy states as
    /// direct controller starts, before requesting permissions or a CX action.
    @discardableResult func presentBlockedStartIfNeeded() -> Bool { reportBlockedStart() }

    @discardableResult private func reportBlockedStart(includePending: Bool = true) -> Bool {
        let message: String?
        if externalMediaBusy() { message = "You are already in a room. Leave it before starting another conversation." }
        else if externalCaptureBusy() { message = "Finish the current recording before starting a call." }
        else if state != .idle || (includePending && creatingCall) { message = "You already have a call in progress. Return to it or end it first." }
        else { message = nil }
        if let message { startBlockedMessage = message; return true }
        return false
    }

    /// Energy counters of the other person's audio for the avatar halo. Nil
    /// (the halo falls silent) unless the call is connected and CallKit has
    /// the call audio active; during an interruption nothing is heard.
    func remoteAudioEnergySample() async -> CallAudioEnergySample? {
        guard !isShutDown, case .connected = state, callAudioActive else { return nil }
        return await engine.remoteAudioEnergySample()
    }

    func cancelOutgoing() async {
        guard case .outgoingRinging(let call) = state else { return }
        apply(.remoteEnded(callId: call.callId, reason: "cancelled"), endedHere: true)
        Task {
            guard let id = UUID(uuidString: call.callId) else { return }
            do { _ = try await api.perform(.cancel, callId: id) }
            catch { _ = try? await api.perform(.hangup, callId: id) }
        }
    }

    func acceptIncoming(until deadline: Date? = nil) async {
        func requireAnswer() throws {
            try requireSession()
            if let peer = state.callInfo?.remoteUserId, !incomingPeerAllowed(peer) { throw CancellationError() }
            if let deadline, Date() >= deadline { throw CancellationError() }
        }
        guard !isShutDown, generation == apiGeneration, !Task.isCancelled,
              case .incomingRinging(let call) = state, acceptingCallID != call.callId else { return }
        acceptingCallID = call.callId
        stopIncomingRecovery()
        defer {
            if acceptingCallID == call.callId {
                acceptingCallID = nil
                if CallIncomingRecoveryPolicy.matches(state, callId: call.callId) {
                    startIncomingRecovery(call.callId)
                }
            }
        }
        do {
            try requireAnswer()
            interruptExternalCapture()
            try await awaitExternalCaptureRelease(deadline)
            try requireAnswer()
            guard case .incomingRinging(let stillRinging) = state, stillRinging.callId == call.callId else { return }
            try await CallMediaPermissions.request(for: .audio)
            if call.mediaKind == .video {
                do { try await CallMediaPermissions.request(for: .video) }
                catch CallMediaPermissions.PermissionError.cameraDenied { /* Receive video with local camera off. */ }
            }
            try requireAnswer()
            guard case .incomingRinging(let current) = state, current.callId == call.callId else { return }
            let accepted = try await api.perform(.accept, callId: UUID(uuidString: call.callId) ?? UUID())
            try requireAnswer()
            guard case .incomingRinging(let current) = state, current.callId == call.callId else { return }
            guard CallReconciliation.matchesParticipants(call: current, server: accepted, owner: api.ownerId) else { return }
            if case .endLocally = CallReconciliation.resolve(local: state, server: accepted, localDeviceId: api.currentDeviceId) {
                apply(.reconnectReconcileSucceeded(accepted, localDeviceId: api.currentDeviceId))
                return
            }
            apply(.acceptSucceeded(call))
        } catch is CallMediaPermissions.PermissionError {
            guard !isShutDown, generation == apiGeneration, !Task.isCancelled,
                  state.callInfo?.callId == call.callId else { return }
            apply(.permissionDenied)
        } catch is CancellationError {
            return
        } catch {
            // A successful accept can lose its HTTP response, or another
            // device can win the race. Resolve the authoritative device before
            // reporting a generic failure; never send a second Accept.
            do {
                try requireAnswer()
                guard case .incomingRinging(let current) = state, current.callId == call.callId else { return }
                let row = try await api.get(callId: UUID(uuidString: call.callId) ?? UUID())
                try requireAnswer()
                guard case .incomingRinging(let current) = state, current.callId == call.callId else { return }
                switch CallReconciliation.recoverExplicitAnswer(call: current, server: row, owner: api.ownerId, localDeviceId: api.currentDeviceId) {
                case .acceptedHere: apply(.acceptSucceeded(current)); return
                case .endLocally:
                    apply(.reconnectReconcileSucceeded(row, localDeviceId: api.currentDeviceId)); return
                case .unresolved: break
                }
            } catch { }
            guard !isShutDown, generation == apiGeneration, !Task.isCancelled,
                  deadline.map({ Date() < $0 }) ?? true,
                  case .incomingRinging(let current) = state, current.callId == call.callId else { return }
            apply(.acceptFailed)
        }
    }

    func rejectIncoming() async {
        guard case .incomingRinging(let call) = state else { return }
        apply(.remoteEnded(callId: call.callId, reason: "rejected"), endedHere: true)
        Task { _ = try? await api.perform(.reject, callId: UUID(uuidString: call.callId) ?? UUID()) }
    }

    func hangup() async {
        guard let call = state.callInfo, !state.isTerminal else { return }
        apply(.remoteEnded(callId: call.callId, reason: "hangup"), endedHere: true)
        Task { _ = try? await api.perform(.hangup, callId: UUID(uuidString: call.callId) ?? UUID()) }
    }

    /// B-059 (nuance role connectedswitch, 19 Nov): «Завершить и переключиться» ended the call here
    /// and switched the account at once, so the fire-and-forget hangup above went out with the next
    /// account's session (or not at all) and the peer saw «Ошибка» when the media timed out instead
    /// of a normal end. Before an account switch, wait until the server took the hangup (bounded:
    /// a dead network must not hold the switch for long).
    func hangupDelivered(timeout: TimeInterval = 3) async {
        guard let call = state.callInfo, !state.isTerminal else { return }
        apply(.remoteEnded(callId: call.callId, reason: "hangup"), endedHere: true)
        let id = UUID(uuidString: call.callId) ?? UUID()
        let request = Task { _ = try? await api.perform(.hangup, callId: id) }
        let deadline = Task {
            try? await Task.sleep(nanoseconds: UInt64(max(0, timeout) * 1_000_000_000))
            request.cancel()
        }
        _ = await request.value
        deadline.cancel()
    }

    func failCall(callId: String) {
        guard reducerState.activeCallId == callId else { return }
        apply(.engineConnectionState(callId: callId, state: .failed))
    }

    /// This device could not present an incoming call (CallKit report failed: Focus,
    /// blocking, report timeout). End it here only: a REST "failed" from a ringing
    /// callee ended the call for the caller and all of the user's other devices.
    func dismissIncomingLocally(callId: String) {
        guard reducerState.activeCallId == callId, case .incomingRinging = state else { return }
        apply(.remoteEnded(callId: callId, reason: "missed"), endedHere: true)
    }

    var audioControls: ConnectedState? { reducerState.audioControls }
    /// Negotiated video of an established call (the minimized window and the
    /// system PiP). While reconnecting, the last connected video state as it
    /// was: the renderer stays mounted and keeps its last frame (shown dimmed)
    /// instead of flashing black on every short network drop. The full call
    /// screen picks its layout earlier, from the call (`CallPresentationPolicy`).
    var videoPresentation: ConnectedState? {
        guard state.isInCallMedia, let c = audioControls, c.videoMode == .video else { return nil }
        return c
    }

    /// What the screen shows of the media; the grader adds what the frame
    /// counters prove, so the bars stay honest even when this is behind.
    private var qualityContext: CallQualityContext {
        guard let c = audioControls, c.videoMode == .video else { return CallQualityContext() }
        return CallQualityContext(sendingVideo: c.localVideoEnabled && c.localCameraState == .on && !reducerState.localCaptureInterrupted,
                                  receivingVideo: c.remoteVideoEnabled && c.remoteVideoState == .on,
                                  peerVideoEnabled: c.remoteVideoEnabled)
    }

    private func stateDidChange() {
        let call = state.callInfo
        let incomingID: String? = {
            if case .incomingRinging(let incoming) = state { return incoming.callId }
            return nil
        }()
        for id in Array(ringingConfirmationTasks.keys) where id != incomingID {
            ringingConfirmationTasks.removeValue(forKey: id)?.cancel()
        }
        if let incomingID { confirmRinging(callId: incomingID) }
        if let call, relayDecision?.callId != call.callId {
            relayDecision = (call.callId, CallRelayPolicy.relayOnly(UnyriDeviceSettings.shared.callPeerToPeer,
                                                                     peerIsContact: isContact(call.remoteUserId)))
        }
        let reconnecting: Bool = { if case .reconnecting = state { return true }; return false }()
        quality.update(callId: call?.callId, active: state.isInCallMedia, reconnecting: reconnecting,
                       privateRelay: relayOnly(for: call?.callId))
    }

    private func relayOnly(for callId: String?) -> Bool {
        guard let callId, let decision = relayDecision, decision.callId == callId else { return false }
        return decision.relayOnly
    }

    /// Notices are toasts: each clears itself after 3 s through the reducer.
    /// Scheduled wherever the controls change (`reducerState`), not only on a
    /// `state` change: a camera failure while reconnecting changes nothing else.
    private func scheduleNoticeExpiry() {
        // Also while reconnecting: the kept call state still shows the toast.
        let notice = audioControls?.notice
        guard notice != scheduledNotice else { return }
        scheduledNotice = notice
        noticeTask?.cancel()
        noticeTask = nil
        guard let notice else { return }
        noticeTask = Task { @MainActor [weak self] in
            do { try await Task.sleep(nanoseconds: 3_000_000_000) } catch { return }
            guard let self, !self.isShutDown else { return }
            self.apply(.noticeExpired(notice))
        }
    }

    /// The screen goes dark at the ear only for an audio call on the receiver.
    private func updateProximityMonitoring() {
        setProximityMonitoring(!isShutDown && CallProximityPolicy.wantsProximity(state: reducerState.ui, controls: reducerState.audioControls))
    }

    private func setProximityMonitoring(_ enabled: Bool) {
        guard enabled != proximityMonitoring else { return }
        proximityMonitoring = enabled
        UIDevice.current.isProximityMonitoringEnabled = enabled
    }

    /// CallKit owns activation. Never activate a second competing audio session.
    func setCallAudioActive(_ active: Bool) {
        callAudioActive = active && !isShutDown
        updateRingback()
    }

    private func updateRingback() {
        guard CallRingbackPolicy.shouldPlay(state, audioActive: callAudioActive,
                                             ringbackAllowed: ringIndicator?.ringbackAllowed == true) else {
            ringbackPlayer?.stop()
            ringbackPlayer = nil
            if ringbackStartedAt != nil { ringbackStartedAt = nil }
            return
        }
        guard ringbackPlayer?.isPlaying != true else { return }
        do {
            let player = try AVAudioPlayer(data: ringbackData)
            player.numberOfLoops = -1
            player.volume = 0.7
            if player.play() {
                ringbackPlayer = player
                ringbackStartedAt = ProcessInfo.processInfo.systemUptime
            }
        } catch {
            // Failure to play feedback must never end or block the actual call.
            ringbackPlayer = nil
        }
    }

    func restoreAudioControls() {
        guard !isShutDown, generation == apiGeneration, reducerState.activeCallId != nil else { return }
        synchronizeAudioRoute()
        guard let controls = audioControls else { return }
        engine.setMuted(controls.isMuted)
        if automaticSpeaker.restoreSpeakerIfNeeded(in: reducerState) { engine.setSpeakerOn(true) }
        else if let route = reducerState.selectedAudioRoute { engine.setAudioRoute(route) }
    }

    /// Read the current OS route before restoring explicit controls. A choice
    /// made in native CallKit or by connecting a headset outranks an older app
    /// speaker preference. This also publishes the actual outgoing-tone route
    /// before the WebRTC engine's route observer has been installed.
    func synchronizeAudioRoute(userInitiated: Bool = false) {
        guard !isShutDown, generation == apiGeneration, reducerState.activeCallId != nil else { return }
        handleAudioRouteChanged(engine.currentAudioRoute(), userInitiated: userInitiated)
    }

    /// Kept in every phase of a live call, ringing in included (no controls are
    /// on screen then): the automatic speaker decides the moment it is answered.
    private func handleAudioRouteChanged(_ route: AudioRoute, userInitiated: Bool = false) {
        guard reducerState.activeCallId != nil, !reducerState.ui.isTerminal else { return }
        let known = audioControls?.audioRoute ?? reducerState.observedAudioRoute
        if !userInitiated, known == route.kind,
           reducerState.selectedAudioRoute.map({ $0.isSameOutput(as: route) }) ?? true { return }
        apply(.engineAudioRouteChanged(route, userInitiated: userInitiated))
    }

    func toggleMute() { apply(.toggleMute) }
    /// Sets mute to a specific value (used by CallKit's `CXSetMutedCallAction`).
    func setMuted(_ muted: Bool) {
        if let c = audioControls, c.isMuted != muted {
            apply(.toggleMute)
        }
    }
    func toggleSpeaker() { apply(.toggleSpeaker) }
    func availableAudioRoutes() -> [AudioRoute] { engine.availableAudioRoutes() }
    func currentAudioRoute() -> AudioRoute { engine.currentAudioRoute() }
    func setAudioRoute(_ route: AudioRoute) { apply(.setAudioRoute(route)) }
    func toggleVideo() {
        guard let c = audioControls, c.videoMode == .video else { return }
        if c.localVideoEnabled {
            apply(.setCameraEnabled(false))
            return
        }
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await CallMediaPermissions.request(for: .video)
                try self.requireSession()
                guard let current = self.audioControls, current.videoMode == .video,
                      current.call.callId == c.call.callId, !current.localVideoEnabled else { return }
                // Before media setup there is no peer connection to attach to.
                // Initialization reads this choice after its asynchronous ICE fetch.
                guard self.engineCallId == c.call.callId else {
                    self.apply(.setCameraEnabled(true))
                    return
                }
                guard self.engine.localVideoRunning()
                        || self.engine.startLocalVideo(capture: true, seedBitrate: self.bitrateSeed(.cameraStart)) else {
                    self.apply(.localVideoFailed)
                    return
                }
                self.apply(.setCameraEnabled(true))
            } catch is CallMediaPermissions.PermissionError {
                if self.state.callInfo?.callId == c.call.callId { self.apply(.cameraDenied) }
            } catch { }
        }
    }
    func requestVideoUpgrade() {
        guard case .connected(let c) = state, c.videoMode == .audio, c.videoUpgrade == .idle else { return }
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await CallMediaPermissions.request(for: .video)
                try self.requireSession()
                guard case .connected(let current) = self.state,
                      current.call.callId == c.call.callId,
                      current.videoMode == .audio, current.videoUpgrade == .idle else { return }
                if self.engine.localVideoRunning()
                    || self.engine.startLocalVideo(capture: true, seedBitrate: self.bitrateSeed(.cameraStart)) {
                    self.apply(.requestVideoUpgrade)
                } else {
                    self.apply(.localVideoFailed)
                }
            } catch is CallMediaPermissions.PermissionError {
                guard self.state.callInfo?.callId == c.call.callId else { return }
                self.apply(.cameraDenied)
            } catch { }
        }
    }
    func acceptVideoUpgrade() {
        guard case .connected(let c) = state, c.videoUpgrade == .incomingRequest else { return }
        // Receiving the peer's video does not require camera permission on
        // this device. Permission is requested only when this user enables
        // their own camera through the in-call control.
        apply(.acceptVideoUpgrade)
    }
    func rejectVideoUpgrade() { apply(.rejectVideoUpgrade) }
    func cancelVideoUpgrade() { apply(.cancelVideoUpgrade) }
    func switchCamera() { engine.switchCamera(); syncTorch() }
    func toggleTorch() {
        guard torchAvailable else { return }
        let lit = engine.setTorch(!torchOn)
        if torchOn != lit { torchOn = lit }
    }
    /// The engine darkens the torch on every camera stop, switch and restart;
    /// this mirrors that (and `.localTorchChanged`: interruption, heat) and
    /// relights it only while the same lit camera runs.
    private func syncTorch() {
        let available = engine.localCameraHasTorch()
        if torchAvailable != available { torchAvailable = available }
        guard torchOn else { return }
        if !(available && engine.setTorch(true)) { torchOn = false }
    }
    /// Camera discovery is costly (an AVCaptureDevice.DiscoverySession per
    /// position); the answer cannot change during a call, so read it once.
    func hasMultipleCameras() -> Bool {
        let callId = reducerState.activeCallId
        if let cached = multipleCameras, cached.callId == callId { return cached.value }
        let value = engine.hasMultipleCameras()
        multipleCameras = (callId, value)
        return value
    }
    func attachPictureInPictureRenderer(_ renderer: Any?) { engine.attachPictureInPictureRenderer(renderer) }
    func detachPictureInPictureRenderer(_ renderer: Any?) { engine.detachPictureInPictureRenderer(renderer) }
    func attachLocalRenderer(_ renderer: Any?) { engine.attachLocalRenderer(renderer) }
    func attachRemoteRenderer(_ renderer: Any?) { engine.attachRemoteRenderer(renderer) }
    func detachLocalRenderer(_ renderer: Any?) { engine.detachLocalRenderer(renderer) }
    func detachRemoteRenderer(_ renderer: Any?) { engine.detachRemoteRenderer(renderer) }

    func acknowledgeEnded() {
        if state.isTerminal {
            cancelRecovery()
            reducerState = CallReducerState()
            ringIndicator = nil
            outgoingReads = nil
            stopOutgoingRingingChecks()
            state = .idle
            connectedAt = nil
        }
    }

    /// A PushKit report is only a hint. Reconcile the exact call through the
    /// existing account API before publishing ringing or accepting an action.
    func receiveIncomingPush(_ callId: UUID, admitted: () -> Bool, takeReject: () -> Bool,
                             terminal: (CallEndReason) -> Void) async -> Bool {
        guard !isShutDown, generation == apiGeneration, !Task.isCancelled else { return false }
        if state.isBusy { return state.callInfo.flatMap { UUID(uuidString: $0.callId) } == callId && admitted() }
        do {
            let call = try await api.get(callId: callId)
            try requireSession()
            guard !Task.isCancelled, call.id == callId, call.calleeUserId == api.ownerId,
                  CallIncomingRecoveryPolicy.validIncomingIdentity(callId: call.id.uuidString,
                    callerUserId: call.callerUserId.uuidString, owner: api.ownerId) else { return false }
            guard call.state == "ringing", !call.isEnded else {
                terminal(call.isEnded ? (call.endReason.flatMap(CallEndReason.init(rawValue:)) ?? .remoteEnded) :
                    (call.acceptedDeviceId != api.currentDeviceId ? .answeredElsewhere : .failed))
                return false
            }
            guard incomingPeerAllowed(call.callerUserId.uuidString.lowercased()) else { terminal(.failed); return false }
            if takeReject() {
                _ = try? await api.perform(.reject, callId: callId)
                return false
            }
            guard admitted(), !state.isBusy, !creatingCall, incomingCallAllowed(callId.uuidString.lowercased()) else { return false }
            handleSignaling(.ringing(callId: callId.uuidString.lowercased(), callerUserId: call.callerUserId.uuidString.lowercased(),
                                    mediaKind: call.mediaKind.rawValue))
            return state.callInfo.flatMap { UUID(uuidString: $0.callId) } == callId && !state.isTerminal
        } catch { return false }
    }

    // MARK: - Event handling

    private func handleSignaling(_ event: CallSignalingEvent) {
        guard !isShutDown, generation == apiGeneration else { return }
        switch event {
        case .ringing(let rawCallId, let callerUserId, let mediaKind):
            let callId = UUID(uuidString: rawCallId)?.uuidString.lowercased() ?? rawCallId
            guard incomingPeerAllowed(callerUserId), incomingCallAllowed(callId), !terminalCalls.isTerminal(callId), CallIncomingRecoveryPolicy.validIncomingIdentity(callId: callId, callerUserId: callerUserId, owner: api.ownerId) else { return }
            if externalMediaBusy() {
                if let id = UUID(uuidString: callId) { Task { _ = try? await api.perform(.reject, callId: id) } }
                return
            }
            guard !state.isBusy else { return }
            interruptExternalCapture()
            let kind: CallMediaKind = mediaKind == "video" ? .video : .audio
            let info = CallInfo(callId: callId, remoteUserId: callerUserId,
                                remoteDisplayName: UnyriL10n.string("Unknown user"), mediaKind: kind, direction: .incoming)
            reducerState = CallReducerState()
            ringIndicator = nil
            outgoingReads = nil
            stopOutgoingRingingChecks()
            state = .incomingRinging(info)
            reducerState.ui = .incomingRinging(info)
            reducerState.activeCallId = callId
            startIncomingRecovery(callId)
            prepareForCall()
            Task {
                guard let name = await resolveCallerName(callerUserId), !name.isEmpty,
                      !isShutDown, generation == apiGeneration,
                      case .incomingRinging(let current) = state, current.callId == callId else { return }
                let resolved = CallInfo(callId: callId, remoteUserId: callerUserId,
                    remoteDisplayName: name, mediaKind: kind, direction: .incoming)
                reducerState.ui = .incomingRinging(resolved)
                state = .incomingRinging(resolved)
            }
        case .offer(let callId, let sdp): apply(.remoteOffer(callId: callId, sdp: sdp))
        case .answer(let callId, let sdp): apply(.remoteAnswer(callId: callId, sdp: sdp))
        case .ice(let callId, let candidate, let sdpMid, let index):
            apply(.remoteIce(callId: callId, candidate: candidate, sdpMid: sdpMid, sdpMLineIndex: index))
        case .peerRinging(let callId):
            guard case .outgoingRinging = state, reducerState.activeCallId == callId else { return }
            reduceRinging(.peerRinging)
        case .accepted(let callId): apply(.remoteAccepted(callId: callId))
        case .answeredElsewhere(let callId): apply(.remoteAnsweredElsewhere(callId: callId))
        case .ended(let callId, let reason): apply(.remoteEnded(callId: callId, reason: reason))
        case .videoUpgradeRequest(let callId):
            apply(.remoteVideoUpgradeRequest(callId: callId))
            // Camera activation is an in-call toggle. Auto-accept the media
            // upgrade so the remote stream appears without a second dialog.
            acceptVideoUpgrade()
        case .videoUpgradeAccept(let callId): apply(.remoteVideoUpgradeAccept(callId: callId))
        case .videoUpgradeReject(let callId): apply(.remoteVideoUpgradeReject(callId: callId))
        case .videoUpgradeCancel(let callId): apply(.remoteVideoUpgradeCancel(callId: callId))
        case .videoState(let callId, let enabled): apply(.remoteVideoState(callId: callId, enabled: enabled))
        }
    }

    private func handleEngine(_ event: WebRtcEvent) {
        guard !isShutDown, generation == apiGeneration else { return }
        switch event {
        case .localIceCandidate(let candidate, let sdpMid, let index):
            guard let callId = reducerState.activeCallId else { return }
            signaling.sendIce(callId: callId, candidate: candidate, sdpMid: sdpMid, sdpMLineIndex: index)
        case .connectionStateChanged(let s):
            guard let callId = reducerState.activeCallId else { return }
            if connectedAt != nil, s == .disconnected || s == .failed { diagnosticMediaProblem = true }
            recordIceState(s, callId: callId)
            noteConsent(s)
            switch s {
            case .disconnected:
                // The caller restarts at once (toward a plain peer after a
                // moment); the callee lets it go first.
                let inCall = state.isInCallMedia
                apply(.engineConnectionState(callId: callId, state: s))
                if inCall {
                    beginRecoveryIfNeeded()
                    let now = Self.uptime
                    deadPair.noteOutage(at: now)
                    recovery.mediaDisconnected(at: now)
                    scheduleRecovery()
                }
            case .failed where state.isInCallMedia:
                // ICE `failed` on an established call is a restart trigger (WebRTC
                // guidance), not an immediate hang-up; recovery decides.
                apply(.engineConnectionState(callId: callId, state: .disconnected))
                beginRecoveryIfNeeded()
                let now = Self.uptime
                deadPair.noteOutage(at: now)
                recovery.mediaFailed(at: now)
                scheduleRecovery()
            case .connected:
                apply(.engineConnectionState(callId: callId, state: s))
                if state.isInCallMedia {
                    recovery.mediaConnected(at: Self.uptime)
                    scheduleRecovery()
                }
            case .failed:
                cancelRecovery()
                apply(.engineConnectionState(callId: callId, state: s))
            default:
                apply(.engineConnectionState(callId: callId, state: s))
            }
        case .remoteVideoEnabled(let enabled):
            guard let callId = reducerState.activeCallId else { return }
            apply(.engineRemoteVideoEnabled(callId: callId, enabled: enabled))
        case .remoteFirstFrame:
            guard let callId = reducerState.activeCallId else { return }
            apply(.engineRemoteFirstFrame(callId: callId))
        case .localCaptureInterrupted(let interrupted):
            guard let callId = reducerState.activeCallId else { return }
            videoPause.captureChanged(at: Self.uptime)
            apply(.engineLocalCaptureInterrupted(callId: callId, interrupted: interrupted))
        case .localFirstFrame:
            guard let callId = reducerState.activeCallId else { return }
            apply(.engineLocalFirstFrame(callId: callId))
        case .localCameraFailed(let cameraGeneration):
            guard engine.isCurrentCameraGeneration(cameraGeneration), let callId = reducerState.activeCallId else { return }
            engine.setVideoEnabled(false)
            syncTorch()
            apply(.localVideoFailed)
            signaling.sendVideoState(callId: callId, enabled: false)
        case .localCameraFacingChanged(let cameraGeneration, let isFrontFacing):
            guard engine.isCurrentCameraGeneration(cameraGeneration) else { return }
            apply(.localCameraFacingChanged(isFrontFacing))
            syncTorch()
        case .localTorchChanged(let cameraGeneration):
            guard engine.isCurrentCameraGeneration(cameraGeneration) else { return }
            syncTorch()
        case .audioRouteChanged(let route):
            guard reducerState.activeCallId != nil else { return }
            handleAudioRouteChanged(route)
        }
    }

    private func handleConnection(_ connected: Bool) {
        guard !isShutDown, generation == apiGeneration else { return }
        let reconnected = connected && !lastConnected && lastConnectedInitialized
        lastConnected = connected
        lastConnectedInitialized = true
        guard reconnected else { return }
        apply(.socketReconnected)
        reconcileAfterReconnect()
    }
    private var lastConnectedInitialized = false

    func refreshActiveCall() { reconcileAfterReconnect() }

    private func stopIncomingRecovery() {
        incomingRecoveryTask?.cancel(); incomingRecoveryTask = nil
    }

    /// Safety net for a lost WS answered_elsewhere/terminal event. Only reads
    /// this ringing call; it must not send keepalive or extend server deadlines.
    private func startIncomingRecovery(_ callId: String) {
        stopIncomingRecovery()
        guard !isShutDown, generation == apiGeneration,
              CallIncomingRecoveryPolicy.matches(state, callId: callId) else { return }
        incomingRecoveryTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: CallIncomingRecoveryPolicy.intervalMs * 1_000_000) }
                catch { return }
                guard !Task.isCancelled, let self, !self.isShutDown, self.generation == self.apiGeneration,
                      self.acceptingCallID != callId,
                      CallIncomingRecoveryPolicy.matches(self.state, callId: callId) else { return }
                if self.reconcileTask == nil { self.reconcileAfterReconnect() }
            }
        }
    }

    /// Reads the current call, superseding a read still in flight. `read`
    /// says why, for a call that rings out (`CallOutgoingReadPolicy`): every
    /// read goes out at once (a reconnect or foreground one is the only way
    /// back to an accept or end the socket lost), only the polling ones
    /// count against its budget.
    private func reconcileAfterReconnect(_ read: CallOutgoingReadPolicy.Read = .refresh) {
        guard !isShutDown, generation == apiGeneration else { return }
        guard let callId = reducerState.activeCallId else {
            recoverMissedIncomingCall()
            return
        }
        let requestedAt = ProcessInfo.processInfo.systemUptime
        if case .outgoingRinging = state { outgoingReads?.issued(read, at: requestedAt) }
        reconcileGeneration += 1
        let token = reconcileGeneration
        reconcileTask?.cancel()
        reconcileTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { if token == self.reconcileGeneration { self.reconcileTask = nil } }
            do {
                let dto = try await self.api.get(callId: UUID(uuidString: callId) ?? UUID())
                try self.requireSession()
                guard token == self.reconcileGeneration,
                      self.reducerState.activeCallId == callId,
                      let current = self.state.callInfo,
                      CallReconciliation.matchesParticipants(call: current, server: dto, owner: self.api.ownerId)
                else { return }
                if case .outgoingRinging = self.state {
                    // A read issued before the deadline never shows notOnline.
                    let afterDeadline = self.outgoingReads.map { requestedAt >= $0.deadline } ?? false
                    self.reduceRinging(.serverRow(state: dto.state, peerRinging: dto.peerRinging,
                        expected: dto.peerRingingExpected, afterDeadline: afterDeadline))
                }
                self.apply(.reconnectReconcileSucceeded(dto, localDeviceId: self.api.currentDeviceId))
            } catch {
                guard token == self.reconcileGeneration,
                      self.reducerState.activeCallId == callId else { return }
                self.apply(.reconnectReconcileFailed)
            }
        }
    }

    private func recoverMissedIncomingCall() {
        guard reconcileTask == nil, !state.isBusy, !creatingCall else { return }
        reconcileGeneration += 1
        let ticket = reconcileGeneration
        reconcileTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { if ticket == self.reconcileGeneration { self.reconcileTask = nil } }
            do {
                guard let call = try await self.api.active() else { return }
                try self.requireSession()
                guard ticket == self.reconcileGeneration, !self.state.isBusy,
                      !self.creatingCall, self.reducerState.activeCallId == nil,
                      call.state == "ringing", !call.isEnded,
                      call.calleeUserId == self.api.ownerId,
                      CallIncomingRecoveryPolicy.validIncomingIdentity(callId: call.id.uuidString,
                          callerUserId: call.callerUserId.uuidString, owner: self.api.ownerId) else { return }
                self.handleSignaling(.ringing(callId: call.id.uuidString.lowercased(),
                    callerUserId: call.callerUserId.uuidString.lowercased(), mediaKind: call.mediaKind.rawValue))
            } catch { /* Foreground recovery retries; never fabricate a call on failure. */ }
        }
    }

    // MARK: - Reducer bridge

    /// `endedHere`: this device hangs up, declines, cancels or drops the
    /// call itself (with the same `.remoteEnded` event a server end arrives
    /// as), for the end diagnostic only.
    private func apply(_ event: CallEvent, endedHere: Bool = false) {
        guard !isShutDown, generation == apiGeneration else { return }
        let wasOutgoingRinging: Bool = {
            if case .outgoingRinging = reducerState.ui { return true }
            return false
        }()
        let wasConnecting: Bool = {
            if case .connecting = reducerState.ui { return true }
            return false
        }()
        let wasVideoLayout = CallPresentationPolicy.showsVideoLayout(state: reducerState.ui,
            controls: reducerState.audioControls, startingVideo: nil, endedInVideo: endedInVideoLayout)
        let engine = self.engine
        let (newState, effects) = CallReduction.apply(reducerState, event, speaker: &automaticSpeaker,
                                                      currentRoute: { engine.currentAudioRoute() })
        // Feed only transitions of this bound outgoing call. Unrelated ids
        // never reach the indicator, and terminal phases absorb late replies.
        if case .outgoingRinging = reducerState.ui {
            switch newState.ui {
            case .connecting:
                reduceRinging(.accepted)
            case .ended(_, let reason):
                if endedHere { reduceRinging(.cancelledHere) }
                else if case .remoteEnded(_, let rawReason) = event { reduceRinging(.ended(reason: rawReason)) }
                else { reduceRinging(.ended(reason: reason.rawValue)) }
            case .failed:
                reduceRinging(.ended(reason: "failed"))
            default: break
            }
        }
        let ending = newState.ui.isTerminal && !reducerState.ui.isTerminal
        if ending {
            endedInVideoLayout = wasVideoLayout
            #if os(iOS)
            var failedToConnect = false
            var endedWithMediaFailure = false
            switch newState.ui {
            case .failed(_, let reason):
                failedToConnect = connectedAt == nil && (reason == .generic || reason == .network)
                endedWithMediaFailure = reason == .generic || reason == .network
            case .ended(_, let reason):
                endedWithMediaFailure = reason == .failed || reason == .timeout
                failedToConnect = connectedAt == nil && wasConnecting && endedWithMediaFailure
            default: break
            }
            DiagnosticReportEvents.emit(.callEnded(failedToConnect: failedToConnect,
                connectedSeconds: connectedAt.map { Date().timeIntervalSince($0) },
                mediaProblem: diagnosticMediaProblem || endedWithMediaFailure))
            #endif
        }
        if newState.activeCallId != reducerState.activeCallId, newState.activeCallId != nil { diagnosticMediaProblem = false }
        let offerWasPending = reducerState.renegotiating && reducerState.ui.isInCallMedia
        let previousOffer = reducerState.offerGeneration
        reducerState = newState
        syncRecovery(wasPending: offerWasPending, previousGeneration: previousOffer, effects: effects)
        if newState.ui.isTerminal, let call = newState.ui.callInfo { terminalCalls.completeCall(call.callId) }
        // Publish only a real change: every ICE candidate and SDP step used to
        // re-render the app root and the whole call screen.
        if state != newState.ui { state = newState.ui }
        // After the call phase it explains (6 ended, 7 failed).
        if ending { recordCallEnd(newState.ui, after: event, endedHere: endedHere) }
        if !CallIncomingRecoveryPolicy.matches(newState.ui, callId: newState.activeCallId ?? "") { stopIncomingRecovery() }
        if case .outgoingRinging = newState.ui { }
        else {
            stopOutgoingRingingChecks()
            if wasOutgoingRinging {
                // A deadline GET can itself accept/end the call. Invalidate
                // other pending reads as well as the sleeping polling task.
                reconcileGeneration &+= 1
                reconcileTask?.cancel()
                reconcileTask = nil
            }
        }
        updateRingback()
        // Duration anchor: only the connecting -> connected transition starts
        // the timer, so mute/camera/route/reconnect changes never reset it.
        if wasConnecting, case .connected = newState.ui, connectedAt == nil {
            connectedAt = Date()
        }
        if newState.ui.isTerminal { connectedAt = nil }
        guard !effects.isEmpty else { return }
        // Audio buttons must not wait behind ICE configuration/network requests.
        var queuedVideoStop = false
        var pendingEffects = effects.filter { effect in
            switch effect {
            case .engineSetMuted(let muted): engine.setMuted(muted); return false
            case .engineSetSpeaker(let on): engine.setSpeakerOn(on); return false
            case .engineSetAudioRoute(let route): engine.setAudioRoute(route); return false
            case .engineSetVideoNegotiationEnabled(let enabled): engine.setVideoNegotiationEnabled(enabled); return false
            case .engineSetVideoSendActive(let active):
                // A resume must follow the camera-off/stop emitted before it,
                // including when that teardown waits behind an in-flight offer.
                if active && queuedVideoStop { return true }
                engine.setVideoSendActive(active, seedBitrate: active ? resumeSeed(after: event) : nil); return false
            case .engineSetVideoEnabled(false):
                queuedVideoStop = true
                return true
            case .dismissIncomingPush(let callId): onDismissIncomingPush(callId); return false
            case .engineStopLocalVideo:
                // A peer's full SDP downgrade withdraws video immediately;
                // never leave capture running behind an awaited SDP/API job.
                if case .remoteOffer = event { engine.stopLocalVideo(); syncTorch(); return false }
                queuedVideoStop = true
                return true
            default: return true
            }
        }
        if effects.contains(.engineDispose) {
            // Local teardown cannot wait behind a slow HTTP/SDK operation.
            effectQueue.cancel()
            stopOutgoingRingingChecks()
            cancelRecovery()
            stopCredentialRefresh()
            keepAliveTask?.cancel(); keepAliveTask = nil
            upgradeTimeoutTask?.cancel(); upgradeTimeoutTask = nil
            callSAS = CallSASHandshake()
            engine.dispose()
            syncTorch()
            pendingEffects.removeAll { $0 == .engineDispose || $0 == .stopKeepAlive || $0 == .cancelVideoUpgradeTimeout }
        }
        guard !pendingEffects.isEmpty else { return }
        let batch = pendingEffects
        let callId = newState.activeCallId
        // An offer effect belongs to the offer its event started.
        let offerGeneration = newState.offerGeneration
        // Only established-call offers registered by syncRecovery own a slot.
        // Keep that ownership with the batch even if the call phase changes
        // while an earlier engine operation is suspended.
        let producesAnswer = batch.contains {
            if case .produceAnswerAndSend = $0 { return true }
            return false
        }
        let answersPeer = newState.ui.isInCallMedia && producesAnswer
        // Effects are ordered (e.g. initialize the engine before producing an
        // offer, start local video before sending an upgrade request); execute
        // them sequentially, never concurrently.
        effectQueue.enqueue { [weak self] in
            guard let self else { return }
            var completed = false
            defer {
                if answersPeer, let callId {
                    self.peerAnswerFinished(callId: callId, succeeded: completed)
                }
                // Settle this answer before the reducer starts a deferred
                // local offer, so that offer cannot prolong an old recovery.
                if completed, producesAnswer, let callId, self.reducerState.ui.isInCallMedia {
                    self.apply(.localAnswerSent(callId: callId))
                }
            }
            for effect in batch {
                guard !Task.isCancelled, !self.isShutDown, self.generation == self.apiGeneration,
                      callId == nil || self.reducerState.activeCallId == callId else { return }
                if !(await self.execute(effect, offerGeneration: offerGeneration)) { return }
            }
            completed = true
        }
    }

    /// Lifecycle reports must reach the server: a hang-up sent while the network was
    /// down left the peer "in call" (and this user "busy"), a lost reject kept the
    /// caller ringing for 40 s, and a lost "connected" let the sweep end a live call.
    /// The server treats repeats as no-ops, so retry network failures with backoff,
    /// off the effect queue (the next effects never wait for these retries).
    private func performDurably(_ action: CallsAPI.Action, callId: String) {
        let id = UUID(uuidString: callId) ?? UUID()
        Task { @MainActor [weak self] in
            for attempt in 0..<6 {
                guard let self, !self.isShutDown, self.generation == self.apiGeneration else { return }
                do { _ = try await self.api.perform(action, callId: id); return }
                catch APIError.server { return }            // a definitive answer (ended, not found, …)
                catch APIError.http(let code) where code < 500 { return }
                catch { }
                try? await Task.sleep(nanoseconds: UInt64(1 << attempt) * 1_000_000_000)
            }
        }
    }

    private func execute(_ effect: CallEffect, offerGeneration: UInt64) async -> Bool {
        switch effect {
        case .restCreate(let remoteUserId, let mediaKind): break // handled by startCall directly
        case .restAccept(let callId):
            _ = try? await api.perform(.accept, callId: UUID(uuidString: callId) ?? UUID())
        case .restReject(let callId):
            performDurably(.reject, callId: callId)
        case .restCancel(let callId):
            performDurably(.cancel, callId: callId)
        case .restHangup(let callId):
            performDurably(.hangup, callId: callId)
        case .restFailed(let callId):
            performDurably(.failed, callId: callId)
        case .restConnected(let callId):
            performDurably(.connected, callId: callId)
        case .restGet(let callId):
            break // handled by reconcileAfterReconnect
        case .engineInitialize(let mediaKind):
            let callId = reducerState.activeCallId
            do {
                // Usually prefetched while ringing: no request on the critical path.
                let cached = freshIceCache
                let fetched = cached == nil ? await fetchIceServers() : nil
                guard let servers = cached?.set ?? fetched else { throw APIError.network("ICE servers unavailable") }
                // The fetch time of exactly this set (a fresh fetch stored it
                // in the cache); unknown otherwise.
                let fetchedAt = cached?.fetchedAt ?? iceCache.flatMap { $0.set == servers ? $0.fetchedAt : nil }
                try requireSession()
                guard !Task.isCancelled, !isShutDown,
                      let callId, callId == reducerState.activeCallId else { return false }
                let requiresRelay = relayOnly(for: callId)
                let hasTurn = CallRelayFallbackPolicy.hasTurnServer(servers.servers.flatMap(\.urls))
                guard CallRelayFallbackPolicy.canInitialize(relayOnly: requiresRelay, urls: servers.servers.flatMap(\.urls)) else {
                    apply(.relayUnavailable(callId: callId))
                    return false
                }
                let profile = currentMediaProfile
                engine.initialize(iceServers: servers.servers, mediaKind: mediaKind, options: CallEngineOptions(
                    relayOnly: requiresRelay, profile: profile,
                    captureProfile: CallMediaLadder.captureProfile(saving: dataSaving),
                    cameraEnabled: reducerState.audioControls?.localVideoEnabled ?? false))
                engineCallId = callId
                engineTurnKnown = hasTurn
                // A relay-only call ("Peer-to-peer calls") has nothing to fall back to.
                relayFallback = CallRelayFallbackPolicy(relayOnly: requiresRelay)
                iceUp = false
                if let fetchedAt, !servers.servers.isEmpty {
                    engineIce = (fetchedAt, CallIceCredentialPolicy.ttl(servers.ttlSeconds))
                } else {
                    engineIce = nil
                }
                mediaProfile = profile
                videoPause = CallVideoPausePolicy()
                lastLink = nil
                deadPair = CallDeadPairPolicy()
                mediaDiagnostics = CallMediaDiagnosticsPolicy()
                scheduleCredentialRefresh()
                restoreAudioControls()
                apply(.localCameraFacingChanged(engine.localCameraIsFrontFacing()))
                if mediaKind == .video && reducerState.audioControls?.localVideoEnabled == true && !engine.localVideoRunning() {
                    apply(.localVideoFailed)
                }
            } catch {
                if let callId { failCall(callId: callId) }
                return false
            }
        case .engineSetRemoteDescription(let callId, let type, let sdp):
            do {
                try await engine.setRemoteDescription(type: type, sdp: sdp)
                try requireSession()
                guard reducerState.activeCallId == callId else { return false }
                if type == "offer" { callSAS.receiveOffer(sdp, callID: callId) }
                else if let reveal = callSAS.receiveAnswer(sdp, callID: callId) {
                    signaling.sendIce(callId: callId, candidate: reveal, sdpMid: nil, sdpMLineIndex: nil)
                }
                recovery.remoteDescriptionApplied(sdp: sdp)
            }
            catch { apply(.mediaNegotiationFailed(callId: callId)); return false }
        case .engineAddIce(let callId, let candidate, let sdpMid, let index):
            if candidate.hasPrefix(CallSASHandshake.revealPrefix) { callSAS.receiveReveal(candidate, callID: callId) }
            else { engine.addIceCandidate(candidate: candidate, sdpMid: sdpMid, sdpMLineIndex: index) }
        case .engineSetMuted(let muted): engine.setMuted(muted)
        case .engineSetSpeaker(let on): engine.setSpeakerOn(on)
        case .engineSetAudioRoute(let route): engine.setAudioRoute(route)
        case .engineSetVideoEnabled(let enabled):
            // An older queued camera-on must not undo a newer camera-off or
            // the peer's full audio downgrade in the same call.
            if !enabled || reducerState.audioControls?.localVideoEnabled == true {
                engine.setVideoEnabled(enabled, seedBitrate: enabled ? bitrateSeed(.cameraStart) : nil)
                syncTorch()
            }
        case .engineStartLocalVideo:
            if reducerState.audioControls?.localVideoEnabled == true {
                _ = engine.startLocalVideo(capture: true, seedBitrate: bitrateSeed(.cameraStart))
            }
        case .enginePrepareLocalVideo: _ = engine.startLocalVideo(capture: false, seedBitrate: bitrateSeed(.cameraStart))
        case .engineStopLocalVideo: engine.stopLocalVideo(); syncTorch()
        case .engineSetVideoNegotiationEnabled(let enabled): engine.setVideoNegotiationEnabled(enabled)
        case .engineSwitchCamera: engine.switchCamera(); syncTorch()
        case .engineDispose:
            cancelRecovery()
            stopCredentialRefresh()
            engine.dispose()
            syncTorch()
        case .engineRollbackLocalOffer:
            await engine.rollbackLocalDescription()
        case .engineSetVideoSendActive(let active):
            // A newer congestion pause can supersede a queued teardown resume.
            if !active || reducerState.audioControls?.outgoingVideoPaused != true { engine.setVideoSendActive(active) }
        case .produceOfferAndSend(let callId), .produceIceRestartOfferAndSend(let callId):
            // In an established call only the offer the reducer still waits for
            // is made and sent (B3); one superseded meanwhile (rolled back by
            // glare, abandoned) never goes out.
            let established = reducerState.ui.isInCallMedia
            func current() -> Bool { reducerState.renegotiating && reducerState.offerGeneration == offerGeneration }
            if established && !current() { return true }
            do {
                if case .produceIceRestartOfferAndSend = effect {
                    // The relay fallback's change of candidate types first,
                    // when one is due: this restart then gathers with it.
                    applyRelayFallbackIfDue()
                    engine.restartIce()
                }
                let sdp = try await engine.createOffer()
                try requireSession()
                guard reducerState.activeCallId == callId else { return false }
                // The sent text announces that crossing offers are resolved here.
                let sent = callSAS.offer(CallSdpCapabilities.advertising(sdp), callID: callId)
                if established {
                    guard current() else { return true }
                    pendingOffer = (offerGeneration, sent)
                    recovery.offerSent(at: Self.uptime)
                }
                signaling.sendOffer(callId: callId, sdp: sent)
            } catch { apply(.mediaNegotiationFailed(callId: callId)); return false }
        case .produceAnswerAndSend(let callId):
            do {
                let sdp = try await engine.createAnswer()
                try requireSession()
                guard reducerState.activeCallId == callId else { return false }
                signaling.sendAnswer(callId: callId, sdp: callSAS.answer(CallSdpCapabilities.advertising(sdp), callID: callId))
            } catch { apply(.mediaNegotiationFailed(callId: callId)); return false }
        case .sendIce(let callId, let candidate, let sdpMid, let index):
            signaling.sendIce(callId: callId, candidate: candidate, sdpMid: sdpMid, sdpMLineIndex: index)
        case .sendVideoUpgradeRequest(let callId): signaling.sendVideoUpgradeRequest(callId: callId)
        case .sendVideoUpgradeAccept(let callId): signaling.sendVideoUpgradeAccept(callId: callId)
        case .sendVideoUpgradeReject(let callId): signaling.sendVideoUpgradeReject(callId: callId)
        case .sendVideoUpgradeCancel(let callId): signaling.sendVideoUpgradeCancel(callId: callId)
        case .sendVideoState(let callId, let enabled): signaling.sendVideoState(callId: callId, enabled: enabled)
        case .startKeepAlive(let callId): startKeepAlive(callId)
        case .stopKeepAlive: keepAliveTask?.cancel(); keepAliveTask = nil
        case .armVideoUpgradeTimeout: armVideoUpgradeTimeout()
        case .cancelVideoUpgradeTimeout: upgradeTimeoutTask?.cancel(); upgradeTimeoutTask = nil
        case .dismissIncomingPush(let callId): onDismissIncomingPush(callId)
        case .engineExpectRemoteVideoFrame: engine.expectRemoteVideoFrame()
        case .engineExpectLocalVideoFrame: engine.expectLocalVideoFrame()
        }
        return true
    }

    private func startKeepAlive(_ callId: String) {
        keepAliveTask?.cancel()
        keepAliveTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.keepAliveIntervalMs * 1_000_000)
                guard !Task.isCancelled else { return }
                guard let self, !self.isShutDown && self.generation == self.apiGeneration else { return }
                self.signaling.sendKeepAlive(callId: callId)
                // TURN credentials are refreshed on their own schedule (ttl/2,
                // `scheduleCredentialRefresh`), not here.
                // Recover a lost terminal event even if the socket still
                // appears connected. Session and call ID guards reject stale replies.
                // Not during recovery: it owns the media path.
                if self.reconcileTask == nil && !self.recovery.isActive { self.reconcileAfterReconnect() }
            }
        }
    }

    private func armVideoUpgradeTimeout() {
        upgradeTimeoutTask?.cancel()
        let callId = reducerState.activeCallId
        upgradeTimeoutTask = Task { @MainActor [weak self] in
            do { try await Task.sleep(nanoseconds: Self.videoUpgradeTimeoutMs * 1_000_000) }
            catch { return }
            guard !Task.isCancelled, let self, !self.isShutDown,
                  self.generation == self.apiGeneration,
                  let callId, callId == self.reducerState.activeCallId else { return }
            self.apply(.videoUpgradeTimeout)
        }
    }

    // MARK: - Recovery of a dropped media path

    private static var uptime: TimeInterval { ProcessInfo.processInfo.systemUptime }

    private func beginRecoveryIfNeeded() {
        guard !recovery.isActive else { return }
        recovery.role = state.callInfo?.direction == .incoming ? .callee : .caller
    }

    /// Feeds the reducer's facts to the recovery policy after every step: a
    /// new offer of the established call or the end of the one in flight, and
    /// whether the peer resolves crossing offers (only then may a callee offer).
    /// An accepted peer offer blocks recovery before glare resolves ours.
    private func syncRecovery(wasPending: Bool, previousGeneration: UInt64, effects: [CallEffect]) {
        recovery.peerResolvesGlare = reducerState.peerResolvesGlare
        if reducerState.ui.isInCallMedia {
            for effect in effects {
                if case .engineSetRemoteDescription(_, let type, let sdp) = effect, type == "offer" {
                    recovery.peerOfferReceived(sdp: sdp)
                    scheduleRecovery()
                }
            }
        }
        let pending = reducerState.renegotiating && reducerState.ui.isInCallMedia
        let now = Self.uptime
        if pending && (!wasPending || reducerState.offerGeneration != previousGeneration) {
            if wasPending { recovery.offerResolved(at: now) }
            pendingOffer = nil
            recovery.offerRequested(at: now, restart: reducerState.pendingOfferIsRestart)
            scheduleRecovery()
        } else if wasPending && !pending {
            pendingOffer = nil
            recovery.offerResolved(at: now)
            scheduleRecovery()
        }
    }

    /// Every accepted peer-answer batch releases its own slot on exit. A
    /// failure's rollback is already queued before recovery wakes to re-plan.
    private func peerAnswerFinished(callId: String, succeeded: Bool) {
        guard !isShutDown, generation == apiGeneration,
              reducerState.activeCallId == callId else { return }
        if succeeded { recovery.peerNegotiated(at: Self.uptime) }
        else { recovery.peerAnswerFailed(at: Self.uptime) }
        scheduleRecovery()
    }

    /// Starts the recovery loop, or wakes the running one to re-plan.
    private func scheduleRecovery() {
        guard !isShutDown else { return }
        if recoveryTask != nil { recoveryWake?.cancel(); return }
        guard recovery.isActive else { return }
        recoveryToken &+= 1
        let token = recoveryToken
        recoveryTask = Task { @MainActor [weak self] in
            await self?.runRecovery()
            guard let self, self.recoveryToken == token else { return }
            self.recoveryTask = nil
            self.recoveryWake = nil
        }
    }

    private func cancelRecovery() {
        recoveryToken &+= 1
        recoveryTask?.cancel(); recoveryTask = nil
        recoveryWake?.cancel(); recoveryWake = nil
        recovery = CallRecoveryPolicy()
        pendingOffer = nil
    }

    private func runRecovery() async {
        var immediateSteps = 0
        while !Task.isCancelled, !isShutDown, generation == apiGeneration,
              let callId = reducerState.activeCallId, !state.isTerminal {
            let now = Self.uptime
            let action = recovery.next(now: now)
            if case .wait = action { immediateSteps = 0 } else { immediateSteps += 1 }
            // A step that changed nothing must never spin the main actor.
            if immediateSteps > 8 {
                immediateSteps = 0
                try? await Task.sleep(nanoseconds: 500_000_000)
                continue
            }
            switch action {
            case .idle:
                return
            case .wait(let until):
                let delay = max(0.05, until - now)
                let wake = Task<Void, Never> { _ = try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
                recoveryWake = wake
                await wake.value
                if recoveryWake == wake { recoveryWake = nil }
            case .restart:
                recordRecoveryStep(110)
                await restartIce(callId)
            case .resendOffer:
                recordRecoveryStep(111)
                // B3: the same offer again; any answer to it is the right one.
                if let offer = pendingOffer, offer.generation == reducerState.offerGeneration, reducerState.renegotiating {
                    signaling.sendOffer(callId: callId, sdp: offer.sdp)
                }
                recovery.offerSent(at: Self.uptime)
            case .abandonOffer:
                recordRecoveryStep(112)
                apply(.localOfferAbandoned(callId: callId))
                if recovery.offerPendingSince != nil { recovery.offerResolved(at: Self.uptime) }
            case .fail:
                recordRecoveryStep(113)
                apply(.engineConnectionState(callId: callId, state: .failed))
                return
            }
        }
    }

    // MARK: - Diagnostics

    /// ICE state as WebRTC reported it, with what the recent stats reads say
    /// about the selected pair (`CallIceReadHistory`), only from a read at
    /// most 2.5 s old: `status` local × 10 + remote candidate type, `network`
    /// our interface, `max_gap_ms` the time since media last arrived on that
    /// pair (as of the read, plus its age: how long a dead pair took to be
    /// noticed); and over `gap_ms` (5-12 s of the same pair) `bytes` what
    /// arrived and `count` how many STUN responses came back (media still
    /// flowing while only STUN is lost, or a dead path). A field that
    /// is missing had no such read (a new pair, the call just started, reads
    /// that stalled); with diagnostics on, stats are read in the background
    /// too (`CallQualityMonitor`). Never addresses. Then, from the same fresh
    /// read, the pair in detail (operation 5, phase 140,
    /// `CallIceDiagnostics.pairDetail`): IP families, round trip, pair
    /// switches, DTLS, all media that arrived and the STUN checks in each
    /// direction.
    private func recordIceState(_ s: PeerConnectionState, callId: String) {
        guard CallDiagnostics.isEnabled else { return }
        let phase: Double
        switch s {
        case .new: phase = 100
        case .checking: phase = 101
        case .connected: phase = 102
        case .disconnected: phase = 103
        case .failed: phase = 104
        case .closed: phase = 105
        }
        let now = Self.uptime
        var sincePrevious: Double?
        if let record = iceRecord, record.callId == callId { sincePrevious = (now - record.at) * 1000 }
        iceRecord = (callId, now)
        var values: [String: Double?] = ["duration_ms": sincePrevious]
        let window = quality.iceReads.window(at: now)
        if let reads = window, reads.newest.pairId != nil {
            let last = reads.newest
            let local = CallDiagnostics.candidateCode(last.localCandidateType)
            let remote = CallDiagnostics.candidateCode(last.remoteCandidateType)
            values["status"] = local * 10 + remote
            values["network"] = CallDiagnostics.networkCode(last.localNetworkType)
            values["max_gap_ms"] = last.pairSinceLastPacketMs.map { $0 + reads.age * 1000 }
            if let base = reads.base, let span = reads.span {
                values["gap_ms"] = span * 1000
                values["bytes"] = CallDiagnostics.delta(base.pairBytesReceived, last.pairBytesReceived)
                values["count"] = CallDiagnostics.delta(base.responsesReceived, last.responsesReceived)
            }
        }
        CallDiagnostics.record(.iceState, phase: phase, values)
        if let window { CallDiagnostics.record(.iceDetail, phase: 140, CallIceDiagnostics.pairDetail(window)) }
    }

    /// The call's end (operation 5, phase 170): why, and whether this device
    /// or the peer (or the server) ended it (`CallIceDiagnostics.endDetail`).
    private func recordCallEnd(_ ui: CallUiState, after event: CallEvent, endedHere: Bool) {
        guard CallDiagnostics.isEnabled,
              let end = CallIceDiagnostics.endDetail(ui, after: event, endedHere: endedHere) else { return }
        CallDiagnostics.record(.iceDetail, phase: 170, ["status": end.status, "direction": end.direction])
    }

    /// A recovery step about to run, with the restart rounds so far.
    private func recordRecoveryStep(_ phase: Double) {
        CallDiagnostics.record(.recovery, phase: phase, ["items": Double(recovery.negotiations)])
    }

    /// Fresh TURN credentials first (a new relay allocation needs unexpired
    /// ones), but a network that is still switching never holds the restart
    /// for long; credentials that arrive later are applied when they do.
    /// While the engine's own credentials are still fresh (ttl/2 or more
    /// left), the restart goes ahead without the round trip.
    private func restartIce(_ callId: String) async {
        let now = Date().timeIntervalSince1970
        if CallIceCredentialPolicy.refreshBeforeRestart(engineFetchedAt: engineIce?.fetchedAt.timeIntervalSince1970,
                                                        ttl: engineIce?.ttl ?? 0, now: now) {
            await refreshCredentialsForRestart()
        }
        guard !Task.isCancelled else { return }
        guard !isShutDown, generation == apiGeneration, reducerState.activeCallId == callId, state.isInCallMedia else {
            recovery.restartDeferred(at: Self.uptime)
            return
        }
        // The wait above may have outlived the reason: media came back, the
        // peer's restart arrived and was answered, or another offer started.
        // Only a restart recovery still asks for goes out; the loop re-plans.
        guard recovery.next(now: Self.uptime) == .restart else { return }
        // An earlier round of this outage did not bring media back: on a
        // direct path this restart goes through the relay, over the
        // fallback's relay it goes back to every candidate type (decided
        // right before the offer is made, `applyRelayFallbackIfDue`).
        if recovery.earlierRoundFailed { relayFallback.restartFailed() }
        let before = reducerState.offerGeneration
        apply(.restartIceOfferRequested(callId: callId))
        // Refused (another offer holds the connection): back off, never spin.
        if reducerState.offerGeneration == before { recovery.restartDeferred(at: Self.uptime) }
    }

    // MARK: - Relay fallback

    /// Consent losses for the relay fallback: ICE connected before and
    /// disconnected or failed now, on an established call, is the selected
    /// pair losing consent (counted once, however the outage goes on).
    private func noteConsent(_ s: PeerConnectionState) {
        switch s {
        case .connected:
            iceUp = true
            relayFallback.mediaRestored()
        case .disconnected, .failed:
            guard iceUp else { return }
            iceUp = false
            guard state.isInCallMedia else { return }
            relayFallback.consentLost(direct: selectedPairIsDirect, at: Self.uptime)
        case .new, .checking, .closed:
            iceUp = false
        }
    }

    /// The selected pair as WebRTC last reported it has no relay end; before
    /// any such report, the selected pair of a fresh stats read (at most
    /// 2.5 s old) says it; with neither, the pair is not taken for direct.
    private var selectedPairIsDirect: Bool {
        if let pair = engine.selectedCandidateTypes() {
            return CallRelayFallbackPolicy.isDirect(localType: pair.local, remoteType: pair.remote)
        }
        guard let read = quality.iceReads.window(at: Self.uptime)?.newest, read.pairId != nil else { return false }
        return CallRelayFallbackPolicy.isDirect(localType: read.localCandidateType, remoteType: read.remoteCandidateType)
    }

    /// Right before a restart offer is made: when the fallback is due, the
    /// connection becomes relay-only (only once it has gathered a relay
    /// candidate of its own, proof that a TURN allocation works from here),
    /// or, after a round over that relay did not bring media back, gathers
    /// every candidate type again; this restart gathers with it (the peer
    /// answers it like any restart offer). Diagnostics: operation 2, phase
    /// 114, `status` what and why (1 to the relay after the second consent
    /// loss of a direct pair, 2 to the relay after a restart on a direct
    /// path that did not bring media back, 3 back to every candidate type,
    /// 4 a switch was due but this connection never gathered a relay
    /// candidate, so the normal restart went out), `count` the direct losses
    /// so far, `success` 1 when WebRTC took the configuration (0: nothing
    /// changed, the normal restart went out and the next one tries again;
    /// always 0 for 4), `items` the restart rounds of this recovery.
    private func applyRelayFallbackIfDue() {
        let status: Double
        let applied: Bool
        if relayFallback.backToAllDue {
            applied = engine.setRelayOnly(false)
            if applied { relayFallback.relayReverted() }
            status = 3
        } else {
            let direct = selectedPairIsDirect
            let gathered = engineTurnKnown && engine.relayCandidateGathered()
            if let reason = relayFallback.relayReason(relayGathered: gathered, pairDirect: direct) {
                applied = engine.setRelayOnly(true)
                if applied { relayFallback.relayApplied() }
                status = reason == .repeatedLoss ? 1 : 2
            } else if !gathered, relayFallback.relayReason(relayGathered: true, pairDirect: direct) != nil {
                applied = false
                status = 4
            } else {
                return
            }
        }
        CallDiagnostics.record(.recovery, phase: 114, [
            "status": status,
            "count": Double(relayFallback.directLosses),
            "success": applied ? 1 : 0,
            "items": Double(recovery.negotiations),
        ])
    }

    // MARK: - TURN credentials

    /// Whichever comes first: fresh credentials, or the timeout. Awaiting a
    /// task's value cannot be cut short, so the two race through a gate that
    /// lets exactly one of them resume the restart; the fetch carries on and
    /// reaches the engine when it completes.
    private func refreshCredentialsForRestart() async {
        let timeout = UInt64(CallIceCredentialPolicy.restartRefreshTimeout * 1_000_000_000)
        let gate = CallOnceGate()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            Task { @MainActor [weak self] in
                _ = await self?.fetchIceServers()
                if gate.pass() { continuation.resume() }
            }
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: timeout)
                if gate.pass() { continuation.resume() }
            }
        }
    }

    /// Builds the media factory and fetches TURN credentials while the call
    /// still rings, so neither is on the path from "accept" to audio.
    private func prepareForCall() {
        engine.prepare()
        guard freshIceServers == nil, iceFetch == nil else { return }
        Task { @MainActor [weak self] in _ = await self?.fetchIceServers() }
    }

    private var freshIceServers: CallICEServerSet? { freshIceCache?.set }

    private var freshIceCache: (set: CallICEServerSet, fetchedAt: Date)? {
        guard let cache = iceCache,
              CallIceCredentialPolicy.isFresh(fetchedAt: cache.fetchedAt.timeIntervalSince1970,
                                              ttl: CallIceCredentialPolicy.ttl(cache.set.ttlSeconds),
                                              now: Date().timeIntervalSince1970) else { return nil }
        return cache
    }

    /// One request at a time; concurrent callers share it. A result reaches
    /// the engine of the current call at once and re-arms the refresh.
    private func fetchIceServers() async -> CallICEServerSet? {
        if let running = iceFetch { return await running.value }
        let task = Task { @MainActor [weak self] () -> CallICEServerSet? in
            guard let self, let set = try? await self.api.iceServers(),
                  !self.isShutDown, self.generation == self.apiGeneration else { return nil }
            let fetchedAt = Date()
            self.iceCache = (set, fetchedAt)
            if let callId = self.engineCallId, callId == self.reducerState.activeCallId, !set.servers.isEmpty {
                self.engine.updateIceServers(set.servers)
                self.engineIce = (fetchedAt, CallIceCredentialPolicy.ttl(set.ttlSeconds))
                self.engineTurnKnown = CallRelayFallbackPolicy.hasTurnServer(set.servers.flatMap(\.urls))
                self.scheduleCredentialRefresh()
            }
            return set
        }
        iceFetch = task
        let result = await task.value
        if iceFetch == task { iceFetch = nil }
        return result
    }

    /// Refresh at ttl/2 of the credentials in use, for as long as the call
    /// runs; a failed refresh retries a minute later with the old ones in use.
    private func scheduleCredentialRefresh() {
        credentialRefreshTask?.cancel()
        credentialRefreshTask = nil
        guard let callId = engineCallId, let cache = iceCache else { return }
        let ttl = CallIceCredentialPolicy.ttl(cache.set.ttlSeconds)
        let due = CallIceCredentialPolicy.refreshAt(fetchedAt: cache.fetchedAt.timeIntervalSince1970, ttl: ttl)
        var delay = max(1, due - Date().timeIntervalSince1970)
        credentialRefreshTask = Task { @MainActor [weak self] in
            while true {
                do { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) } catch { return }
                guard let self, !self.isShutDown, self.engineCallId == callId else { return }
                if await self.fetchIceServers() != nil || Task.isCancelled { return }
                delay = CallIceCredentialPolicy.retryAfterFailure
            }
        }
    }

    private func stopCredentialRefresh() {
        credentialRefreshTask?.cancel()
        credentialRefreshTask = nil
        engineCallId = nil
        engineIce = nil
        engineTurnKnown = false
    }

    // MARK: - Network ladder and outgoing video

    private var currentMediaProfile: CallMediaProfile {
        let current = network.current
        let path = current.satisfied ? current : (lastSatisfiedPath ?? current)
        return CallMediaLadder.profile(for: path, saving: dataSaving)
    }

    private func handleNetworkPath(_ path: CallNetworkPath) {
        guard !isShutDown, generation == apiGeneration else { return }
        let switched = CallNetworkPath.interfaceChanged(from: lastSatisfiedPath, to: path)
        if path.satisfied { lastSatisfiedPath = path }
        guard reducerState.activeCallId != nil, !state.isTerminal else { return }
        // A consent loss that follows is the network's doing, not the pair's.
        if switched || !path.satisfied {
            let now = Self.uptime
            relayFallback.networkChanged(at: now)
            deadPair.noteOutage(at: now)
        }
        updateMediaProfile(networkChanged: switched)
        guard switched, state.isInCallMedia else { return }
        CallDiagnostics.record(.networkChange, phase: 120, ["network": CallDiagnostics.interfaceCode(path.interface)])
        // Wi-Fi <-> cellular: the old candidates are gone; restart now rather
        // than wait for ICE to notice.
        beginRecoveryIfNeeded()
        recovery.interfaceChanged(at: Self.uptime)
        scheduleRecovery()
        if let decision = videoPause.networkChanged(at: Self.uptime) {
            recordVideoPause(decision, quality.details.metrics, cause: 2)
            applyVideoPause(decision)
        }
    }

    /// The ladder row follows the network and "Use less data" live.
    private func updateMediaProfile(networkChanged: Bool) {
        guard let callId = engineCallId, callId == reducerState.activeCallId else { return }
        let profile = currentMediaProfile
        guard profile != mediaProfile || networkChanged else { return }
        let tierChanged = profile.tier != mediaProfile?.tier
        mediaProfile = profile
        // A new path or row: the old estimate measured another link.
        let seed = networkChanged || tierChanged ? CallBitrateSeedPolicy.seed(.newPath, profile: profile, link: nil) : nil
        engine.applyMediaProfile(profile, seedBitrate: seed)
    }

    private func handleQualityMetrics(_ metrics: CallQualityMetrics) {
        guard !isShutDown, reducerState.activeCallId != nil, let controls = audioControls else { return }
        // The camera's intent and capture, never the self-view's first frame:
        // that frame is reported only while a self-view is mounted (not with
        // the call collapsed), and gating on it reset every pause there. A
        // camera that is still starting is not held back by bandwidth, so it
        // is never paused; an interrupted one keeps its pause.
        let substantialLoss: Bool = (metrics.incomingLoss ?? 0) > 0.2 || (metrics.outgoingLoss ?? 0) > 0.2
        let substantialConcealment: Bool = (metrics.concealment ?? 0) > 0.2
        if connectedAt != nil, substantialLoss || substantialConcealment {
            diagnosticMediaProblem = true
        }
        let sending = state.isInCallMedia && controls.videoMode == .video && controls.localVideoEnabled
        let connected: Bool = { if case .connected = state { return true }; return false }()
        let now = Self.uptime
        let estimate = connected ? metrics.availableOutgoingKbps : nil
        lastLink = (now, CallBitrateSeedPolicy.Link(rttMs: metrics.rttMs, outgoingLoss: metrics.outgoingLoss,
                                                    availableKbps: estimate))
        // The reducer is authoritative: a camera toggle, a switch to audio or
        // the peer's full audio downgrade already ended the pause and ordered
        // its resume after camera teardown. Only reset policy/diagnostics here:
        // resuming synchronously would bypass that ordered teardown. Video that
        // stopped with it is 116 status 3; a camera turned back on is not recorded.
        if videoPause.paused && !controls.outgoingVideoPaused, videoPause.reset() != nil {
            if !sending { recordVideoPause(.resume, metrics, cause: 3) }
        }
        // The round trip and our outgoing loss are the congestion evidence a
        // low estimate needs before it pauses anything.
        if let decision = videoPause.update(now: now, availableKbps: estimate,
                                            limitedByBandwidth: metrics.qualityLimitationReason == "bandwidth",
                                            rttMs: metrics.rttMs, outgoingLoss: metrics.outgoingLoss,
                                            sending: sending,
                                            captureInterrupted: reducerState.localCaptureInterrupted) {
            recordVideoPause(decision, metrics, cause: sending ? 1 : 3)
            applyVideoPause(decision)
        }
        if connected { checkDeadPair(now: now) }
        recordMediaDiagnostics(metrics, connected: connected, cameraOn: sending, now: now)
    }

    private func applyVideoPause(_ decision: CallVideoPausePolicy.Decision) {
        guard let callId = reducerState.activeCallId else { return }
        apply(.outgoingVideoPaused(callId: callId, paused: decision == .pause))
    }

    /// Where the bandwidth estimate restarts (`CallBitrateSeedPolicy`),
    /// judged on the last stats read when it is recent (a read older than the
    /// pause policy's sample gap says nothing about the link now).
    private func bitrateSeed(_ cause: CallBitrateSeedPolicy.Cause) -> Int? {
        guard let profile = mediaProfile else { return nil }
        let recent = lastLink.flatMap { Self.uptime - $0.at <= CallVideoPausePolicy.sampleGap ? $0.link : nil }
        return CallBitrateSeedPolicy.seed(cause, profile: profile, link: recent)
    }

    /// Only the pause policy's resume seeds sending here. A camera-on seeds
    /// where capture restarts (`engineSetVideoEnabled`); camera-off and audio
    /// teardown need no seed. A new network row already seeds its estimate.
    private func resumeSeed(after event: CallEvent) -> Int? {
        switch event {
        case .outgoingVideoPaused: return bitrateSeed(.retry)
        default: return nil
        }
    }

    /// A pair that passed DTLS but carries none of the peer's voice, or
    /// refuses our own sends (`CallDeadPairPolicy`): the caller restarts now
    /// rather than after ICE's consent timeout, 5-6 s after connect
    /// (`mediaStalled`; never a consent loss for the relay fallback, never a
    /// failed call). Silence is judged only when the description we last
    /// applied asked the peer for no DTX (the row can change without a
    /// renegotiation; unknown is not judged); refused sends with no byte
    /// growth are judged on every profile.
    /// Diagnostics: operation 2, phase 118, `status` 1 this side restarts on
    /// the peer's silence, 2 on its own refused sends (0 seen only: the
    /// callee, or a peer that does not resolve crossing offers), `count` the
    /// voice packets on the pair, `late_frames` our sends it refused,
    /// `duration_ms` how long it was watched, `items` the restart rounds so
    /// far.
    private func checkDeadPair(now: TimeInterval) {
        guard let read = quality.iceReads.window(at: now)?.newest,
              deadPair.update(now: now, pairId: read.pairId, dtlsConnected: read.dtlsConnected,
                              audioPacketsReceived: read.audioPacketsReceived,
                              packetsDiscardedOnSend: read.pairPacketsDiscardedOnSend,
                              pairBytesSent: read.pairBytesSent,
                              voiceExpected: engine.localAudioRequestsDtx() == false) else { return }
        diagnosticMediaProblem = true
        beginRecoveryIfNeeded()
        let restarts = recovery.mediaStalled(at: now)
        CallDiagnostics.record(.recovery, phase: 118, [
            "status": restarts ? (deadPair.sendsRefused ? 2 : 1) : 0,
            "count": deadPair.packets,
            "late_frames": deadPair.refused,
            "duration_ms": deadPair.watched * 1000,
            "items": Double(recovery.negotiations),
        ])
        if restarts { scheduleRecovery() }
    }

    /// The outgoing video at a pause (115) or a resume (116), and at each
    /// step of its encoded size (117): `items` the upload estimate in kbps,
    /// `budget_ms` the round trip, `count` our outgoing loss in %, `reason`
    /// what holds the encoder back (0 nothing, 1 CPU, 2 bandwidth, 3 other),
    /// `width`/`height` the encoded size.
    private func recordMediaState(phase: Double, _ metrics: CallQualityMetrics, _ extra: [String: Double?] = [:]) {
        guard CallDiagnostics.isEnabled else { return }
        var values: [String: Double?] = [
            "items": metrics.availableOutgoingKbps,
            "budget_ms": metrics.rttMs,
            "count": metrics.outgoingLoss.map { $0 * 100 },
            "reason": CallMediaDiagnosticsPolicy.limitationCode(metrics.qualityLimitationReason),
            "width": metrics.videoOutWidth,
            "height": metrics.videoOutHeight,
        ]
        values.merge(extra) { _, new in new }
        CallDiagnostics.record(.recovery, phase: phase, values)
    }

    /// A pause (115, `attempt` the pauses in this backoff: 1 retries after
    /// 10 s) or a resume (116, `status` 1 the timed retry, 2 a network
    /// change, 3 video sending stopped: the call went audio-only or the
    /// camera was turned off).
    private func recordVideoPause(_ decision: CallVideoPausePolicy.Decision, _ metrics: CallQualityMetrics, cause: Double) {
        switch decision {
        case .pause: recordMediaState(phase: 115, metrics, ["attempt": Double(videoPause.failedTries)])
        case .resume: recordMediaState(phase: 116, metrics, ["status": cause])
        }
    }

    /// From the 1 Hz read, no timer of its own (`CallMediaDiagnosticsPolicy`):
    /// each step of the encoded size (117), and the playout delay (119)
    /// every 10 s while connected and 3 s and 8 s after the camera turns on:
    /// `status` 0 periodic, 1 and 2 the camera marks, `budget_ms` the peer's
    /// voice in its jitter buffer (mean ms), `max_gap_ms` the delay that
    /// buffer aims for (lip-sync raises it toward the video's), `gap_ms` the
    /// peer's picture from first packet to decode, `skew_ms` video minus
    /// audio (left out when the voice waits longer), `duration_ms` the round
    /// trip, `items` the upload estimate in kbps, `width`/`height` our
    /// encoded size, `count` the delay lip-sync adds to the voice (ms),
    /// `frames` our audio packets a second, `late_frames` the peer's (arrived
    /// or lost), `code` the peer's samples dropped to catch up (per mille).
    /// Beside each of those, the video sender (121) and the receiver and
    /// transport (122). And the audio path once, on the first connected read
    /// (phase 147 `status` 3, `CallAudioPathDiagnostics`).
    private func recordMediaDiagnostics(_ metrics: CallQualityMetrics, connected: Bool, cameraOn: Bool, now: TimeInterval) {
        // Followed with diagnostics off too, so the camera marks are timed
        // from the camera, never from diagnostics being turned on.
        let recording = CallDiagnostics.isEnabled
        mediaDiagnostics.noteCamera(cameraOn, at: now, recording: recording)
        guard recording else { return }
        if mediaDiagnostics.audioPathDue(connected: connected) { CallAudioPathDiagnostics.record(status: 3) }
        let sizeStep = mediaDiagnostics.sizeStep(width: metrics.videoOutWidth, height: metrics.videoOutHeight)
        if sizeStep { recordMediaState(phase: 117, metrics) }
        let audio = metrics.audioPlayoutDelayMs
        let video = metrics.videoPlayoutDelayMs
        let playout = mediaDiagnostics.playout(now: now, connected: connected, measured: audio != nil || video != nil)
        if let status = playout {
            CallDiagnostics.record(.recovery, phase: 119, [
                "status": status,
                "budget_ms": audio,
                "max_gap_ms": metrics.audioTargetDelayMs,
                "gap_ms": video,
                "skew_ms": video.flatMap { v in audio.map { v - $0 } },
                "duration_ms": metrics.rttMs,
                "items": metrics.availableOutgoingKbps,
                "width": metrics.videoOutWidth,
                "height": metrics.videoOutHeight,
                "count": metrics.avSyncDelayMs,
                "frames": metrics.audioPacketsOutPerSecond,
                "late_frames": metrics.audioPacketsInPerSecond,
                "code": metrics.audioAcceleratedPerMille,
            ])
        }
        guard sizeStep || playout != nil else { return }
        recordVideoSender(metrics)
        recordReceiver(metrics)
    }

    /// Phase 121, the video sender, over the grade's window (about 4 s):
    /// `width`/`height`/`fps` encoded, `maximum_fps` the camera's rate,
    /// `code` the mean QP, `budget_ms` encode ms per frame, `items` the
    /// encoder's target in kbps, `reason` what holds it back (as in 115),
    /// `duration_ms` ms held back by bandwidth, `count` resolution changes,
    /// `frames` key frames encoded, `late_frames` key frames the peer asked
    /// for, `gap_ms` ms each packet waited in the pacer, `status` the codec
    /// (`CallMediaDiagnosticsPolicy.videoCodecCode`, H.264 level included).
    /// 14 keys with `operation` and `phase` making 16, the most an event may
    /// carry. None without outgoing video.
    private func recordVideoSender(_ m: CallQualityMetrics) {
        guard m.videoOutWidth != nil || m.videoOutTargetKbps != nil || m.videoOutCodecCode != nil else { return }
        CallDiagnostics.record(.recovery, phase: 121, [
            "width": m.videoOutWidth,
            "height": m.videoOutHeight,
            "fps": m.videoOutFps,
            "maximum_fps": m.cameraFps,
            "code": m.videoOutQp,
            "budget_ms": m.videoEncodeMs,
            "items": m.videoOutTargetKbps,
            "reason": CallMediaDiagnosticsPolicy.limitationCode(m.qualityLimitationReason),
            "duration_ms": m.videoBandwidthLimitedMs,
            "count": m.videoResolutionChanges,
            "frames": m.videoKeyFramesEncoded,
            "late_frames": m.videoKeyFrameRequestsIn,
            "gap_ms": m.videoPacerMs,
            "status": m.videoOutCodecCode,
        ])
    }

    /// Phase 122, the peer's picture and the transport, over the same
    /// window: `width`/`height`/`fps` decoded, `status` its codec, `budget_ms`
    /// decode ms per frame, `frames` frames dropped, `late_frames` key frames
    /// this side asked for, `gap_ms` the standard deviation of the gaps
    /// between decoded frames, `duration_ms` the STUN round trip,
    /// `max_gap_ms` the RTCP one, `items` the upload estimate in kbps,
    /// `bytes` our video retransmitted, `thermal` the device's thermal state.
    private func recordReceiver(_ m: CallQualityMetrics) {
        CallDiagnostics.record(.recovery, phase: 122, [
            "width": m.videoInWidth,
            "height": m.videoInHeight,
            "fps": m.videoInFps,
            "status": m.videoInCodecCode,
            "budget_ms": m.videoDecodeMs,
            "frames": m.videoFramesDropped,
            "late_frames": m.videoKeyFrameRequestsOut,
            "gap_ms": m.videoInterFrameStddevMs,
            "duration_ms": m.stunRttMs,
            "max_gap_ms": m.rtcpRttMs,
            "items": m.availableOutgoingKbps,
            "bytes": m.videoRetransmittedBytes,
            "thermal": m.thermalState,
        ])
    }

    private func requireSession() throws {
        try Task.checkCancellation()
        guard !isShutDown, generation == apiGeneration else { throw CancellationError() }
    }

    private var apiGeneration: UInt64 { api.currentSessionGeneration }
}

/// Call diagnostics beyond the call phase, for people who turned diagnostics
/// on. Numbers only: never addresses, ids or names. Kind `call_state`; the
/// `operation` tells the event apart (1 ICE state, 2 recovery step 110-113,
/// the relay fallback 114, outgoing video paused 115 and resumed 116, its
/// encoded size 117, a dead pair 118, the playout delay 119, the video
/// sender 121 and the receiver and transport 122, 3 network change, 4 a
/// renderer's first and later video frame, 5 ICE detail: the selected pair's
/// STUN traffic 140, candidates 141-142, TURN servers applied 143, the first
/// RTP packet of each kind 144, the first voice played 145, CallKit's audio
/// activation 146, the audio path 147, the audio negotiation 148, pair
/// switches 150-159, connection state 160-165, the call's end 170)
/// and its phases start at 100, apart from the call phases 0-7 (codes in
/// docs/ANDROID_PORT_FROM_IOS.md). Only metric keys the server and the
/// watcher accept, at most 16 per event: an unknown key makes the server
/// reject the whole snapshot.
enum CallDiagnostics {
    enum Operation: Double {
        case iceState = 1, recovery, networkChange, firstFrame, iceDetail
    }

    static var isEnabled: Bool {
        #if os(iOS)
        return PerformanceRecorder.shared.isEnabled
        #else
        return false
        #endif
    }

    /// `nil`, negative and non-finite values are left out.
    static func record(_ operation: Operation, phase: Double, _ values: [String: Double?] = [:]) {
        #if os(iOS)
        guard PerformanceRecorder.shared.isEnabled else { return }
        var metrics: [String: Double] = ["operation": operation.rawValue, "phase": phase]
        for (key, value) in values {
            if let value, value.isFinite, value >= 0 { metrics[key] = value }
        }
        PerformanceRecorder.shared.record(.callState, metrics)
        #endif
    }

    /// A candidate type: host 1, srflx 2, prflx 3, relay 4, anything else 0.
    static func candidateCode(_ type: String?) -> Double {
        switch type {
        case "host": return 1
        case "srflx": return 2
        case "prflx": return 3
        case "relay": return 4
        default: return 0
        }
    }

    /// WebRTC's name for the candidate's interface: wifi 1, cellular (any
    /// generation) 2, ethernet 3, vpn 4, anything else 0.
    static func networkCode(_ type: String?) -> Double {
        guard let type = type?.lowercased() else { return 0 }
        if type == "wifi" { return 1 }
        if type.hasPrefix("cellular") { return 2 }
        if type == "ethernet" { return 3 }
        if type == "vpn" { return 4 }
        return 0
    }

    /// The device's path after a switch: wifi 1, cellular 2, wired 3, other 4.
    static func interfaceCode(_ interface: CallNetworkInterface?) -> Double {
        switch interface {
        case .wifi: return 1
        case .cellular: return 2
        case .wired: return 3
        case .other, nil: return 4
        }
    }

    /// Growth of a cumulative counter; nil when either read is missing or it
    /// went backwards (a new pair).
    static func delta(_ a: Double?, _ b: Double?) -> Double? {
        guard let a, let b, b >= a else { return nil }
        return b - a
    }
}

/// Lets exactly one of several racing main-actor tasks through.
@MainActor
private final class CallOnceGate {
    private var passed = false
    func pass() -> Bool {
        guard !passed else { return false }
        passed = true
        return true
    }
}

private extension CallUiState {
    var isTerminal: Bool {
        switch self {
        case .ended, .failed: return true
        default: return false
        }
    }
    var isBusy: Bool {
        switch self {
        case .incomingRinging, .outgoingRinging, .connecting, .connected, .reconnecting: return true
        default: return false
        }
    }
}

extension CallController {
    /// This call's DTLS fingerprints; not published, so nothing re-renders
    /// for them. One input of `verificationTranscript`.
    func dtlsFingerprints(callId: String) -> CallDtlsFingerprints? {
        guard !isShutDown, reducerState.activeCallId == callId, engineCallId == callId, state.isInCallMedia else { return nil }
        return engine.dtlsFingerprints()
    }

    /// Read on demand; absent until the commitment exchange completed and
    /// its two fingerprints still match the active peer connection.
    func verificationTranscript(callId: String) -> CallVerificationTranscript? {
        guard let fingerprints = dtlsFingerprints(callId: callId) else { return nil }
        return callSAS.transcript(callID: callId, fingerprints: fingerprints)
    }
}
