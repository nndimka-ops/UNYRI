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
    @Published var actionError: String?
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
    private var lastState: CallUiState = .idle
    private var isShutDown = false
    private var preparingAnswerCallID: String?
    private var outgoingStart = CallKitOutgoingStartGate()
    private var cancelledOutgoingUUID: UUID?
    private var incomingAnswer: (action: CXAnswerCallAction, task: Task<Void, Never>)?

    func shutdown() {
        guard !isShutDown else { return }
        isShutDown = true
        cancellables.removeAll()
        cancelIncomingAnswer()
        pendingOutgoing = nil
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

        // CallKit owns AVAudioSession activation; WebRTC follows it.
        RTCAudioSession.sharedInstance().useManualAudio = true

        // The media engine is not created while the outgoing tone is ringing.
        // Observe routing already in that phase (and across CallKit resumes).
        NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] notification in
                guard let self, !self.isShutDown, self.generation == self.apiGeneration else { return }
                let reason = (notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? NSNumber)?.uintValue
                self.controller.synchronizeAudioRoute(userInitiated: reason == AVAudioSession.RouteChangeReason.override.rawValue)
            }
            .store(in: &cancellables)

        controller.$state
            .removeDuplicates()
            .sink { [weak self] newState in self?.stateChanged(newState) }
            .store(in: &cancellables)
    }

    func handlesSystemCall(_ uuid: UUID) -> Bool {
        !isShutDown && generation == apiGeneration &&
            (backendIdByCallKitUUID[uuid] != nil || pendingOutgoing?.uuid == uuid || outgoingStart.activeUUID == uuid)
    }

    func incomingSystemReportFailed(_ uuid: UUID, error: Error) {
        guard handlesSystemCall(uuid), CallKitPolicy.endAction(for: controller.state) != .none,
              let call = controller.state.callInfo, UUID(uuidString: call.callId) == uuid else { return }
        reportError(error)
        controller.failCall(callId: call.callId)
    }

    // MARK: - Outgoing (chat header call button)

    var hasPendingMediaStart: Bool { pendingOutgoing != nil }

    func startOutgoing(remoteUserId: String, displayName: String, isVideo: Bool) {
        guard !isShutDown, generation == apiGeneration else { return }
        guard !controller.presentBlockedStartIfNeeded(), pendingOutgoing == nil else { return }
        let uuid = UUID()
        guard outgoingStart.begin(uuid) else { return }
        pendingOutgoing = (uuid, remoteUserId, displayName, isVideo)
        actionError = nil
        Task {
            do { try await CallMediaPermissions.request(for: isVideo ? .video : .audio) }
            catch {
                let ownsAttempt = pendingOutgoing?.uuid == uuid
                if ownsAttempt { pendingOutgoing = nil }
                outgoingStart.finish(uuid)
                guard ownsAttempt, !isShutDown, generation == apiGeneration,
                      !(error is CancellationError) else { return }
                actionError = error.localizedDescription
                return
            }
            guard !isShutDown, generation == apiGeneration, pendingOutgoing?.uuid == uuid else {
                if pendingOutgoing?.uuid == uuid { pendingOutgoing = nil }
                outgoingStart.finish(uuid)
                return
            }
            if controller.presentBlockedStartIfNeeded() {
                pendingOutgoing = nil
                outgoingStart.finish(uuid)
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
                        self.outgoingStart.finish(uuid)
                        self.reportError(error)
                    }
                }
            }
        }
    }

    // MARK: - CXProviderDelegate (nonisolated hops to MainActor)

    /// In-app Answer must also activate audio through CallKit, which owns
    /// RTCAudioSession while useManualAudio is enabled.
    func answerIncoming() {
        guard !isShutDown, generation == apiGeneration,
              case .incomingRinging(let call) = controller.state,
              let uuid = callKitUUIDByBackendId[call.callId], preparingAnswerCallID == nil else { return }
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
        Task { @MainActor in self.performStart(action) }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        Task { @MainActor in self.performAnswer(action) }
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
        Task { @MainActor in
            guard !self.isShutDown, self.generation == self.apiGeneration else { return }
            let session = RTCAudioSession.sharedInstance()
            session.audioSessionDidActivate(audioSession)
            session.isAudioEnabled = true
            self.controller.restoreAudioControls()
            self.controller.setCallAudioActive(true)
        }
    }

    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        Task { @MainActor in
            guard !self.isShutDown, self.generation == self.apiGeneration else { return }
            let session = RTCAudioSession.sharedInstance()
            self.controller.setCallAudioActive(false)
            session.isAudioEnabled = false
            session.audioSessionDidDeactivate(audioSession)
        }
    }

    // MARK: - Action implementations

    private func performStart(_ action: CXStartCallAction) {
        guard !isShutDown, generation == apiGeneration else { action.fail(); return }
        if let pending = pendingOutgoing, pending.uuid == action.callUUID {
            pendingOutgoing = nil
            startCall(remoteUserId: pending.remoteUserId, name: pending.name, isVideo: pending.isVideo, action: action)
        } else { action.fail() }
    }

    private func startCall(remoteUserId: String, name: String, isVideo: Bool, action: CXStartCallAction) {
        Task {
            defer {
                outgoingStart.finish(action.callUUID)
                if cancelledOutgoingUUID == action.callUUID { cancelledOutgoingUUID = nil }
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
                do { try UnyriCallAudioSession.configure(outputOverride: .none) }
                catch {
                    controller.failCall(callId: call.callId)
                    if !action.isComplete { action.fail() }
                    reportError(error)
                    return
                }
                controller.synchronizeAudioRoute()
                let backendId = call.callId
                callKitUUIDByBackendId[backendId] = action.callUUID
                backendIdByCallKitUUID[action.callUUID] = backendId
                let update = CXCallUpdate()
                configureCapabilities(update)
                update.remoteHandle = CXHandle(type: .generic, value: call.remoteDisplayName)
                update.localizedCallerName = call.remoteDisplayName
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
        do { try UnyriCallAudioSession.configure(outputOverride: .none) }
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
        guard !isShutDown, generation == apiGeneration else { return }
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
            if let uuid = UUID(uuidString: call.callId) { cancelIncomingAnswer(callUUID: uuid) }
            reportTerminal(call, reason: reason)
        case .connected(let c):
            if let previous = CallKitPolicy.mute(for: lastState), previous != c.isMuted, !isHandlingMuteAction {
                syncMuteToSystem(c)
            }
        default:
            break
        }
    }

    private func reportIncoming(_ call: CallInfo) {
        guard !tracker.isTerminal(call.callId), let uuid = UUID(uuidString: call.callId) else { return }
        if let existing = callKitUUIDByBackendId[call.callId] {
            let update = CXCallUpdate()
            configureCapabilities(update)
            update.remoteHandle = CXHandle(type: .generic, value: call.remoteDisplayName)
            update.localizedCallerName = call.remoteDisplayName
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
            update.remoteHandle = CXHandle(type: .generic, value: call.remoteDisplayName)
            update.localizedCallerName = call.remoteDisplayName
            update.hasVideo = call.mediaKind == .video
            provider.reportCall(with: uuid, updated: update)
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
                        self.controller.failCall(callId: call.callId)
                    }
                }
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

    private func syncMuteToSystem(_ c: ConnectedState) {
        guard let uuid = callKitUUIDByBackendId[c.call.callId] ?? UUID(uuidString: c.call.callId) else { return }
        let action = CXSetMutedCallAction(call: uuid, muted: c.isMuted)
        callKit.request(CXTransaction(action: action)) { _ in }
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
