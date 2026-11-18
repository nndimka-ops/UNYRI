import AVFoundation
import CallKit
import Combine
import Foundation
import WebRTC

/// Thin account adapter: shares the process `CXProvider` and mirrors
/// the existing `CallController` (the single source of truth) into the native
/// call UI. It is NOT a second state machine — every CallKit action funnels
/// into an existing `CallController` operation.
///
/// Incoming calls reuse one early process provider for both authenticated
/// realtime ringing and authoritatively reconciled PushKit wake-ups.
@MainActor
final class CallKitCoordinator: NSObject, ObservableObject, CXProviderDelegate {
    static let systemCallUI = CallKitPolicy.systemCallUI
    @Published var actionError: String?
    /// The call the user just tapped, shown at once as "Calling…" while
    /// permissions, CallKit and POST /calls run (Telegram-like instant screen).
    struct StartingCall: Equatable { let remoteUserId: String; let name: String; let isVideo: Bool }
    @Published private(set) var startingCall: StartingCall? {
        didSet {
            if startingCall != nil { controller.beginOutgoingRingingIndicator() }
            else { controller.clearOutgoingRingingIndicatorIfUnbound() }
        }
    }
    private let controller: CallController
    private let generation: UInt64
    private let provider: CXProvider
    private let callKit: CXCallController

    /// Backend call id <-> CallKit UUID. Incoming: identical (backend id IS
    /// the UUID). Outgoing: CallKit generates a UUID, the backend generates
    /// its own — mapped together once `POST /calls` returns.
    private var callKitUUIDByBackendId: [String: UUID] = [:]
    private var backendIdByCallKitUUID: [UUID: String] = [:]

    private var tracker = CallKitReportTracker()
    private var pendingOutgoing: (uuid: UUID, remoteUserId: String, name: String, isVideo: Bool)?
    private var cancellables = Set<AnyCancellable>()
    private var isHandlingMuteAction = false
    /// The mute CallKit shows for the current call (it starts unmuted).
    private var systemMute: (callId: String, muted: Bool)?
    private var lastState: CallUiState = .idle
    private var isShutDown = false
    private var preparingAnswerCallID: String?
    private var manualAudioActive = false
    private var outgoingStart = CallKitOutgoingStartGate()
    private var cancelledOutgoingUUID: UUID?
    private var incomingAnswer: (action: CXAnswerCallAction, task: Task<Void, Never>)?
    /// The last start or answer action (uptime), until the audio activation
    /// it leads to (phase 146).
    private var audioAction: (at: TimeInterval, answer: Bool)?
    /// Audio path records of the current call (phase 147, at most `audioPathCap`).
    private var audioPathRecords: (callId: String, count: Int)?
    private static let audioPathCap = 12

    func shutdown() {
        guard !isShutDown else { return }
        isShutDown = true
        cancellables.removeAll()
        cancelIncomingAnswer()
        deactivateManualAudio()
        pendingOutgoing = nil
        startingCall = nil
        outgoingStart = CallKitOutgoingStartGate()
        cancelledOutgoingUUID = nil
        RTCAudioSession.sharedInstance().isAudioEnabled = false
        controller.setCallAudioActive(false)
        UnyriCallKitProvider.shared.detach(self, calls: Set(callKitUUIDByBackendId.values))
        callKitUUIDByBackendId.removeAll()
        backendIdByCallKitUUID.removeAll()
        tracker.reset()
    }

