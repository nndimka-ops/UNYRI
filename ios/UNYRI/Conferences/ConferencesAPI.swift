import Foundation

struct ConferenceJoinRequest: Decodable, Identifiable, Sendable, Equatable {
    let requestId: UUID
    let conferenceId: UUID
    let requesterUserId: UUID
    let requesterDeviceId: UUID
    let displayName: String
    let username: String
    let status: String
    let expiresAt: Date
    let retryAt: Date?
    var id: UUID { requestId }
}
enum ConferenceApprovalFailure: Error, LocalizedError {
    case denied(Date?), expired, cancelled
    var errorDescription: String? {
        switch self {
        case .denied(let date):
            let message = UnyriL10n.string("The room creator declined your request.")
            guard let date else { return message }
            let time = DateFormatter.localizedString(from: date, dateStyle: .none, timeStyle: .short)
            return message + " " + String(format: UnyriL10n.string("Try again after %@."), time)
        case .expired: return UnyriL10n.string("The join request expired. Please try again.")
        case .cancelled: return UnyriL10n.string("The join request was cancelled.")
        }
    }
}

struct ConferenceCapabilities: Decodable {
    let enabled: Bool
    let maxParticipants: Int
    let e2eeRequired: Bool
    let hostControls: Bool?
    let participantModeration: Bool?
    let ownerRooms: Bool?
    let roomDirectory: Bool?
    let roomAccessV2: Bool?
    let roomKeyHandoff: Bool?
    var roomJoinApproval: Bool? = nil
}
struct ConferenceDTO: Decodable, Identifiable {
    struct Member: Decodable {
        let userId: UUID
        let deviceId: UUID
        let displayName: String
        let participantIdentity: String?
        let audioPublishBlocked: Bool?
    }
    let id: UUID
    let title: String
    let ownerUserId: UUID
    let maxParticipants: Int
    let state: String
    let createdAt: Date
    let expiresAt: Date
    let participants: [Member]
    let admissionLocked: Bool?
    var approvalRequired: Bool? = nil
    let description: String?
    let listed: Bool?
    let accessMode: String?
    let settingsRevision: Int64?
    let accessRevision: Int64?
}
struct ConferenceJoinDTO: Decodable {
    let conference: ConferenceDTO
    let livekitUrl: String
    let participantToken: String
    let participantIdentity: String
    let tokenExpiresAt: Date
    let e2eeRequired: Bool
}

