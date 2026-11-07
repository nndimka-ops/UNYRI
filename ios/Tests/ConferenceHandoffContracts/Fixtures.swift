import Foundation

// Explicit in-memory transport/Signal seams. This suite makes NO claim of
// LibSignal cryptography, Apple SDK, capture or network verification.
enum AppConfig { static let publicDefaultOrigin = "https://conference-test.invalid" }
enum APIError: Error { case network(String), http(Int), server(String, String) }
enum ConferenceApprovalFailure: Error, LocalizedError {
    case denied(Date?), expired, cancelled
    var errorDescription: String? { "Entry decision" }
}
struct User { let id = "33333333-3333-4333-8333-333333333333" }
@MainActor final class AuthRepository {
    var sessionGeneration: UInt64 = 1
    var currentUser: User? = User()
    var currentDeviceId: UUID? = UUID(uuidString: "44444444-4444-4444-8444-444444444444")
}
@MainActor final class Gate {
    var entered = false
    var continuation: CheckedContinuation<Void, Never>?
    func wait() async { entered = true; await withCheckedContinuation { continuation = $0 } }
    func open() { continuation?.resume(); continuation = nil }
}
@MainActor final class ConferencesAPI {
    let server = URL(string: AppConfig.publicDefaultOrigin)!
    let auth: AuthRepository
    let generation: UInt64
    var originCurrent = true
    var isCurrentSession: Bool { generation == auth.sessionGeneration && auth.currentUser != nil && originCurrent }
    var postGate: Gate?
    var commitAfterGate = false
    var ackLostOnce = false, notCommittedOnce = false, rateLimitedOnce = false, pending = false, expired = false
    var readFailures = 0
    var wrongRecipient = false
    var changedAssignment = false
    var approvalRequired = false
    var approvalGate: Gate?
    var approvals = 0
    var posts: [(UUID, UUID, String, String?)] = []
    var reads: [UUID] = [], cancelled: [UUID] = []
    var records: [UUID: ConferenceKeyRequest] = [:]
    init(auth: AuthRepository) { self.auth = auth; generation = auth.sessionGeneration }
    static func accessError(_ error: Error) -> Error { error }
    static func needsApproval(_ error: Error) -> Bool {
        if case APIError.server("conflict", "Conference creator approval is required.") = error { return true }
        return false
    }
    func waitForJoinApproval(room: UUID, secret: String?, password: String?, waiting: @escaping @MainActor () -> Void) async throws {
        approvals += 1; waiting()
        if let gate = approvalGate { await gate.wait() }
        try Task.checkCancellation()
        approvalRequired = false
    }
    func make(room: UUID, id: UUID, nonce: String, revision: Int64 = 1) -> ConferenceKeyRequest {
        let requester = auth.currentUser!.id, device = auth.currentDeviceId!.uuidString.lowercased()
        let provider = "55555555-5555-4555-8555-555555555555", providerDevice = "66666666-6666-4666-8666-666666666666"
        return ConferenceKeyRequest(id: id.uuidString.lowercased(), roomId: room.uuidString.lowercased(), requesterUserId: requester,
            requesterDeviceId: device, providerUserId: pending ? nil : provider, providerDeviceId: pending ? nil : providerDevice,
            providerIdentity: pending ? nil : provider + ":" + providerDevice + ":77777777-7777-4777-8777-777777777777",
            assignmentRevision: pending ? 0 : revision, nonce: nonce, accessRevision: 1,
            expiresAt: Date().addingTimeInterval(expired ? -1 : 60), status: pending ? "pending" : "ready",
            envelope: pending ? nil : EnvelopeInput(recipientDeviceId: wrongRecipient ? providerDevice : device,
                messageType: 1, ciphertext: Data([1, 2, 3]).base64EncodedString()))
    }
    func requestKey(room: UUID, id: UUID, nonce: String, password: String?) async throws -> ConferenceKeyRequest {
        posts.append((room, id, nonce, password))
        if approvalRequired { throw APIError.server("conflict", "Conference creator approval is required.") }
        if rateLimitedOnce { rateLimitedOnce = false; throw APIError.http(429) }
        if notCommittedOnce { notCommittedOnce = false; throw APIError.http(503) }
        let result = make(room: room, id: id, nonce: nonce)
        if !commitAfterGate { records[id] = result }
        if let gate = postGate { postGate = nil; await gate.wait() }
        if commitAfterGate {
            // Explicit server seam: cancellation tombstones survive a DELETE
            // that happened before this delayed POST reached its insert.
            guard !cancelled.contains(id) else { throw APIError.http(410) }
            records[id] = result
        }
        if ackLostOnce { ackLostOnce = false; throw APIError.network("synthetic acknowledgement lost") }
        return result
    }
    func keyRequest(room: UUID, id: UUID) async throws -> ConferenceKeyRequest {
        reads.append(id)
        if readFailures > 0 { readFailures -= 1; throw APIError.http(503) }
        guard let value = records[id] else { throw APIError.http(404) }
        if changedAssignment { return make(room: room, id: id, nonce: value.nonce, revision: value.assignmentRevision + 1) }
        return value
    }
    func cancelKey(room: UUID, id: UUID) async throws { cancelled.append(id); records[id] = nil }
}
@MainActor final class SignalSessionManager {
    let api: ConferencesAPI
    var decrypts = 0
    var gate: Gate?
    init(api: ConferencesAPI) { self.api = api }
    func decryptExact(_ envelope: EnvelopeInput, senderUserId: String, senderDeviceId: String) async throws -> Data {
        decrypts += 1
        let request = api.records.values.first(where: { $0.envelope == envelope })!
        precondition(request.providerUserId == senderUserId && request.providerDeviceId == senderDeviceId)
        let data = try ConferenceKeyPayload.providing(request: request, server: api.server,
            mediaKey: ConferenceInvite.encode(Data(32..<64)), now: Date())
        if let gate { await gate.wait() }
        return data
    }
}
