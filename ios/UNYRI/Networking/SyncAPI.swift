import Foundation

/// `GET /sync` response — forward catch-up over the global device-scoped
/// cursor (`server/src/sync/models.rs`). `nextSequence` is always the cursor
/// to send back on the next call.
///
/// No explicit CodingKeys: decoded through `APIClient`'s decoder, which uses
/// `.convertFromSnakeCase` (`next_sequence` -> `nextSequence`, `has_more` ->
/// `hasMore`).
struct SyncResponse: Decodable, Equatable {
    let messages: [MessageDto]
    let nextSequence: Int64
    let hasMore: Bool
    let historyClearedThroughSequence: Int64?
    let historyClearedBeforeMs: Int64?
    let deletedChatIds: [String]
    private enum CodingKeys: String, CodingKey { case messages, nextSequence, hasMore, deletedChatIds, historyClearedBeforeMs, historyClearedThroughSequence }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        historyClearedThroughSequence = try values.decodeIfPresent(Int64.self, forKey: .historyClearedThroughSequence)
        historyClearedBeforeMs = try values.decodeIfPresent(Int64.self, forKey: .historyClearedBeforeMs)
        messages = try values.decode([MessageDto].self, forKey: .messages)
        nextSequence = try values.decode(Int64.self, forKey: .nextSequence)
        hasMore = try values.decode(Bool.self, forKey: .hasMore)
        deletedChatIds = try values.decodeIfPresent([String].self, forKey: .deletedChatIds) ?? []
    }
}

/// The correctness-path REST client: `GET /sync` (mirrors Android's
/// `SyncApi`). WebSocket is the fast path; this guarantees nothing is lost
/// across disconnects, force-stops, or backend unavailability.
struct SyncAPI {
    let client: APIClient

    func sync(accessToken: String, afterSequence: Int64, limit: Int) async throws -> SyncResponse {
        try await client.get(
            "/sync",
            accessToken: accessToken,
            query: [
                URLQueryItem(name: "after_sequence", value: String(afterSequence)),
                URLQueryItem(name: "limit", value: String(limit)),
            ],
            as: SyncResponse.self
        )
    }
}
