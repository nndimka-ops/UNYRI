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
    var otherUser: PublicUser?
    let createdAt: String
    var lastMessageAt: String?
    let otherMemberDeliveredSequence: Int64
    let otherMemberReadSequence: Int64
    /// Last-message preview overlaid from the LOCAL message cache (never
    /// decoded from JSON — see `CodingKeys`). Nil until the cache has a
    /// displayable message for this chat; callers fall back to public identity.
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
    private lazy var erasureCheckpoint = ChatErasureCheckpoint(url: databaseURL)
    private let databaseURL: URL

    init(databaseURL: URL, readOnly: Bool = false) throws {
        self.databaseURL = databaseURL
        var handle: OpaquePointer?
        let flags = (readOnly ? SQLITE_OPEN_READONLY : SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE) | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(databaseURL.path, &handle, flags, nil) == SQLITE_OK else {
            throw StoreError.openFailed
        }
        db = handle
        guard sqlite3_exec(handle, "PRAGMA secure_delete=ON;", nil, nil, nil) == SQLITE_OK else {
            sqlite3_close(handle); db = nil; throw StoreError.openFailed
        }
        sqlite3_busy_timeout(handle, 1000)
        if readOnly { return }
        erasureCheckpoint.request()
        try execute("PRAGMA journal_mode=WAL;")
        try execute(
            """
            CREATE TABLE IF NOT EXISTS deleted_peers (user_id TEXT PRIMARY KEY NOT NULL);
            CREATE TABLE IF NOT EXISTS chat_unbound_history (chat_id TEXT PRIMARY KEY NOT NULL);
            CREATE TABLE IF NOT EXISTS chat_security_binding (
                chat_id TEXT PRIMARY KEY NOT NULL, identity TEXT NOT NULL, frozen INTEGER NOT NULL DEFAULT 0
            );
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
        var participantColumns = Set<String>()
        try withStatement("PRAGMA table_info(chat_participants);") { stmt in
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW {
                if let name = columnOptionalText(stmt, 1) { participantColumns.insert(name) }
                step = sqlite3_step(stmt)
            }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        for column in ["public_username", "unyri_number"] where !participantColumns.contains(column) {
            try execute("ALTER TABLE chat_participants ADD COLUMN \(column) TEXT;")
        }
        // B-033: remove credentials retained by pre-public-identity clients once.
        // Keep the marker and scrub atomic so an interrupted upgrade retries.
        try execute("CREATE TABLE IF NOT EXISTS chat_store_migrations (name TEXT PRIMARY KEY NOT NULL);")
        try execute("""
            BEGIN IMMEDIATE;
            UPDATE chat_participants SET username = ''
                WHERE NOT EXISTS (SELECT 1 FROM chat_store_migrations WHERE name = 'b033_private_login');
            INSERT OR IGNORE INTO chat_store_migrations(name) VALUES ('b033_private_login');
            COMMIT;
            """)
        // Zero-peer legacy direct rows retain an unresolved direct type. Only
        // multiple peers or conflicting types prove a contradictory binding.
        try execute("""
            INSERT OR IGNORE INTO chat_security_binding(chat_id, identity, frozen)
                SELECT LOWER(c.chat_id), c.type || ':' || COALESCE(LOWER(MIN(m.user_id)), ''),
                       CASE WHEN COUNT(DISTINCT c.type) != 1 OR (c.type = 'direct' AND COUNT(DISTINCT LOWER(m.user_id)) > 1) THEN 1 ELSE 0 END
                FROM chats c LEFT JOIN chat_members m ON m.chat_id = c.chat_id
                WHERE LOWER(c.chat_id) NOT IN (SELECT chat_id FROM chat_unbound_history)
                GROUP BY LOWER(c.chat_id);
            """)
        for operation in ["INSERT", "UPDATE"] {
            try execute("CREATE TRIGGER IF NOT EXISTS deleted_peer_profile_\(operation) BEFORE \(operation) ON chat_participants WHEN EXISTS(SELECT 1 FROM deleted_peers WHERE user_id=NEW.user_id) BEGIN SELECT RAISE(IGNORE); END;")
        }
    }

    deinit {
        if let db { sqlite3_close(db) }
    }

    // MARK: - Writes

    /// Merges the server's chat list into the cache (upsert by `chat_id`).
    /// Never deletes: a partial page or an empty response must not erase
    /// previously cached chats (parity with Android's upsert-only refresh).
    func upsertChats(_ chats: [ChatSummary]) throws {
        for chat in chats { try validateBinding(chat) }
        try execute("BEGIN IMMEDIATE;")
        do {
            let deleted = try deletedChatIDs()
            for chat in chats where !deleted.contains(chat.chatId) { try upsertOne(chat, checkDeleted: false) }
            try execute("COMMIT;")
        } catch {
            try? execute("ROLLBACK;")
            throw error
        }
    }

    /// Only the legacy archive importer may create a presentation shell without
    /// a type/peer assertion. Never remove or overwrite an existing pin here.
    func upsertUnboundHistoryShell(_ chat: ChatSummary) throws {
        guard chat.chatType == "group", chat.otherUser == nil else { throw DirectChatBindingError.changed }
        try execute("BEGIN IMMEDIATE;")
        do {
            var exists = false
            try withStatement("SELECT 1 FROM chats WHERE LOWER(chat_id)=? UNION ALL SELECT 1 FROM chat_security_binding WHERE chat_id=?;") { stmt in
                try bindText(stmt, 1, chat.chatId.lowercased()); try bindText(stmt, 2, chat.chatId.lowercased())
                let result = sqlite3_step(stmt)
                guard result == SQLITE_ROW || result == SQLITE_DONE else { throw StoreError.stepFailed }
                exists = result == SQLITE_ROW
            }
            if !exists {
                try withStatement("INSERT INTO chat_unbound_history(chat_id) VALUES (?);") { stmt in
                    try bindText(stmt, 1, chat.chatId.lowercased())
                    guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
                }
                try upsertOne(chat)
            }
            try execute("COMMIT;")
        } catch { try? execute("ROLLBACK;"); throw error }
    }
    func unboundHistoryChatIDs() throws -> Set<String> {
        var ids = Set<String>()
        try withStatement("SELECT chat_id FROM chat_unbound_history;") { stmt in
            var result = sqlite3_step(stmt)
            while result == SQLITE_ROW {
                if let id = columnOptionalText(stmt, 0) { ids.insert(id) }
                result = sqlite3_step(stmt)
            }
            guard result == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return ids
    }

    /// Kept outside metadata transactions so a rejection/freeze cannot roll back.
    func validateBinding(_ chat: ChatSummary, requestedPeer: String? = nil) throws {
        let chatID = chat.chatId.lowercased()
        var pinned: String?
        var frozen = false
        try withStatement("SELECT identity, frozen FROM chat_security_binding WHERE chat_id = ?;") { stmt in
            try bindText(stmt, 1, chatID.lowercased())
            let result = sqlite3_step(stmt)
            guard result == SQLITE_ROW || result == SQLITE_DONE else { throw StoreError.stepFailed }
            if result == SQLITE_ROW { pinned = columnOptionalText(stmt, 0); frozen = sqlite3_column_int(stmt, 1) != 0 }
        }
        guard !frozen else { throw DirectChatBindingError.changed }
        let identity: String
        do { identity = try DirectChatBinding.validate(pinned: pinned, type: chat.chatType, peer: chat.otherUser?.id, requestedPeer: requestedPeer) }
        catch DirectChatBindingError.unavailable { throw DirectChatBindingError.unavailable }
        catch {
            try freezeBinding(chatID)
            throw error
        }
        if let pinned, pinned != "direct:" { return }
        if pinned == "direct:" {
            // Promote only an unresolved, unfrozen legacy direct binding. A
            // concurrent resolution/freeze must win before metadata is used.
            try withStatement("UPDATE chat_security_binding SET identity=? WHERE chat_id=? AND identity='direct:' AND frozen=0;") { (stmt: OpaquePointer) throws -> Void in
                try bindText(stmt, 1, identity); try bindText(stmt, 2, chatID)
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
        } else {
            try withStatement("INSERT OR IGNORE INTO chat_security_binding(chat_id, identity) VALUES (?, ?);") { (stmt: OpaquePointer) throws -> Void in
                try bindText(stmt, 1, chatID); try bindText(stmt, 2, identity)
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
        }
        try withStatement("DELETE FROM chat_unbound_history WHERE chat_id=?;") { stmt in
            try bindText(stmt, 1, chatID)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        // Another connection can win INSERT OR IGNORE with a different peer.
        // Re-read before allowing metadata to use this newly established pin.
        try validateBinding(chat, requestedPeer: requestedPeer)
    }

    /// Read authorization uses the original immutable pin, even when sending
    /// is frozen. A durable receive can recover without fetching server metadata.
    func incomingPeerMatches(chatID: String, senderID: String, ownerID: String) throws -> Bool? {
        var identity: String?
        try withStatement("SELECT identity FROM chat_security_binding WHERE chat_id = ?;") { stmt in
            try bindText(stmt, 1, chatID.lowercased())
            let result = sqlite3_step(stmt)
            guard result == SQLITE_ROW || result == SQLITE_DONE else { throw StoreError.stepFailed }
            if result == SQLITE_ROW { identity = columnOptionalText(stmt, 0) }
        }
        guard let identity, identity != "direct:" else { return nil }
        let own = senderID.lowercased() == ownerID.lowercased()
        if identity == "saved:" { return own }
        if identity.hasPrefix("direct:") { return own || identity == "direct:" + senderID.lowercased() }
        return false
    }

    /// Presentation/preference recovery only; sending still validates the pin.
    func pinnedDirectPeer(_ chatID: String) throws -> String? {
        var peer: String?
        try withStatement("SELECT identity FROM chat_security_binding WHERE chat_id=?;") { stmt in
            try bindText(stmt, 1, chatID.lowercased())
            let result = sqlite3_step(stmt)
            guard result == SQLITE_ROW || result == SQLITE_DONE else { throw StoreError.stepFailed }
            if result == SQLITE_ROW, let identity = columnOptionalText(stmt, 0), identity.hasPrefix("direct:"), identity != "direct:" {
                peer = String(identity.dropFirst("direct:".count))
            }
        }
        return peer
    }

    func freezeBinding(_ chatID: String) throws {
        try withStatement("INSERT INTO chat_security_binding(chat_id, identity, frozen) VALUES (?, 'invalid:', 1) ON CONFLICT(chat_id) DO UPDATE SET frozen = 1;") { stmt in
            try bindText(stmt, 1, chatID.lowercased())
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    func bindingIsFrozen(_ chatID: String) throws -> Bool {
        var frozen = false
        try withStatement("SELECT frozen FROM chat_security_binding WHERE chat_id = ?;") { stmt in
            try bindText(stmt, 1, chatID.lowercased())
            let result = sqlite3_step(stmt)
            guard result == SQLITE_ROW || result == SQLITE_DONE else { throw StoreError.stepFailed }
            if result == SQLITE_ROW { frozen = sqlite3_column_int(stmt, 0) != 0 }
        }
        return frozen
    }

    private func upsertOne(_ chat: ChatSummary, checkDeleted: Bool = true) throws {
        if checkDeleted { guard try !isDeleted(chat.chatId) else { return } }
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
                (user_id, username, display_name, avatar_url, bio, profile_version, avatar_version, has_avatar, public_username, unyri_number)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """
            ) { stmt in
                try bindText(stmt, 1, other.id)
                try bindText(stmt, 2, "")
                try bindText(stmt, 3, other.displayName)
                try bindOptionalText(stmt, 4, other.avatarUrl)
                try bindOptionalText(stmt, 5, other.bio)
                sqlite3_bind_int64(stmt, 6, other.profileVersion)
                sqlite3_bind_int64(stmt, 7, other.avatarVersion)
                sqlite3_bind_int64(stmt, 8, other.hasAvatar ? 1 : 0)
                try bindOptionalText(stmt, 9, other.publicUsername)
                try bindOptionalText(stmt, 10, other.unyriNumber)
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

    func erasePeerProfile(_ userID: String) throws {
        try execute("BEGIN IMMEDIATE;")
        do {
            try withStatement("UPDATE chat_participants SET username='',display_name=?,avatar_url=NULL,bio=NULL,has_avatar=0,public_username=NULL WHERE user_id=?;") { stmt in
                try bindText(stmt, 1, UnyriL10n.string("Deleted account")); try bindText(stmt, 2, userID)
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
            try withStatement("INSERT OR IGNORE INTO deleted_peers(user_id) VALUES(?);") { stmt in
                try bindText(stmt, 1, userID)
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
            try execute("COMMIT;")
            erasureCheckpoint.request()
        } catch { try? execute("ROLLBACK;"); throw error }
    }

    func deletedChatIDs() throws -> Set<String> {
        var ids = Set<String>()
        try withStatement("SELECT chat_id FROM deleted_chats;") { stmt in
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW {
                ids.insert(String(cString: sqlite3_column_text(stmt, 0)))
                step = sqlite3_step(stmt)
            }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return ids
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
                   p.profile_version, p.avatar_version, p.has_avatar, cp.muted, cp.until_date, cp.blocked, cp.blocked_user,
                   p.public_username, p.unyri_number
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
                username: "",
                displayName: columnOptionalText(stmt, 8) ?? "",
                bio: columnOptionalText(stmt, 10),
                avatarUrl: columnOptionalText(stmt, 9),
                profileVersion: sqlite3_column_int64(stmt, 11),
                avatarVersion: sqlite3_column_int64(stmt, 12),
                hasAvatar: sqlite3_column_int64(stmt, 13) != 0,
                unyriNumber: columnOptionalText(stmt, 19),
                publicUsername: columnOptionalText(stmt, 18)
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
        if sql.contains("DELETE FROM") { erasureCheckpoint.request() }
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
        if sql.contains("DELETE FROM") || (sql.contains("UPDATE messages") &&
            (sql.contains("delete_sequence") || sql.contains("hidden_locally"))) {
            erasureCheckpoint.request()
        }
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

/// Debounced WAL truncation on a separate connection; no recurring timer or UI work.
private final class ChatErasureCheckpoint: @unchecked Sendable {
    private let url: URL
    private let lock = NSLock()
    private var revision: UInt64 = 0
    private var pending: DispatchWorkItem?
    init(url: URL) { self.url = url }
    func request(attempt: Int = 0) {
        lock.lock()
        // A durable, content-free marker survives process death and a busy WAL.
        // A later writable open calls request again; no idle poll is installed.
        let marker = URL(fileURLWithPath: url.path + ".erase-pending")
        if FileManager.default.fileExists(atPath: url.path), !FileManager.default.fileExists(atPath: marker.path) {
            try? Data([1]).write(to: marker, options: .atomic)
        }
        revision &+= 1
        let ticket = revision
        pending?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let current = self.revision == ticket
            self.lock.unlock()
            guard current else { return }
            // READWRITE without CREATE: an account wipe can never resurrect its DB.
            var db: OpaquePointer?
            guard sqlite3_open_v2(self.url.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
                if let db { sqlite3_close(db) }; return
            }
            defer { sqlite3_close(db) }
            sqlite3_busy_timeout(db, 1000)
            // A fresh handle has not opened the WAL yet. Without a completed
            // schema read, checkpoint can return OK with -1/-1 and do no work.
            guard sqlite3_exec(db, "PRAGMA secure_delete=ON;", nil, nil, nil) == SQLITE_OK,
                  sqlite3_exec(db, "SELECT name FROM sqlite_master LIMIT 1;", nil, nil, nil) == SQLITE_OK else { return }
            var logFrames: Int32 = -1
            var checkpointedFrames: Int32 = -1
            let status = sqlite3_wal_checkpoint_v2(db, "main", SQLITE_CHECKPOINT_TRUNCATE, &logFrames, &checkpointedFrames)
            self.lock.lock()
            let stillCurrent = self.revision == ticket
            if status == SQLITE_OK, logFrames >= 0, checkpointedFrames >= 0, stillCurrent {
                try? FileManager.default.removeItem(at: marker)
            }
            self.lock.unlock()
            // Bounded retries only while deletion work is pending. Long readers
            // keep the marker until another real deletion or writable open.
            if stillCurrent, [SQLITE_BUSY, SQLITE_LOCKED].contains(status), attempt < 2 {
                self.request(attempt: attempt + 1)
            }
        }
        pending = work
        lock.unlock()
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.6, execute: work)
    }
    deinit { pending?.cancel() }
}