/// No E2EE media key is accepted by any transport method. Redirects are
/// disabled, so invitation capabilities cannot follow an origin redirect.
@MainActor
final class ConferencesAPI {
    let server: URL
    private let client: APIClient
    private weak var auth: AuthRepository?
    private let generation: UInt64
    init(auth: AuthRepository) {
        let expectedGeneration = auth.sessionGeneration
        server = AppConfig.apiBaseURL
        client = APIClient(baseURL: server, preventRedirects: true, ephemeral: true,
            onAuthenticatedRequestError: { [weak auth] error, bearer in
                // Let the failed operation unwind before erasure waits for
                // room/key-store writers owned by that operation.
                Task { await auth?.handleAuthenticatedRequestError(error, generation: expectedGeneration, requestToken: bearer) }
            })
        self.auth = auth; generation = expectedGeneration
    }
    var isCurrentSession: Bool {
        guard let auth else { return false }
        return auth.sessionGeneration == generation && AppConfig.apiBaseURL == server && auth.currentUser != nil
    }
    func requireCurrent() throws {
        try Task.checkCancellation()
        guard isCurrentSession else { throw APIError.missingSession }
    }
    static func endsConference(_ error: Error) -> Bool {
        switch error {
        case APIError.http(let status): return [401, 403, 404, 409, 410].contains(status)
        case APIError.server(let code, _):
            return ["unauthorized", "session_revoked", "installation_restricted", "account_read_only", "forbidden", "conflict", "not_found", "gone"].contains(code)
        case APIError.missingSession: return true
        default: return false
        }
    }
    private func token() async throws -> String {
        try requireCurrent()
        _ = try ConferenceInvite.origin(server)
        guard let auth else { throw APIError.missingSession }
        let token = try await auth.currentAccessToken()
        try requireCurrent()
        return token
    }
    func capabilities() async throws -> ConferenceCapabilities {
        let value = try await client.get("/conferences/capabilities", accessToken: token(), as: ConferenceCapabilities.self)
        try requireCurrent(); return value
    }
    static func needsApproval(_ error: Error) -> Bool {
        if case APIError.server("conflict", "Conference creator approval is required.") = error { return true }
        return false
    }
    func requestJoinApproval(room: UUID, id: UUID, secret: String?, password: String?) async throws -> ConferenceJoinRequest {
        struct Request: Encodable { let requestId: UUID; let inviteSecret: String?; let password: String? }
        let value = try await client.post("/conferences/\(room.uuidString.lowercased())/join-requests", accessToken: token(),
            body: Request(requestId: id, inviteSecret: secret, password: password), as: ConferenceJoinRequest.self)
        try requireCurrent(); return value
    }
    func joinApproval(room: UUID, id: UUID) async throws -> ConferenceJoinRequest {
        let value = try await client.get("/conferences/\(room.uuidString.lowercased())/join-requests/\(id.uuidString.lowercased())",
            accessToken: token(), as: ConferenceJoinRequest.self)
        try requireCurrent(); return value
    }
    func cancelJoinApproval(room: UUID, id: UUID) async throws {
        struct Reply: Decodable { let ok: Bool }
        let _: Reply = try await client.delete("/conferences/\(room.uuidString.lowercased())/join-requests/\(id.uuidString.lowercased())",
            accessToken: token(), as: Reply.self)
        try requireCurrent()
    }
    func pendingJoinApprovals(room: UUID) async throws -> [ConferenceJoinRequest] {
        struct Reply: Decodable { let items: [ConferenceJoinRequest] }
        let value = try await approvalBounded(deadline: ProcessInfo.processInfo.systemUptime + 4) {
            try await self.client.get("/conferences/\(room.uuidString.lowercased())/join-requests", accessToken: self.token(), as: Reply.self)
        }
        try requireCurrent()
        guard value.items.count <= 64, value.items.allSatisfy({ $0.conferenceId == room && $0.status == "pending" }) else {
            throw ConferenceDirectoryFailure.invalidHandoff
        }
        return value.items
    }
    func decideJoinApproval(room: UUID, id: UUID, approve: Bool) async throws -> ConferenceJoinRequest {
        struct Request: Encodable { let approve: Bool }
        let value = try await approvalBounded(deadline: ProcessInfo.processInfo.systemUptime + 6) {
            try await self.client.post("/conferences/\(room.uuidString.lowercased())/join-requests/\(id.uuidString.lowercased())/decision",
                accessToken: self.token(), body: Request(approve: approve), as: ConferenceJoinRequest.self)
        }
        try requireCurrent()
        guard value.requestId == id, value.conferenceId == room else { throw ConferenceDirectoryFailure.invalidHandoff }
        return value
    }
    /// Wait before requesting any SDK grant/key. Cancellation is an exact-ID
    /// deletion in a fresh bounded task, including a lost POST acknowledgement.
    func waitForJoinApproval(room: UUID, secret: String?, password: String?, waiting: @escaping @MainActor () -> Void) async throws {
        try requireCurrent()
        guard let user = auth?.currentUser.flatMap({ UUID(uuidString: $0.id) }), let device = auth?.currentDeviceId else { throw APIError.missingSession }
        let id = UUID(), deadline = ProcessInfo.processInfo.systemUptime + 130
        func check(_ value: ConferenceJoinRequest) throws {
            try requireCurrent()
            guard value.requestId == id, value.conferenceId == room, value.requesterUserId == user, value.requesterDeviceId == device,
                ["pending","approved","consumed","not_required","denied","cooldown","cancelled","expired"].contains(value.status) else { throw ConferenceDirectoryFailure.invalidHandoff }
        }
        do {
            var value: ConferenceJoinRequest?
            while value == nil {
                try requireCurrent()
                do { value = try await approvalBounded(deadline: deadline) { try await self.requestJoinApproval(room: room, id: id, secret: secret, password: password) } }
                catch {
                    try requireCurrent()
                    guard Self.approvalTransient(error) else { throw error }
                    var backOff = Self.approvalRateLimited(error)
                    do { value = try await approvalBounded(deadline: deadline) { try await self.joinApproval(room: room, id: id) } }
                    catch {
                        try requireCurrent()
                        backOff = backOff || Self.approvalRateLimited(error)
                        if !Self.approvalTransient(error) {
                            switch error { case APIError.http(404), APIError.server("not_found", _): break; default: throw error }
                        }
                    }
                    if value == nil { try await approvalPause(backOff ? 61 : 3, deadline: deadline) }
                }
            }
            while let response = value {
                try check(response)
                switch response.status {
                case "approved", "consumed", "not_required": return
                case "denied", "cooldown": throw ConferenceApprovalFailure.denied(response.retryAt)
                case "expired": throw ConferenceApprovalFailure.expired
                case "cancelled": throw ConferenceApprovalFailure.cancelled
                default: break
                }
                waiting(); try await approvalPause(3, deadline: deadline)
                do { value = try await approvalBounded(deadline: deadline) { try await self.joinApproval(room: room, id: id) } }
                catch {
                    try requireCurrent()
                    guard Self.approvalTransient(error) else { throw error }
                    try await approvalPause(Self.approvalRateLimited(error) ? 61 : 3, deadline: deadline)
                }
            }
        } catch {
            if isCurrentSession {
                let cleanup = Task { @MainActor in
                    try? await self.approvalBounded(deadline: ProcessInfo.processInfo.systemUptime + 3) {
                        try await self.cancelJoinApproval(room: room, id: id)
                    }
                }
                await cleanup.value
            }
            throw error
        }
    }
    private static func approvalRateLimited(_ error: Error) -> Bool {
        switch error { case APIError.http(429), APIError.server("rate_limited", _): return true; default: return false }
    }
    private static func approvalTransient(_ error: Error) -> Bool {
        switch error {
        case APIError.network: return true
        case APIError.http(let status): return status == 429 || (500...599).contains(status)
        case APIError.server(let code, _): return ["rate_limited","unavailable","internal_error"].contains(code)
        default: return error is URLError
        }
    }
    private func approvalPause(_ seconds: TimeInterval, deadline: TimeInterval) async throws {
        guard deadline - ProcessInfo.processInfo.systemUptime > seconds else { throw ConferenceApprovalFailure.expired }
        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)); try requireCurrent()
    }
    private func approvalBounded<T: Sendable>(deadline: TimeInterval, operation: @escaping @MainActor () async throws -> T) async throws -> T {
        let remaining = deadline - ProcessInfo.processInfo.systemUptime
        guard remaining > 0 else { throw ConferenceApprovalFailure.expired }
        let request = Task { @MainActor in try await operation() }
        let timer = Task { @MainActor in
            do { try await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000)) } catch { return }
            request.cancel()
        }
        defer { timer.cancel() }
        do {
            let value = try await withTaskCancellationHandler(operation: { try await request.value }, onCancel: { request.cancel() })
            try requireCurrent()
            guard ProcessInfo.processInfo.systemUptime < deadline else { throw ConferenceApprovalFailure.expired }
            return value
        } catch {
            if ProcessInfo.processInfo.systemUptime >= deadline { throw ConferenceApprovalFailure.expired }
            throw error
        }
    }
    func create(title: String, limit: Int, hash: String) async throws -> ConferenceDTO {
        struct Request: Encodable { let title: String; let maxParticipants: Int; let inviteSecretHash: String }
        do {
            let value = try await client.post("/conferences", accessToken: token(),
                body: Request(title: title, maxParticipants: limit, inviteSecretHash: hash), as: ConferenceDTO.self)
            try requireCurrent(); return value
        } catch APIError.server(let code, let message) where code == "conflict" && message == "End an existing conference before creating another." {
            try requireCurrent(); throw ConferenceInvite.Failure.ownerLimit
        }
    }
    func join(_ invite: ConferenceInvite) async throws -> ConferenceJoinDTO {
        struct Request: Encodable { let inviteSecret: String }
        guard try ConferenceInvite.origin(server) == invite.server else { throw ConferenceInvite.Failure.differentServer }
        do {
            let value = try await client.post("/conferences/\(invite.roomID.uuidString.lowercased())/join", accessToken: token(),
                body: Request(inviteSecret: invite.secret), as: ConferenceJoinDTO.self)
            try requireCurrent(); return value
        } catch APIError.server(let code, let message) where code == "conflict" && message == "Conference admission is locked." {
            try requireCurrent()
            throw ConferenceInvite.Failure.admissionLocked
        }
    }
    func room(_ id: UUID) async throws -> ConferenceDTO {
        let value = try await client.get("/conferences/\(id.uuidString.lowercased())", accessToken: token(), as: ConferenceDTO.self)
        try requireCurrent(); return value
    }
    func ownedRooms() async throws -> [ConferenceDTO] {
        let value = try await client.get("/conferences/owned", accessToken: token(), as: [ConferenceDTO].self)
        try requireCurrent(); return value
    }
    func directory(query: String, cursor: String?) async throws -> ConferenceDirectoryPage {
        let bounded = String(query.prefix(80))
        var items = [URLQueryItem(name: "q", value: bounded), URLQueryItem(name: "limit", value: "20")]
        if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
        let value = try await client.get("/conferences/directory", accessToken: token(), query: items, as: ConferenceDirectoryPage.self)
        try requireCurrent(); return value
    }
    func create(configuration: ConferenceRoomConfiguration, limit: Int, hash: String) async throws -> ConferenceDTO {
        try configuration.validate(creating: true)
        struct Request: Encodable {
            let title: String; let maxParticipants: Int; let inviteSecretHash: String
            let description: String; let listed: Bool; let accessMode: String; let password: String?; let approvalRequired: Bool?
        }
        do {
            let value = try await client.post("/conferences", accessToken: token(), body: Request(
                title: configuration.title.trimmingCharacters(in: .whitespacesAndNewlines), maxParticipants: min(30, max(2, limit)), inviteSecretHash: hash,
                description: configuration.description, listed: configuration.listed, accessMode: configuration.accessMode.rawValue,
                password: configuration.passwordAction == .set ? configuration.password : nil, approvalRequired: configuration.approvalRequired), as: ConferenceDTO.self)
            try requireCurrent(); return value
        } catch { try requireCurrent(); throw Self.accessError(error) }
    }
    func updateSettings(_ id: UUID, configuration: ConferenceRoomConfiguration, expectedRevision: Int64) async throws -> ConferenceDTO {
        try configuration.validate(creating: false)
        guard expectedRevision > 0 else { throw ConferenceDirectoryFailure.changed }
        struct Request: Encodable {
            let title: String; let admissionLocked: Bool; let description: String; let listed: Bool
            let accessMode: String; let expectedRevision: Int64; let passwordAction: String; let password: String?; let approvalRequired: Bool?
        }
        do {
            let value = try await client.patch("/conferences/\(id.uuidString.lowercased())/settings", accessToken: token(), body: Request(
                title: configuration.title.trimmingCharacters(in: .whitespacesAndNewlines), admissionLocked: configuration.admissionLocked,
                description: configuration.description, listed: configuration.listed, accessMode: configuration.accessMode.rawValue,
                expectedRevision: expectedRevision, passwordAction: configuration.passwordAction.rawValue,
                password: configuration.passwordAction == .set ? configuration.password : nil, approvalRequired: configuration.approvalRequired), as: ConferenceDTO.self)
            try requireCurrent(); return value
        } catch { try requireCurrent(); throw Self.accessError(error) }
    }
    func joinCredential(_ credential: ConferenceAdmissionCredential, supportsKeyHandoff: Bool) async throws -> ConferenceJoinDTO {
        if case .invitation(let invite, let password) = credential, !supportsKeyHandoff, password == nil { return try await join(invite) }
        struct Request: Encodable { let inviteSecret: String?; let keyRequestId: UUID?; let password: String?; let supportsKeyHandoff: Bool }
        let request: Request
        switch credential {
        case .invitation(let invite, let password):
            guard try ConferenceInvite.origin(server) == invite.server else { throw ConferenceInvite.Failure.differentServer }
            request = Request(inviteSecret: invite.secret, keyRequestId: nil, password: password, supportsKeyHandoff: supportsKeyHandoff)
        case .catalog(let value): request = Request(inviteSecret: nil, keyRequestId: value.requestID, password: nil, supportsKeyHandoff: supportsKeyHandoff)
        }
        do {
            let value = try await client.post("/conferences/\(credential.roomID.uuidString.lowercased())/join", accessToken: token(), body: request, as: ConferenceJoinDTO.self)
            try requireCurrent(); return value
        } catch { try requireCurrent(); throw Self.accessError(error) }
    }
    func requestKey(room: UUID, id: UUID, nonce: String, password: String?) async throws -> ConferenceKeyRequest {
        struct Request: Encodable { let requestId: UUID; let nonce: String; let password: String? }
        do {
            let value = try await client.post("/conferences/\(room.uuidString.lowercased())/key-requests", accessToken: token(),
                body: Request(requestId: id, nonce: nonce, password: password), as: ConferenceKeyRequest.self)
            try requireCurrent(); return value
        } catch { try requireCurrent(); throw Self.accessError(error) }
    }
    func keyRequest(room: UUID, id: UUID) async throws -> ConferenceKeyRequest {
        let value = try await client.get("/conferences/\(room.uuidString.lowercased())/key-requests/\(id.uuidString.lowercased())", accessToken: token(), as: ConferenceKeyRequest.self)
        try requireCurrent(); return value
    }
    func pendingKeys(room: UUID) async throws -> [ConferenceKeyRequest] {
        let value = try await client.get("/conferences/\(room.uuidString.lowercased())/key-requests", accessToken: token(), as: ConferenceKeyRequests.self)
        try requireCurrent(); return Array(value.items.prefix(8))
    }
    func fulfillKey(room: UUID, request: ConferenceKeyRequest, envelope: EnvelopeInput) async throws {
        struct Request: Encodable { let assignmentRevision: Int64; let accessRevision: Int64; let envelope: EnvelopeInput }
        let _: ConferenceKeyRequest = try await client.post("/conferences/\(room.uuidString.lowercased())/key-requests/\(request.id)/fulfill", accessToken: token(),
            body: Request(assignmentRevision: request.assignmentRevision, accessRevision: request.accessRevision, envelope: envelope), as: ConferenceKeyRequest.self)
        try requireCurrent()
    }
    func cancelKey(room: UUID, id: UUID) async throws {
        struct Reply: Decodable { let ok: Bool }
        let _: Reply = try await client.delete("/conferences/\(room.uuidString.lowercased())/key-requests/\(id.uuidString.lowercased())", accessToken: token(), as: Reply.self)
        try requireCurrent()
    }
    static func accessError(_ error: Error) -> Error {
        switch error {
        case APIError.server("conflict", "busy"): return ConferenceInvite.Failure.busy
        case APIError.server("forbidden", "Conference password is required."): return ConferenceDirectoryFailure.passwordRequired
        case APIError.server("forbidden", "Conference password is incorrect."): return ConferenceDirectoryFailure.incorrectPassword
        case APIError.server("settings_changed", _): return ConferenceDirectoryFailure.changed
        case APIError.server("gone", "You were removed from this conference."): return ConferenceDirectoryFailure.removed
        case APIError.server("conflict", "End an existing conference before creating another."): return ConferenceInvite.Failure.ownerLimit
        case APIError.server("conflict", "Conference admission is locked."): return ConferenceInvite.Failure.admissionLocked
        default: return error
        }
    }
    func updateSettings(_ id: UUID, title: String, admissionLocked: Bool) async throws -> ConferenceDTO {
        struct Request: Encodable { let title: String; let admissionLocked: Bool }
        let value = try await client.patch("/conferences/\(id.uuidString.lowercased())/settings", accessToken: token(),
            body: Request(title: title, admissionLocked: admissionLocked), as: ConferenceDTO.self)
        try requireCurrent(); return value
    }
    func moderateAudio(_ id: UUID, userID: UUID, participantIdentity: String, blocked: Bool) async throws -> ConferenceDTO {
        struct Request: Encodable { let participantIdentity: String; let blocked: Bool }
        let value = try await client.post("/conferences/\(id.uuidString.lowercased())/participants/\(userID.uuidString.lowercased())/audio",
            accessToken: token(), body: Request(participantIdentity: participantIdentity, blocked: blocked), as: ConferenceDTO.self)
        try requireCurrent(); return value
    }
    func removeParticipant(_ id: UUID, userID: UUID, participantIdentity: String) async throws -> ConferenceDTO {
        struct Request: Encodable { let participantIdentity: String }
        let value = try await client.post("/conferences/\(id.uuidString.lowercased())/participants/\(userID.uuidString.lowercased())/remove",
            accessToken: token(), body: Request(participantIdentity: participantIdentity), as: ConferenceDTO.self)
        try requireCurrent(); return value
    }
    func finish(_ id: UUID, end: Bool) async throws {
        struct Empty: Encodable {}
        struct Reply: Decodable { let ok: Bool }
        let _: Reply = try await client.post("/conferences/\(id.uuidString.lowercased())/\(end ? "end" : "leave")",
            accessToken: token(), body: Empty(), as: Reply.self)
        try requireCurrent()
    }
}
