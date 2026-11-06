import Foundation

/// Owns an outgoing attempt through permission prompts, the CallKit action,
/// and the REST create response. The controller can still be idle meanwhile.
struct CallKitOutgoingStartGate {
    private(set) var activeUUID: UUID?
    mutating func begin(_ uuid: UUID) -> Bool {
        guard activeUUID == nil else { return false }
        activeUUID = uuid
        return true
    }
    mutating func finish(_ uuid: UUID) {
        if activeUUID == uuid { activeUUID = nil }
    }
}

// Pure, testable CallKit decision layer. No `CallKit` import — only UNYRI types
// — so it compiles and runs on macOS CI. The coordinator translates these
// decisions into concrete `CX*` calls.

/// Which existing `CallController` operation a system End action maps to.
enum CallKitAction: Equatable {
    case reject   // incoming ringing -> reject
    case cancel   // outgoing ringing -> cancel
    case hangup   // connecting / connected / reconnecting -> hangup
    case none
}

/// System-facing end-reason kind (mapped to `CXCallEndedReason` by the
/// coordinator). Our own enum so the mapping is pure and testable.
enum CallKitEndReasonKind: Equatable {
    case remoteEnded
    case declinedElsewhere
    case answeredElsewhere
    case failed
    case unanswered
    case none
}

enum CallKitPolicy {
    /// Receiving a video call requires a microphone, but not a local camera.
    /// Keep this permission preflight identical for in-app and system Answer:
    /// only camera denial is tolerated, never microphone errors/cancellation.
    static func prepareIncomingAnswer(
        for kind: CallMediaKind,
        request: (CallMediaKind) async throws -> Void,
        isCameraDenied: (Error) -> Bool
    ) async throws {
        try Task.checkCancellation()
        try await request(.audio)
        try Task.checkCancellation()
        if kind == .video {
            do { try await request(.video) }
            catch {
                guard isCameraDenied(error) else { throw error }
            }
        }
        try Task.checkCancellation()
    }

    static func keepsSignalingInBackground(_ state: CallUiState) -> Bool {
        switch state {
        case .incomingRinging, .outgoingRinging, .connecting, .connected, .reconnecting: return true
        default: return false
        }
    }
    static func hasVideo(for state: CallUiState) -> Bool? {
        if case .connected(let connected) = state { return connected.videoMode == .video }
        return state.callInfo.map { $0.mediaKind == .video }
    }
    /// Late system actions from an ended call must never control its successor.
    static func matchesActiveCall(backendId: String?, state: CallUiState) -> Bool {
        guard let backendId, let current = state.callInfo?.callId else { return false }
        return UUID(uuidString: backendId) != nil && UUID(uuidString: backendId) == UUID(uuidString: current)
    }
    /// Context-aware mapping for `CXEndCallAction` — never blindly hangup.
    static func endAction(for state: CallUiState) -> CallKitAction {
        switch state {
        case .incomingRinging: return .reject
        case .outgoingRinging: return .cancel
        case .connecting, .connected, .reconnecting: return .hangup
        default: return .none
        }
    }

    /// UNYRI backend end reason -> system-facing reason kind. UNYRI's exact
    /// reason stays internal; this is only the OS-facing representation.
    static func endReasonKind(for reason: CallEndReason) -> CallKitEndReasonKind {
        switch reason {
        case .hangup, .cancelled, .remoteEnded: return .remoteEnded
        case .rejected: return .declinedElsewhere
        case .answeredElsewhere: return .answeredElsewhere
        case .failed: return .failed
        case .missed, .timeout: return .unanswered
        }
    }

    /// Whether an in-call state carries mute state (`.connected` only).
    static func mute(for state: CallUiState) -> Bool? {
        if case .connected(let c) = state { return c.isMuted }
        return nil
    }
}

/// Idempotency guard: a call ended by a LOCAL action must not be reported
/// again when the backend's `call.ended` echo arrives, and a terminal already
/// reported must never be reported twice. One active call at a time.
struct CallKitReportTracker: Equatable {
    static let terminalHistoryLimit = 128
    private var terminalOrder: [String] = []
    var retainedTerminalCount: Int { terminalOrder.count }
    var activeTrackingCount: Int { connecting.union(connected).union(pendingIncoming).union(deferredEnds.keys).count }
    private var pendingIncoming: Set<String> = []
    private var deferredEnds: [String: CallEndReason] = [:]
    /// Both the controller and platform adapter consult the same bounded
    /// terminal policy before accepting duplicate WS/REST ringing events.
    func isTerminal(_ id: String) -> Bool {
        if locallyEnded.contains(id) || reportedEnded.contains(id) || deferredEnds[id] != nil { return true }
        guard let uuid = UUID(uuidString: id) else { return false }
        return locallyEnded.contains { UUID(uuidString: $0) == uuid } ||
            reportedEnded.contains { UUID(uuidString: $0) == uuid } ||
            deferredEnds.keys.contains { UUID(uuidString: $0) == uuid }
    }
    mutating func beginIncomingReport(_ id: String) { pendingIncoming.insert(id) }
    mutating func deferEndWhileIncomingPending(_ id: String, reason: CallEndReason) -> Bool {
        guard pendingIncoming.contains(id) else { return false }
        deferredEnds[id] = reason
        return true
    }
    mutating func finishIncomingReport(_ id: String) -> CallEndReason? {
        pendingIncoming.remove(id)
        return deferredEnds.removeValue(forKey: id)
    }
    private var connecting: Set<String> = []
    private var connected: Set<String> = []

    mutating func shouldReportConnecting(_ callId: String) -> Bool {
        guard !locallyEnded.contains(callId), !reportedEnded.contains(callId) else { return false }
        return connecting.insert(callId).inserted
    }

    mutating func shouldReportConnected(_ callId: String) -> Bool {
        guard !locallyEnded.contains(callId), !reportedEnded.contains(callId) else { return false }
        return connected.insert(callId).inserted
    }
    private(set) var locallyEnded: Set<String> = []
    private(set) var reportedEnded: Set<String> = []

    mutating func markLocallyEnded(_ backendCallId: String) {
        locallyEnded.insert(backendCallId)
        rememberTerminal(backendCallId)
    }

    /// Returns `true` the first time a terminal is eligible to be reported.
    mutating func shouldReportEnded(_ backendCallId: String) -> Bool {
        guard !locallyEnded.contains(backendCallId), !reportedEnded.contains(backendCallId) else { return false }
        reportedEnded.insert(backendCallId)
        rememberTerminal(backendCallId)
        return true
    }

    /// Release per-call progress after CallKit registration/terminal delivery
    /// completes, retaining only a bounded recent tombstone for duplicate echoes.
    mutating func completeCall(_ id: String) {
        pendingIncoming.remove(id); deferredEnds.removeValue(forKey: id)
        connecting.remove(id); connected.remove(id)
        if !locallyEnded.contains(id) { reportedEnded.insert(id) }
        rememberTerminal(id)
    }

    private mutating func rememberTerminal(_ id: String) {
        guard !terminalOrder.contains(id) else { return }
        terminalOrder.append(id)
        while terminalOrder.count > Self.terminalHistoryLimit {
            let expired = terminalOrder.removeFirst()
            locallyEnded.remove(expired); reportedEnded.remove(expired)
        }
    }

    mutating func reset() {
        pendingIncoming = []
        deferredEnds = [:]
        connecting = []
        connected = []
        locallyEnded = []
        reportedEnded = []
        terminalOrder = []
    }
}
