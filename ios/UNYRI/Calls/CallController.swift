import AVFoundation
import Combine
import Foundation

/// Single app-scoped controller owning the entire lifecycle of "the current
/// call" (mirror of Android's `CallController`, Этап J п.28). The UI observes
/// `state` only; nothing else touches the engine/API/signaling seams.
///
/// Account-scoped: created per `AccountServices`, captures the session
/// generation, and aborts every callback when it no longer matches — a stale
/// old-account event can never mutate the new account's call state.
@MainActor
final class CallController: ObservableObject {
    var currentSessionGeneration: UInt64 { api.currentSessionGeneration }
    var incomingCallAllowed: (String) -> Bool = { _ in true }
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
        }
    }
    /// When the current call first reached `.connected` — drives the in-call
    /// timer and the minimized active-call banner. Survives a transient
    /// `.reconnecting` (only set on the connecting -> connected transition).
    @Published private(set) var connectedAt: Date?
    @Published var startBlockedMessage: String?

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

    private var reducerState = CallReducerState()
    // Reuse the native call adapter's bounded terminal policy. A local decline
    // wins over late WS duplicates and a still-ringing REST recovery response.
    private var terminalCalls = CallKitReportTracker()
    private var cancellables = Set<AnyCancellable>()
    private var keepAliveTask: Task<Void, Never>?
    private var upgradeTimeoutTask: Task<Void, Never>?
    private var reconcileTask: Task<Void, Never>?
    private var incomingRecoveryTask: Task<Void, Never>?
    private var iceRestartTask: Task<Void, Never>?
    private var iceRestartAttempts = 0
    private var reconcileGeneration: UInt64 = 0
    private var lastConnected: Bool = false
    private var isShutDown = false
    private var creatingCall = false
    private var acceptingCallID: String?
    private let effectQueue = CallEffectSerialQueue()
    private var ringbackPlayer: AVAudioPlayer?
    private var callAudioActive = false
    private var automaticSpeaker = CallAutomaticSpeakerPolicy()
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
        cancelIceRestart()
        engine.dispose()
        reducerState = CallReducerState()
        state = .idle
        connectedAt = nil
    }

    private static let keepAliveIntervalMs: UInt64 = 10_000
    private static let videoUpgradeTimeoutMs: UInt64 = 25_000

    init(api: CallsRepository, signaling: CallSignalingClient, engine: WebRTCEngine, generation: UInt64) {
        self.api = api
        self.signaling = signaling
        self.engine = engine
        self.generation = generation

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
    }

    // MARK: - User actions

    func startCall(remoteUserId: String, remoteDisplayName: String, mediaKind: CallMediaKind) async {
        guard !isShutDown, generation == apiGeneration else { return }
        if reportBlockedStart() { return }
        creatingCall = true
        defer { creatingCall = false }
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
            apply(.startCall(info))
            // An exceptionally fast peer can accept before POST /calls has
            // returned our call id, when the WS event cannot be correlated.
            // Re-read once after binding the id; ordinary events and this
            // authoritative response converge through the same reducer path.
            reconcileAfterReconnect()
        } catch is CallMediaPermissions.PermissionError {
            apply(.createFailed(.generic))
        } catch {
            guard !isShutDown, generation == apiGeneration else { return }
            let reason: CallFailureReason
            if case APIError.server(code: "conflict", message: "busy") = error { reason = .busy }
            else if case APIError.network = error { reason = .network }
            else { reason = .generic }
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

    func cancelOutgoing() async {
        guard case .outgoingRinging(let call) = state else { return }
        apply(.remoteEnded(callId: call.callId, reason: "cancelled"))
        Task {
            guard let id = UUID(uuidString: call.callId) else { return }
            do { _ = try await api.perform(.cancel, callId: id) }
            catch { _ = try? await api.perform(.hangup, callId: id) }
        }
    }

    func acceptIncoming(until deadline: Date? = nil) async {
        func requireAnswer() throws {
            try requireSession()
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
        apply(.remoteEnded(callId: call.callId, reason: "rejected"))
        Task { _ = try? await api.perform(.reject, callId: UUID(uuidString: call.callId) ?? UUID()) }
    }

    func hangup() async {
        guard let call = state.callInfo, !state.isTerminal else { return }
        apply(.remoteEnded(callId: call.callId, reason: "hangup"))
        Task { _ = try? await api.perform(.hangup, callId: UUID(uuidString: call.callId) ?? UUID()) }
    }

    func failCall(callId: String) {
        guard reducerState.activeCallId == callId else { return }
        apply(.engineConnectionState(callId: callId, state: .failed))
    }

    var audioControls: ConnectedState? { reducerState.audioControls }
    var videoPresentation: ConnectedState? {
        guard let c = audioControls, c.videoMode == .video else { return nil }
        if case .reconnecting = state { return CallStateReducer.restoredAfterReconnect(c) }
        return c
    }

    /// CallKit owns activation. Never activate a second competing audio session.
    func setCallAudioActive(_ active: Bool) {
        callAudioActive = active && !isShutDown
        updateRingback()
    }

    private func updateRingback() {
        guard CallRingbackPolicy.shouldPlay(state, audioActive: callAudioActive) else {
            ringbackPlayer?.stop()
            ringbackPlayer = nil
            return
        }
        guard ringbackPlayer?.isPlaying != true else { return }
        do {
            let player = try AVAudioPlayer(data: ringbackData)
            player.numberOfLoops = -1
            player.volume = 0.7
            if player.play() { ringbackPlayer = player }
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

    private func handleAudioRouteChanged(_ route: AudioRoute, userInitiated: Bool = false) {
        guard let controls = audioControls else { return }
        if !userInitiated, controls.audioRoute == route.kind,
           reducerState.selectedAudioRoute == nil || reducerState.selectedAudioRoute?.kind == route.kind { return }
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
                guard self.engine.localVideoRunning() || self.engine.startLocalVideo() else {
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
                if self.engine.localVideoRunning() || self.engine.startLocalVideo() {
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
    func switchCamera() { engine.switchCamera() }
    func hasMultipleCameras() -> Bool { engine.hasMultipleCameras() }
    func attachPictureInPictureRenderer(_ renderer: Any?) { engine.attachPictureInPictureRenderer(renderer) }
    func detachPictureInPictureRenderer(_ renderer: Any?) { engine.detachPictureInPictureRenderer(renderer) }
    func attachLocalRenderer(_ renderer: Any?) { engine.attachLocalRenderer(renderer) }
    func attachRemoteRenderer(_ renderer: Any?) { engine.attachRemoteRenderer(renderer) }
    func detachLocalRenderer(_ renderer: Any?) { engine.detachLocalRenderer(renderer) }
    func detachRemoteRenderer(_ renderer: Any?) { engine.detachRemoteRenderer(renderer) }

    func acknowledgeEnded() {
        if state.isTerminal {
            cancelIceRestart()
            reducerState = CallReducerState()
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
            guard incomingCallAllowed(callId), !terminalCalls.isTerminal(callId), CallIncomingRecoveryPolicy.validIncomingIdentity(callId: callId, callerUserId: callerUserId, owner: api.ownerId) else { return }
            if externalMediaBusy() {
                if let id = UUID(uuidString: callId) { Task { _ = try? await api.perform(.reject, callId: id) } }
                return
            }
            guard !state.isBusy else { return }
            interruptExternalCapture()
            let kind: CallMediaKind = mediaKind == "video" ? .video : .audio
            let info = CallInfo(callId: callId, remoteUserId: callerUserId,
                                remoteDisplayName: "Incoming call", mediaKind: kind, direction: .incoming)
            reducerState = CallReducerState()
            state = .incomingRinging(info)
            reducerState.ui = .incomingRinging(info)
            reducerState.activeCallId = callId
            startIncomingRecovery(callId)
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
            switch s {
            case .disconnected:
                // Only a Connected -> Disconnected transition starts the
                // bounded restart loop (Android parity: duplicate DISCONNECTED
                // while already Reconnecting is ignored).
                let shouldRestart: Bool = { if case .connected = state { return true }; return false }()
                apply(.engineConnectionState(callId: callId, state: s))
                if shouldRestart { startIceRestart(callId) }
            case .connected, .failed:
                cancelIceRestart()
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
            apply(.engineLocalCaptureInterrupted(callId: callId, interrupted: interrupted))
        case .localFirstFrame:
            guard let callId = reducerState.activeCallId else { return }
            apply(.engineLocalFirstFrame(callId: callId))
        case .localCameraFailed(let cameraGeneration):
            guard engine.isCurrentCameraGeneration(cameraGeneration), let callId = reducerState.activeCallId else { return }
            engine.setVideoEnabled(false)
            apply(.localVideoFailed)
            signaling.sendVideoState(callId: callId, enabled: false)
        case .localCameraFacingChanged(let cameraGeneration, let isFrontFacing):
            guard engine.isCurrentCameraGeneration(cameraGeneration) else { return }
            apply(.localCameraFacingChanged(isFrontFacing))
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

    private func reconcileAfterReconnect() {
        guard !isShutDown, generation == apiGeneration else { return }
        guard let callId = reducerState.activeCallId else {
            recoverMissedIncomingCall()
            return
        }
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

    private func apply(_ event: CallEvent) {
        guard !isShutDown, generation == apiGeneration else { return }
        let wasConnecting: Bool = {
            if case .connecting = reducerState.ui { return true }
            return false
        }()
        let (newState, reducedEffects) = CallStateReducer.reduce(reducerState, event)
        var effects = reducedEffects
        if let speaker = automaticSpeaker.update(event: event, after: newState) {
            effects.append(.engineSetSpeaker(speaker))
        }
        reducerState = newState
        if newState.ui.isTerminal, let call = newState.ui.callInfo { terminalCalls.completeCall(call.callId) }
        state = newState.ui
        if !CallIncomingRecoveryPolicy.matches(newState.ui, callId: newState.activeCallId ?? "") { stopIncomingRecovery() }
        updateRingback()
        // Duration anchor: only the connecting -> connected transition starts
        // the timer, so mute/camera/route/reconnect changes never reset it.
        if wasConnecting, case .connected = newState.ui, connectedAt == nil {
            connectedAt = Date()
        }
        if newState.ui.isTerminal { connectedAt = nil }
        guard !effects.isEmpty else { return }
        // Audio buttons must not wait behind ICE configuration/network requests.
        var pendingEffects = effects.filter { effect in
            switch effect {
            case .engineSetMuted(let muted): engine.setMuted(muted); return false
            case .engineSetSpeaker(let on): engine.setSpeakerOn(on); return false
            case .engineSetAudioRoute(let route): engine.setAudioRoute(route); return false
            case .engineSetVideoNegotiationEnabled(let enabled): engine.setVideoNegotiationEnabled(enabled); return false
            case .dismissIncomingPush(let callId): onDismissIncomingPush(callId); return false
            case .engineStopLocalVideo:
                // A peer's full SDP downgrade withdraws video immediately;
                // never leave capture running behind an awaited SDP/API job.
                if case .remoteOffer = event { engine.stopLocalVideo(); return false }
                return true
            default: return true
            }
        }
        if effects.contains(.engineDispose) {
            // Local teardown cannot wait behind a slow HTTP/SDK operation.
            effectQueue.cancel()
            cancelIceRestart()
            keepAliveTask?.cancel(); keepAliveTask = nil
            upgradeTimeoutTask?.cancel(); upgradeTimeoutTask = nil
            engine.dispose()
            pendingEffects.removeAll { $0 == .engineDispose || $0 == .stopKeepAlive || $0 == .cancelVideoUpgradeTimeout }
        }
        guard !pendingEffects.isEmpty else { return }
        let batch = pendingEffects
        let callId = newState.activeCallId
        // Effects are ordered (e.g. initialize the engine before producing an
        // offer, start local video before sending an upgrade request); execute
        // them sequentially, never concurrently.
        effectQueue.enqueue { [weak self] in
            guard let self else { return }
            for effect in batch {
                guard !Task.isCancelled, !self.isShutDown, self.generation == self.apiGeneration,
                      callId == nil || self.reducerState.activeCallId == callId else { return }
                if !(await self.execute(effect)) { return }
            }
        }
    }

    private func execute(_ effect: CallEffect) async -> Bool {
        switch effect {
        case .restCreate(let remoteUserId, let mediaKind): break // handled by startCall directly
        case .restAccept(let callId):
            _ = try? await api.perform(.accept, callId: UUID(uuidString: callId) ?? UUID())
        case .restReject(let callId):
            _ = try? await api.perform(.reject, callId: UUID(uuidString: callId) ?? UUID())
        case .restCancel(let callId):
            _ = try? await api.perform(.cancel, callId: UUID(uuidString: callId) ?? UUID())
        case .restHangup(let callId):
            _ = try? await api.perform(.hangup, callId: UUID(uuidString: callId) ?? UUID())
        case .restFailed(let callId):
            _ = try? await api.perform(.failed, callId: UUID(uuidString: callId) ?? UUID())
        case .restConnected(let callId):
            _ = try? await api.perform(.connected, callId: UUID(uuidString: callId) ?? UUID())
        case .restGet(let callId):
            break // handled by reconcileAfterReconnect
        case .engineInitialize(let mediaKind):
            let callId = reducerState.activeCallId
            do {
                let servers = try await api.iceServers()
                try requireSession()
                guard !Task.isCancelled, !isShutDown,
                      let callId, callId == reducerState.activeCallId else { return false }
                engine.initialize(iceServers: servers, mediaKind: mediaKind)
                restoreAudioControls()
                apply(.localCameraFacingChanged(engine.localCameraIsFrontFacing()))
                if mediaKind == .video && !engine.localVideoRunning() {
                    apply(.localVideoFailed)
                }
            } catch {
                if let callId { failCall(callId: callId) }
                return false
            }
        case .engineSetRemoteDescription(let callId, let type, let sdp):
            do { try await engine.setRemoteDescription(type: type, sdp: sdp) }
            catch { apply(.mediaNegotiationFailed(callId: callId)); return false }
        case .engineAddIce(let callId, let candidate, let sdpMid, let index):
            engine.addIceCandidate(candidate: candidate, sdpMid: sdpMid, sdpMLineIndex: index)
        case .engineSetMuted(let muted): engine.setMuted(muted)
        case .engineSetSpeaker(let on): engine.setSpeakerOn(on)
        case .engineSetAudioRoute(let route): engine.setAudioRoute(route)
        case .engineSetVideoEnabled(let enabled):
            // An older queued camera-on must not undo a newer camera-off or
            // the peer's full audio downgrade in the same call.
            if !enabled || reducerState.audioControls?.localVideoEnabled == true { engine.setVideoEnabled(enabled) }
        case .engineStartLocalVideo:
            if reducerState.audioControls?.localVideoEnabled == true { _ = engine.startLocalVideo() }
        case .enginePrepareLocalVideo: _ = engine.startLocalVideo(capture: false)
        case .engineStopLocalVideo: engine.stopLocalVideo()
        case .engineSetVideoNegotiationEnabled(let enabled): engine.setVideoNegotiationEnabled(enabled)
        case .engineSwitchCamera: engine.switchCamera()
        case .engineDispose:
            cancelIceRestart()
            engine.dispose()
        case .produceOfferAndSend(let callId):
            do {
                let sdp = try await engine.createOffer()
                try requireSession()
                guard reducerState.activeCallId == callId else { return false }
                signaling.sendOffer(callId: callId, sdp: sdp)
            } catch { apply(.mediaNegotiationFailed(callId: callId)); return false }
        case .produceAnswerAndSend(let callId):
            do {
                let sdp = try await engine.createAnswer()
                try requireSession()
                guard reducerState.activeCallId == callId else { return false }
                signaling.sendAnswer(callId: callId, sdp: sdp)
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
                // Recover a lost terminal event even if the socket still
                // appears connected. Session and call ID guards reject stale replies.
                if self.reconcileTask == nil { self.reconcileAfterReconnect() }
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

    /// Bounded ICE restart (Android `CallController` DISCONNECTED handler): up
    /// to `IceRestartPolicy.maxAttempts` `restartIce()` calls across a grace
    /// period, then a terminal failure. Runs only while the call is
    /// `.reconnecting`; a recovered CONNECTED/COMPLETED state cancels it.
    private func startIceRestart(_ callId: String) {
        iceRestartTask?.cancel()
        iceRestartAttempts = 0
        iceRestartTask = Task { @MainActor [weak self] in
            guard let self else { return }
            while case .reconnecting = self.state {
                let step = IceRestartPolicy.step(attempt: self.iceRestartAttempts, isReconnecting: true)
                guard step.shouldRestart else {
                    if step.shouldFail { self.apply(.engineConnectionState(callId: callId, state: .failed)) }
                    return
                }
                self.iceRestartAttempts += 1
                self.engine.restartIce()
                // One deterministic offerer prevents simultaneous restart-offer glare.
                if self.state.callInfo?.direction == .outgoing {
                    self.apply(.restartIceOfferRequested(callId: callId))
                }
                try? await Task.sleep(nanoseconds: IceRestartPolicy.retryDelayMs * 1_000_000)
                if Task.isCancelled { return }
                guard !self.isShutDown && self.generation == self.apiGeneration else { return }
            }
        }
    }

    private func cancelIceRestart() {
        iceRestartTask?.cancel()
        iceRestartTask = nil
        iceRestartAttempts = 0
    }

    private func requireSession() throws {
        try Task.checkCancellation()
        guard !isShutDown, generation == apiGeneration else { throw CancellationError() }
    }

    private var apiGeneration: UInt64 { api.currentSessionGeneration }
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
