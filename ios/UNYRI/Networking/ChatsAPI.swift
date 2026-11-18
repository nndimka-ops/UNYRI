import Foundation

/// REST client for the 1:1 chat endpoints. Message send/receive endpoints
/// are intentionally out of scope for this stage.
struct ChatsAPI {
    let client: APIClient

    struct Presence: Decodable {
        let online: Bool
        let lastSeenAt: String?
        let observedAt: String?
        let validForMs: Int?
    }
    func presence(accessToken: String, chatId: String) async throws -> Presence {
        try await client.get("/chats/\(chatId)/presence", accessToken: accessToken, as: Presence.self)
    }
    struct Pins: Decodable { let chatIds: [String] }
    struct PinRequest: Encodable { let chatIds: [String]; let pinned: Bool }
    func pins(accessToken: String) async throws -> Pins {
        try await client.get("/chats/pins", accessToken: accessToken, as: Pins.self)
    }
    func setPins(accessToken: String, ids: Set<String>, pinned: Bool) async throws -> Pins {
        try await client.post("/chats/pins", accessToken: accessToken, body: PinRequest(chatIds: ids.sorted(), pinned: pinned), as: Pins.self)
    }
    struct Archives: Decodable { let chatIds: [String] }
    struct ArchiveRequest: Encodable { let chatIds: [String]; let archived: Bool }
    func archives(accessToken: String) async throws -> Archives {
        try await client.get("/chats/archives", accessToken: accessToken, as: Archives.self)
    }
    func setArchived(accessToken: String, ids: Set<String>, archived: Bool) async throws -> Archives {
        try await client.put("/chats/archives", accessToken: accessToken,
            body: ArchiveRequest(chatIds: ids.sorted(), archived: archived), as: Archives.self)
    }
    struct DeleteBody: Encodable { let forEveryone: Bool; let requestId: String }
    func deleteForBoth(accessToken: String, chatId: String, forEveryone: Bool = true, requestId: String) async throws {
        let _: Bool? = try await client.post("/chats/\(chatId)/delete", accessToken: accessToken, body: DeleteBody(forEveryone: forEveryone, requestId: requestId), as: Optional<Bool>.self)
    }
    private struct BoundedClearBody: Encodable {
        let requestId: String
        let forEveryone: Bool
        let throughSequence: Int64
        let callsBefore: String?
    }
    func clearHistory(accessToken: String, command: ChatClearCommand) async throws {
        let _: Bool? = try await client.post("/chats/\(command.chatId)/clear-history", accessToken: accessToken,
            body: BoundedClearBody(requestId:command.requestId,forEveryone:command.forEveryone,
                throughSequence:command.throughSequence,callsBefore:command.callsBeforeISO), as:Optional<Bool>.self)
    }
    struct PersonalClear: Decodable { let chatId: String; let throughSequence: Int64; var callsBefore: Date? = nil }
    func personalClears(accessToken: String) async throws -> [PersonalClear] {
        try await client.get("/chats/cleared", accessToken: accessToken, as: [PersonalClear].self)
    }
    struct PreferenceBody: Encodable {
        var blockedUserId: String? = nil
        var reason: String? = nil
        var reportedUserId: String? = nil
        var description: String? = nil
    }
    func updatePreference(accessToken: String, chatId: String, action: String, body: PreferenceBody = .init()) async throws {
        let _: Bool? = try await client.post("/chats/\(chatId)/\(action)", accessToken: accessToken, body: body, as: Optional<Bool>.self)
    }

    func createDirect(accessToken: String, otherUserId: String) async throws -> ChatSummary {
        try await client.post(
            "/chats/direct",
            accessToken: accessToken,
            body: CreateDirectChatRequest(otherUserId: otherUserId),
            as: ChatSummary.self
        )
    }

    func listKnownChats(accessToken: String, chatIds: [String]) async throws -> ChatListResponse {
        guard !chatIds.isEmpty, chatIds.count <= 50 else { throw APIError.http(400) }
        let items = [
            URLQueryItem(name: "limit", value: "50"),
            URLQueryItem(name: "chat_ids", value: chatIds.joined(separator: ",")),
        ]
        return try await client.get("/chats/known", accessToken: accessToken, query: items, as: ChatListResponse.self)
    }

    func getSaved(accessToken: String) async throws -> ChatSummary {
        try await client.get("/chats/saved", accessToken: accessToken, as: ChatSummary.self)
    }

    func getChat(accessToken: String, chatId: String) async throws -> ChatSummary {
        try await client.get("/chats/\(chatId)", accessToken: accessToken, as: ChatSummary.self)
    }

    func getPeerChat(accessToken: String, userId: String) async throws -> ChatSummary? {
        try await client.get("/chats/peer/\(userId)", accessToken:accessToken, as:Optional<ChatSummary>.self)
    }
}