    init(controller: CallController, generation: UInt64, pushSession: UnyriPushSession) {
        self.controller = controller
        self.generation = generation

        self.provider = UnyriCallKitProvider.shared.provider
        self.callKit = UnyriCallKitProvider.shared.transactions
        super.init()
        UnyriCallKitProvider.shared.attach(self, session: pushSession)

        // WebRTC follows CallKit activation, or the same explicit handoff
        // owned by this coordinator when the simulator has no system UI.
        RTCAudioSession.sharedInstance().useManualAudio = true

        // The media engine is not created while the outgoing tone is ringing.
        // Observe routing already in that phase (and across CallKit resumes).
        NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] notification in
                guard let self, !self.isShutDown, self.generation == self.apiGeneration else { return }
                let reason = (notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? NSNumber)?.uintValue
                self.controller.synchronizeAudioRoute(userInitiated: reason == AVAudioSession.RouteChangeReason.override.rawValue)
                self.noteAudioPath(status: 2, reason: reason)
            }
            .store(in: &cancellables)

        controller.$state
            .removeDuplicates()
            .sink { [weak self] newState in self?.stateChanged(newState) }
            .store(in: &cancellables)
        controller.muteChanges
            .sink { [weak self] change in self?.muteChanged(callId: change.callId, muted: change.muted) }
            .store(in: &cancellables)
    }

    func handlesSystemCall(_ uuid: UUID) -> Bool {
        Self.systemCallUI && !isShutDown && generation == apiGeneration &&
            (backendIdByCallKitUUID[uuid] != nil || pendingOutgoing?.uuid == uuid || outgoingStart.activeUUID == uuid)
    }

    func incomingSystemReportFailed(_ uuid: UUID, error: Error) {
        guard handlesSystemCall(uuid), CallKitPolicy.endAction(for: controller.state) != .none,
              let call = controller.state.callInfo, UUID(uuidString: call.callId) == uuid else { return }
        reportError(error)
        if case .incomingRinging = controller.state { controller.dismissIncomingLocally(callId: call.callId) }
        else { controller.failCall(callId: call.callId) }
    }

    // MARK: - Outgoing (chat header call button)

    var hasPendingMediaStart: Bool { pendingOutgoing != nil }

    func startOutgoing(remoteUserId: String, displayName: String, isVideo: Bool) {
        let displayName = DisplayNameSanitizer.peer(displayName, unknown: UnyriL10n.string("Unknown user"))
        guard !isShutDown, generation == apiGeneration else { return }
        // A second tap while the first is still starting is not "another call":
        // it used to hit the creating flag and claim a call was already active.
        guard startingCall == nil, pendingOutgoing == nil, outgoingStart.activeUUID == nil else { return }
        guard !controller.presentBlockedStartIfNeeded() else { return }
        let uuid = UUID()
        guard outgoingStart.begin(uuid) else { return }
        pendingOutgoing = (uuid, remoteUserId, displayName, isVideo)
        startingCall = StartingCall(remoteUserId: remoteUserId, name: displayName, isVideo: isVideo)
        actionError = nil
        Task {
            do { try await CallMediaPermissions.request(for: isVideo ? .video : .audio) }
            catch {
                let ownsAttempt = pendingOutgoing?.uuid == uuid
                if ownsAttempt { pendingOutgoing = nil; startingCall = nil }
                outgoingStart.finish(uuid)
                guard ownsAttempt, !isShutDown, generation == apiGeneration,
                      !(error is CancellationError) else { return }
                actionError = error.localizedDescription
                return
            }
            guard !isShutDown, generation == apiGeneration, pendingOutgoing?.uuid == uuid else {
                if pendingOutgoing?.uuid == uuid { pendingOutgoing = nil; startingCall = nil }
                outgoingStart.finish(uuid)
                return
            }
            if controller.presentBlockedStartIfNeeded() {
                pendingOutgoing = nil; startingCall = nil
                outgoingStart.finish(uuid)
                return
            }
            if !Self.systemCallUI {
                await startWithoutSystemUI(uuid: uuid, remoteUserId: remoteUserId, name: displayName, isVideo: isVideo)
                return
            }
            let handle = CXHandle(type: .generic, value: displayName)
            let action = CXStartCallAction(call: uuid, handle: handle)
            action.isVideo = isVideo
            action.contactIdentifier = remoteUserId
            callKit.request(CXTransaction(action: action)) { [weak self] error in
                Task { @MainActor in
                    guard let self else { return }
                    if let error {
                        guard self.pendingOutgoing?.uuid == uuid || self.outgoingStart.activeUUID == uuid else { return }
                        if self.pendingOutgoing?.uuid == uuid { self.pendingOutgoing = nil }
                        self.startingCall = nil
                        self.outgoingStart.finish(uuid)
                        self.reportError(error)
                    }
                }
            }
        }
    }

    /// End on the instant "Calling…" screen before the call exists.
    func cancelStarting() {
        guard startingCall != nil else { return }
        startingCall = nil
        if let pending = pendingOutgoing {
            pendingOutgoing = nil
            outgoingStart.finish(pending.uuid)
        } else if let active = outgoingStart.activeUUID {
            // The running start sees this and cancels the call it creates.
            cancelledOutgoingUUID = active
        }
    }

    // MARK: - CXProviderDelegate (nonisolated hops to MainActor)

    /// Resolve permissions before either the system action or manual audio
    /// activation. Both paths keep WebRTC's useManualAudio ownership.
    func answerIncoming() {
        guard !isShutDown, generation == apiGeneration,
              case .incomingRinging(let call) = controller.state,
              preparingAnswerCallID == nil else { return }
        let uuid = callKitUUIDByBackendId[call.callId]
        guard !Self.systemCallUI || uuid != nil else { return }
        preparingAnswerCallID = call.callId
        Task {
            defer { if preparingAnswerCallID == call.callId { preparingAnswerCallID = nil } }
            // Resolve first-use microphone/camera prompts before starting the
            // CallKit action deadline, just as for an outgoing video call.
            do {
                try await CallKitPolicy.prepareIncomingAnswer(for: call.mediaKind,
                    request: { try await CallMediaPermissions.request(for: $0) },
                    isCameraDenied: { error in
                        if case .cameraDenied? = error as? CallMediaPermissions.PermissionError { return true }
                        return false
                    })
            }
            catch {
                guard !isShutDown, generation == apiGeneration,
                      case .incomingRinging(let current) = controller.state,
                      current.callId == call.callId else { return }
                actionError = error.localizedDescription
                return
            }
            guard !isShutDown, generation == apiGeneration,
                  case .incomingRinging(let current) = controller.state,
                  current.callId == call.callId else { return }
            if !Self.systemCallUI {
                do {
                    try activateManualAudio(outputOverride: call.mediaKind == .video ? nil : AVAudioSession.PortOverride.none)
                } catch {
                    controller.failCall(callId: call.callId)
                    reportError(error)
                    return
                }
                await controller.acceptIncoming()
                return
            }
            guard let uuid else { return }
            callKit.request(CXTransaction(action: CXAnswerCallAction(call: uuid))) { [weak self] error in
                Task { @MainActor in if let error { self?.reportError(error) } }
            }
        }
    }

    nonisolated func providerDidReset(_ provider: CXProvider) {
        Task { @MainActor in self.handleReset() }
    }

    nonisolated func providerDidBegin(_ provider: CXProvider) {}

    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        let at = ProcessInfo.processInfo.systemUptime
        Task { @MainActor in
            self.audioAction = (at, false)
            self.performStart(action)
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        let at = ProcessInfo.processInfo.systemUptime
        Task { @MainActor in
            self.audioAction = (at, true)
            self.performAnswer(action)
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        Task { @MainActor in self.performEnd(action) }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        Task { @MainActor in self.performMute(action) }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        action.fail()
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetGroupCallAction) {
        action.fail()
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        action.fail()
    }

    nonisolated func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
        // Apple explicitly forbids fulfilling/failing an already timed-out action.
        Task { @MainActor in self.handleActionTimeout(action) }
    }

    private func handleActionTimeout(_ action: CXAction) {
        if let start = action as? CXStartCallAction, outgoingStart.activeUUID == start.callUUID {
            cancelledOutgoingUUID = start.callUUID
        } else if let answer = action as? CXAnswerCallAction, incomingAnswer?.action.uuid == answer.uuid {
            cancelIncomingAnswer(callUUID: answer.callUUID, actionTimedOut: true)
            endExpiredCall(callUUID: answer.callUUID)
        } else if let end = action as? CXEndCallAction, !end.isComplete {
            endExpiredCall(callUUID: end.callUUID)
        }
    }

    private func cancelIncomingAnswer(callUUID: UUID? = nil, actionTimedOut: Bool = false) {
        if callUUID == nil || preparingAnswerCallID.flatMap(UUID.init(uuidString:)) == callUUID { preparingAnswerCallID = nil }
        guard let pending = incomingAnswer,
              callUUID == nil || pending.action.callUUID == callUUID else { return }
        incomingAnswer = nil
        pending.task.cancel()
        // Cancelling only the Swift task leaves CallKit waiting for its answer
        // action, which can keep system call UI blocked after the call ended.
        // Apple forbids completing an action after timedOutPerforming.
        if !actionTimedOut, !pending.action.isComplete, pending.action.timeoutDate > Date() { pending.action.fail() }
    }

    private func endExpiredCall(callUUID: UUID) {
        if outgoingStart.activeUUID == callUUID && backendIdByCallKitUUID[callUUID] == nil {
            cancelledOutgoingUUID = callUUID
            return
        }
        let backendId = backendIdByCallKitUUID[callUUID] ?? callUUID.uuidString.lowercased()
        Task {
            guard !isShutDown, generation == apiGeneration,
                  CallKitPolicy.matchesActiveCall(backendId: backendId, state: controller.state),
                  CallKitPolicy.endAction(for: controller.state) != .none else { return }
            // Accept may already have committed before its response/deadline.
            // Hangup handles both ringing and accepted states idempotently;
            // Reject only handles ringing and would leave that race connected.
            await controller.hangup()
        }
    }

    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        let enteredAt = ProcessInfo.processInfo.systemUptime
        Task { @MainActor in
            guard Self.systemCallUI, !self.isShutDown, self.generation == self.apiGeneration else { return }
            self.activateAudio(audioSession, enteredAt: enteredAt)
        }
    }

    private func activateAudio(_ audioSession: AVAudioSession, enteredAt: TimeInterval) {
        let session = RTCAudioSession.sharedInstance()
        session.audioSessionDidActivate(audioSession)
        session.isAudioEnabled = true
        recordAudioActivation(enteredAt: enteredAt)
        controller.restoreAudioControls()
        controller.setCallAudioActive(true)
    }

    private func activateManualAudio(outputOverride: AVAudioSession.PortOverride?) throws {
        try UnyriCallAudioSession.configure(outputOverride: outputOverride)
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setActive(true)
        manualAudioActive = true
        activateAudio(audioSession, enteredAt: ProcessInfo.processInfo.systemUptime)
    }

    private func deactivateManualAudio() {
        guard manualAudioActive else { return }
        manualAudioActive = false
        let audioSession = AVAudioSession.sharedInstance()
        controller.setCallAudioActive(false)
        let session = RTCAudioSession.sharedInstance()
        session.isAudioEnabled = false
        do { try audioSession.setActive(false, options: .notifyOthersOnDeactivation) }
        catch { reportError(error) }
        session.audioSessionDidDeactivate(audioSession)
    }

    /// Phase 146 (`call_state` operation 5): CallKit's audio activation
    /// reached WebRTC. `duration_ms` from this delegate's call to audio
    /// enabled (the process provider's forwarding hop before it is not
    /// included), `gap_ms` since the start or answer action that led to it
    /// (`status` 1 start, 2 answer; both left out for an activation no action
    /// of ours asked for, such as the end of an interruption). Then the audio
    /// path (phase 147 `status` 1).
    private func recordAudioActivation(enteredAt: TimeInterval) {
        let action = audioAction
        audioAction = nil
        guard CallDiagnostics.isEnabled else { return }
        let now = ProcessInfo.processInfo.systemUptime
        CallDiagnostics.record(.iceDetail, phase: 146, [
            "duration_ms": (now - enteredAt) * 1000,
            "gap_ms": action.map { (now - $0.at) * 1000 },
            "status": action.map { $0.answer ? 2.0 : 1.0 },
        ])
        noteAudioPath(status: 1)
    }

    /// Phase 147 at CallKit's activation and on each route change during a
    /// call (`CallAudioPathDiagnostics`), at most `audioPathCap` a call.
    private func noteAudioPath(status: Double, reason: UInt? = nil) {
        guard CallDiagnostics.isEnabled, controller.isPrivateMediaBusy,
              let callId = controller.state.callInfo?.callId else { return }
        let used = audioPathRecords?.callId == callId ? audioPathRecords?.count ?? 0 : 0
        guard used < Self.audioPathCap else { return }
        audioPathRecords = (callId, used + 1)
        CallAudioPathDiagnostics.record(status: status, reason: reason)
    }

    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        Task { @MainActor in
            guard Self.systemCallUI, !self.isShutDown, self.generation == self.apiGeneration else { return }
            let session = RTCAudioSession.sharedInstance()
            self.controller.setCallAudioActive(false)
            session.isAudioEnabled = false
            session.audioSessionDidDeactivate(audioSession)
        }
    }

    // MARK: - Action implementations

    private func startWithoutSystemUI(uuid: UUID, remoteUserId: String, name: String, isVideo: Bool) async {
        // From here until POST /calls returns, cancelStarting must mark the
        // running attempt rather than release its gate and admit a second one.
        pendingOutgoing = nil
        defer {
            outgoingStart.finish(uuid)
            if cancelledOutgoingUUID == uuid { cancelledOutgoingUUID = nil }
            startingCall = nil
            // Backend/permission failures can leave idle or failed, with no
            // ended transition to release manually activated audio.
            if !CallKitPolicy.keepsSignalingInBackground(controller.state) { deactivateManualAudio() }
        }
        do { try activateManualAudio(outputOverride: AVAudioSession.PortOverride.none) }
        catch { reportError(error); return }
        await controller.startCall(remoteUserId: remoteUserId, remoteDisplayName: name,
                                   mediaKind: isVideo ? .video : .audio)
        guard !isShutDown, generation == apiGeneration else { return }
        if cancelledOutgoingUUID == uuid {
            if let call = controller.state.callInfo, call.direction == .outgoing, call.remoteUserId == remoteUserId {
                if case .outgoingRinging = controller.state { await controller.cancelOutgoing() }
                else if CallKitPolicy.endAction(for: controller.state) != .none { await controller.hangup() }
            }
        } else { controller.synchronizeAudioRoute() }
    }

    private func performStart(_ action: CXStartCallAction) {
        guard !isShutDown, generation == apiGeneration else { action.fail(); return }
        if let pending = pendingOutgoing, pending.uuid == action.callUUID {
            pendingOutgoing = nil
            startCall(remoteUserId: pending.remoteUserId, name: pending.name, isVideo: pending.isVideo, action: action)
        } else {
            if outgoingStart.activeUUID == action.callUUID { outgoingStart.finish(action.callUUID) }
            action.fail()
        }
    }

    private func startCall(remoteUserId: String, name: String, isVideo: Bool, action: CXStartCallAction) {
        Task {
            defer {
                outgoingStart.finish(action.callUUID)
                if cancelledOutgoingUUID == action.callUUID { cancelledOutgoingUUID = nil }
                // The real call state (ringing / failed / ended) takes over the screen.
                startingCall = nil
            }
            // The call category is set, and a leftover speaker override
            // released, before the call can ring: a video call takes the speaker
            // the moment it does (unless a headset is connected), and resetting
            // the override afterwards used to pull its ringback to the receiver.
            do { try UnyriCallAudioSession.configure(outputOverride: AVAudioSession.PortOverride.none) }
            catch {
                if !action.isComplete { action.fail() }
                reportError(error)
                return
            }
            await controller.startCall(remoteUserId: remoteUserId, remoteDisplayName: name,
                                       mediaKind: isVideo ? .video : .audio)
            guard !isShutDown, generation == apiGeneration else {
                if !action.isComplete { action.fail() }
                return
            }
            if action.isComplete || cancelledOutgoingUUID == action.callUUID {
                if let call = controller.state.callInfo, call.direction == .outgoing,
                   call.remoteUserId == remoteUserId, CallKitPolicy.endAction(for: controller.state) != .none {
                    callKitUUIDByBackendId[call.callId] = action.callUUID
                    backendIdByCallKitUUID[action.callUUID] = call.callId
                    if case .outgoingRinging = controller.state { await controller.cancelOutgoing() }
                    else { await controller.hangup() }
                }
                if !action.isComplete { action.fail() }
                return
            }
            if let call = controller.state.callInfo, call.direction == .outgoing,
               call.remoteUserId == remoteUserId, CallKitPolicy.endAction(for: controller.state) != .none {
                controller.synchronizeAudioRoute()
                let backendId = call.callId
                callKitUUIDByBackendId[backendId] = action.callUUID
                backendIdByCallKitUUID[action.callUUID] = backendId
                let update = CXCallUpdate()
                configureCapabilities(update)
                update.remoteHandle = CXHandle(type: .generic, value: call.visibleRemoteName)
                update.localizedCallerName = call.visibleRemoteName
                update.hasVideo = call.mediaKind == .video
                provider.reportCall(with: action.callUUID, updated: update)
                reportOutgoingProgress(controller.state)
                action.fulfill()
            } else {
                // Backend create failed (busy/permission/network) — roll the
                // system call back so no zombie UI remains.
                action.fail()
            }
        }
    }

    private func performAnswer(_ action: CXAnswerCallAction) {
        guard !action.isComplete else { return }
        guard action.timeoutDate > Date() else { endExpiredCall(callUUID: action.callUUID); return }
        guard !isShutDown, generation == apiGeneration else { action.fail(); return }
        if let pending = incomingAnswer {
            if pending.action.uuid != action.uuid { action.fail() }
            return
        }
        guard case .incomingRinging(let call) = controller.state,
              UUID(uuidString: call.callId) == action.callUUID else {
            action.fail()
            return
        }
        // A video call keeps (and soon takes) the speaker: resetting the
        // override here sent its first seconds to the receiver. With a headset
        // connected `nil` still releases a leftover speaker override, so the
        // answer (which reads the route) finds the headset, not the speaker.
        do { try UnyriCallAudioSession.configure(outputOverride: call.mediaKind == .video ? nil : AVAudioSession.PortOverride.none) }
        catch {
            controller.failCall(callId: call.callId)
            if !action.isComplete { action.fail() }
            reportError(error)
            return
        }
        let task = Task {
            defer {
                if incomingAnswer?.action.uuid == action.uuid { incomingAnswer = nil }
            }
            await controller.acceptIncoming(until: action.timeoutDate)
            guard !Task.isCancelled, incomingAnswer?.action.uuid == action.uuid else { return }
            guard action.timeoutDate > Date() else { handleActionTimeout(action); return }
            guard !action.isComplete else { return }
            guard !isShutDown, generation == apiGeneration else { action.fail(); return }
            if CallKitPolicy.matchesActiveCall(backendId: call.callId, state: controller.state) {
                switch controller.state {
                case .connecting, .connected, .reconnecting: action.fulfill()
                default: action.fail()
                }
            } else { action.fail() }
        }
        incomingAnswer = (action, task)
    }

    private func performEnd(_ action: CXEndCallAction) {
        guard !action.isComplete else { return }
        guard action.timeoutDate > Date() else { endExpiredCall(callUUID: action.callUUID); return }
        guard !isShutDown, generation == apiGeneration else { action.fulfill(); return }
        if outgoingStart.activeUUID == action.callUUID && backendIdByCallKitUUID[action.callUUID] == nil {
            cancelledOutgoingUUID = action.callUUID
            action.fulfill()
            return
        }
        let backendId = backendIdByCallKitUUID[action.callUUID] ?? action.callUUID.uuidString.lowercased()
        let wasAnswering = incomingAnswer?.action.callUUID == action.callUUID
        cancelIncomingAnswer(callUUID: action.callUUID)
        guard CallKitPolicy.matchesActiveCall(backendId: backendId, state: controller.state) else {
            action.fulfill()
            return
        }
        tracker.markLocallyEnded(backendId)
        UnyriCallKitProvider.shared.locallyEnded(action.callUUID, coordinator: self)
        // Complete the system End while this delegate still owns its deadline.
        // Its local teardown is queued below; no REST response is awaited.
        // Delaying fulfillment until that Task runs can let the action expire
        // after our terminal tracker has suppressed a second system End report.
        action.fulfill()
        switch CallKitPolicy.endAction(for: controller.state) {
        case .reject:
            Task {
                if !isShutDown, generation == apiGeneration,
                   CallKitPolicy.matchesActiveCall(backendId: backendId, state: controller.state) {
                    if wasAnswering { await controller.hangup() }
                    else { await controller.rejectIncoming() }
                }
            }
        case .cancel:
            Task {
                if !isShutDown, generation == apiGeneration,
                   CallKitPolicy.matchesActiveCall(backendId: backendId, state: controller.state) { await controller.cancelOutgoing() }
            }
        case .hangup:
            Task {
                if !isShutDown, generation == apiGeneration,
                   CallKitPolicy.matchesActiveCall(backendId: backendId, state: controller.state) { await controller.hangup() }
            }
        case .none:
            break
        }
    }

    private func performMute(_ action: CXSetMutedCallAction) {
        guard !isShutDown, generation == apiGeneration,
              CallKitPolicy.matchesActiveCall(backendId: backendIdByCallKitUUID[action.callUUID], state: controller.state)
        else { action.fail(); return }
        isHandlingMuteAction = true
        controller.setMuted(action.isMuted)
        isHandlingMuteAction = false
        action.fulfill()
    }

    private func handleReset() {
        guard Self.systemCallUI, !isShutDown, generation == apiGeneration else { return }
        controller.setCallAudioActive(false)
        RTCAudioSession.sharedInstance().isAudioEnabled = false
        tracker.reset()
        callKitUUIDByBackendId.removeAll()
        backendIdByCallKitUUID.removeAll()
        pendingOutgoing = nil
        let wasAnswering = incomingAnswer != nil
        let resetCallID = controller.state.callInfo?.callId
        cancelIncomingAnswer()
        if let uuid = outgoingStart.activeUUID { cancelledOutgoingUUID = uuid }
        // Terminate the current call through the existing context-aware path.
        switch CallKitPolicy.endAction(for: controller.state) {
        case .reject:
            Task {
                guard !isShutDown, generation == apiGeneration,
                      CallKitPolicy.matchesActiveCall(backendId: resetCallID, state: controller.state) else { return }
                if wasAnswering { await controller.hangup() }
                else { await controller.rejectIncoming() }
            }
        case .cancel: Task {
            guard !isShutDown, generation == apiGeneration,
                  CallKitPolicy.matchesActiveCall(backendId: resetCallID, state: controller.state) else { return }
            await controller.cancelOutgoing()
        }
        case .hangup: Task {
            guard !isShutDown, generation == apiGeneration,
                  CallKitPolicy.matchesActiveCall(backendId: resetCallID, state: controller.state) else { return }
            await controller.hangup()
        }
        case .none: break
        }
    }

    // MARK: - Controller state observation

    private func stateChanged(_ newState: CallUiState) {
        guard !isShutDown, generation == apiGeneration else { return }
        defer { lastState = newState }
        reportOutgoingProgress(newState)
        if let call = newState.callInfo,
           let uuid = callKitUUIDByBackendId[call.callId],
           let video = CallKitPolicy.hasVideo(for: newState),
           video != CallKitPolicy.hasVideo(for: lastState) {
            let update = CXCallUpdate()
            configureCapabilities(update)
            update.hasVideo = video
            provider.reportCall(with: uuid, updated: update)
        }

        switch newState {
        case .incomingRinging(let call):
            reportIncoming(call)
        case .ended(let call, let reason):
            deactivateManualAudio()
            if let uuid = UUID(uuidString: call.callId) { cancelIncomingAnswer(callUUID: uuid) }
            reportTerminal(call, reason: reason)
        case .connected(let c):
            // A mute set while ringing out or connecting may have found no
            // CallKit call to update yet; connecting is the next chance.
            if !isHandlingMuteAction { syncMuteToSystem(callId: c.call.callId, muted: c.isMuted) }
        default:
            break
        }
    }

    private func reportIncoming(_ call: CallInfo) {
        guard !tracker.isTerminal(call.callId), let uuid = UUID(uuidString: call.callId) else { return }
        if !Self.systemCallUI {
            controller.incomingCallReportCompleted(callId: call.callId, succeeded: true)
            return
        }
        if let existing = callKitUUIDByBackendId[call.callId] {
            let update = CXCallUpdate()
            configureCapabilities(update)
            update.remoteHandle = CXHandle(type: .generic, value: call.visibleRemoteName)
            update.localizedCallerName = call.visibleRemoteName
            update.hasVideo = call.mediaKind == .video
            provider.reportCall(with: existing, updated: update)
            return
        }
        callKitUUIDByBackendId[call.callId] = uuid
        backendIdByCallKitUUID[uuid] = call.callId
        let shared = UnyriCallKitProvider.shared
        if shared.adopt(uuid, coordinator: self) {
            let update = CXCallUpdate()
            configureCapabilities(update)
            update.remoteHandle = CXHandle(type: .generic, value: call.visibleRemoteName)
            update.localizedCallerName = call.visibleRemoteName
            update.hasVideo = call.mediaKind == .video
            provider.reportCall(with: uuid, updated: update)
            observeIncomingReport(uuid, callId: call.callId)
            return
        }
        if shared.hasReport(uuid) {
            callKitUUIDByBackendId[call.callId] = nil
            backendIdByCallKitUUID[uuid] = nil
            controller.failCall(callId: call.callId)
            return
        }
        // Process ownership retains pending report/end ordering even if this
        // account is detached before Apple's completion arrives.
        shared.reportIncoming(call, coordinator: self) { [weak self] error in
            Task { @MainActor in
                guard let self, !self.isShutDown, self.generation == self.apiGeneration else { return }
                if let error {
                    self.callKitUUIDByBackendId[call.callId] = nil
                    self.backendIdByCallKitUUID[uuid] = nil
                    self.tracker.completeCall(call.callId)
                    if case .incomingRinging(let current) = self.controller.state, current.callId == call.callId {
                        self.reportError(error)
                        self.controller.dismissIncomingLocally(callId: call.callId)
                    }
                }
            }
        }
        observeIncomingReport(uuid, callId: call.callId)
    }

    private func observeIncomingReport(_ uuid: UUID, callId: String) {
        UnyriCallKitProvider.shared.whenReported(uuid, coordinator: self) { [weak self] succeeded in
            Task { @MainActor in
                guard let self, !self.isShutDown, self.generation == self.apiGeneration,
                      self.callKitUUIDByBackendId[callId] == uuid else { return }
                self.controller.incomingCallReportCompleted(callId: callId, succeeded: succeeded)
            }
        }
    }

    private func reportTerminal(_ call: CallInfo, reason: CallEndReason) {
        if tracker.deferEndWhileIncomingPending(call.callId, reason: reason) { return }
        guard let uuid = callKitUUIDByBackendId[call.callId] else { return }
        // A locally completed CXEndCallAction suppresses an extra system End,
        // but must still release both maps and progress/tracker entries.
        defer {
            callKitUUIDByBackendId[call.callId] = nil
            backendIdByCallKitUUID[uuid] = nil
            tracker.completeCall(call.callId)
        }
        guard tracker.shouldReportEnded(call.callId) else { return }
        let kind = CallKitPolicy.endReasonKind(for: reason)
        guard kind != .none else { return }
        UnyriCallKitProvider.shared.reportEnd(uuid, reason: cxReason(kind))
    }

    private func muteChanged(callId: String, muted: Bool) {
        guard !isShutDown, generation == apiGeneration else { return }
        // A change made by CallKit's own mute action is already shown there.
        if isHandlingMuteAction { systemMute = (callId, muted); return }
        syncMuteToSystem(callId: callId, muted: muted)
    }

    /// CallKit mirrors the app's mute in every phase of the call (B11): a
    /// mute set before the call connected used to stay invisible in the
    /// system call UI. Only a call CallKit knows is updated, only on a change.
    private func syncMuteToSystem(callId: String, muted: Bool) {
        guard Self.systemCallUI else { return }
        let shown = systemMute?.callId == callId ? systemMute?.muted ?? false : false
        guard shown != muted, let uuid = callKitUUIDByBackendId[callId] else { return }
        systemMute = (callId, muted)
        callKit.request(CXTransaction(action: CXSetMutedCallAction(call: uuid, muted: muted))) { [weak self] error in
            guard error != nil else { return }
            Task { @MainActor [weak self] in
                // Not applied: the next change or the connect tries again.
                if self?.systemMute?.callId == callId { self?.systemMute = nil }
            }
        }
    }

    private func configureCapabilities(_ update: CXCallUpdate) {
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = false
    }

    private func reportOutgoingProgress(_ state: CallUiState) {
        guard let call = state.callInfo, call.direction == .outgoing,
              let uuid = callKitUUIDByBackendId[call.callId] else { return }
        switch state {
        case .outgoingRinging, .connecting, .connected, .reconnecting:
            if tracker.shouldReportConnecting(call.callId) {
                provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
            }
        default: return
        }
        if case .connected = state, tracker.shouldReportConnected(call.callId) {
            provider.reportOutgoingCall(with: uuid, connectedAt: Date())
        }
    }

    private func cxReason(_ kind: CallKitEndReasonKind) -> CXCallEndedReason {
        switch kind {
        case .remoteEnded: return .remoteEnded
        case .declinedElsewhere: return .declinedElsewhere
        case .answeredElsewhere: return .answeredElsewhere
        case .failed: return .failed
        case .unanswered: return .unanswered
        case .none: return .failed
        }
    }

    private var apiGeneration: UInt64 { controller.currentSessionGeneration }

    private func reportError(_ error: Error) {
        guard !isShutDown, generation == apiGeneration else { return }
        let details = error as NSError
        actionError = "Call could not start or connect. \(details.localizedDescription) (\(details.domain): \(details.code))"
    }
}

