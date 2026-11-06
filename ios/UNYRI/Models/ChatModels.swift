import Foundation
import SQLite3

// MARK: - Chat models (mirror server/src/chats/models.rs)

/// A human-safe preview of a chat's latest message, derived from the LOCAL
/// message cache only (never the backend, which exposes no preview text).
enum ChatPreview: Equatable {
    case text(String)
    case photo
    case voice
    case video
    case file(String?)
    case deleted
}

/// A chat as seen in the chat list or `GET /chats/{id}`.
/// `other_user` is only populated for direct chats. The list API exposes no
/// last-message preview text and no unread count — only `last_message_at`.
struct ChatSummary: Codable, Identifiable, Equatable {
    let chatId: String
    /// "direct" | "group" | "saved"
    let chatType: String
    let otherUser: PublicUser?
    let createdAt: String
    var lastMessageAt: String?
    let otherMemberDeliveredSequence: Int64
    let otherMemberReadSequence: Int64
    /// Last-message preview overlaid from the LOCAL message cache (never
    /// decoded from JSON — see `CodingKeys`). Nil until the cache has a
    /// displayable message for this chat; callers fall back to @username.
    var lastPreview: ChatPreview?
    var ownReadSequence: Int64? = nil
    var isMuted: Bool? = nil
    var mutedUntil: String? = nil
    var isBlocked: Bool? = nil
    var blockedUserId: String? = nil
    var suppressNotifications: Bool {
        isBlocked == true || (isMuted == true && (mutedUntil.flatMap(ISO8601Cocoa.parse).map { $0 > Date() } ?? true))
    }

    var id: String { chatId }

    enum CodingKeys: String, CodingKey {
        case chatId
        case chatType = "type"
        case otherUser
        case createdAt
        case lastMessageAt
        case otherMemberDeliveredSequence
        case otherMemberReadSequence
        case isMuted, mutedUntil, isBlocked, blockedUserId
        case ownReadSequence
    }
}

/// `POST /chats/direct` — the only client input is the other user's id.
struct CreateDirectChatRequest: Encodable {
    let otherUserId: String
}

/// `GET /chats` — offset-based pagination response.
struct ChatListResponse: Codable, Equatable {
    let items: [ChatSummary]
    let nextOffset: Int64?
}

// MARK: - ChatStore (persistent chat-list cache)

private let chatSqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// Account-scoped SQLite store for the chat list, mirroring Android's
/// Room-backed `chats` / `chat_members` / `chat_participants` tables. The
/// home screen hydrates from here first, then merges `GET /chats` into it:
/// a failed or empty refresh never wipes previously cached chats, so the
/// list survives restart and offline.
///
/// Lives in ChatModels.swift (rather than a standalone file) so it stays in
/// the app target without a pbxproj edit — same reason CallsView lives inside
/// LoggedInView.swift.
final class ChatStore {
    enum StoreError: Error, LocalizedError {
        case openFailed
        case stepFailed
        case bindingFailed

        var errorDescription: String? {
            switch self {
            case .openFailed: return "Could not open the local chat store."
            case .stepFailed: return "Local chat store query failed."
            case .bindingFailed: return "Local chat store write failed."
            }
        }
    }

    private var db: OpaquePointer?
    private let databaseURL: URL

    init(databaseURL: URL, readOnly: Bool = false) throws {
        self.databaseURL = databaseURL
        var handle: OpaquePointer?
        let flags = (readOnly ? SQLITE_OPEN_READONLY : SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE) | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(databaseURL.path, &handle, flags, nil) == SQLITE_OK else {
            throw StoreError.openFailed
        }
        db = handle
        if readOnly { sqlite3_busy_timeout(handle, 1000); return }
        try execute(
            """
            CREATE TABLE IF NOT EXISTS chat_preferences (
                chat_id TEXT PRIMARY KEY NOT NULL, muted INTEGER NOT NULL, until_date TEXT,
                blocked INTEGER NOT NULL, blocked_user TEXT
            );
            CREATE TABLE IF NOT EXISTS deleted_chats (chat_id TEXT PRIMARY KEY NOT NULL);
            CREATE TABLE IF NOT EXISTS chats (
                chat_id TEXT PRIMARY KEY NOT NULL,
                type TEXT NOT NULL,
                created_at TEXT NOT NULL,
                last_message_at TEXT,
                other_delivered_sequence INTEGER NOT NULL DEFAULT 0,
                other_read_sequence INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_chats_last_message_at ON chats(last_message_at);
            CREATE TABLE IF NOT EXISTS chat_members (
                chat_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                PRIMARY KEY (chat_id, user_id)
            );
            CREATE INDEX IF NOT EXISTS idx_chat_members_chat ON chat_members(chat_id);
            CREATE TABLE IF NOT EXISTS chat_participants (
                user_id TEXT PRIMARY KEY NOT NULL,
                username TEXT NOT NULL,
                display_name TEXT NOT NULL,
                avatar_url TEXT,
                bio TEXT,
                profile_version INTEGER NOT NULL DEFAULT 0,
                avatar_version INTEGER NOT NULL DEFAULT 0,
                has_avatar INTEGER NOT NULL DEFAULT 0
            );
            """
        )
    }

