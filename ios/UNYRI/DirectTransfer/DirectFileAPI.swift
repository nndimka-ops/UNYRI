import Foundation

struct DirectFileMailbox: Decodable, Equatable {
    var id: String
    var chatId: String
    var offerClientMessageId: String
    var senderUserId: String
    var senderDeviceId: String
    var recipientUserId: String
    var recipientDeviceId: String?
    var expiresAt: Int64
    var status: String

    func binds(_ record: DirectFileRecord) -> Bool {
        id == record.id && chatId == record.chatId && offerClientMessageId == record.offer.offerClientMessageId &&
            senderUserId == record.offer.senderUserId && senderDeviceId == record.offer.senderDeviceId &&
            recipientUserId == record.offer.recipientUserId && expiresAt == record.offer.expiresAt &&
            ["active", "closed"].contains(status) &&
            (recipientDeviceId == nil || UUID(uuidString: recipientDeviceId!)?.uuidString.lowercased() == recipientDeviceId)
    }
}

struct DirectFileSignalsPage: Decodable {
    var transfer: DirectFileMailbox
    var signals: [DirectFileSignalEnvelope]
    var hasMore: Bool
}

struct DirectFileAPI {
    let client: APIClient
    private let httpGate: DirectFileHTTPGate
    init(client: APIClient, httpGate: DirectFileHTTPGate? = nil) {
        self.client = client; self.httpGate = httpGate ?? client.directHTTPGate
    }
    struct Context {
        let key: String?
        let priority: DirectFileHTTPGate.Priority
        let current: @MainActor () -> Bool
        let token: @MainActor () async throws -> String
    }
    func promote(_ key: String) -> UUID { httpGate.promote(key) }
    func demote(_ key: String, lease: UUID) { httpGate.demote(key, lease: lease) }
    func cancelRequests(ownerPrefix: String) { httpGate.cancelScope(ownerPrefix) }
    enum RemoteFailure { case notFound, closed, claimedElsewhere }
    static func classify(_ error: Error) -> RemoteFailure? {
        switch error {
        case APIError.http(404), APIError.server(code: "not_found", message: _),
             APIError.server(code: "chat_not_found", message: _): return .notFound
        case APIError.http(410), APIError.server(code: "gone", message: _): return .closed
        case APIError.http(409), APIError.server(code: "conflict", message: _): return .claimedElsewhere
        default: return nil
        }
    }
    struct Capabilities: Decodable { let version: Int; let maxFileBytes: Int64; let directOnly: Bool }
    private struct Register: Encodable {
        var chatId: String
        var offerClientMessageId: String
        var recipientUserId: String
        var expiresAt: Int64
    }
    private func encode<T: Encodable>(_ body: T) throws -> Data {
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        return try encoder.encode(body)
    }
    private struct Empty: Encodable {}
    private struct Posted: Decodable { var sequence: Int64; var clientSignalId: String }

    private func request<Response: Decodable>(_ path: String, method: String, context: Context,
        query: [URLQueryItem] = [], body: Data? = nil,
        beforeAttempt: (@MainActor () throws -> Void)? = nil, as: Response.Type) async throws -> Response {
        try await httpGate.withPermit(context: .init(key: context.key, priority: context.priority, current: context.current),
            read: method == "GET", prepare: {
                let token = try await context.token()
                try Task.checkCancellation()
                return try client.prepareBoundedDirectJSON(path, method: method, accessToken: token,
                    query: query, body: body, observed429: { httpGate.observe429($0) }, as: Response.self)
            }, beforeStart: {
                guard context.current() else { throw CancellationError() }
                try beforeAttempt?()
            })
    }

    func capabilities(context: Context) async throws -> Capabilities {
        try await request("/direct-transfers/capabilities", method: "GET", context: context, as: Capabilities.self)
    }

    func register(_ record: DirectFileRecord, context: Context) async throws -> DirectFileMailbox {
        try await request("/direct-transfers/\(record.id)", method: "PUT", context: context,
            body: encode(Register(chatId: record.chatId, offerClientMessageId: record.offer.offerClientMessageId,
                recipientUserId: record.offer.recipientUserId, expiresAt: record.offer.expiresAt)), as: DirectFileMailbox.self)
    }
    func metadata(_ id: String, context: Context) async throws -> DirectFileMailbox {
        try await request("/direct-transfers/\(id)", method: "GET", context: context, as: DirectFileMailbox.self)
    }
    func claim(_ id: String, context: Context, beforeAttempt: (@MainActor () throws -> Void)? = nil) async throws -> DirectFileMailbox {
        try await request("/direct-transfers/\(id)/claim", method: "POST", context: context, body: encode(Empty()), beforeAttempt: beforeAttempt, as: DirectFileMailbox.self)
    }
    func send(_ id: String, signal: DirectFileFrozenSignal, context: Context, beforeAttempt: (@MainActor () throws -> Void)? = nil) async throws {
        let reply = try await request("/direct-transfers/\(id)/signals", method: "POST", context: context,
            body: encode(signal), beforeAttempt: beforeAttempt, as: Posted.self)
        guard reply.clientSignalId == signal.clientSignalId, reply.sequence > 0 else { throw APIError.decoding("Invalid transfer signal acknowledgement") }
    }
    func signals(_ id: String, after: Int64, context: Context) async throws -> DirectFileSignalsPage {
        try await request("/direct-transfers/\(id)/signals", method: "GET", context: context,
            query: [URLQueryItem(name: "after", value: String(after)), URLQueryItem(name: "limit", value: "32")],
            as: DirectFileSignalsPage.self)
    }
    func close(_ id: String, context: Context) async throws {
        let _: DirectFileMailbox = try await request("/direct-transfers/\(id)/close", method: "POST", context: context,
            body: encode(Empty()), as: DirectFileMailbox.self)
    }
}
