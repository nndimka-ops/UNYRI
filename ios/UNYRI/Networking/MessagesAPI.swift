import Foundation

/// REST client for message history and send. Pure transport — reuse the
/// existing `APIClient` and token refresh (no duplicate networking/auth).
struct MessagesAPI {
    let client: APIClient

    struct Receipt: Codable {
        let sequence: Int64
    }

    func markDelivered(accessToken: String, chatId: String, sequence: Int64) async throws -> Receipt {
        try await client.post(
            "/chats/\(chatId)/delivered",
            accessToken: accessToken,
            body: Receipt(sequence: sequence),
            as: Receipt.self
        )
    }

    /// `POST /chats/{chat_id}/read` — same forward-only cumulative watermark
    /// semantics as delivered, on `read_at` instead of `delivered_at`.
    func markRead(accessToken: String, chatId: String, sequence: Int64) async throws -> Receipt {
        try await client.post(
            "/chats/\(chatId)/read",
            accessToken: accessToken,
            body: Receipt(sequence: sequence),
            as: Receipt.self
        )
    }

    /// `GET /chats/{chat_id}/messages` — keyset pagination by `before_sequence`
    /// (newest-first response). Never paginates by timestamp.
    func listMessages(
        accessToken: String,
        chatId: String,
        beforeSequence: Int64?,
        limit: Int
    ) async throws -> MessageListResponse {
        var query: [URLQueryItem] = [URLQueryItem(name: "limit", value: String(limit))]
        if let beforeSequence {
            query.append(URLQueryItem(name: "before_sequence", value: String(beforeSequence)))
        }
        return try await client.get(
            "/chats/\(chatId)/messages",
            accessToken: accessToken,
            query: query,
            as: MessageListResponse.self
        )
    }

    /// `POST /chats/{chat_id}/messages`.
    func sendMessage(
        accessToken: String,
        chatId: String,
        request: SendMessageRequest
    ) async throws -> SendMessageResponse {
        try await client.post(
            "/chats/\(chatId)/messages",
            accessToken: accessToken,
            body: request,
            as: SendMessageResponse.self
        )
    }
}
