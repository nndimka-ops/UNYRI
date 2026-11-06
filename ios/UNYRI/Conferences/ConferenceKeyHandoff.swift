import Combine
import Foundation

/// Screen-owned request state, separate from capture/admission ownership.
/// Closing the request cannot leave a late task able to start media.
@MainActor final class ConferenceKeyHandoff: ObservableObject {
    enum Phase: Equatable { case idle, requesting, waitingApproval, waiting, timedOut, failed, cancelled, ready }
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var error: String?
    @Published private(set) var roomTitle = ""
    var onReady: ((ConferenceCatalogCredential) -> Void)?
    var isWaiting: Bool { phase == .requesting || phase == .waitingApproval || phase == .waiting }
    private let api: ConferencesAPI
    private weak var auth: AuthRepository?
    private let signal: SignalSessionManager?
    private let nonceFactory: () throws -> String
    private var task: Task<Void, Never>?
    private var creationTask: Task<ConferenceKeyRequest, Error>?
    private var cancellationTask: Task<Void, Never>?
    private var ticket = UUID()
    private var request: (room: UUID, id: UUID)?
    private(set) var retryRoom: ConferenceDirectoryRoom?

    init(api: ConferencesAPI, auth: AuthRepository, signal: SignalSessionManager?, nonceFactory: @escaping () throws -> String = ConferenceInvite.randomNonce) {
        self.api = api; self.auth = auth; self.signal = signal
        self.nonceFactory = nonceFactory
    }
    func cancel() {
        ticket = UUID(); task?.cancel(); task = nil
        creationTask?.cancel(); creationTask = nil
        if isWaiting { phase = .cancelled }
        let previous = request; request = nil
        if let previous, api.isCurrentSession {
            // The server records an exact-device tombstone even if this
            // DELETE wins the race with the still-arriving POST.
            cancellationTask = Task { try? await self.api.cancelKey(room: previous.room, id: previous.id) }
        }
    }
    func start(room: ConferenceDirectoryRoom, password: String?) {
        cancel(); error = nil; roomTitle = room.title; retryRoom = room
        guard room.canRequestKey, let signal, let user = auth?.currentUser?.id.lowercased(),
              let device = auth?.currentDeviceId?.uuidString.lowercased(), api.isCurrentSession else {
            phase = .failed; error = ConferenceDirectoryFailure.unavailable.localizedDescription; return
        }
        let id = UUID()
        let nonce: String
        do { nonce = try nonceFactory(); guard ConferenceKeyPayload.canonicalKey(nonce) else { throw ConferenceDirectoryFailure.invalidHandoff } }
        catch { phase = .failed; self.error = ConferenceDirectoryFailure.unavailable.localizedDescription; return }
        let current = UUID(); ticket = current; request = (room.id, id); phase = .requesting
        let cleanup = cancellationTask
        let generation = auth?.sessionGeneration
        func currentOwner() -> Bool {
            self.ticket == current && self.api.isCurrentSession && self.auth?.sessionGeneration == generation &&
                self.auth?.currentUser?.id.lowercased() == user
        }
        task = Task { [weak self] in
            guard let self, currentOwner(), !Task.isCancelled else { return }
            do {
                // Retry follows the bounded exact-ID deletion. The server's
                // tombstone rejects any later commit of the previous POST.
                await cleanup?.value
                guard currentOwner(), !Task.isCancelled else { return }
                var deadline = ProcessInfo.processInfo.systemUptime + 60
                let firstDeadline = deadline
                let creation = Task { @MainActor in
                    try await Self.createOrRecover(api: self.api, room: room.id, id: id, nonce: nonce, password: password, deadline: firstDeadline, current: currentOwner)
                }
                self.creationTask = creation
                var value: ConferenceKeyRequest
                do { value = try await creation.value }
                catch {
                    guard ConferencesAPI.needsApproval(error), currentOwner(), !Task.isCancelled else { throw error }
                    self.creationTask = nil
                    try await self.api.waitForJoinApproval(room: room.id, secret: nil, password: password) {
                        if currentOwner(), !Task.isCancelled { self.phase = .waitingApproval }
                    }
                    guard currentOwner(), !Task.isCancelled else { throw CancellationError() }
                    // Approval can take two minutes. The encrypted key handshake
                    // receives its own original 60-second budget afterwards.
                    deadline = ProcessInfo.processInfo.systemUptime + 60
                    let nextDeadline = deadline
                    let retry = Task { @MainActor in
                        try await Self.createOrRecover(api: self.api, room: room.id, id: id, nonce: nonce, password: password, deadline: nextDeadline, current: currentOwner)
                    }
                    self.creationTask = retry
                    value = try await retry.value
                }
                guard currentOwner(), !Task.isCancelled else { return }
                self.creationTask = nil
                self.phase = .waiting
                var seen = Set<String>()
                while currentOwner(), !Task.isCancelled, ProcessInfo.processInfo.systemUptime < deadline {
                    try self.validate(value, room: room.id, id: id, nonce: nonce, user: user, device: device, now: Date())
                    if value.status == "ready", let envelope = value.envelope,
                       let sender = value.providerUserId, let senderDevice = value.providerDeviceId {
                        // Never feed a consumed ciphertext into Signal again.
                        let binding = "\(value.assignmentRevision):\(value.accessRevision):" + envelope.ciphertext
                        guard seen.insert(binding).inserted else { throw ConferenceDirectoryFailure.invalidHandoff }
                        let bytes = try await Self.bounded(deadline: deadline) { try await signal.decryptExact(envelope, senderUserId: sender, senderDeviceId: senderDevice) }
                        guard currentOwner(), !Task.isCancelled else { return }
                        let decoded = try ConferenceKeyPayload.decode(bytes, request: value, server: api.server, requester: user, device: device, now: Date())
                        // Authoritative mailbox recheck after Signal's awaits:
                        // cancellation, reassignment and policy changes win.
                        let confirmed = try await Self.readWithRetry(api: api, room: room.id, id: id, deadline: deadline, current: currentOwner)
                        guard currentOwner(), !Task.isCancelled else { return }
                        try self.validate(confirmed, room: room.id, id: id, nonce: nonce, user: user, device: device, now: Date())
                        guard confirmed.status == "ready", confirmed.envelope == envelope,
                              confirmed.assignmentRevision == value.assignmentRevision,
                              confirmed.accessRevision == value.accessRevision,
                              confirmed.providerIdentity == value.providerIdentity,
                              confirmed.providerUserId == sender, confirmed.providerDeviceId == senderDevice else { throw ConferenceDirectoryFailure.changed }
                        try decoded.validate(request: confirmed, server: api.server, requester: user, device: device, now: Date())
                        self.phase = .ready; self.request = nil; self.task = nil
                        self.onReady?(ConferenceCatalogCredential(roomID: room.id, requestID: id, mediaKey: decoded.mediaKey))
                        return
                    }
                    if ["expired", "cancelled", "revoked", "consumed"].contains(value.status) { throw ConferenceDirectoryFailure.expired }
                    try await Self.pause(2, deadline: deadline)
                    guard currentOwner(), !Task.isCancelled else { return }
                    do { value = try await Self.bounded(deadline: deadline) { try await self.api.keyRequest(room: room.id, id: id) } }
                    catch {
                        guard currentOwner(), !Task.isCancelled else { return }
                        if Self.transient(error) {
                            if Self.rateLimited(error) { try await Self.pause(30, deadline: deadline) }
                            continue
                        }
                        throw ConferencesAPI.accessError(error)
                    }
                }
                if currentOwner(), !Task.isCancelled { self.phase = .timedOut; self.error = ConferenceDirectoryFailure.expired.localizedDescription }
            } catch {
                guard currentOwner(), !Task.isCancelled else { return }
                let failure = ConferencesAPI.accessError(error)
                self.phase = failure as? ConferenceDirectoryFailure == .expired ? .timedOut : .failed
                self.error = (error as? ConferenceApprovalFailure)?.localizedDescription ?? (failure as? ConferenceDirectoryFailure)?.localizedDescription ?? ConferenceDirectoryFailure.invalidHandoff.localizedDescription
            }
            if currentOwner() {
                self.request = nil; self.task = nil; self.creationTask = nil
                if api.isCurrentSession { try? await api.cancelKey(room: room.id, id: id) }
            }
        }
    }
    /// Retrying reopens admission with an empty password field. The previous
    /// exact request is cancelled; no old task may publish the new admission.
    func dismissStatus() {
        cancel(); phase = .idle; error = nil; retryRoom = nil; roomTitle = ""
    }
    private func validate(_ value: ConferenceKeyRequest, room: UUID, id: UUID, nonce: String, user: String, device: String, now: Date) throws {
        guard value.id == id.uuidString.lowercased(), value.roomId == room.uuidString.lowercased(),
              value.requesterUserId == user, value.requesterDeviceId == device, value.nonce == nonce else { throw ConferenceDirectoryFailure.invalidHandoff }
        if ["expired", "cancelled", "revoked", "consumed"].contains(value.status) { throw ConferenceDirectoryFailure.expired }
        if value.expiresAt <= now { throw ConferenceDirectoryFailure.expired }
        try value.validate(now: now)
    }
    private static func transient(_ error: Error) -> Bool {
        switch error {
        case APIError.network: return true
        case APIError.http(let status): return status == 429 || (500...599).contains(status)
        case APIError.server(let code, _): return ["rate_limited", "unavailable", "internal_error"].contains(code)
        default: return error is URLError
        }
    }
    private static func rateLimited(_ error: Error) -> Bool {
        if case APIError.http(429) = error { return true }
        if case APIError.server("rate_limited", _) = error { return true }
        return false
    }
    private static func pause(_ seconds: TimeInterval, deadline: TimeInterval) async throws {
        let remaining = deadline - ProcessInfo.processInfo.systemUptime
        guard remaining > seconds else { throw ConferenceDirectoryFailure.expired }
        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }
    private static func bounded<T: Sendable>(deadline: TimeInterval, operation: @escaping @MainActor () async throws -> T) async throws -> T {
        let remaining = deadline - ProcessInfo.processInfo.systemUptime
        guard remaining > 0 else { throw ConferenceDirectoryFailure.expired }
        let request = Task { @MainActor in try await operation() }
        let timer = Task { @MainActor in
            do { try await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000)) } catch { return }
            request.cancel()
        }
        defer { timer.cancel() }
        do {
            let value = try await withTaskCancellationHandler(operation: { try await request.value }, onCancel: { request.cancel() })
            try Task.checkCancellation()
            guard ProcessInfo.processInfo.systemUptime < deadline else { throw ConferenceDirectoryFailure.expired }
            return value
        } catch {
            if ProcessInfo.processInfo.systemUptime >= deadline { throw ConferenceDirectoryFailure.expired }
            throw error
        }
    }
    private static func createOrRecover(api: ConferencesAPI, room: UUID, id: UUID, nonce: String, password: String?, deadline: TimeInterval, current: () -> Bool) async throws -> ConferenceKeyRequest {
        var attempt = 0
        while current(), !Task.isCancelled {
            do { return try await bounded(deadline: deadline) { try await api.requestKey(room: room, id: id, nonce: nonce, password: password) } }
            catch {
                guard current(), !Task.isCancelled else { throw CancellationError() }
                guard transient(error) else { throw error }
                var mustBackOff = rateLimited(error)
                // POST may have committed before its acknowledgement was lost.
                // Recover that exact request first; every retry keeps ID/body.
                do { return try await bounded(deadline: deadline) { try await api.keyRequest(room: room, id: id) } }
                catch {
                    guard current(), !Task.isCancelled else { throw CancellationError() }
                    mustBackOff = mustBackOff || rateLimited(error)
                    if !transient(error) {
                        switch error {
                        case APIError.http(404), APIError.server("not_found", _): break
                        default: throw error
                        }
                    }
                }
                attempt += 1
                try await pause(mustBackOff ? 30 : min(8, pow(2, Double(min(attempt - 1, 3)))), deadline: deadline)
            }
        }
        throw CancellationError()
    }
    private static func readWithRetry(api: ConferencesAPI, room: UUID, id: UUID, deadline: TimeInterval, current: () -> Bool) async throws -> ConferenceKeyRequest {
        while current(), !Task.isCancelled {
            do { return try await bounded(deadline: deadline) { try await api.keyRequest(room: room, id: id) } }
            catch {
                guard current(), !Task.isCancelled else { throw CancellationError() }
                guard transient(error) else { throw error }
                try await pause(rateLimited(error) ? 30 : 2, deadline: deadline)
            }
        }
        throw CancellationError()
    }
}