/// Phase 147 (`call_state` operation 5): the audio path a call runs on, from
/// the shared audio session. `status` 1 at CallKit's activation, 2 a route
/// change (`reason` its `AVAudioSession.RouteChangeReason`), 3 the call's
/// first connected stats read, when WebRTC has configured the session (it
/// may not have at the activation); `duration_ms` the I/O buffer,
/// `budget_ms` the input latency, `max_gap_ms` the output latency, `items`
/// the sample rate in kHz, `interface` the output: receiver 1, speaker 2,
/// wired or USB 3, Bluetooth HFP 4, A2DP or LE 5, anything else 0. Only with
/// diagnostics on; a few property reads, on those events only.
enum CallAudioPathDiagnostics {
    static func record(status: Double, reason: UInt? = nil) {
        guard CallDiagnostics.isEnabled else { return }
        let session = AVAudioSession.sharedInstance()
        CallDiagnostics.record(.iceDetail, phase: 147, [
            "status": status,
            "reason": reason.map { Double($0) },
            "duration_ms": session.ioBufferDuration * 1000,
            "budget_ms": session.inputLatency * 1000,
            "max_gap_ms": session.outputLatency * 1000,
            "items": session.sampleRate / 1000,
            "interface": interfaceCode(session.currentRoute.outputs.first?.portType),
        ])
    }

    static func interfaceCode(_ port: AVAudioSession.Port?) -> Double {
        guard let port else { return 0 }
        switch port {
        case .builtInReceiver: return 1
        case .builtInSpeaker: return 2
        case .headphones, .headsetMic, .usbAudio, .lineOut: return 3
        case .bluetoothHFP: return 4
        case .bluetoothA2DP, .bluetoothLE: return 5
        default: return 0
        }
    }
}
