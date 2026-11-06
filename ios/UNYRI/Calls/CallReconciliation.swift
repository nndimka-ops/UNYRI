import Foundation

/// FIFO across entire async batches, including suspension points. Cancelling
/// invalidates queued work, but a successor still waits for an in-flight SDK
/// callback to return before starting another media operation.
@MainActor
final class CallEffectSerialQueue {
    private var tail: Task<Void, Never>?
    private var tailId: UUID?
    private var tasks: [UUID: Task<Void, Never>] = [:]

    @discardableResult
    func enqueue(_ operation: @escaping @MainActor () async -> Void) -> Task<Void, Never> {
        let previous = tail
        let id = UUID()
        let task = Task { @MainActor [weak self] in
            await previous?.value
            defer {
                self?.tasks.removeValue(forKey: id)
                if self?.tailId == id { self?.tail = nil; self?.tailId = nil }
            }
            guard !Task.isCancelled else { return }
            await operation()
        }
        tasks[id] = task
        tail = task
        tailId = id
        return task
    }

    func cancel() {
        for task in tasks.values { task.cancel() }
    }
}

/// Pure reconciliation of the authoritative backend call row against the
/// local UI state (Android `reconcileAfterReconnect` semantics). After a WS
/// reconnect the SERVER is the source of truth: a local state that
/// contradicts it is corrected, never the other way around. Kept free of the
/// controller so the whole matrix is table-testable.
enum CallIncomingRecoveryPolicy {
    static let intervalMs: UInt64 = 5_000
    static func validIncomingIdentity(callId: String, callerUserId: String, owner: UUID?) -> Bool {
        guard let owner, UUID(uuidString: callId) != nil,
              let caller = UUID(uuidString: callerUserId) else { return false }
        return caller != owner
    }
    static func matches(_ state: CallUiState, callId: String) -> Bool {
        guard case .incomingRinging(let call) = state else { return false }
        return call.callId == callId
    }
}

enum CallReconciliation {
    enum Decision: Equatable {
        /// Server still active; local state already reflects that (no change).
        case keepCurrent
        /// Server still active; local was transiently `.reconnecting` — fold
        /// back to the in-call state.
        case restoreConnected
        /// The remote accepted while its WS event was unavailable (including
        /// delivery before POST /calls returned the caller's call id).
        case acceptOutgoing
        /// Server terminal — end locally with the server's raw reason string.
        case endLocally(reason: String)
    }

    /// REST rows must still belong to this authenticated account, peer and
    /// local direction after an asynchronous lookup. A matching UUID alone
    /// must not restore another account's or the peer's different call.
    static func matchesParticipants(call: CallInfo, server: CallDTO, owner: UUID?) -> Bool {
        guard let owner, let remote = UUID(uuidString: call.remoteUserId), remote != owner,
              UUID(uuidString: call.callId) == server.id else { return false }
        switch call.direction {
        case .outgoing: return server.callerUserId == owner && server.calleeUserId == remote
        case .incoming: return server.calleeUserId == owner && server.callerUserId == remote
        }
    }

    /// `server` is the `GET /calls/{id}` row for the current call.
    ///
    /// Active server states are `ringing` / `accepted` / `connecting` /
    /// `connected`; terminal is `ended` (with an `end_reason`). There is no
    /// separate `failed` state — failure is `state = 'ended'` +
    /// `end_reason = 'failed'` (backend `end_call`).
    static func resolve(local: CallUiState, server: CallDTO, localDeviceId: UUID? = nil) -> Decision {
        if server.isEnded {
            return .endLocally(reason: server.endReason ?? "failed")
        }
        if let call = local.callInfo, call.direction == .incoming,
           UUID(uuidString: call.callId) == server.id,
           UUID(uuidString: call.remoteUserId) == server.callerUserId,
           ["accepted", "connecting", "connected"].contains(server.state),
           let localDeviceId, let accepted = server.acceptedDeviceId, accepted != localDeviceId {
            // Only the known accepting device owns this call. A missed WS
            // answered_elsewhere must not keep another device ringing forever.
            return .endLocally(reason: "answered_elsewhere")
        }
        switch local {
        case .outgoingRinging(let call)
            where call.direction == .outgoing && UUID(uuidString: call.callId) == server.id &&
                  UUID(uuidString: call.remoteUserId) == server.calleeUserId &&
                  ["accepted", "connecting", "connected"].contains(server.state):
            // This is not a connected-media claim: use the ordinary accepted
            // reducer path to initialize WebRTC and negotiate one offer.
            return .acceptOutgoing
        case .reconnecting:
            return .restoreConnected
        default:
            return .keepCurrent
        }
    }

    enum ExplicitAnswerRecovery: Equatable {
        case unresolved
        case acceptedHere
        case endLocally(reason: String)
    }

    /// Used only while this device's explicit Answer is still in flight and
    /// its HTTP response was lost/failed. Never auto-answer from passive REST.
    static func recoverExplicitAnswer(call: CallInfo, server: CallDTO, owner: UUID?, localDeviceId: UUID?) -> ExplicitAnswerRecovery {
        guard call.direction == .incoming, matchesParticipants(call: call, server: server, owner: owner) else { return .unresolved }
        if server.isEnded { return .endLocally(reason: server.endReason ?? "failed") }
        guard ["accepted", "connecting", "connected"].contains(server.state),
              let localDeviceId, let accepted = server.acceptedDeviceId else { return .unresolved }
        return accepted == localDeviceId ? .acceptedHere : .endLocally(reason: "answered_elsewhere")
    }
}