    deinit {
        if let db { sqlite3_close(db) }
    }

    // MARK: - Writes

    /// Merges the server's chat list into the cache (upsert by `chat_id`).
    /// Never deletes: a partial page or an empty response must not erase
    /// previously cached chats (parity with Android's upsert-only refresh).
    func upsertChats(_ chats: [ChatSummary]) throws {
        try execute("BEGIN IMMEDIATE;")
        do {
            for chat in chats { try upsertOne(chat) }
            try execute("COMMIT;")
        } catch {
            try? execute("ROLLBACK;")
            throw error
        }
    }

    private func upsertOne(_ chat: ChatSummary) throws {
        guard try !isDeleted(chat.chatId) else { return }
        try withStatement(
            """
            INSERT INTO chats
            (chat_id, type, created_at, last_message_at, other_delivered_sequence, other_read_sequence)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(chat_id) DO UPDATE SET
                type = excluded.type, created_at = excluded.created_at,
                last_message_at = CASE WHEN chats.last_message_at IS NULL THEN excluded.last_message_at
                    WHEN excluded.last_message_at IS NULL THEN chats.last_message_at
                    WHEN julianday(excluded.last_message_at) > julianday(chats.last_message_at) THEN excluded.last_message_at ELSE chats.last_message_at END,
                other_delivered_sequence = MAX(chats.other_delivered_sequence, excluded.other_delivered_sequence),
                other_read_sequence = MAX(chats.other_read_sequence, excluded.other_read_sequence);
            """
        ) { stmt in
            try bindText(stmt, 1, chat.chatId)
            try bindText(stmt, 2, chat.chatType)
            try bindText(stmt, 3, chat.createdAt)
            try bindOptionalText(stmt, 4, chat.lastMessageAt)
            sqlite3_bind_int64(stmt, 5, chat.otherMemberDeliveredSequence)
            sqlite3_bind_int64(stmt, 6, chat.otherMemberReadSequence)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        try withStatement("INSERT OR REPLACE INTO chat_preferences(chat_id, muted, until_date, blocked, blocked_user) VALUES (?, ?, ?, ?, ?)") { stmt in
            try bindText(stmt, 1, chat.chatId)
            sqlite3_bind_int(stmt, 2, chat.isMuted == true ? 1 : 0)
            try bindOptionalText(stmt, 3, chat.mutedUntil)
            sqlite3_bind_int(stmt, 4, chat.isBlocked == true ? 1 : 0)
            try bindOptionalText(stmt, 5, chat.blockedUserId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        if let other = chat.otherUser {
            try withStatement(
                """
                INSERT OR REPLACE INTO chat_participants
                (user_id, username, display_name, avatar_url, bio, profile_version, avatar_version, has_avatar)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?);
                """
            ) { stmt in
                try bindText(stmt, 1, other.id)
                try bindText(stmt, 2, other.username)
                try bindText(stmt, 3, other.displayName)
                try bindOptionalText(stmt, 4, other.avatarUrl)
                try bindOptionalText(stmt, 5, other.bio)
                sqlite3_bind_int64(stmt, 6, other.profileVersion)
                sqlite3_bind_int64(stmt, 7, other.avatarVersion)
                sqlite3_bind_int64(stmt, 8, other.hasAvatar ? 1 : 0)
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
            try withStatement(
                "INSERT OR REPLACE INTO chat_members (chat_id, user_id) VALUES (?, ?);"
            ) { stmt in
                try bindText(stmt, 1, chat.chatId)
                try bindText(stmt, 2, other.id)
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
        }
    }

    func isDeleted(_ chatId: String) throws -> Bool {
        var deleted = false
        try withStatement("SELECT 1 FROM deleted_chats WHERE chat_id=?;") { stmt in
            try bindText(stmt, 1, chatId)
            let step = sqlite3_step(stmt)
            guard step == SQLITE_ROW || step == SQLITE_DONE else { throw StoreError.stepFailed }
            deleted = step == SQLITE_ROW
        }
        return deleted
    }
    func deleteChat(_ chatId: String) throws {
        try execute("BEGIN IMMEDIATE;")
        do {
            for sql in ["INSERT OR IGNORE INTO deleted_chats(chat_id) VALUES (?);", "DELETE FROM chat_members WHERE chat_id=?;", "DELETE FROM chats WHERE chat_id=?;", "DELETE FROM chat_preferences WHERE chat_id=?;"] {
                try withStatement(sql) { stmt in
                    try bindText(stmt, 1, chatId)
                    guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
                }
            }
            try execute("DELETE FROM chat_participants WHERE user_id NOT IN (SELECT user_id FROM chat_members);")
            try execute("COMMIT;")
        } catch { try? execute("ROLLBACK;"); throw error }
    }

    // MARK: - Reads

    /// Independent read-only connection: list hydration must not run SQLite
    /// on the main actor or share a connection with a write transaction.
    func loadChatsAsync() async throws -> [ChatSummary] {
        let url = databaseURL
        let work = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            let reader = try ChatStore(databaseURL: url, readOnly: true)
            let chats = try reader.loadChats()
            try Task.checkCancellation()
            return chats
        }
        return try await withTaskCancellationHandler(operation: { try await work.value }, onCancel: { work.cancel() })
    }

    /// Cached chat list, ordered like Android's `observeChats`:
    /// `(last_message_at IS NULL), last_message_at DESC, chat_id DESC`.
    func loadChats() throws -> [ChatSummary] {
        try query(
            """
            SELECT c.chat_id, c.type, c.created_at, c.last_message_at,
                   c.other_delivered_sequence, c.other_read_sequence,
                   p.user_id, p.username, p.display_name, p.avatar_url, p.bio,
                   p.profile_version, p.avatar_version, p.has_avatar, cp.muted, cp.until_date, cp.blocked, cp.blocked_user
            FROM chats c
            LEFT JOIN chat_preferences cp ON cp.chat_id = c.chat_id
            LEFT JOIN chat_members m ON m.chat_id = c.chat_id
            LEFT JOIN chat_participants p ON p.user_id = m.user_id
            WHERE NOT EXISTS (SELECT 1 FROM deleted_chats d WHERE d.chat_id = c.chat_id)
            ORDER BY (c.last_message_at IS NULL), c.last_message_at DESC, c.chat_id DESC;
            """,
            binding: { _ in }
        )
    }

    // MARK: - Helpers

    private func query(
        _ sql: String,
        binding: (OpaquePointer) throws -> Void
    ) throws -> [ChatSummary] {
        var result: [ChatSummary] = []
        try withStatement(sql) { stmt in
            try binding(stmt)
            while sqlite3_step(stmt) == SQLITE_ROW {
                result.append(decodeRow(stmt))
            }
        }
        return result
    }

    private func decodeRow(_ stmt: OpaquePointer) -> ChatSummary {
        let chatId = String(cString: sqlite3_column_text(stmt, 0))
        let type = String(cString: sqlite3_column_text(stmt, 1))
        let createdAt = String(cString: sqlite3_column_text(stmt, 2))
        let lastMessageAt = columnOptionalText(stmt, 3)
        let delivered = sqlite3_column_int64(stmt, 4)
        let read = sqlite3_column_int64(stmt, 5)
        var otherUser: PublicUser?
        if sqlite3_column_type(stmt, 6) != SQLITE_NULL {
            otherUser = PublicUser(
                id: String(cString: sqlite3_column_text(stmt, 6)),
                username: columnOptionalText(stmt, 7) ?? "",
                displayName: columnOptionalText(stmt, 8) ?? "",
                bio: columnOptionalText(stmt, 10),
                avatarUrl: columnOptionalText(stmt, 9),
                profileVersion: sqlite3_column_int64(stmt, 11),
                avatarVersion: sqlite3_column_int64(stmt, 12),
                hasAvatar: sqlite3_column_int64(stmt, 13) != 0
            )
        }
        return ChatSummary(
            chatId: chatId,
            chatType: type,
            otherUser: otherUser,
            createdAt: createdAt,
            lastMessageAt: lastMessageAt,
            otherMemberDeliveredSequence: delivered,
            otherMemberReadSequence: read,
            isMuted: sqlite3_column_int(stmt, 14) != 0,
            mutedUntil: columnOptionalText(stmt, 15),
            isBlocked: sqlite3_column_int(stmt, 16) != 0,
            blockedUserId: columnOptionalText(stmt, 17)
        )
    }

    private func execute(_ sql: String) throws {
        var errorMessage: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(db, sql, nil, nil, &errorMessage) == SQLITE_OK else {
            sqlite3_free(errorMessage)
            throw StoreError.openFailed
        }
    }

    private func withStatement(
        _ sql: String,
        _ body: (OpaquePointer) throws -> Void
    ) throws {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw StoreError.stepFailed
        }
        defer { sqlite3_finalize(stmt) }
        try body(stmt)
    }

    private func columnOptionalText(_ stmt: OpaquePointer, _ index: Int32) -> String? {
        guard sqlite3_column_type(stmt, index) != SQLITE_NULL else { return nil }
        return String(cString: sqlite3_column_text(stmt, index))
    }

    private func bindText(_ stmt: OpaquePointer, _ index: Int32, _ value: String) throws {
        guard sqlite3_bind_text(stmt, index, (value as NSString).utf8String, -1, chatSqliteTransient) == SQLITE_OK else {
            throw StoreError.bindingFailed
        }
    }

    private func bindOptionalText(_ stmt: OpaquePointer, _ index: Int32, _ value: String?) throws {
        if let value {
            try bindText(stmt, index, value)
        } else {
            sqlite3_bind_null(stmt, index)
        }
    }
}
