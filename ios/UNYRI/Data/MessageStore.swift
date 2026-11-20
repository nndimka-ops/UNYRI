import Foundation
import SQLite3

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// One recovery owner, many cancellable consumers. Cancelling a share/composer
/// wait detaches only that consumer; retirement still cancels and drains `result`.
final class DraftSourceRecovery: @unchecked Sendable {
    private let task: Task<Void, Error>
    private let lock = NSLock()
    private var finished = false
    private var waiters: [UUID: AsyncStream<Void>.Continuation] = [:]
    init(_ task: Task<Void, Error>) {
        self.task = task
        Task.detached { [self] in _ = await task.result; self.complete() }
    }
    var value: Void { get async throws { try await task.value } }
    var result: Result<Void, Error> { get async { await task.result } }
    func cancel() { task.cancel() }
    var waitingConsumerCount: Int { lock.lock(); defer { lock.unlock() }; return waiters.count }
    func wait() async throws {
        try Task.checkCancellation()
        let id = UUID()
        let stream = AsyncStream<Void> { self.register(id, $0) }
        try await withTaskCancellationHandler(operation: {
            for await _ in stream {}
            try Task.checkCancellation()
        }, onCancel: { self.detach(id) })
    }
    private func register(_ id: UUID, _ continuation: AsyncStream<Void>.Continuation) {
        lock.lock()
        if finished { lock.unlock(); continuation.finish(); return }
        waiters[id] = continuation
        lock.unlock()
    }
    private func detach(_ id: UUID) {
        lock.lock(); let continuation = waiters.removeValue(forKey: id); lock.unlock()
        continuation?.finish()
    }
    private func complete() {
        lock.lock(); finished = true
        let pending = Array(waiters.values); waiters.removeAll()
        lock.unlock()
        for continuation in pending { continuation.finish() }
    }
}

/// Raw, self-contained preview of a chat's latest message, built only from
/// the local message store. Kept separate from the UI's `ChatPreview`
/// (ChatModels.swift) so MessageStore compiles without depending on
/// ChatModels — the macOS contract steps compile those files in isolation.
enum MessagePreview: Equatable {
    case text(String)
    case conferenceInvitation
    case image
    case voice
    case video
    case file(String?)
    case deleted
}

// MARK: - Native history archive transaction
extension MessageStore {
    func historyAttachmentJobs() throws -> [(chat: String, client: String, payload: String)] {
        try historyRestoreJobs(prefix: "restore_attachment:")
    }
    func historyCleanupJobs() throws -> [(chat: String, client: String, payload: String)] {
        try historyRestoreJobs(prefix: "restore_cleanup:")
    }
    func historyDeletedChatJobs() throws -> [(chat: String, client: String, payload: String)] {
        try historyRestoreJobs(prefix: "restore_deleted_chat:")
    }
    private func historyRestoreJobs(prefix: String) throws -> [(chat: String, client: String, payload: String)] {
        var jobs: [(chat: String, client: String, payload: String)] = []
        try withStatement("SELECT chat_id,substr(name,?),value FROM chat_workspace WHERE substr(name,1,?)=? LIMIT 50001;") { stmt in
            sqlite3_bind_int(stmt,1,Int32(prefix.count + 1)); sqlite3_bind_int(stmt,2,Int32(prefix.count)); try bindText(stmt,3,prefix)
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW {
                jobs.append((String(cString:sqlite3_column_text(stmt,0)),String(cString:sqlite3_column_text(stmt,1)),String(cString:sqlite3_column_text(stmt,2))))
                step = sqlite3_step(stmt)
            }
            guard step == SQLITE_DONE, jobs.count <= 50_000 else { throw StoreError.stepFailed }
        }
        return jobs
    }
    func finishHistoryAttachmentJob(chat: String, client: String, payload: String) throws {
        try finishHistoryRestoreJob(chat:chat,name:"restore_attachment:" + client,payload:payload)
    }
    func finishHistoryRestoreJob(chat: String, name: String, payload: String) throws {
        guard ["restore_attachment:","restore_cleanup:","restore_deleted_chat:"].contains(where:name.hasPrefix) else { throw StoreError.stepFailed }
        try withStatement("DELETE FROM chat_workspace WHERE chat_id=? AND name=? AND value=?;") { stmt in
            try bindText(stmt,1,chat); try bindText(stmt,2,name); try bindText(stmt,3,payload)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }
    /// The caller must hold its history-operation lease and reject account changes
    /// and in-flight optimistic edits. These synchronous MainActor methods do not
    /// suspend between validation and COMMIT. No sync/read cursor is advanced.
    func exportHistoryArchive(server: String, owner: String, snapshot: String,
                              assertCurrentAndQuiescent: () throws -> Void) throws -> HistoryArchive {
        try Task.checkCancellation()
        try assertCurrentAndQuiescent()
        workspaceQueue.sync {}
        try execute("BEGIN IMMEDIATE;")
        do {
            try withStatement("SELECT 1 FROM messages WHERE hidden_locally=0 AND (status IN ('pending','failed') OR (kind<>'content' AND status NOT IN ('sent','delivered','read'))) LIMIT 1;") { stmt in
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw HistoryArchive.Failure.conflict }
            }
            let archive = try readHistoryArchive(server: server, owner: owner, snapshot: snapshot, allowUnreadable: false)
            try Task.checkCancellation()
            try assertCurrentAndQuiescent()
            try execute("COMMIT;")
            return archive
        } catch { try? execute("ROLLBACK;"); throw error }
    }

    /// Authenticated archive + separately verified complete media are prerequisites.
    /// Attachment hydration is journalled with the messages because iOS stores
    /// attachments in a second SQLite database. The coordinator must drain this
    /// journal before announcing success or exposing the restored chat list.
    func importHistoryArchive(_ archive: HistoryArchive, server: String, owner: String,
                              assertCurrentAndQuiescent: () throws -> Void) throws -> Set<String> {
        try Task.checkCancellation()
        try archive.validate(server: server, owner: owner)
        for row in archive.messages { _ = try historyContent(row, owner: owner) }
        try assertCurrentAndQuiescent()
        workspaceQueue.sync {}
        try execute("BEGIN IMMEDIATE;")
        do {
            // Ordinary unsent content is device-local and is excluded from the
            // archive projection. Preserve it while rejecting optimistic
            // lifecycle operations that may still change an imported target.
            try withStatement("SELECT 1 FROM messages WHERE hidden_locally=0 AND delete_sequence=0 AND kind<>'content' AND status NOT IN ('sent','delivered','read') LIMIT 1;") { stmt in
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw HistoryArchive.Failure.conflict }
            }
            let local = try readHistoryArchive(server: server, owner: owner, snapshot: archive.snapshot, allowUnreadable: true)
            let merged = try HistoryArchiveMerge.merge(local: local, restored: archive)
            let touched = Set((local.messages + merged.messages).map { $0.chat })
                .union(merged.deletedChats).union(merged.chatClears.keys)
            try writeChatValue(chatId: "__server_history__", name: "cleared_through_sequence", value: String(merged.clearedThrough))
            for (chat, cutoff) in merged.chatClears { try writeChatValue(chatId: chat, name: "personal_clear", value: String(cutoff)) }
            for chat in merged.deletedChats {
                try writeChatValue(chatId:"__history_restore__",name:"restore_deleted_chat:" + chat,value:"1")
                try withStatement("INSERT OR IGNORE INTO deleted_chats(chat_id) VALUES(?);") { stmt in
                    try bindText(stmt, 1, chat)
                    guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
                }
            }
            // Archives contain messages, never authoritative call visibility/read state.
            // Quarantine affected local projections until the live call contract is reconciled.
            for chat in merged.deletedChats.union(merged.chatClears.keys) {
                try withStatement("UPDATE call_activity SET visible=0,unread=0,pending_read=0 WHERE chat_id=?;") { stmt in
                    try bindText(stmt,1,chat)
                    guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
                }
            }
            // Persist cleanup work before erasing rows; filesystem cleanup is a
            // recoverable follow-up, never a reason to commit half an import.
            let removed = "chat_id IN (SELECT chat_id FROM deleted_chats) OR sequence <= \(merged.clearedThrough) OR sequence <= (SELECT CAST(value AS INTEGER) FROM chat_workspace w WHERE w.chat_id=messages.chat_id AND name='personal_clear')"
            try execute("INSERT OR IGNORE INTO chat_workspace(chat_id,name,value) SELECT chat_id,'restore_cleanup:' || client_message_id,'1' FROM messages WHERE \(removed);")
            try execute("DELETE FROM reactions WHERE target_message_id IN (SELECT server_id FROM messages WHERE \(removed));")
            try execute("DELETE FROM pending_decrypts WHERE client_message_id IN (SELECT client_message_id FROM messages WHERE \(removed));")
            try execute("DELETE FROM messages WHERE \(removed);")
            for row in merged.messages {
                try Task.checkCancellation()
                let old = try message(byClientMessageId: row.client)
                if let collision = try message(byServerId: row.id), collision.clientMessageId != row.client { throw HistoryArchive.Failure.conflict }
                if let old {
                    guard old.serverId == row.id, old.chatId == row.chat, old.senderId == row.sender,
                          old.sequence == row.sequence, old.kind == "content", old.targetMessageId == row.replyTo,
                          old.status != .pending, old.status != .failed else { throw HistoryArchive.Failure.conflict }
                }
                let content = try historyContent(row, owner: owner) ?? MessageContent(kind: MessageContent.kindDelete)
                let status: MessageStatus = old?.status == .read ? .read : old?.status == .delivered ? .delivered : .sent
                var message = DecryptedChatMessage(clientMessageId: row.client, serverId: row.id,
                    chatId: row.chat, senderId: row.sender, sequence: row.sequence,
                    createdAt: Date(timeIntervalSince1970: Double(row.createdAtMs) / 1000), kind: "content",
                    targetMessageId: row.replyTo, status: status, content: content)
                message.lastEditSequence = row.editedThrough; message.deleteSequence = row.deletedThrough
                message.hiddenLocally = row.hidden
                // Reject duplicate chat/sequence even on legacy SQLite schemas
                // whose index is not unique.
                try withStatement("SELECT client_message_id FROM messages WHERE chat_id=? AND sequence=? AND client_message_id<>? LIMIT 1;") { stmt in
                    try bindText(stmt, 1, row.chat); sqlite3_bind_int64(stmt, 2, row.sequence); try bindText(stmt, 3, row.client)
                    guard sqlite3_step(stmt) == SQLITE_DONE else { throw HistoryArchive.Failure.conflict }
                }
                try writeMessage(message)
                try deletePendingDecrypt(clientMessageId: row.client)
                if content.attachment != nil {
                    try writeChatValue(chatId: row.chat, name: "restore_attachment:" + row.client, value: row.content)
                } else if row.hidden || row.deletedThrough > 0 {
                    try writeChatValue(chatId: row.chat, name: "restore_cleanup:" + row.client, value: "1")
                    try writeChatValue(chatId: row.chat, name: "restore_attachment:" + row.client, value: nil)
                }
            }
            for row in merged.reactions {
                try upsertReaction(targetMessageId: row.target, userId: row.user, emoji: row.emoji, lastReactionSequence: row.sequence)
            }
            // Local newer authenticated lifecycle events may target a row that
            // only became readable through this restore.
            for row in merged.messages {
                try replayLifecycle(targetServerId: row.id)
                if let current = try message(byServerId:row.id), current.hiddenLocally || current.isDeletedForEveryone {
                    try deleteReactions(forMessage:row.id)
                    try writeChatValue(chatId:row.chat,name:"restore_cleanup:" + row.client,value:"1")
                }
            }
            try assertCurrentAndQuiescent()
            try Task.checkCancellation()
            try execute("COMMIT;")
            return touched
        } catch { try? execute("ROLLBACK;"); throw error }
    }

    private func historyContent(_ row: HistoryArchiveMessage, owner: String) throws -> MessageContent? {
        guard let payload = row.content else { return nil }
        let content = try decoder.decode(MessageContent.self, from: Data(payload.utf8))
        guard content.version == 1, content.authentication == nil,
              [MessageContent.kindText, MessageContent.kindAttachment].contains(content.kind) else { throw HistoryArchive.Failure.invalidArchive }
        if content.kind == MessageContent.kindText {
            guard content.text != nil, content.attachment == nil else { throw HistoryArchive.Failure.invalidArchive }
        } else {
            guard let a = content.attachment else { throw HistoryArchive.Failure.invalidArchive }
            try HistoryArchive.validateId(a.attachmentId)
            guard a.size >= 0, a.encryptedSize > 0, (1...1024).contains(a.objectKey.utf8.count),
                  (1...255).contains(a.mimeType.utf8.count), [1,2].contains(a.encryption.version),
                  Data(base64Encoded: a.encryption.sha256)?.count == 32 else { throw HistoryArchive.Failure.invalidArchive }
            if let image = a.image {
                guard (1...32768).contains(image.width), (1...32768).contains(image.height) else { throw HistoryArchive.Failure.invalidArchive }
            }
            for duration in [a.voice?.durationMs,a.video?.durationMs].compactMap({ $0 }) {
                guard (0...Int64(Int32.max)).contains(duration) else { throw HistoryArchive.Failure.invalidArchive }
            }
            if a.encryption.version == 1 {
                guard let key = a.encryption.key, let nonce = a.encryption.nonce,
                      Data(base64Encoded: key)?.count == 32, Data(base64Encoded: nonce)?.count == 12 else { throw HistoryArchive.Failure.invalidArchive }
            } else {
                guard let keyset = a.encryption.keyset, let bytes = Data(base64Encoded: keyset),
                      (1...4096).contains(bytes.count) else { throw HistoryArchive.Failure.invalidArchive }
            }
        }
        if payload.contains("direct_file") {
            let raw = try JSONSerialization.jsonObject(with: Data(payload.utf8)) as? [String: Any]
            if raw?.keys.contains("direct_file") == true, content.directFile == nil { throw HistoryArchive.Failure.invalidArchive }
        }
        if let offer = content.directFile {
            guard offer.hasValidShape, content.kind == MessageContent.kindText, !content.forwarded,
                  content.conferenceInvite == nil, offer.offerClientMessageId == row.client,
                  offer.senderUserId == row.sender,
                  [offer.senderUserId, offer.recipientUserId].contains(owner) else { throw HistoryArchive.Failure.invalidArchive }
        }
        return content
    }

    private func readHistoryArchive(server: String, owner: String, snapshot: String, allowUnreadable: Bool) throws -> HistoryArchive {
        let historyEncoder = JSONEncoder()
        historyEncoder.outputFormatting = [.sortedKeys]
        var result = HistoryArchive(server: server, owner: owner, snapshot: snapshot, messages: [])
        guard let cleared = Int64(try chatValue(chatId: "__server_history__", name: "cleared_through_sequence") ?? "0") else { throw StoreError.stepFailed }
        result.clearedThrough = cleared
        try withStatement("SELECT chat_id,value FROM chat_workspace WHERE name='personal_clear' LIMIT 10001;") { stmt in
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW {
                guard let value = Int64(String(cString: sqlite3_column_text(stmt, 1))) else { throw StoreError.stepFailed }
                result.chatClears[String(cString: sqlite3_column_text(stmt, 0))] = value
                step = sqlite3_step(stmt)
            }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        try withStatement("SELECT chat_id FROM deleted_chats LIMIT 10001;") { stmt in
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW { result.deletedChats.insert(String(cString: sqlite3_column_text(stmt, 0))); step = sqlite3_step(stmt) }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        var budget = 1024 + result.chatClears.count * 80 + result.deletedChats.count * 40
        try withStatement("SELECT \(Self.messageColumns) FROM messages WHERE kind='content' AND sequence IS NOT NULL ORDER BY sequence LIMIT 50001;") { stmt in
            var step = sqlite3_step(stmt), count = 0
            while step == SQLITE_ROW {
                try Task.checkCancellation()
                count += 1; guard count <= 50_000 else { throw HistoryArchive.Failure.invalidArchive }
                let message = try decodeRow(stmt)
                guard let sequence = message.sequence, sequence > 0, let id = message.serverId else { throw HistoryArchive.Failure.invalidArchive }
                if !result.deletedChats.contains(message.chatId) && sequence > max(result.clearedThrough, result.chatClears[message.chatId] ?? 0) {
                    let readable = [MessageStatus.sent,.delivered,.read].contains(message.status)
                    if !readable && !message.hiddenLocally && !message.isDeletedForEveryone {
                        guard allowUnreadable else { throw HistoryArchive.Failure.invalidArchive }
                    } else {
                        var content = message.content; content.authentication = nil
                        let payload = message.hiddenLocally || message.isDeletedForEveryone ? nil : String(decoding: try historyEncoder.encode(content), as: UTF8.self)
                        budget += 512 + 6 * (payload?.utf8.count ?? 0)
                        guard budget <= HistoryArchive.maxBytes else { throw HistoryArchive.Failure.invalidArchive }
                        result.messages.append(HistoryArchiveMessage(client: message.clientMessageId, id: id, chat: message.chatId,
                            sender: message.senderId, sequence: sequence, createdAtMs: Int64((message.createdAt.timeIntervalSince1970 * 1000).rounded(.down)),
                            content: payload, editedThrough: message.lastEditSequence, deletedThrough: message.deleteSequence,
                            hidden: message.hiddenLocally, replyTo: message.targetMessageId))
                    }
                }
                step = sqlite3_step(stmt)
            }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        let live = Set(result.messages.filter { !$0.hidden && $0.deletedThrough == 0 }.map { $0.id })
        try withStatement("SELECT target_message_id,user_id,emoji,last_reaction_sequence FROM reactions ORDER BY target_message_id,user_id LIMIT 50001;") { stmt in
            var step = sqlite3_step(stmt), count = 0
            while step == SQLITE_ROW {
                count += 1; guard count <= 50_000 else { throw HistoryArchive.Failure.invalidArchive }
                let row = decodeReactionRow(stmt)
                if live.contains(row.targetMessageId) { result.reactions.append(HistoryArchiveReaction(target: row.targetMessageId, user: row.userId, sequence: row.lastReactionSequence, emoji: row.emoji)) }
                step = sqlite3_step(stmt)
            }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        try result.validate(server: server, owner: owner)
        for row in result.messages { _ = try historyContent(row, owner: owner) }
        return result
    }
}

/// One row in the `reactions` table — the current reaction state for a
/// (target message, user) pair. Mirrors Android's `ReactionEntity`.
struct ReactionRow: Equatable {
    let targetMessageId: String
    let userId: String
    let emoji: String?
    let lastReactionSequence: Int64
}

/// Minimal persistent SQLite store for messages. Provides the durable
/// `client_message_id` dedup boundary (survives restart) and chronological
/// message rows, matching Android's Room-backed message table. Writes belong
/// to the MainActor repository; background reads own a separate connection.
/// Keyset cursor for bounded outbox reads. `created_at` is stable for a row;
/// `rowid` breaks ties without loading or decoding the whole queue.
struct PendingMessageCursor: Equatable {
    let createdAt: Double
    let rowId: Int64
}

struct PendingMessagePage {
    let messages: [DecryptedChatMessage]
    let next: PendingMessageCursor?
    let hasMore: Bool
}

final class MessageStore {
    enum StoreError: Error, LocalizedError {
        case openFailed
        case stepFailed
        case bindingFailed
        case sqliteFailed(operation: String, code: Int32, extendedCode: Int32, message: String)

        var errorDescription: String? {
            switch self {
            case .openFailed: return "Could not open the local message store."
            case .stepFailed: return "Local message store query failed."
            case .bindingFailed: return "Local message store write failed."
            case let .sqliteFailed(operation, code, extendedCode, message):
                return "Local message store \(operation) failed (SQLite \(code)/\(extendedCode)): \(message)"
            }
        }
    }

    private var db: OpaquePointer?
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private lazy var erasureCheckpoint = MessageErasureCheckpoint(url: databaseURL)
    private let databaseURL: URL
    private let readQueue = DispatchQueue(label: "unyri.messages.read", qos: .userInitiated)
    private var reader: MessageStore?
    private let workspaceQueue = DispatchQueue(label: "unyri.messages.workspace", qos: .userInitiated)
    private var workspaceWriter: MessageStore?
    private final class DraftRecoveryProtection: @unchecked Sendable {
        private let lock = NSLock()
        private var active = true
        private var claimed = Set<String>()
        func claim(_ paths: [String]) {
            lock.lock(); defer { lock.unlock() }
            if active { claimed.formUnion(paths) }
        }
        func contains(_ path: String) -> Bool {
            lock.lock(); defer { lock.unlock() }
            return claimed.contains(path)
        }
        func finish() {
            lock.lock(); defer { lock.unlock() }
            active = false; claimed.removeAll()
        }
    }
    private let draftRecoveryProtection = DraftRecoveryProtection()

    init(databaseURL: URL, readOnly: Bool = false) throws {
        self.databaseURL = databaseURL
        var handle: OpaquePointer?
        let flags = (readOnly ? SQLITE_OPEN_READONLY : SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE) | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(databaseURL.path, &handle, flags, nil) == SQLITE_OK else {
            let error = sqliteFailure("open", handle: handle)
            if let handle { sqlite3_close(handle) }
            throw error
        }
        db = handle
        guard sqlite3_exec(handle, "PRAGMA secure_delete=ON;", nil, nil, nil) == SQLITE_OK else {
            let error = sqliteFailure("secure delete", handle: handle)
            sqlite3_close(handle); db = nil; throw error
        }
        sqlite3_busy_timeout(handle, 1000)
        if readOnly {
            sqlite3_busy_timeout(handle, 1000)
            return
        }
        // Readers must not prevent message ingestion from committing writes.
        try execute("PRAGMA journal_mode=WAL;")
        try execute(
            """
            CREATE TABLE IF NOT EXISTS deleted_chats (chat_id TEXT PRIMARY KEY NOT NULL);
            CREATE TABLE IF NOT EXISTS call_activity (
                owner_id TEXT NOT NULL, call_id TEXT NOT NULL, chat_id TEXT, peer_id TEXT NOT NULL,
                created_at REAL NOT NULL, media_kind TEXT NOT NULL, outcome TEXT NOT NULL,
                visible INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0,
                local_seen INTEGER NOT NULL DEFAULT 0, pending_read INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(owner_id,call_id)
            );
            CREATE INDEX IF NOT EXISTS idx_call_activity_chat ON call_activity(owner_id,chat_id,created_at);
            CREATE TABLE IF NOT EXISTS call_history_delete_commands (
                owner_id TEXT NOT NULL, server_origin TEXT NOT NULL, call_id TEXT NOT NULL,
                everyone INTEGER NOT NULL, pending INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(owner_id,server_origin,call_id));
            CREATE TABLE IF NOT EXISTS chat_clear_commands (
                owner_id TEXT NOT NULL, server_origin TEXT NOT NULL, request_id TEXT NOT NULL,
                chat_id TEXT NOT NULL, for_everyone INTEGER NOT NULL, through_sequence INTEGER NOT NULL,
                calls_before REAL, pending INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(owner_id,server_origin,request_id));
            CREATE TABLE IF NOT EXISTS chat_workspace (
                chat_id TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL,
                PRIMARY KEY(chat_id, name)
            );
            CREATE TABLE IF NOT EXISTS messages (
                client_message_id TEXT PRIMARY KEY NOT NULL,
                server_id TEXT,
                chat_id TEXT NOT NULL,
                sender_id TEXT NOT NULL,
                sequence INTEGER,
                created_at REAL NOT NULL,
                kind TEXT NOT NULL,
                target_message_id TEXT,
                status TEXT NOT NULL,
                content_json TEXT NOT NULL,
                last_edit_sequence INTEGER NOT NULL DEFAULT 0,
                delete_sequence INTEGER NOT NULL DEFAULT 0,
                hidden_locally INTEGER NOT NULL DEFAULT 0,
                payload_text TEXT,
                pending_edit_text TEXT,
                pending_edit_revision INTEGER NOT NULL DEFAULT 0,
                send_attempted INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_messages_chat ON messages(chat_id);
            CREATE INDEX IF NOT EXISTS idx_messages_target ON messages(target_message_id);
            CREATE INDEX IF NOT EXISTS idx_messages_server ON messages(server_id);
            CREATE TABLE IF NOT EXISTS local_read_receipts (
                chat_id TEXT PRIMARY KEY NOT NULL,
                sequence INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS peer_receipts (
                chat_id TEXT PRIMARY KEY NOT NULL,
                owner_id TEXT NOT NULL,
                delivered INTEGER NOT NULL,
                read_sequence INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS reactions (
                target_message_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                emoji TEXT,
                last_reaction_sequence INTEGER NOT NULL,
                PRIMARY KEY (target_message_id, user_id)
            );
            CREATE TABLE IF NOT EXISTS pending_decrypts (
                client_message_id TEXT PRIMARY KEY NOT NULL,
                server_id TEXT NOT NULL,
                chat_id TEXT NOT NULL,
                sender_id TEXT NOT NULL,
                sender_device_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                created_at REAL NOT NULL,
                message_type INTEGER NOT NULL,
                ciphertext TEXT NOT NULL,
                kind TEXT NOT NULL,
                target_message_id TEXT,
                retry_count INTEGER NOT NULL DEFAULT 0,
                last_error_class TEXT,
                notification_batch_alert INTEGER
            );
            CREATE INDEX IF NOT EXISTS idx_pending_decrypts_sender ON pending_decrypts(sender_id);
            CREATE INDEX IF NOT EXISTS idx_pending_decrypts_retry ON pending_decrypts(sequence,client_message_id) WHERE COALESCE(last_error_class,'') NOT IN ('permanent_duplicate','permanent_malformed_ciphertext','permanent_unsupported_type');
            CREATE INDEX IF NOT EXISTS idx_pending_decrypts_sender_retry ON pending_decrypts(sender_id,sequence,client_message_id) WHERE COALESCE(last_error_class,'') NOT IN ('permanent_duplicate','permanent_malformed_ciphertext','permanent_unsupported_type');
            """
        )
        // Forward-compatible column migration for databases created before the
        // lifecycle columns existed (CREATE TABLE IF NOT EXISTS never adds
        // columns to an already-created table).
        for operation in ["INSERT", "UPDATE"] {
            try execute("CREATE TRIGGER IF NOT EXISTS personal_clear_messages_\(operation) BEFORE \(operation) ON messages WHEN NEW.sequence IS NOT NULL AND NEW.sequence <= (SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chat_id=NEW.chat_id AND name='personal_clear') BEGIN SELECT RAISE(IGNORE); END;")
            try execute("CREATE TRIGGER IF NOT EXISTS history_clear_messages_\(operation) BEFORE \(operation) ON messages WHEN NEW.sequence IS NOT NULL AND NEW.sequence <= (SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chat_id='__server_history__' AND name='cleared_through_sequence') BEGIN SELECT RAISE(IGNORE); END;")
        }
        try ensureColumn(table: "pending_decrypts", column: "notification_batch_alert", definition: "INTEGER")
        try ensureColumn(table: "messages", column: "last_edit_sequence", definition: "INTEGER NOT NULL DEFAULT 0")
        try ensureColumn(table: "messages", column: "delete_sequence", definition: "INTEGER NOT NULL DEFAULT 0")
        try ensureColumn(table: "messages", column: "hidden_locally", definition: "INTEGER NOT NULL DEFAULT 0")
        try ensureColumn(table: "messages", column: "payload_text", definition: "TEXT")
        try ensureColumn(table: "messages", column: "pending_edit_text", definition: "TEXT")
        try ensureColumn(table: "messages", column: "pending_edit_revision", definition: "INTEGER NOT NULL DEFAULT 0")
        try ensureColumn(table: "messages", column: "send_attempted", definition: "INTEGER NOT NULL DEFAULT 0")
        try execute("CREATE INDEX IF NOT EXISTS idx_messages_ui_tail ON messages(chat_id, created_at DESC, COALESCE(sequence,9223372036854775807) DESC, client_message_id DESC) WHERE kind='content' AND hidden_locally=0 AND delete_sequence=0;")

        // Install the erasure boundary atomically with respect to other writers.
        try execute("BEGIN IMMEDIATE;")
        do {
            // Scrub the persisted projection at the DB boundary, including restore,
            // sync, local hide and optimistic deletion paths. Retain identity/sequence
            // tombstones so old lifecycle events cannot restore erased plaintext.
            // The journal needs an explicit UPSERT: an outer message UPSERT overrides
            // a trigger's legacy OR REPLACE policy when the cleanup key already exists.
            for operation in ["INSERT", "UPDATE"] {
                // Replace the previous trigger on existing installations too.
                try execute("DROP TRIGGER IF EXISTS erase_message_content_\(operation);")
                try execute("""
                    CREATE TRIGGER erase_message_content_\(operation)
                    AFTER \(operation) ON messages
                    WHEN (NEW.hidden_locally=1 OR NEW.delete_sequence>0) AND
                      (NEW.content_json IS NOT '{"kind":"delete"}' OR NEW.payload_text IS NOT NULL OR NEW.pending_edit_text IS NOT NULL OR NEW.pending_edit_revision<>0)
                    BEGIN
                      INSERT INTO chat_workspace(chat_id,name,value)
                        VALUES(NEW.chat_id,'restore_cleanup:' || NEW.client_message_id,'1')
                        ON CONFLICT(chat_id,name) DO UPDATE SET value=excluded.value;
                      DELETE FROM chat_workspace WHERE chat_id=NEW.chat_id AND name='restore_attachment:' || NEW.client_message_id;
                      UPDATE messages SET content_json='{"kind":"delete"}',payload_text=NULL,pending_edit_text=NULL,pending_edit_revision=0
                        WHERE client_message_id=NEW.client_message_id;
                      DELETE FROM pending_decrypts WHERE client_message_id=NEW.client_message_id;
                      UPDATE messages SET content_json='{"kind":"delete"}',payload_text=NULL,pending_edit_text=NULL,pending_edit_revision=0
                        WHERE chat_id=NEW.chat_id AND target_message_id=NEW.server_id AND kind='edit';
                      UPDATE messages SET content_json=json_remove(content_json,'$.reply_snapshot'),payload_text=NULL
                        WHERE chat_id=NEW.chat_id AND target_message_id=NEW.server_id AND kind='content' AND json_valid(content_json);
                    END;
                    """)
            }
            // Clearing a bounded history can physically remove a target while a
            // later reply survives. Erase only its quoted snapshot, not its own text.
            try execute("DROP TRIGGER IF EXISTS erase_deleted_target_snapshot;")
            try execute("""
                CREATE TRIGGER erase_deleted_target_snapshot AFTER DELETE ON messages
                BEGIN
                  DELETE FROM pending_decrypts WHERE client_message_id=OLD.client_message_id;
                  UPDATE messages SET content_json=json_remove(content_json,'$.reply_snapshot'),payload_text=NULL
                    WHERE chat_id=OLD.chat_id AND target_message_id=OLD.server_id AND kind='content' AND json_valid(content_json);
                  UPDATE messages SET content_json='{"kind":"delete"}',payload_text=NULL,pending_edit_text=NULL
                    WHERE chat_id=OLD.chat_id AND target_message_id=OLD.server_id AND kind='edit';
                END;
                """)
            for operation in ["INSERT", "UPDATE"] {
                try execute("DROP TRIGGER IF EXISTS erase_late_reply_snapshot_\(operation);")
                try execute("""
                    CREATE TRIGGER erase_late_reply_snapshot_\(operation) AFTER \(operation) ON messages
                    WHEN NEW.kind='content' AND CASE WHEN json_valid(NEW.content_json) THEN json_type(NEW.content_json,'$.reply_snapshot') IS NOT NULL ELSE 0 END
                      AND EXISTS(SELECT 1 FROM messages target WHERE target.server_id=NEW.target_message_id AND target.chat_id=NEW.chat_id AND (target.hidden_locally=1 OR target.delete_sequence>0))
                    BEGIN
                      UPDATE messages SET content_json=json_remove(content_json,'$.reply_snapshot'),payload_text=NULL WHERE client_message_id=NEW.client_message_id;
                    END;
                    """)
            }
            try execute("UPDATE messages SET content_json=content_json WHERE hidden_locally=1 OR delete_sequence>0;")
            try execute("COMMIT;")
        } catch {
            try? execute("ROLLBACK;")
            throw error
        }
        erasureCheckpoint.request()

        // One-time conservative backfill: a pending row that predates this
        // schema may already have been POSTed, so it must not be treated as a
        // safe pre-send edit. New rows keep the default 0.
        Self.setCompletedLifecycleRepairs((try? legacyLifecycleRepairCompleteChatIDs()) ?? [], for: databaseURL)
        var schemaVersion: Int32 = 0
        try withStatement("PRAGMA user_version;") { stmt in
            if sqlite3_step(stmt) == SQLITE_ROW { schemaVersion = sqlite3_column_int(stmt, 0) }
        }
        if schemaVersion < 1 {
            try execute("UPDATE messages SET send_attempted = 1 WHERE server_id IS NULL AND kind = 'content' AND status IN ('pending','failed');")
            try execute("PRAGMA user_version = 1;")
        }
    }

    func draftFileDestination(_ source: URL) -> URL {
        databaseURL.deletingLastPathComponent().appendingPathComponent("draft-media", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true).appendingPathComponent(source.lastPathComponent)
    }

    static let trayFilesKey = "composerTrayFiles"
    /// Short-lived owners for IO which may outlive a cancelled composer Send.
    /// These do not represent upload jobs and are retired after process restart.
    func beginRecentFileCopy(sources: [URL]) throws -> String {
        let name = "recent-file-copy:" + UUID().uuidString
        let value = String(decoding: try JSONEncoder().encode(sources), as: UTF8.self)
        // A conversation purge must not retire a still-running reader's owner.
        try saveChatValue(chatId: "__recent_file_copies__", name: name, value: value)
        protectDraftSourcesDuringRecovery(sources)
        return name
    }
    func finishRecentFileCopy(name: String) throws {
        guard name.hasPrefix("recent-file-copy:") else { throw StoreError.stepFailed }
        try saveChatValue(chatId: "__recent_file_copies__", name: name, value: nil)
    }
    private var draftSourceRoot: URL {
        databaseURL.deletingLastPathComponent().appendingPathComponent("draft-media", isDirectory: true).standardizedFileURL
    }

    /// Staged copies have an account-relative identity. Older journals used absolute
    /// URLs; the app container may have moved since those URLs were written.
    private func currentDraftSource(_ url: URL) -> URL? {
        guard url.isFileURL else { return nil }
        let file = url.standardizedFileURL
        if file.path.hasPrefix(draftSourceRoot.path + "/") { return file }
        let parts = file.pathComponents
        guard parts.count >= 3, parts[parts.count - 3] == "draft-media" else { return nil }
        let folder = parts[parts.count - 2]
        // Share jobs use share-<client UUID> rather than the composer's UUID folder.
        // Both identities must survive container moves in the ownership snapshot.
        let identifier = folder.hasPrefix("share-") ? String(folder.dropFirst(6)) : folder
        guard UUID(uuidString: identifier) != nil else { return nil }
        return draftSourceRoot.appendingPathComponent(parts[parts.count - 2], isDirectory: true)
            .appendingPathComponent(parts[parts.count - 1])
    }
    func draftSourceRelativePath(_ url: URL) -> String? {
        currentDraftSource(url).map { String($0.path.dropFirst(draftSourceRoot.path.count + 1)) }
    }
    /// Upload jobs predate relative source identities. Resolve their UUID-scoped
    /// draft into this account's current container before any preparation/read.
    /// The original container may still exist; it must never win over this one.
    func rebasedMediaSource(_ url: URL) -> URL {
        currentDraftSource(url) ?? url
    }
    /// A restored camera draft may join a live tray while the launch reader runs.
    /// Remember every handoff until recovery ends, even if that tray subsequently
    /// hands the source to an upload after the reader took its snapshot.
    func protectDraftSourcesDuringRecovery(_ urls: [URL]) {
        draftRecoveryProtection.claim(urls.map(draftSourceIdentity))
    }
    private func draftSourceJournalURL(_ value: String) -> URL? {
        if let url = URL(string: value), url.isFileURL { return currentDraftSource(url) }
        guard !(value as NSString).isAbsolutePath else { return currentDraftSource(URL(fileURLWithPath: value)) }
        let parts = value.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2, UUID(uuidString: String(parts[0])) != nil,
              !parts[1].isEmpty, parts[1] != ".", parts[1] != "..", !parts[1].contains("\\") else { return nil }
        return draftSourceRoot.appendingPathComponent(String(parts[0]), isDirectory: true).appendingPathComponent(String(parts[1]))
    }
    private func draftSourceIdentity(_ url: URL) -> String {
        (currentDraftSource(url) ?? url.standardizedFileURL).path
    }

    /// Starts once per repository, before any new staged copy is allowed. Retire the
    /// previous process's tray journal on the writer lane, ahead of this session's
    /// coalesced writes. A crash before a journal flush is covered by scanning the
    /// directory itself. Durable drafts, pending batch members, uploads and cached
    /// attachments remain owners, including records with an old container prefix.
    func startDraftSourceRecovery(attachmentReferences: @escaping @Sendable () async throws -> Set<URL>) -> DraftSourceRecovery {
        let retired = AsyncThrowingStream<Void, Error> { continuation in
            workspaceQueue.async {
                do {
                    if self.workspaceWriter == nil { self.workspaceWriter = try MessageStore(databaseURL: self.databaseURL) }
                    try self.workspaceWriter!.execute("DELETE FROM chat_workspace WHERE name='composerTrayFiles' OR name LIKE 'recent-file-copy:%';")
                    continuation.finish()
                } catch { continuation.finish(throwing: error) }
            }
        }
        let database = databaseURL
        let protection = draftRecoveryProtection
        let task = Task.detached(priority: .utility) {
            defer { protection.finish() }
            for try await _ in retired {}
            try Task.checkCancellation()
            let reader = try MessageStore(databaseURL: database, readOnly: true)
            let attachmentPaths = try await attachmentReferences()
            try Task.checkCancellation()
            let owned = try reader.workspaceSourceReferences().union(attachmentPaths.map { reader.draftSourceIdentity($0) })
            let root = reader.draftSourceRoot
            let manager = FileManager.default
            guard manager.fileExists(atPath: root.path) else { return }
            let rootValues = try root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard rootValues.isDirectory == true, rootValues.isSymbolicLink != true else { return }
            // Bounded turns: collect up to `turn` unowned files, stop enumerating,
            // then delete them and start a fresh enumeration. Deleting files or
            // directories while an enumerator walks them skips entries on Apple
            // platforms, which left orphans behind. No array of every staged file,
            // no polling and no main-thread filesystem/SQLite work. Ownership
            // decoding is separately size-bounded and fails closed before removal.
            let turn = 128
            while true {
                try Task.checkCancellation()
                var enumerationError: Error?
                guard let files = manager.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey],
                    options: [], errorHandler: { _, error in enumerationError = error; return false }) else { throw StoreError.stepFailed }
                var doomed: [URL] = []
                var visited = 0
                while let file = files.nextObject() as? URL {
                    try Task.checkCancellation()
                    visited += 1
                    if visited % 128 == 0 { await Task.yield() }
                    let values = try file.resourceValues(forKeys: [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey])
                    // The enumerator never follows a link. Asking it to skip a link's
                    // descendants skipped the rest of the parent directory on Apple
                    // platforms instead (the orphans after the link survived).
                    if values.isSymbolicLink == true { continue }
                    let identity = reader.draftSourceIdentity(file)
                    guard values.isRegularFile == true, !owned.contains(identity), !protection.contains(identity) else { continue }
                    doomed.append(file)
                    if doomed.count >= turn { break }
                }
                if let enumerationError { throw enumerationError }
                var parents = Set<URL>()
                for file in doomed {
                    // A live tray may have claimed it after this turn's enumeration.
                    guard !protection.contains(reader.draftSourceIdentity(file)) else { continue }
                    try manager.removeItem(at: file)
                    let parent = file.deletingLastPathComponent()
                    if parent != root { parents.insert(parent) }
                }
                for parent in parents where (try? manager.contentsOfDirectory(atPath: parent.path).isEmpty) == true {
                    try? manager.removeItem(at: parent)
                }
                if doomed.count < turn { break }
                await Task.yield()
            }
        }
        return DraftSourceRecovery(task)
    }
    func chatValue(chatId: String, name: String) throws -> String? {
        var value: String?
        try withStatement("SELECT value FROM chat_workspace WHERE chat_id = ? AND name = ?;") { stmt in
            try bindText(stmt, 1, chatId); try bindText(stmt, 2, name)
            if sqlite3_step(stmt) == SQLITE_ROW { value = String(cString: sqlite3_column_text(stmt, 0)) }
        }
        return value
    }
    func saveChatValue(chatId: String, name: String, value: String?) throws {
        // Explicit send/cancel writes must follow an already dispatched draft batch.
        workspaceQueue.sync {}
        try writeChatValue(chatId: chatId, name: name, value: value)
    }
    private func writeChatValue(chatId: String, name: String, value: String?) throws {
        guard try !isChatDeleted(chatId) else { return }
        try withStatement(value == nil ? "DELETE FROM chat_workspace WHERE chat_id = ? AND name = ?;" : "INSERT INTO chat_workspace(chat_id,name,value) VALUES (?,?,?) ON CONFLICT(chat_id,name) DO UPDATE SET value=excluded.value;") { stmt in
            try bindText(stmt, 1, chatId); try bindText(stmt, 2, name)
            if let value { try bindText(stmt, 3, value) }
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    func workspaceValues(chatId: String) throws -> [String: String] {
        var values: [String: String] = [:]
        try withStatement("SELECT name,value FROM chat_workspace WHERE chat_id = ?;") { stmt in
            try bindText(stmt, 1, chatId)
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW {
                values[String(cString: sqlite3_column_text(stmt, 0))] = String(cString: sqlite3_column_text(stmt, 1))
                step = sqlite3_step(stmt)
            }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return values
    }

    func completedMediaJobIDs() throws -> [String] {
        var ids: [String] = []
        try withStatement("SELECT m.client_message_id FROM messages m JOIN chat_workspace w ON w.chat_id=m.chat_id AND w.name='upload:' || m.client_message_id WHERE m.hidden_locally=1 OR m.status IN ('sent','delivered','read');") { stmt in
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW { ids.append(String(cString: sqlite3_column_text(stmt, 0))); step = sqlite3_step(stmt) }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return ids
    }

    func startWorkspaceWrite(_ batches: [String: [String: String]]) -> Task<Void, Error> {
        let completion = AsyncThrowingStream<Void, Error> { continuation in
            workspaceQueue.async {
                do {
                    if self.workspaceWriter == nil { self.workspaceWriter = try MessageStore(databaseURL: self.databaseURL) }
                    let writer = self.workspaceWriter!
                    try writer.execute("BEGIN IMMEDIATE;")
                    do {
                        for (chat, values) in batches {
                            for (name, value) in values { try writer.writeChatValue(chatId: chat, name: name, value: value) }
                        }
                        try writer.execute("COMMIT;")
                    } catch { try? writer.execute("ROLLBACK;"); throw error }
                    continuation.finish()
                } catch { continuation.finish(throwing: error) }
            }
        }
        return Task { for try await _ in completion {} }
    }

    private static let lifecycleRepairKey = "lifecycle_projection_v1"
    private struct LifecycleRepairProgress: Codable {
        var upperRow: Int64
        var sequence: Int64 = 0
        var row: Int64 = 0
        var complete = false
    }
    private let lifecycleRepairCancellation = SearchCancellation()
    private static let lifecycleRepairCacheLock = NSLock()
    private static var completedLifecycleRepairsByPath: [String: Set<String>] = [:]
    private static func isLifecycleRepairComplete(_ chatId: String, for url: URL) -> Bool {
        lifecycleRepairCacheLock.lock(); defer { lifecycleRepairCacheLock.unlock() }
        return completedLifecycleRepairsByPath[url.standardizedFileURL.path]?.contains(chatId) == true
    }
    private static func markLifecycleRepairComplete(_ chatId: String, for url: URL) {
        lifecycleRepairCacheLock.lock(); defer { lifecycleRepairCacheLock.unlock() }
        completedLifecycleRepairsByPath[url.standardizedFileURL.path, default: []].insert(chatId)
    }
    private static func setCompletedLifecycleRepairs(_ chatIds: Set<String>, for url: URL) {
        lifecycleRepairCacheLock.lock(); defer { lifecycleRepairCacheLock.unlock() }
        completedLifecycleRepairsByPath[url.standardizedFileURL.path] = chatIds
    }

    func legacyLifecycleRepairIsComplete(chatId: String) throws -> Bool {
        if Self.isLifecycleRepairComplete(chatId, for: databaseURL) { return true }
        guard let raw = try chatValue(chatId: chatId, name: Self.lifecycleRepairKey) else { return false }
        let complete = try decoder.decode(LifecycleRepairProgress.self, from: Data(raw.utf8)).complete
        if complete { Self.markLifecycleRepairComplete(chatId, for: databaseURL) }
        return complete
    }

    /// One bulk read of the durable legacy-projection progress markers for the
    /// whole cache. A caller that would otherwise run `repairLegacyLifecycle`
    /// for every cached chat uses this to skip chats whose marker already says
    /// `complete = true` (same key and decode as `legacyLifecycleRepairIsComplete`,
    /// one statement instead of one workspace-queue round-trip per chat).
    /// Read-only: it neither repairs nor exposes message content, so it is safe
    /// to run on the read queue before maintenance.
    func legacyLifecycleRepairCompleteChatIDs() throws -> Set<String> {
        var complete = Set<String>()
        try withStatement("SELECT chat_id, value FROM chat_workspace WHERE name = ?;") { stmt in
            try bindText(stmt, 1, Self.lifecycleRepairKey)
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW {
                let chatId = String(cString: sqlite3_column_text(stmt, 0))
                // A corrupt marker behaves like an incomplete one: the bounded
                // per-chat repair is what fails closed for that chat.
                if let raw = columnOptionalText(stmt, 1),
                   let progress = try? decoder.decode(LifecycleRepairProgress.self, from: Data(raw.utf8)),
                   progress.complete {
                    complete.insert(chatId)
                }
                step = sqlite3_step(stmt)
            }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        Self.setCompletedLifecycleRepairs(complete, for: databaseURL)
        return complete
    }

    /// Old sender ACKs could confirm a lifecycle row without committing its
    /// projection. Repair before presenting cached history, never in init or a
    /// view's render path. Each background transaction decodes at most 32 rows.
    func repairLegacyLifecycle(chatId: String) async throws {
        try Task.checkCancellation()
        try lifecycleRepairCancellation.check()
        if Self.isLifecycleRepairComplete(chatId, for: databaseURL) { return }
        let cancellation = SearchCancellation()
        try await withTaskCancellationHandler(operation: {
            while true {
                try Task.checkCancellation()
                let complete = try await withCheckedThrowingContinuation { continuation in
                    workspaceQueue.async {
                        do {
                            try self.lifecycleRepairCancellation.check()
                            try cancellation.check()
                            if self.workspaceWriter == nil { self.workspaceWriter = try MessageStore(databaseURL: self.databaseURL) }
                            let complete = try self.workspaceWriter!.repairLegacyLifecycleBatch(chatId: chatId) {
                                try self.lifecycleRepairCancellation.check()
                                try cancellation.check()
                            }
                            continuation.resume(returning: complete)
                        } catch { continuation.resume(throwing: error) }
                    }
                }
                if complete { return }
                await Task.yield()
            }
        }, onCancel: { cancellation.cancel() })
    }

    /// Account teardown cancels and drains work before deleting the account's
    /// files. Cancellation alone must not leave a queued writer behind.
    func finishLegacyLifecycleRepairs() async {
        lifecycleRepairCancellation.cancel()
        await withCheckedContinuation { continuation in
            workspaceQueue.async { continuation.resume() }
        }
    }

    func legacyLifecycleChatIDs() throws -> [String] {
        var result: [String] = []
        try withStatement("SELECT DISTINCT chat_id FROM messages WHERE kind='content' AND chat_id NOT IN (SELECT chat_id FROM deleted_chats);") { stmt in
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW { result.append(String(cString: sqlite3_column_text(stmt, 0))); step = sqlite3_step(stmt) }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return result
    }

    /// Internal synchronous seam also exercised by the native SQLite contracts.
    /// The progress row and all side effects commit or roll back together.
    @discardableResult
    func repairLegacyLifecycleBatch(chatId: String, checkCancellation: () throws -> Void = {}) throws -> Bool {
        try checkCancellation()
        if try isChatDeleted(chatId) { return true }
        if try legacyLifecycleRepairIsComplete(chatId: chatId) { return true }
        // Build this covering index on the background writer, not MainActor init.
        try execute("CREATE INDEX IF NOT EXISTS idx_messages_lifecycle_repair ON messages(chat_id, sequence) WHERE kind IN ('edit','delete','reaction') AND status IN ('sent','delivered','read');")
        try execute("BEGIN IMMEDIATE;")
        do {
            var progress: LifecycleRepairProgress
            if let raw = try chatValue(chatId: chatId, name: Self.lifecycleRepairKey) {
                progress = try decoder.decode(LifecycleRepairProgress.self, from: Data(raw.utf8))
            } else {
                var upper: Int64 = 0
                try withStatement("SELECT COALESCE(MAX(rowid),0) FROM messages;") { stmt in
                    guard sqlite3_step(stmt) == SQLITE_ROW else { throw StoreError.stepFailed }
                    upper = sqlite3_column_int64(stmt, 0)
                }
                progress = LifecycleRepairProgress(upperRow: upper)
            }
            var rows: [(Int64, DecryptedChatMessage)] = []
            try withStatement("""
                SELECT \(Self.messageColumns),rowid FROM messages
                WHERE chat_id=? AND kind IN ('edit','delete','reaction') AND status IN ('sent','delivered','read')
                  AND rowid<=? AND (sequence,rowid)>(?,?)
                ORDER BY sequence,rowid LIMIT 32;
                """) { stmt in
                try bindText(stmt, 1, chatId); sqlite3_bind_int64(stmt, 2, progress.upperRow)
                sqlite3_bind_int64(stmt, 3, progress.sequence); sqlite3_bind_int64(stmt, 4, progress.row)
                var step = sqlite3_step(stmt)
                while step == SQLITE_ROW {
                    try checkCancellation()
                    rows.append((sqlite3_column_int64(stmt, 17), try decodeRow(stmt)))
                    step = sqlite3_step(stmt)
                }
                guard step == SQLITE_DONE else { throw StoreError.stepFailed }
            }
            for (row, event) in rows {
                try checkCancellation()
                if let targetID = event.targetMessageId, let target = try message(byServerId: targetID) {
                    try applyLifecycle(event, to: target)
                }
                progress.sequence = event.sequence ?? progress.sequence; progress.row = row
            }
            progress.complete = rows.count < 32
            try writeChatValue(chatId: chatId, name: Self.lifecycleRepairKey,
                value: String(decoding: try encoder.encode(progress), as: UTF8.self))
            try checkCancellation()
            try execute("COMMIT;")
            if progress.complete { Self.markLifecycleRepairComplete(chatId, for: databaseURL) }
            return progress.complete
        } catch { try? execute("ROLLBACK;"); throw error }
    }

    /// One bounded authorised direct-batch descriptor. Completed/legacy drafts are not auto-sent.
    func nextDirectComposerBatch() throws -> (chatId: String, raw: String)? {
        var result: (String, String)?
        try withStatement("SELECT w.chat_id,w.value FROM chat_workspace w WHERE w.name='composerMediaBatchV1' AND json_valid(w.value) AND json_extract(w.value,'$.directSend')=1 AND json_extract(w.value,'$.autoSend')=1 AND json_array_length(w.value,'$.members')>json_array_length(w.value,'$.acceptedIDs') AND NOT EXISTS(SELECT 1 FROM deleted_chats d WHERE d.chat_id=w.chat_id) ORDER BY w.chat_id LIMIT 1;") { stmt in
            let step = sqlite3_step(stmt)
            if step == SQLITE_ROW {
                let raw = String(cString: sqlite3_column_text(stmt, 1))
                guard raw.utf8.count <= 1024 * 1024 else { throw StoreError.stepFailed }
                result = (String(cString: sqlite3_column_text(stmt, 0)), raw)
            } else if step != SQLITE_DONE { throw StoreError.stepFailed }
        }
        return result
    }

    /// Typed reference projection only; the store does not depend on composer/UI preparation code.
    private struct ComposerBatchSourceReferences: Decodable {
        struct Source: Decodable { let owned: Bool; let url: URL? }
        struct Prepared: Decodable { let url: URL }
        struct Member: Decodable { let id: String; let source: Source; let prepared: Prepared? }
        let version: Int
        let members: [Member]
        let acceptedIDs: [String]
        var isValid: Bool {
            version == 1 && !members.isEmpty && members.count <= 10 &&
            Set(members.map(\.id)).count == members.count && members.allSatisfy({ UUID(uuidString: $0.id) != nil }) &&
            Set(acceptedIDs).count == acceptedIDs.count && acceptedIDs.allSatisfy({ id in members.contains { $0.id == id } })
        }
        var ownedSources: [URL] {
            members.flatMap { member -> [URL] in
                guard !acceptedIDs.contains(member.id) else { return [] }
                return [member.prepared?.url, member.source.owned ? member.source.url : nil].compactMap { $0 }
            }
        }
    }

    /// Only URLs explicitly stored in live drafts or durable upload jobs own sources.
    func workspaceReferences(_ url: URL) throws -> Bool {
        try workspaceSourceReferences().contains(draftSourceIdentity(url))
    }
    private func workspaceSourceReferences() throws -> Set<String> {
        var references = Set<String>()
        var rows = 0, bytes = 0
        try withStatement("SELECT name,value FROM chat_workspace WHERE name IN ('mediaDrafts','fileDraft','voiceDraft','composerMediaBatchV1') OR name LIKE 'upload:%' OR name LIKE 'recent-file-copy:%';") { stmt in
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW {
                let name = String(cString: sqlite3_column_text(stmt, 0))
                let raw = String(cString: sqlite3_column_text(stmt, 1))
                rows += 1; bytes += raw.utf8.count
                guard rows <= 50_000, raw.utf8.count <= 1024 * 1024, bytes <= 16 * 1024 * 1024 else { throw StoreError.stepFailed }
                if name == "composerMediaBatchV1" {
                    guard raw.utf8.count <= 1024 * 1024,
                          let batch = try? JSONDecoder().decode(ComposerBatchSourceReferences.self, from: Data(raw.utf8)), batch.isValid else {
                        throw StoreError.stepFailed
                    }
                    references.formUnion(batch.ownedSources.map(draftSourceIdentity))
                    guard references.count <= 100_000 else { throw StoreError.stepFailed }
                    step = sqlite3_step(stmt); continue
                }
                let value = try JSONSerialization.jsonObject(with: Data(raw.utf8))
                func collect(_ object: Any) {
                    if let string = object as? String {
                        let candidate: URL?
                        if let encoded = URL(string: string), encoded.isFileURL { candidate = encoded }
                        else if (string as NSString).isAbsolutePath { candidate = URL(fileURLWithPath: string) }
                        else { candidate = nil }
                        if let candidate { references.insert(draftSourceIdentity(candidate)) }
                    }
                    if let array = object as? [Any] { array.forEach(collect) }
                    if let dictionary = object as? [String: Any] { dictionary.values.forEach(collect) }
                }
                collect(value)
                guard references.count <= 100_000 else { throw StoreError.stepFailed }
                step = sqlite3_step(stmt)
            }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return references
    }

    func removeUnownedDraftSource(_ url: URL, attachmentReferenced: Bool) throws {
        let root = draftSourceRoot.resolvingSymlinksInPath()
        guard let current = currentDraftSource(url) else { return }
        let resolved = current.resolvingSymlinksInPath()
        guard resolved.path.hasPrefix(root.path + "/"), !attachmentReferenced, try !workspaceReferences(url) else { return }
        if FileManager.default.fileExists(atPath: resolved.path) { try FileManager.default.removeItem(at: resolved) }
        let parent = resolved.deletingLastPathComponent()
        if parent != root, (try? FileManager.default.contentsOfDirectory(atPath: parent.path).isEmpty) == true { try? FileManager.default.removeItem(at: parent) }
    }

    deinit {
        if let db { sqlite3_close(db) }
    }

    @discardableResult
    func markLocallyRead(chatId: String, sequence: Int64) throws -> Bool {
        try withStatement("INSERT INTO local_read_receipts(chat_id, sequence) VALUES (?, ?) ON CONFLICT(chat_id) DO UPDATE SET sequence = excluded.sequence WHERE excluded.sequence > local_read_receipts.sequence;") { stmt in
            try bindText(stmt, 1, chatId)
            sqlite3_bind_int64(stmt, 2, sequence)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return sqlite3_changes(db) > 0
    }

    func localReadWatermarks() throws -> [String: Int64] {
        var result: [String: Int64] = [:]
        try withStatement("SELECT chat_id, sequence FROM local_read_receipts WHERE sequence > 0;") { stmt in
            while sqlite3_step(stmt) == SQLITE_ROW {
                result[String(cString: sqlite3_column_text(stmt, 0))] = sqlite3_column_int64(stmt, 1)
            }
        }
        return result
    }

    func unreadCounts(ownerId: String, historyCutoff: Int64 = 0) throws -> [String: Int] {
        var result: [String: Int] = [:]
        try withStatement("""
            SELECT m.chat_id, COUNT(*) FROM messages m
            LEFT JOIN local_read_receipts r ON r.chat_id = m.chat_id
            WHERE m.sender_id != ? AND m.kind = 'content' AND m.hidden_locally = 0
              AND m.delete_sequence = 0 AND m.sequence > COALESCE(r.sequence, 0)
              AND (m.sequence > ? OR m.status NOT IN ('decrypt_failed','identity_changed'))
            GROUP BY m.chat_id;
            """) { stmt in
            try bindText(stmt, 1, ownerId)
            sqlite3_bind_int64(stmt, 2, max(0, historyCutoff))
            while sqlite3_step(stmt) == SQLITE_ROW {
                result[String(cString: sqlite3_column_text(stmt, 0))] = Int(sqlite3_column_int64(stmt, 1))
            }
        }
        try withStatement("SELECT chat_id,COUNT(*) FROM call_activity WHERE owner_id=? AND visible=1 AND unread=1 AND local_seen=0 AND chat_id IS NOT NULL GROUP BY chat_id;") { stmt in
            try bindText(stmt, 1, ownerId)
            while sqlite3_step(stmt) == SQLITE_ROW {
                result[String(cString: sqlite3_column_text(stmt, 0)), default: 0] += Int(sqlite3_column_int64(stmt, 1))
            }
        }
        return result
    }

    // MARK: - Writes

    func personalCutoff(_ chatId: String) throws -> Int64 {
        var result: Int64 = -1
        try withStatement("SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chat_id=? AND name='personal_clear';") { stmt in
            try bindText(stmt, 1, chatId)
            if sqlite3_step(stmt) == SQLITE_ROW { result = sqlite3_column_int64(stmt, 0) }
        }
        return result
    }
    /// Rechecked after asynchronous attachment writes. A durable clear boundary
    /// remains authoritative even if a stale row is still present locally.
    func attachmentMessageIsVisible(chatId: String, clientMessageId: String, attachmentId: String, sequence: Int64) throws -> Bool {
        guard try !isChatDeleted(chatId) else { return false }
        var serverCutoff: Int64 = -1
        try withStatement("SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chat_id='__server_history__' AND name='cleared_through_sequence';") { stmt in
            let step = sqlite3_step(stmt)
            guard step == SQLITE_ROW || step == SQLITE_DONE else { throw StoreError.stepFailed }
            if step == SQLITE_ROW { serverCutoff = sqlite3_column_int64(stmt, 0) }
        }
        let cutoff = max(try personalCutoff(chatId), serverCutoff)
        guard sequence > cutoff, let message = try message(byClientMessageId: clientMessageId),
              message.chatId == chatId, (message.sequence ?? sequence) > cutoff,
              !message.hiddenLocally, !message.isDeletedForEveryone,
              message.content.attachment?.attachmentId == attachmentId else { return false }
        return true
    }
    func personalRemovedIDs(_ chatId: String, through: Int64) throws -> Set<String> {
        var result = Set(try workspaceValues(chatId: chatId).keys.filter { $0.hasPrefix("personal_cleanup:") }.map { String($0.dropFirst("personal_cleanup:".count)) })
        try withStatement("SELECT client_message_id FROM messages WHERE chat_id=? AND sequence<=?;") { stmt in
            try bindText(stmt, 1, chatId); sqlite3_bind_int64(stmt, 2, through)
            while sqlite3_step(stmt) == SQLITE_ROW { result.insert(String(cString: sqlite3_column_text(stmt, 0))) }
        }
        return result
    }
    func personalChatHidden(_ chatId: String) throws -> Bool {
        var hidden = false
        try withStatement("SELECT EXISTS(SELECT 1 FROM chat_workspace WHERE chat_id=? AND name='personal_clear') AND NOT EXISTS(SELECT 1 FROM messages WHERE chat_id=? AND kind='content' AND hidden_locally=0 AND (sequence IS NULL OR sequence>CAST((SELECT value FROM chat_workspace WHERE chat_id=? AND name='personal_clear') AS INTEGER))) AND NOT EXISTS(SELECT 1 FROM call_activity WHERE chat_id=? AND visible=1);") { stmt in
            for index in 1...4 { sqlite3_bind_text(stmt, Int32(index), chatId, -1, sqliteTransient) }
            guard sqlite3_step(stmt) == SQLITE_ROW else { throw StoreError.stepFailed }
            hidden = sqlite3_column_int(stmt, 0) != 0
        }
        return hidden
    }
    /// Same visibility rule as personalChatHidden, evaluated for all cleared
    /// chats in one background query rather than one main-thread query per row.
    func personallyHiddenChatIDs() throws -> Set<String> {
        var result = Set<String>()
        try withStatement("""
            SELECT w.chat_id FROM chat_workspace w WHERE w.name='personal_clear'
              AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.chat_id=w.chat_id
                AND m.kind='content' AND m.hidden_locally=0
                AND (m.sequence IS NULL OR m.sequence>CAST(w.value AS INTEGER)))
              AND NOT EXISTS (SELECT 1 FROM call_activity c WHERE c.chat_id=w.chat_id AND c.visible=1);
            """) { stmt in
            while sqlite3_step(stmt) == SQLITE_ROW { result.insert(String(cString: sqlite3_column_text(stmt, 0))) }
        }
        return result
    }
    @discardableResult
    func projectCallActivity(_ row: CallActivityProjection, owner: String) throws -> Bool {
        guard UUID(uuidString: owner) != nil, UUID(uuidString: row.callId) != nil,
              UUID(uuidString: row.peerId) != nil, row.chatId.map({ UUID(uuidString: $0) != nil }) ?? true else { throw StoreError.bindingFailed }
        let cutoff = try row.chatId.map { try callChatCutoff($0) } ?? .distantPast
        let deletedChat = try row.chatId.map { try isChatDeleted($0) } ?? false
        let deletedCall = try callIsDeleted(owner: owner, id: row.callId)
        let deleted = deletedChat || deletedCall
        let visible = row.visible && row.createdAt > cutoff && !deleted
        let unread = row.unread && row.createdAt > cutoff && !deleted
        try withStatement("""
            INSERT INTO call_activity(owner_id,call_id,chat_id,peer_id,created_at,media_kind,outcome,visible,unread,local_seen)
            VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(owner_id,call_id) DO UPDATE SET
              chat_id=excluded.chat_id,peer_id=excluded.peer_id,created_at=excluded.created_at,
              media_kind=excluded.media_kind,outcome=excluded.outcome,visible=excluded.visible,
              local_seen=MAX(call_activity.local_seen,excluded.local_seen),
              unread=CASE WHEN call_activity.local_seen=1 OR excluded.local_seen=1 THEN 0 ELSE excluded.unread END
            WHERE call_activity.chat_id IS NOT excluded.chat_id OR call_activity.peer_id<>excluded.peer_id
              OR call_activity.created_at<>excluded.created_at OR call_activity.media_kind<>excluded.media_kind
              OR call_activity.outcome<>excluded.outcome OR call_activity.visible<>excluded.visible
              OR call_activity.local_seen<>MAX(call_activity.local_seen,excluded.local_seen)
              OR call_activity.unread<>CASE WHEN call_activity.local_seen=1 OR excluded.local_seen=1 THEN 0 ELSE excluded.unread END;
            """) { stmt in
            try bindText(stmt,1,owner); try bindText(stmt,2,row.callId); try bindOptionalText(stmt,3,row.chatId)
            try bindText(stmt,4,row.peerId); sqlite3_bind_double(stmt,5,row.createdAt.timeIntervalSince1970)
            try bindText(stmt,6,row.mediaKind); try bindText(stmt,7,row.outcome)
            sqlite3_bind_int(stmt,8,visible ? 1 : 0); sqlite3_bind_int(stmt,9,unread ? 1 : 0)
            sqlite3_bind_int(stmt,10,row.outcome.hasPrefix("ended:") && !unread ? 1 : 0)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return sqlite3_changes(db) > 0
    }

    func callUnread(owner: String, id: String) throws -> Bool {
        var unread = false
        try withStatement("SELECT unread=1 AND local_seen=0 FROM call_activity WHERE owner_id=? AND call_id=?;") { stmt in
            try bindText(stmt,1,owner); try bindText(stmt,2,id)
            if sqlite3_step(stmt) == SQLITE_ROW { unread = sqlite3_column_int(stmt,0) != 0 }
        }
        return unread
    }
    /// The unread calls of an owner, in one query (the call history used to ask once per call).
    func unreadCallIDs(owner: String) throws -> Set<String> {
        var ids = Set<String>()
        try withStatement("SELECT call_id FROM call_activity WHERE owner_id=? AND unread=1 AND local_seen=0;") { stmt in
            try bindText(stmt,1,owner)
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let text = sqlite3_column_text(stmt,0) { ids.insert(String(cString: text)) }
            }
        }
        return ids
    }
    func callVisible(owner: String, id: String) throws -> Bool {
        var visible = false
        try withStatement("SELECT visible FROM call_activity WHERE owner_id=? AND call_id=?;") { stmt in
            try bindText(stmt,1,owner); try bindText(stmt,2,id)
            if sqlite3_step(stmt) == SQLITE_ROW { visible = sqlite3_column_int(stmt,0) != 0 }
        }
        return visible
    }
    /// One bulk read of call-in-chat visibility state (`call_activity.visible`,
    /// `deleted_chats`, and `calls_before` workspace cutoffs) so `CallsRepository.chatHistory`
    /// never runs 3 per-call SQLite queries on the main actor.
    func callChatVisibilitySnapshot(owner: String) throws -> (visibleCallIDs: Set<String>, deletedChatIDs: Set<String>, cutoffs: [String: Date]) {
        var visibleCalls = Set<String>()
        try withStatement("SELECT call_id FROM call_activity WHERE owner_id=? AND visible=1;") { stmt in
            try bindText(stmt, 1, owner)
            while sqlite3_step(stmt) == SQLITE_ROW {
                visibleCalls.insert(String(cString: sqlite3_column_text(stmt, 0)))
            }
        }
        var deletedChats = Set<String>()
        try withStatement("SELECT chat_id FROM deleted_chats;") { stmt in
            while sqlite3_step(stmt) == SQLITE_ROW {
                deletedChats.insert(String(cString: sqlite3_column_text(stmt, 0)))
            }
        }
        var cutoffs: [String: Date] = [:]
        try withStatement("SELECT chat_id,value FROM chat_workspace WHERE name='calls_before';") { stmt in
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let value = Double(String(cString: sqlite3_column_text(stmt, 1))) {
                    cutoffs[String(cString: sqlite3_column_text(stmt, 0))] = Date(timeIntervalSince1970: value)
                }
            }
        }
        return (visibleCalls, deletedChats, cutoffs)
    }
    func markCallsViewed(owner: String, ids: Set<String>) throws {
        for id in ids {
            try withStatement("UPDATE call_activity SET local_seen=1,unread=0,pending_read=1 WHERE owner_id=? AND call_id=? AND unread=1 AND local_seen=0;") { stmt in
                try bindText(stmt,1,owner); try bindText(stmt,2,id)
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
        }
    }
    func pendingCallReads(owner: String, since: Date) throws -> [String] {
        var ids: [String] = []
        try withStatement("SELECT call_id FROM call_activity WHERE owner_id=? AND pending_read=1 AND created_at>=? LIMIT 100;") { stmt in
            try bindText(stmt,1,owner); sqlite3_bind_double(stmt,2,since.timeIntervalSince1970)
            while sqlite3_step(stmt) == SQLITE_ROW { ids.append(String(cString:sqlite3_column_text(stmt,0))) }
        }
        return ids
    }
    func completeCallRead(owner: String, id: String) throws {
        try withStatement("UPDATE call_activity SET pending_read=0 WHERE owner_id=? AND call_id=?;") { stmt in
            try bindText(stmt,1,owner); try bindText(stmt,2,id)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }
    func removeCallActivity(owner: String, ids: Set<String>) throws {
        for id in ids {
            try withStatement("DELETE FROM call_activity WHERE owner_id=? AND call_id=?;") { stmt in
                try bindText(stmt,1,owner); try bindText(stmt,2,id)
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
        }
    }
    func callChatCutoff(_ chat: String) throws -> Date {
        guard let raw = try chatValue(chatId: chat, name: "calls_before"), let value = Double(raw) else { return .distantPast }
        return Date(timeIntervalSince1970:value)
    }
    func exportCallChatClears() throws -> [String: Int64] {
        var result: [String: Int64] = [:]
        try withStatement("SELECT chat_id,value FROM chat_workspace WHERE name='calls_before' LIMIT 10001;") { stmt in
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW {
                guard let seconds = Double(String(cString: sqlite3_column_text(stmt,1))), seconds.isFinite,
                      seconds >= 0, seconds <= 253402300799 else { throw StoreError.stepFailed }
                result[String(cString: sqlite3_column_text(stmt,0))] = Int64((seconds * 1000).rounded())
                step = sqlite3_step(stmt)
            }
            guard step == SQLITE_DONE, result.count <= 10_000 else { throw StoreError.stepFailed }
        }
        return result
    }
    @discardableResult
    func clearCallActivity(chat: String, before: Date) throws -> Bool {
        let previous = try callChatCutoff(chat)
        let cutoff = max(previous, before)
        if cutoff > previous { try saveChatValue(chatId:chat,name:"calls_before",value:String(cutoff.timeIntervalSince1970)) }
        try withStatement("UPDATE call_activity SET visible=0,unread=0,pending_read=0 WHERE chat_id=? AND created_at<=? AND (visible<>0 OR unread<>0 OR pending_read<>0);") { stmt in
            try bindText(stmt,1,chat); sqlite3_bind_double(stmt,2,cutoff.timeIntervalSince1970)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return cutoff > previous || sqlite3_changes(db) > 0
    }
    func clearCallActivity(before: Date) throws {
        try withStatement("DELETE FROM call_activity WHERE created_at<?;") { stmt in
            sqlite3_bind_double(stmt,1,before.timeIntervalSince1970)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }
    func suspendCallActivity(owner: String) throws {
        try withStatement("UPDATE call_activity SET visible=0 WHERE owner_id=?;") { stmt in
            try bindText(stmt,1,owner)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    /// The intent and local projection removal share one transaction. Completed
    /// commands retain a tombstone so cached pages cannot resurrect this call.
    func enqueueCallDeletion(owner: String, server: String, id: String, everyone: Bool) throws {
        guard UUID(uuidString: owner) != nil, UUID(uuidString: id) != nil, !server.isEmpty else { throw StoreError.bindingFailed }
        workspaceQueue.sync {}
        try execute("BEGIN IMMEDIATE;")
        do {
            try withStatement("""
                INSERT INTO call_history_delete_commands(owner_id,server_origin,call_id,everyone,pending)
                VALUES(?,?,?,?,1) ON CONFLICT(owner_id,server_origin,call_id) DO UPDATE SET
                  pending=CASE WHEN excluded.everyone>call_history_delete_commands.everyone THEN 1 ELSE call_history_delete_commands.pending END,
                  everyone=MAX(call_history_delete_commands.everyone,excluded.everyone);
                """) { stmt in
                try bindText(stmt,1,owner); try bindText(stmt,2,server); try bindText(stmt,3,id)
                sqlite3_bind_int(stmt,4,everyone ? 1 : 0)
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
            try removeCallActivity(owner: owner, ids: [id])
            try execute("COMMIT;")
        } catch { try? execute("ROLLBACK;"); throw error }
    }
    private func callIsDeleted(owner: String, id: String) throws -> Bool {
        var deleted = false
        try withStatement("SELECT 1 FROM call_history_delete_commands WHERE owner_id=? AND call_id=? LIMIT 1;") { stmt in
            try bindText(stmt,1,owner); try bindText(stmt,2,id)
            let step = sqlite3_step(stmt)
            guard step == SQLITE_ROW || step == SQLITE_DONE else { throw StoreError.stepFailed }
            deleted = step == SQLITE_ROW
        }
        return deleted
    }
    func callDeletionIDs(owner: String, server: String) throws -> Set<String> {
        var ids = Set<String>()
        try withStatement("SELECT call_id FROM call_history_delete_commands WHERE owner_id=? AND server_origin=?;") { stmt in
            try bindText(stmt,1,owner); try bindText(stmt,2,server)
            while sqlite3_step(stmt) == SQLITE_ROW { ids.insert(String(cString: sqlite3_column_text(stmt,0))) }
        }
        return ids
    }
    func pendingCallDeletions(owner: String, server: String) throws -> [PendingCallDeletion] {
        var rows: [PendingCallDeletion] = []
        try withStatement("SELECT call_id,everyone FROM call_history_delete_commands WHERE owner_id=? AND server_origin=? AND pending=1 ORDER BY rowid LIMIT 100;") { stmt in
            try bindText(stmt,1,owner); try bindText(stmt,2,server)
            while sqlite3_step(stmt) == SQLITE_ROW {
                rows.append(PendingCallDeletion(callId:String(cString: sqlite3_column_text(stmt,0)),everyone:sqlite3_column_int(stmt,1) != 0))
            }
        }
        return rows
    }
    func completeCallDeletion(owner: String, server: String, id: String, everyone: Bool) throws {
        // A delayed for-me acknowledgement must not complete a newer for-all upgrade.
        try withStatement("UPDATE call_history_delete_commands SET pending=0 WHERE owner_id=? AND server_origin=? AND call_id=? AND everyone=?;") { stmt in
            try bindText(stmt,1,owner); try bindText(stmt,2,server); try bindText(stmt,3,id)
            sqlite3_bind_int(stmt,4,everyone ? 1 : 0)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    func chatClearCommand(owner: String, server: String, requestId: String) throws -> ChatClearCommand? {
        var command: ChatClearCommand?
        try withStatement("SELECT request_id,chat_id,for_everyone,through_sequence,calls_before FROM chat_clear_commands WHERE owner_id=? AND server_origin=? AND request_id=?;") { stmt in
            try bindText(stmt,1,owner); try bindText(stmt,2,server); try bindText(stmt,3,requestId)
            if sqlite3_step(stmt) == SQLITE_ROW { command = decodeChatClear(stmt) }
        }
        return command
    }
    private func decodeChatClear(_ stmt: OpaquePointer?) -> ChatClearCommand {
        ChatClearCommand(requestId:String(cString:sqlite3_column_text(stmt,0)),chatId:String(cString:sqlite3_column_text(stmt,1)),
            forEveryone:sqlite3_column_int(stmt,2) != 0,throughSequence:sqlite3_column_int64(stmt,3),
            callsBefore:sqlite3_column_type(stmt,4) == SQLITE_NULL ? nil : Date(timeIntervalSince1970:sqlite3_column_double(stmt,4)))
    }
    func captureChatClear(owner: String, chat: String, requestId: String, everyone: Bool, minimumSequence: Int64) throws -> ChatClearCommand {
        var sequence = max(0, minimumSequence)
        try withStatement("SELECT COALESCE(MAX(sequence),0) FROM messages;") { stmt in
            guard sqlite3_step(stmt) == SQLITE_ROW else { throw StoreError.stepFailed }
            sequence = max(sequence, sqlite3_column_int64(stmt,0))
        }
        var calls: Date?
        try withStatement("SELECT MAX(created_at) FROM call_activity WHERE owner_id=? AND chat_id=? AND outcome LIKE 'ended:%';") { stmt in
            try bindText(stmt,1,owner); try bindText(stmt,2,chat)
            guard sqlite3_step(stmt) == SQLITE_ROW else { throw StoreError.stepFailed }
            if sqlite3_column_type(stmt,0) != SQLITE_NULL {
                // Foundation's parsed server date can lose sub-millisecond
                // precision. A 1 ms bound guard includes that known ended call;
                // it derives solely from server history, never the phone clock.
                let known = sqlite3_column_double(stmt,0)
                calls = Date(timeIntervalSince1970:(known * 1_000).rounded(.down) / 1_000 + 0.001)
            }
        }
        return ChatClearCommand(requestId:requestId,chatId:chat,forEveryone:everyone,throughSequence:sequence,callsBefore:calls)
    }
    /// Freeze the command and clear the captured local content in one commit.
    /// Replaying an existing request never cancels future unsents or new drafts.
    @discardableResult
    func enqueueChatClear(owner: String, server: String, command: ChatClearCommand) throws -> Set<String> {
        guard UUID(uuidString: owner) != nil, UUID(uuidString: command.chatId) != nil,
              UUID(uuidString: command.requestId) != nil, command.throughSequence >= 0, !server.isEmpty else { throw StoreError.bindingFailed }
        workspaceQueue.sync {}
        if let existing = try chatClearCommand(owner:owner,server:server,requestId:command.requestId) {
            guard existing == command else { throw StoreError.bindingFailed }
            return []
        }
        var cancelled = Set<String>()
        try execute("BEGIN IMMEDIATE;")
        do {
            try withStatement("INSERT INTO chat_clear_commands(owner_id,server_origin,request_id,chat_id,for_everyone,through_sequence,calls_before,pending) VALUES(?,?,?,?,?,?,?,1);") { stmt in
                try bindText(stmt,1,owner); try bindText(stmt,2,server); try bindText(stmt,3,command.requestId)
                try bindText(stmt,4,command.chatId); sqlite3_bind_int(stmt,5,command.forEveryone ? 1 : 0)
                sqlite3_bind_int64(stmt,6,command.throughSequence)
                if let before = command.callsBefore { sqlite3_bind_double(stmt,7,before.timeIntervalSince1970) } else { sqlite3_bind_null(stmt,7) }
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
            try withStatement("SELECT client_message_id FROM messages WHERE chat_id=? AND status='pending' AND hidden_locally=0;") { stmt in
                try bindText(stmt,1,command.chatId)
                while sqlite3_step(stmt) == SQLITE_ROW { cancelled.insert(String(cString:sqlite3_column_text(stmt,0))) }
            }
            for id in cancelled {
                try writeChatValue(chatId:command.chatId,name:"personal_cleanup:" + id,value:"1")
                try withStatement("UPDATE messages SET hidden_locally=1,status='failed' WHERE chat_id=? AND client_message_id=? AND status='pending';") { stmt in
                    try bindText(stmt,1,command.chatId); try bindText(stmt,2,id)
                    guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
                }
            }
            try applyPersonalClear(command.chatId, throughSequence: command.throughSequence)
            if let before = command.callsBefore {
                // These tombstones are part of the bounded chat command, never
                // separate HTTP call-delete commands or archive payloads.
                try withStatement("INSERT OR IGNORE INTO call_history_delete_commands(owner_id,server_origin,call_id,everyone,pending) SELECT owner_id,?,call_id,?,0 FROM call_activity WHERE owner_id=? AND chat_id=? AND outcome LIKE 'ended:%' AND created_at<=?;") { stmt in
                    try bindText(stmt,1,server); sqlite3_bind_int(stmt,2,command.forEveryone ? 1 : 0)
                    try bindText(stmt,3,owner); try bindText(stmt,4,command.chatId); sqlite3_bind_double(stmt,5,before.timeIntervalSince1970)
                    guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
                }
                try clearCallActivity(chat:command.chatId,before:before)
            }
            let draftNames = Set(["draft","recoveredDraft","mediaDrafts","mediaCaption","mediaAlbum","fileDraft","voiceDraft","mediaImportPending","mediaDirectSendPending"])
            let drafts = try workspaceValues(chatId:command.chatId).filter { draftNames.contains($0.key) }
            if !drafts.isEmpty {
                try writeChatValue(chatId:command.chatId,name:"clear_draft_cleanup:" + command.requestId,value:String(decoding:try JSONEncoder().encode(drafts),as:UTF8.self))
            }
            for name in draftNames { try writeChatValue(chatId:command.chatId,name:name,value:nil) }
            try execute("COMMIT;")
        } catch { try? execute("ROLLBACK;"); throw error }
        return cancelled
    }
    func pendingChatClears(owner: String, server: String) throws -> [ChatClearCommand] {
        var commands: [ChatClearCommand] = []
        try withStatement("SELECT request_id,chat_id,for_everyone,through_sequence,calls_before FROM chat_clear_commands WHERE owner_id=? AND server_origin=? AND pending=1 ORDER BY rowid LIMIT 100;") { stmt in
            try bindText(stmt,1,owner); try bindText(stmt,2,server)
            while sqlite3_step(stmt) == SQLITE_ROW { commands.append(decodeChatClear(stmt)) }
        }
        return commands
    }
    func completeChatClear(owner: String, server: String, requestId: String) throws {
        try withStatement("UPDATE chat_clear_commands SET pending=0 WHERE owner_id=? AND server_origin=? AND request_id=?;") { stmt in
            try bindText(stmt,1,owner); try bindText(stmt,2,server); try bindText(stmt,3,requestId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    func clearPersonalChat(_ chatId: String, throughSequence: Int64) throws {
        guard throughSequence >= 0 else { throw StoreError.stepFailed }
        workspaceQueue.sync {}
        try execute("BEGIN IMMEDIATE;")
        do {
            try applyPersonalClear(chatId, throughSequence: throughSequence)
            try execute("COMMIT;")
        } catch { try? execute("ROLLBACK;"); throw error }
    }
    private func applyPersonalClear(_ chatId: String, throughSequence: Int64) throws {
    try withStatement("INSERT INTO chat_workspace(chat_id,name,value) VALUES(?,'personal_clear',?) ON CONFLICT(chat_id,name) DO UPDATE SET value=MAX(CAST(value AS INTEGER),CAST(excluded.value AS INTEGER));") { stmt in
        sqlite3_bind_text(stmt, 1, chatId, -1, sqliteTransient)
        sqlite3_bind_int64(stmt, 2, throughSequence)
        guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
    }
    for sql in ["INSERT OR IGNORE INTO chat_workspace(chat_id,name,value) SELECT chat_id,'personal_cleanup:' || client_message_id,'1' FROM messages WHERE chat_id=? AND sequence<=?;", "DELETE FROM reactions WHERE target_message_id IN (SELECT server_id FROM messages WHERE chat_id=? AND sequence<=?);", "DELETE FROM messages WHERE chat_id=? AND sequence<=?;", "DELETE FROM pending_decrypts WHERE chat_id=? AND sequence<=?;"] {
        try withStatement(sql) { stmt in
            sqlite3_bind_text(stmt, 1, chatId, -1, sqliteTransient)
            sqlite3_bind_int64(stmt, 2, throughSequence)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }
    }
    func clearServerHistory(throughSequence: Int64) throws -> Set<String> {
        guard throughSequence >= 0 else { throw StoreError.stepFailed }
        workspaceQueue.sync {}
        var touched = Set<String>()
        try execute("BEGIN IMMEDIATE;")
        do {
            try withStatement("SELECT DISTINCT chat_id FROM messages WHERE sequence IS NOT NULL AND sequence <= ?;") { stmt in
                sqlite3_bind_int64(stmt, 1, throughSequence)
                while sqlite3_step(stmt) == SQLITE_ROW {
                    if let text = sqlite3_column_text(stmt, 0) { touched.insert(String(cString: text)) }
                }
            }
            try withStatement("INSERT INTO chat_workspace(chat_id,name,value) VALUES ('__server_history__','cleared_through_sequence',?) ON CONFLICT(chat_id,name) DO UPDATE SET value=MAX(CAST(value AS INTEGER),CAST(excluded.value AS INTEGER));") { stmt in
                sqlite3_bind_int64(stmt, 1, throughSequence)
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
            let removed = "SELECT server_id FROM messages WHERE sequence IS NOT NULL AND sequence <= (SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chat_id='__server_history__' AND name='cleared_through_sequence')"
            try execute("DELETE FROM reactions WHERE target_message_id IN (\(removed));")
            try execute("DELETE FROM messages WHERE sequence IS NOT NULL AND sequence <= (SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chat_id='__server_history__' AND name='cleared_through_sequence');")
            try execute("DELETE FROM pending_decrypts WHERE sequence <= (SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chat_id='__server_history__' AND name='cleared_through_sequence');")
            try execute("COMMIT;")
        } catch { try? execute("ROLLBACK;"); throw error }
        return touched
    }

    func upsert(_ message: DecryptedChatMessage, mediaJob: String? = nil, scheduledAt: String? = nil, journalAttachment: Bool = false) throws {
        workspaceQueue.sync {}
        guard try !isChatDeleted(message.chatId) else { return }
        try execute("BEGIN IMMEDIATE;")
        do {
            if let scheduledAt { try writeChatValue(chatId: message.chatId, name: "schedule:" + message.clientMessageId, value: scheduledAt) }
            if let mediaJob { try writeChatValue(chatId: message.chatId, name: "upload:" + message.clientMessageId, value: mediaJob) }
            try writeMessage(message)
            if journalAttachment, message.kind == "content", message.content.attachment != nil {
                let payload = String(decoding: try encoder.encode(message.content), as: UTF8.self)
                try writeChatValue(chatId: message.chatId, name: "restore_attachment:" + message.clientMessageId, value: payload)
            }
            try applyPeerReceipts(chatId: message.chatId)
            let target = message.kind == "content" ? message.serverId : message.targetMessageId
            if let target { try replayLifecycle(targetServerId: target) }
            try execute("COMMIT;")
        } catch {
            try? execute("ROLLBACK;")
            throw error
        }
    }

    /// Commit server coordinates and the deferred edit row atomically. If the
    /// edit row cannot be written, the sent row is rolled back and retried;
    /// no edit intent is lost and no second content message is created.
    func upsertSentWithPendingEdit(_ sent: DecryptedChatMessage, editRow: DecryptedChatMessage?) throws {
        workspaceQueue.sync {}
        guard try !isChatDeleted(sent.chatId) else { return }
        try execute("BEGIN IMMEDIATE;")
        do {
            try writeMessage(sent)
            if let editRow {
                try writeMessage(editRow)
                try withStatement("UPDATE messages SET pending_edit_text = NULL, pending_edit_revision = 0 WHERE client_message_id = ?;") { stmt in
                    try bindText(stmt, 1, sent.clientMessageId)
                    guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
                }
            }
            try applyPeerReceipts(chatId: sent.chatId)
            // This device may receive no encrypted echo of its own lifecycle
            // event. Commit its projection before the pending overlay expires.
            let target = sent.kind == "content" ? sent.serverId : sent.targetMessageId
            if let target { try replayLifecycle(targetServerId: target) }
            try execute("COMMIT;")
        } catch {
            try? execute("ROLLBACK;")
            throw error
        }
    }

    func isChatDeleted(_ chatId: String) throws -> Bool {
        var deleted = false
        try withStatement("SELECT 1 FROM deleted_chats WHERE chat_id=?;") { stmt in
            try bindText(stmt, 1, chatId)
            let step = sqlite3_step(stmt)
            guard step == SQLITE_ROW || step == SQLITE_DONE else { throw StoreError.stepFailed }
            deleted = step == SQLITE_ROW
        }
        return deleted
    }

    func markChatDeleted(_ chatId: String) throws {
        workspaceQueue.sync {}
        try withStatement("INSERT OR IGNORE INTO deleted_chats(chat_id) VALUES (?);") { stmt in
            try bindText(stmt, 1, chatId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        try withStatement("UPDATE call_activity SET visible=0,unread=0,pending_read=0 WHERE chat_id=?;") { stmt in
            try bindText(stmt,1,chatId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    /// Tombstones remain after erasure to reject delayed REST/WS/outbox writes.
    func purgeDeletedChat(_ chatId: String) throws -> Set<URL> {
        try markChatDeleted(chatId)
        var urls = Set<URL>()
        func collect(_ object: Any) {
            if let string = object as? String {
                if let url = URL(string: string), url.isFileURL { urls.insert(url) }
                else if (string as NSString).isAbsolutePath { urls.insert(URL(fileURLWithPath: string)) }
            } else if let values = object as? [Any] { values.forEach(collect) }
            else if let values = object as? [String: Any] { values.values.forEach(collect) }
        }
        for (name, value) in try workspaceValues(chatId: chatId) where ["mediaDrafts", "fileDraft", "voiceDraft", Self.trayFilesKey].contains(name) || name.hasPrefix("upload:") {
            if name == Self.trayFilesKey, let paths = try? decoder.decode([String].self, from: Data(value.utf8)) {
                urls.formUnion(paths.compactMap(draftSourceJournalURL))
            } else if let json = try? JSONSerialization.jsonObject(with: Data(value.utf8)) { collect(json) }
        }
        try execute("BEGIN IMMEDIATE;")
        do {
            for sql in [
                "DELETE FROM reactions WHERE target_message_id IN (SELECT server_id FROM messages WHERE chat_id=?);",
                "DELETE FROM messages WHERE chat_id=?;", "DELETE FROM pending_decrypts WHERE chat_id=?;", "DELETE FROM call_activity WHERE chat_id=?;", "DELETE FROM chat_workspace WHERE chat_id=?;",
                "DELETE FROM local_read_receipts WHERE chat_id=?;", "DELETE FROM peer_receipts WHERE chat_id=?;"
            ] {
                try withStatement(sql) { stmt in
                    try bindText(stmt, 1, chatId)
                    guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
                }
            }
            try execute("COMMIT;")
        } catch { try? execute("ROLLBACK;"); throw error }
        return urls
    }

    @discardableResult
    func recordPeerReceipt(chatId: String, ownerId: String, delivered: Int64, read: Int64) throws -> Bool {
        try withStatement("INSERT INTO peer_receipts VALUES (?, ?, ?, ?) ON CONFLICT(chat_id) DO UPDATE SET delivered = MAX(delivered, excluded.delivered), read_sequence = MAX(read_sequence, excluded.read_sequence);") { stmt in
            try bindText(stmt, 1, chatId)
            try bindText(stmt, 2, ownerId)
            sqlite3_bind_int64(stmt, 3, delivered)
            sqlite3_bind_int64(stmt, 4, read)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return try applyPeerReceipts(chatId: chatId)
    }

    @discardableResult
    private func applyPeerReceipts(chatId: String) throws -> Bool {
        try withStatement("""
            UPDATE messages SET status = CASE
              WHEN sequence <= (SELECT read_sequence FROM peer_receipts WHERE chat_id = ?) THEN 'read'
              WHEN status = 'sent' AND sequence <= (SELECT delivered FROM peer_receipts WHERE chat_id = ?) THEN 'delivered'
              ELSE status END
            WHERE chat_id = ? AND sender_id = (SELECT owner_id FROM peer_receipts WHERE chat_id = ?)
                AND status IN ('sent', 'delivered') AND sequence IS NOT NULL
                AND (sequence <= (SELECT read_sequence FROM peer_receipts WHERE chat_id = ?)
                     OR (status = 'sent' AND sequence <= (SELECT delivered FROM peer_receipts WHERE chat_id = ?)));
            """) { stmt in
            for index in 1...6 { try bindText(stmt, Int32(index), chatId) }
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return sqlite3_changes(db) > 0
    }

    private func replayLifecycle(targetServerId: String) throws {
        guard let target = try message(byServerId: targetServerId), target.kind == "content" else { return }
        var events: [DecryptedChatMessage] = []
        try withStatement("SELECT \(Self.messageColumns) FROM messages WHERE target_message_id = ? AND kind IN ('edit', 'delete', 'reaction') AND status IN ('sent', 'delivered', 'read') ORDER BY sequence;") { stmt in
            try bindText(stmt, 1, targetServerId)
            while sqlite3_step(stmt) == SQLITE_ROW { events.append(try decodeRow(stmt)) }
        }
        for event in events {
            try applyLifecycle(event, to: target)
        }
    }

    /// Shared by live ingestion/ACK and the bounded legacy projection repair.
    /// Replaying a persisted row never bypasses its authenticated target identity.
    private func applyLifecycle(_ event: DecryptedChatMessage, to target: DecryptedChatMessage) throws {
            guard let targetServerId = target.serverId, target.kind == "content",
                  event.targetMessageId == targetServerId, event.chatId == target.chatId,
                  let sequence = event.sequence else { return }
            guard MessageContentCodec.authenticates(event.content, chatId: event.chatId,
                clientMessageId: event.clientMessageId, operation: event.kind, targetMessageId: event.targetMessageId),
                event.content.authentication?.targetClientMessageId == target.clientMessageId else { return }
            switch event.kind {
            case "edit" where event.senderId == target.senderId:
                // Old builds stored placeholders, which cannot reconstruct an edit.
                if let text = event.content.text, text != "[edit]" {
                    _ = try applyEdit(targetServerId: targetServerId, newText: text, sequence: sequence)
                }
            case "delete" where event.senderId == target.senderId:
                _ = try applyDelete(targetServerId: targetServerId, sequence: sequence)
                try deleteReactions(forMessage: targetServerId)
            case "reaction":
                guard event.content.kind == MessageContent.kindReaction,
                      try message(byServerId: targetServerId)?.deleteSequence == 0,
                      sequence > (try reactionCurrentSequence(targetMessageId: targetServerId, userId: event.senderId)) else { return }
                try upsertReaction(targetMessageId: targetServerId, userId: event.senderId,
                    emoji: event.content.reactionAction == MessageContent.reactionActionRemove ? nil : event.content.reaction,
                    lastReactionSequence: sequence)
            default: break
            }
    }

    private func writeMessage(_ message: DecryptedChatMessage) throws {
        let contentJSON = (try? encoder.encode(message.content)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
        try withStatement(
            """
            INSERT INTO messages
            (client_message_id, server_id, chat_id, sender_id, sequence, created_at, kind, target_message_id, status, content_json, last_edit_sequence, delete_sequence, hidden_locally, payload_text, pending_edit_text, pending_edit_revision, send_attempted)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(client_message_id) DO UPDATE SET
                server_id = COALESCE(excluded.server_id, messages.server_id),
                sequence = COALESCE(excluded.sequence, messages.sequence),
                created_at = excluded.created_at,
                status = CASE WHEN messages.status = 'read' THEN 'read'
                    WHEN messages.status = 'delivered' AND excluded.status IN ('pending', 'sent', 'failed') THEN 'delivered'
                    ELSE excluded.status END,
                content_json = CASE
                    WHEN messages.last_edit_sequence > excluded.last_edit_sequence THEN messages.content_json
                    WHEN messages.pending_edit_text IS NOT NULL AND excluded.pending_edit_text IS NULL
                         AND excluded.server_id IS NOT NULL THEN messages.content_json
                    ELSE excluded.content_json END,
                last_edit_sequence = MAX(messages.last_edit_sequence, excluded.last_edit_sequence),
                delete_sequence = MAX(messages.delete_sequence, excluded.delete_sequence),
                hidden_locally = MAX(messages.hidden_locally, excluded.hidden_locally),
                payload_text = COALESCE(messages.payload_text, excluded.payload_text),
                pending_edit_text = COALESCE(excluded.pending_edit_text, messages.pending_edit_text),
                pending_edit_revision = MAX(messages.pending_edit_revision, excluded.pending_edit_revision),
                send_attempted = MAX(messages.send_attempted, excluded.send_attempted);
            """
        ) { stmt in
            try bindText(stmt, 1, message.clientMessageId)
            try bindOptionalText(stmt, 2, message.serverId)
            try bindText(stmt, 3, message.chatId)
            try bindText(stmt, 4, message.senderId)
            try bindOptionalInt64(stmt, 5, message.sequence)
            sqlite3_bind_double(stmt, 6, message.createdAt.timeIntervalSince1970)
            try bindText(stmt, 7, message.kind)
            try bindOptionalText(stmt, 8, message.targetMessageId)
            try bindText(stmt, 9, message.status.rawValue)
            try bindText(stmt, 10, contentJSON)
            sqlite3_bind_int64(stmt, 11, message.lastEditSequence)
            sqlite3_bind_int64(stmt, 12, message.deleteSequence)
            sqlite3_bind_int64(stmt, 13, message.hiddenLocally ? 1 : 0)
            try bindOptionalText(stmt, 14, message.payloadText)
            try bindOptionalText(stmt, 15, message.pendingEditText)
            sqlite3_bind_int64(stmt, 16, message.pendingEditRevision)
            sqlite3_bind_int64(stmt, 17, message.sendAttempted ? 1 : 0)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    func status(forClientMessageId id: String) throws -> MessageStatus? {
        var result: MessageStatus?
        try withStatement("SELECT status FROM messages WHERE client_message_id = ?;") { stmt in
            try bindText(stmt, 1, id)
            if sqlite3_step(stmt) == SQLITE_ROW {
                let raw = String(cString: sqlite3_column_text(stmt, 0))
                result = MessageStatus(rawValue: raw)
            }
        }
        return result
    }

    /// Minimal projection of one stored message for the ingest dedupe pre-filter.
    struct IngestMessageState: Equatable {
        let status: MessageStatus
        /// Stored `MessageContent.kind`. A `text` kind with a non-nil `text`
        /// is the only state that can carry a legacy text-encoded attachment,
        /// so the recovery pass needs no full-row read otherwise.
        let contentKind: String
        let text: String?
    }

    /// One bounded statement for a whole ingest page. `MessageIngestor` calls
    /// this through `readAsync`, so the page's store work runs once on the
    /// background reader instead of two full-message reads (each decoding
    /// `content_json`) per DTO on the MainActor. The tolerant content fallback
    /// mirrors `decodeRow` exactly: undecodable JSON is stored as text content.
    func ingestMessageStates(clientMessageIds: [String]) throws -> [String: IngestMessageState] {
        var states: [String: IngestMessageState] = [:]
        guard !clientMessageIds.isEmpty else { return states }
        states.reserveCapacity(clientMessageIds.count)
        // SQLite's host-parameter ceiling is far above 400; chunking keeps even
        // an unexpected oversized page from failing the whole sync pass.
        var start = 0
        while start < clientMessageIds.count {
            let end = min(start + 400, clientMessageIds.count)
            let chunk = Array(clientMessageIds[start..<end])
            let placeholders = Array(repeating: "?", count: chunk.count).joined(separator: ",")
            try withStatement("SELECT client_message_id,status,content_json FROM messages WHERE client_message_id IN (\(placeholders));") { stmt in
                for (offset, id) in chunk.enumerated() { try bindText(stmt, Int32(offset + 1), id) }
                var step = sqlite3_step(stmt)
                while step == SQLITE_ROW {
                    if let idText = sqlite3_column_text(stmt, 0),
                       let statusText = sqlite3_column_text(stmt, 1),
                       let jsonText = sqlite3_column_text(stmt, 2) {
                        let contentJSON = String(cString: jsonText)
                        let content = (try? decoder.decode(MessageContent.self, from: Data(contentJSON.utf8)))
                            ?? MessageContent(kind: MessageContent.kindText, text: contentJSON)
                        states[String(cString: idText)] = IngestMessageState(
                            status: MessageStatus(rawValue: String(cString: statusText)) ?? .decryptFailed,
                            contentKind: content.kind,
                            text: content.kind == MessageContent.kindText ? content.text : nil)
                    }
                    step = sqlite3_step(stmt)
                }
                guard step == SQLITE_DONE else { throw StoreError.stepFailed }
            }
            start = end
        }
        return states
    }

    // MARK: - Reads

    /// Forward-only status advance for the sender's own message bubbles
    /// (sent -> delivered -> read, never backwards) — mirrors Android's
    /// `MessageDao.advanceStatusUpTo`. Only rows currently in one of
    /// `fromStatuses` are touched, so a delivered watermark never upgrades a
    /// failed/pending message and a read watermark can't skip a state.
    func advanceStatusUpTo(
        chatId: String,
        myUserId: String,
        sequence: Int64,
        status: MessageStatus,
        fromStatuses: [MessageStatus]
    ) throws {
        let placeholders = fromStatuses.map { _ in "?" }.joined(separator: ",")
        let sql = """
            UPDATE messages SET status = ?
            WHERE chat_id = ? AND sender_id = ? AND sequence IS NOT NULL AND sequence <= ?
            AND status IN (\(placeholders));
            """
        try withStatement(sql) { stmt in
            try bindText(stmt, 1, status.rawValue)
            try bindText(stmt, 2, chatId)
            try bindText(stmt, 3, myUserId)
            sqlite3_bind_int64(stmt, 4, sequence)
            for (index, from) in fromStatuses.enumerated() {
                try bindText(stmt, Int32(index + 5), from.rawValue)
            }
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    /// Lifecycle EVENT rows (kind != "content") never render as their own
    /// bubble — only their side effect on a target row's projection matters
    /// (Android's `observeMessages` filter, Этап I). Locally-hidden
    /// ("delete for me") rows are excluded too, and that exclusion must
    /// survive restart/re-sync (Этап I п.15/45).
    private static let messageColumns = "client_message_id, server_id, chat_id, sender_id, sequence, created_at, kind, target_message_id, status, content_json, last_edit_sequence, delete_sequence, hidden_locally, payload_text, pending_edit_text, pending_edit_revision, send_attempted"

    /// A separate SQLite connection and decoder are owned solely by this
    /// serial queue. Large history reads never share a statement/decoder with
    /// main-actor writes or block navigation/animations.
    func readAsync<T>(_ operation: @escaping (MessageStore) throws -> T) async throws -> T {
        let cancellation = SearchCancellation()
        return try await withTaskCancellationHandler(operation: {
        try await withCheckedThrowingContinuation { continuation in
            readQueue.async {
                do {
                    // A dismissed screen must not leave expensive reads queued
                    // ahead of the next visible screen's snapshot.
                    try cancellation.check()
                    if self.reader == nil { self.reader = try MessageStore(databaseURL: self.databaseURL, readOnly: true) }
                    guard let reader = self.reader else { throw StoreError.openFailed }
                    let value = try operation(reader)
                    try cancellation.check()
                    continuation.resume(returning: value)
                } catch { continuation.resume(throwing: error) }
            }
        }
        }, onCancel: { cancellation.cancel() })
    }

    /// A bounded existence query for the visible chat's receipt-recovery path.
    /// It never decodes message text/media or enumerates another conversation.
    func hasPeerReceiptCandidates(chatId: String, ownerId: String) throws -> Bool {
        var found = false
        try withStatement("""
            SELECT 1 FROM messages WHERE chat_id=? AND sender_id=? AND kind='content'
                AND status IN ('sent','delivered') AND sequence IS NOT NULL
                AND hidden_locally=0 AND delete_sequence=0 LIMIT 1;
            """) { stmt in
            try bindText(stmt, 1, chatId)
            try bindText(stmt, 2, ownerId)
            let step = sqlite3_step(stmt)
            guard step == SQLITE_ROW || step == SQLITE_DONE else { throw StoreError.stepFailed }
            found = step == SQLITE_ROW
        }
        return found
    }

    func receiptSequence(chatId: String, ownerId: String, deliveredOnly: Bool, upperBound: Int64 = .max) throws -> Int64 {
        var sequence: Int64 = 0
        let statusFilter = deliveredOnly ? "status = 'delivered'" : "status NOT IN ('decrypt_failed', 'identity_changed')"
        try withStatement("""
            SELECT COALESCE(MAX(sequence), 0) FROM messages
            WHERE chat_id = ? AND sender_id <> ? AND kind = 'content' AND hidden_locally = 0
              AND \(statusFilter) AND sequence <= ?;
            """) { stmt in
            try bindText(stmt, 1, chatId)
            try bindText(stmt, 2, ownerId)
            sqlite3_bind_int64(stmt, 3, upperBound)
            guard sqlite3_step(stmt) == SQLITE_ROW else { throw StoreError.stepFailed }
            sequence = sqlite3_column_int64(stmt, 0)
        }
        return sequence
    }

    private final class SearchCancellation {
        private let lock = NSLock()
        private var cancelled = false
        func cancel() { lock.lock(); cancelled = true; lock.unlock() }
        func check() throws {
            lock.lock(); let value = cancelled; lock.unlock()
            if value { throw CancellationError() }
        }
    }
    func searchVisibleMessagesAsync(query: String, chatId: String? = nil, limit: Int = 100) async throws -> [DecryptedChatMessage] {
        let cancellation = SearchCancellation()
        return try await withTaskCancellationHandler(operation: {
            try await readAsync { reader in
                try reader.searchVisibleMessages(query: query, chatId: chatId, limit: limit, checkCancellation: { try cancellation.check() })
            }
        }, onCancel: { cancellation.cancel() })
    }

    /// Stream decoded, visible content on the existing background reader.
    /// Never search serialized crypto/attachment metadata or hidden history.
    func searchVisibleMessages(query: String, chatId: String? = nil, limit: Int = 100, checkCancellation: () throws -> Void = {}) throws -> [DecryptedChatMessage] {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty, limit > 0 else { return [] }
        try checkCancellation()
        var result: [DecryptedChatMessage] = []
        try withStatement("""
            SELECT \(Self.messageColumns) FROM messages
            WHERE kind = 'content' AND hidden_locally = 0 AND delete_sequence = 0
              AND status NOT IN ('decrypt_failed', 'identity_changed')
              AND chat_id NOT IN (SELECT chat_id FROM deleted_chats)
              \(chatId == nil ? "" : "AND chat_id = ?")
            ORDER BY created_at DESC, client_message_id DESC;
            """) { stmt in
            if let chatId { try bindText(stmt, 1, chatId) }
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW {
                try checkCancellation()
                let message = try decodeRow(stmt)
                let text: String = DirectFilePreviewText.projected(text: message.content.text, offer: message.content.directFile) ?? message.content.text ?? ""
                let filename: String = message.content.directFile?.filename ?? message.content.attachment?.filename ?? ""
                if text.localizedCaseInsensitiveContains(term) || filename.localizedCaseInsensitiveContains(term) {
                    result.append(message)
                    if result.count >= limit { return }
                }
                step = sqlite3_step(stmt)
            }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return result
    }

    /// Startup reconciliation for the narrow gap between a durable message
    /// write and the account-owned direct-transfer observer. This never scans
    /// or publishes an entire chat history into a render pass.
    func directFileOfferMessages() throws -> [DecryptedChatMessage] {
        var result: [DecryptedChatMessage] = []
        try withStatement("SELECT \(MessageStore.messageColumns) FROM messages WHERE kind='content' AND hidden_locally=0 AND delete_sequence=0 AND instr(content_json,'\"direct_file\"')>0 AND created_at>=? ORDER BY created_at DESC LIMIT 1000;") { stmt in
            sqlite3_bind_double(stmt, 1, Date().timeIntervalSince1970 - Double(DirectFileOffer.lifetimeSeconds))
            while sqlite3_step(stmt) == SQLITE_ROW {
                let message = try decodeRow(stmt)
                guard message.content.directFile != nil, message.content.authentication != nil,
                      !message.content.forwarded,
                      MessageContentCodec.authenticates(message.content, chatId: message.chatId,
                        clientMessageId: message.clientMessageId, operation: "content", targetMessageId: nil) else { continue }
                result.append(message)
            }
        }
        return result
    }

    func messages(forChatId chatId: String) throws -> [DecryptedChatMessage] {
        var result: [DecryptedChatMessage] = []
        try withStatement(
            """
            SELECT \(MessageStore.messageColumns),
                   EXISTS (SELECT 1 FROM messages target WHERE target.server_id = messages.target_message_id
                       AND target.chat_id = messages.chat_id AND (target.hidden_locally = 1 OR target.delete_sequence > 0))
            FROM messages WHERE chat_id = ? AND kind = 'content' AND hidden_locally = 0 AND delete_sequence = 0
            ORDER BY (sequence IS NULL), sequence ASC, created_at ASC;
            """
        ) { stmt in
            try bindText(stmt, 1, chatId)
            while sqlite3_step(stmt) == SQLITE_ROW {
                var message = try decodeRow(stmt)
                if sqlite3_column_int(stmt, 17) != 0 { message.content.replySnapshot = nil }
                result.append(message)
            }
        }
        // Overlay pending local intents without changing confirmed backup data.
        for event in try pendingLifecycle(chatId: chatId) {
            guard let index = result.firstIndex(where: { $0.serverId == event.targetMessageId }),
                  event.kind == "reaction" || result[index].senderId == event.senderId else { continue }
            switch event.kind {
            case "edit": result[index].content.text = event.content.text
            case "delete": result.remove(at: index)
            default: break
            }
        }
        return result
    }

    /// UI-only tail. Export/backup continues to use the complete messages API.
    /// Extra row detects local history without decoding the entire conversation.
    func messageWindow(forChatId chatId: String, limit: Int) throws -> (messages: [DecryptedChatMessage], hasMore: Bool) {
        let size = max(1, min(limit, Int(Int32.max) - 1))
        var result: [DecryptedChatMessage] = []
        try withStatement("""
            SELECT \(MessageStore.messageColumns),
                   EXISTS (SELECT 1 FROM messages target WHERE target.server_id = messages.target_message_id
                       AND target.chat_id = messages.chat_id AND (target.hidden_locally = 1 OR target.delete_sequence > 0))
            FROM messages WHERE chat_id = ? AND kind = 'content' AND hidden_locally = 0 AND delete_sequence = 0
            ORDER BY created_at DESC, COALESCE(sequence, 9223372036854775807) DESC, client_message_id DESC LIMIT ?;
            """) { stmt in
            try bindText(stmt, 1, chatId)
            sqlite3_bind_int64(stmt, 2, Int64(size + 1))
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW {
                var message = try decodeRow(stmt)
                if sqlite3_column_int(stmt, 17) != 0 { message.content.replySnapshot = nil }
                result.append(message)
                step = sqlite3_step(stmt)
            }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        let more = result.count > size
        if more { result.removeLast() }
        result.reverse()
        // Same durable local edit/delete projection as the complete read.
        for event in try pendingLifecycle(chatId: chatId) {
            guard let index = result.firstIndex(where: { $0.serverId == event.targetMessageId }),
                  event.kind == "reaction" || result[index].senderId == event.senderId else { continue }
            switch event.kind {
            case "edit": result[index].content.text = event.content.text
            case "delete": result.remove(at: index)
            default: break
            }
        }
        return (result, more)
    }

    /// Number of tail rows needed to include a search target/bookmark. Uses
    /// indexed scalar data, not JSON decoding of all intervening messages.
    func messageWindowSize(chatId: String, anchor: String) throws -> Int? {
        var result: Int?
        try withStatement("""
            WITH target AS (
                SELECT chat_id,client_message_id,created_at,sequence FROM messages
                WHERE chat_id=? AND (client_message_id=? OR server_id=?)
                  AND kind='content' AND hidden_locally=0 AND delete_sequence=0 LIMIT 1
            )
            SELECT COUNT(*) FROM target t JOIN messages m ON t.chat_id=m.chat_id
            WHERE m.kind='content' AND m.hidden_locally=0 AND m.delete_sequence=0
              AND (m.created_at>t.created_at OR (m.created_at=t.created_at AND
                (COALESCE(m.sequence,9223372036854775807)>COALESCE(t.sequence,9223372036854775807) OR
                 (COALESCE(m.sequence,9223372036854775807)=COALESCE(t.sequence,9223372036854775807) AND m.client_message_id>=t.client_message_id))));
            """) { stmt in
            try bindText(stmt, 1, chatId); try bindText(stmt, 2, anchor); try bindText(stmt, 3, anchor)
            guard sqlite3_step(stmt) == SQLITE_ROW else { throw StoreError.stepFailed }
            let count = Int(sqlite3_column_int64(stmt, 0))
            if count > 0 { result = count }
        }
        return result
    }

    /// Timeline position of a chat media gallery entry.
    struct GalleryOrder: Equatable, Sendable {
        let createdAt: Double
        /// `Int64.max` for an unsent row, so it sorts newest like the timeline.
        let sequence: Int64
    }

    /// Ordering keys for the gallery's attachment rows whose message is still
    /// visible in the timeline: a content row not hidden locally, not deleted
    /// for everyone, not being deleted for everyone by this device, and after
    /// the personal history cutoff. Indexed id lookups; no JSON is decoded.
    func galleryMessageOrder(chatId: String, clientMessageIds: [String]) throws -> [String: GalleryOrder] {
        var result: [String: GalleryOrder] = [:]
        var seen = Set<String>()
        let ids = clientMessageIds.filter { seen.insert($0).inserted }
        // Stay below SQLite's conservative bind-variable limit.
        for start in stride(from: 0, to: ids.count, by: 400) {
            let batch = Array(ids[start..<min(start + 400, ids.count)])
            try withStatement("""
                SELECT m.client_message_id, m.created_at, COALESCE(m.sequence, 9223372036854775807) FROM messages m
                WHERE m.chat_id = ? AND m.client_message_id IN (\(Array(repeating: "?", count: batch.count).joined(separator: ",")))
                  AND m.kind = 'content' AND m.hidden_locally = 0 AND m.delete_sequence = 0
                  AND (m.sequence IS NULL OR m.sequence > COALESCE((SELECT CAST(value AS INTEGER) FROM chat_workspace
                       WHERE chat_id = m.chat_id AND name = 'personal_clear'), -1))
                  AND NOT EXISTS (SELECT 1 FROM messages d WHERE d.target_message_id = m.server_id AND d.chat_id = m.chat_id
                       AND d.kind = 'delete' AND d.status = 'pending' AND d.hidden_locally = 0 AND d.sender_id = m.sender_id);
                """) { stmt in
                try bindText(stmt, 1, chatId)
                for (index, id) in batch.enumerated() { try bindText(stmt, Int32(index + 2), id) }
                var step = sqlite3_step(stmt)
                while step == SQLITE_ROW {
                    result[String(cString: sqlite3_column_text(stmt, 0))] = GalleryOrder(
                        createdAt: sqlite3_column_double(stmt, 1), sequence: sqlite3_column_int64(stmt, 2))
                    step = sqlite3_step(stmt)
                }
                guard step == SQLITE_DONE else { throw StoreError.stepFailed }
            }
        }
        return result
    }

    /// True while this device's own "delete for everyone" of the target is
    /// queued; the timeline already hides the target (see `pendingLifecycle`).
    func hasPendingDelete(targetServerId: String, senderId: String) throws -> Bool {
        var found = false
        try withStatement("""
            SELECT 1 FROM messages WHERE target_message_id = ? AND kind = 'delete' AND status = 'pending'
                AND hidden_locally = 0 AND sender_id = ? LIMIT 1;
            """) { stmt in
            try bindText(stmt, 1, targetServerId)
            try bindText(stmt, 2, senderId)
            let step = sqlite3_step(stmt)
            guard step == SQLITE_ROW || step == SQLITE_DONE else { throw StoreError.stepFailed }
            found = step == SQLITE_ROW
        }
        return found
    }

    func bookmarkWindowSize(chatId: String, anchor: String) throws -> Int? {
        guard anchor.hasPrefix("call:") else { return try messageWindowSize(chatId: chatId, anchor: anchor) }
        var size: Int?
        try withStatement("""
            WITH target AS (SELECT created_at FROM call_activity WHERE call_id=? AND chat_id=? AND visible=1 LIMIT 1)
            SELECT COUNT(*) FROM target t JOIN messages m ON m.created_at>=t.created_at
            WHERE m.chat_id=? AND m.kind='content' AND m.hidden_locally=0 AND m.delete_sequence=0;
            """) { stmt in
            try bindText(stmt, 1, String(anchor.dropFirst(5)))
            try bindText(stmt, 2, chatId); try bindText(stmt, 3, chatId)
            guard sqlite3_step(stmt) == SQLITE_ROW else { throw StoreError.stepFailed }
            size = Int(sqlite3_column_int64(stmt, 0))
        }
        return size
    }

    /// Bounded keyset page over the durable outbox. The caller owns ordering,
    /// per-chat dependencies and backoff; this method only decodes this page.
    func pendingMessages(limit: Int, after: PendingMessageCursor? = nil) throws -> PendingMessagePage {
        guard limit > 0 else { return PendingMessagePage(messages: [], next: nil, hasMore: false) }
        var result: [DecryptedChatMessage] = []
        var lastCursor: PendingMessageCursor?
        try withStatement(
            """
            SELECT \(MessageStore.messageColumns), rowid
            FROM messages
            WHERE status = 'pending' AND kind IN ('content','edit','delete','reaction') AND hidden_locally = 0
              AND (? IS NULL OR created_at > ? OR (created_at = ? AND rowid > ?))
            ORDER BY created_at ASC, rowid ASC LIMIT ?;
            """
        ) { stmt in
            if let after {
                sqlite3_bind_double(stmt, 1, after.createdAt)
                sqlite3_bind_double(stmt, 2, after.createdAt)
                sqlite3_bind_double(stmt, 3, after.createdAt)
                sqlite3_bind_int64(stmt, 4, after.rowId)
            } else {
                sqlite3_bind_null(stmt, 1)
                sqlite3_bind_null(stmt, 2)
                sqlite3_bind_null(stmt, 3)
                sqlite3_bind_null(stmt, 4)
            }
            sqlite3_bind_int(stmt, 5, Int32(clamping: limit))
            while sqlite3_step(stmt) == SQLITE_ROW {
                result.append(try decodeRow(stmt))
                lastCursor = PendingMessageCursor(
                    createdAt: sqlite3_column_double(stmt, 5),
                    rowId: sqlite3_column_int64(stmt, 17)
                )
            }
        }
        return PendingMessagePage(messages: result, next: lastCursor, hasMore: result.count == limit)
    }

    /// IDs only for chat deletion cleanup; never decodes payloads.
    func pendingMessageIDs(chatId: String) throws -> [String] {
        var result: [String] = []
        try withStatement(
            "SELECT client_message_id FROM messages WHERE chat_id = ? AND status = 'pending' AND hidden_locally = 0 ORDER BY created_at ASC, rowid ASC;"
        ) { stmt in
            try bindText(stmt, 1, chatId)
            while sqlite3_step(stmt) == SQLITE_ROW {
                result.append(String(cString: sqlite3_column_text(stmt, 0)))
            }
        }
        return result
    }

    /// Freeze the plaintext that belongs to this idempotency key. After this
    /// point edits update the visible text + pending intent, never `payloadText`.
    func beginSend(clientMessageId: String, fallbackText: String?) throws {
        try withStatement(
            """
            UPDATE messages SET payload_text = COALESCE(payload_text, ?), send_attempted = 1
            WHERE client_message_id = ? AND status = 'pending' AND kind = 'content' AND hidden_locally = 0;
            """
        ) { stmt in
            try bindOptionalText(stmt, 1, fallbackText)
            try bindText(stmt, 2, clientMessageId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    /// An edit before the first attempt is just the message's text. Returns
    /// false when the row is already frozen/attempted; caller then stores an
    /// intent.
    func updateTextBeforeSend(clientMessageId: String, content: MessageContent) throws -> Bool {
        let json = (try? encoder.encode(content)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
        var changed = false
        try withStatement(
            """
            UPDATE messages SET content_json = ?, payload_text = ?, pending_edit_text = NULL, pending_edit_revision = 0
            WHERE client_message_id = ? AND server_id IS NULL AND send_attempted = 0
              AND status IN ('pending','failed') AND kind = 'content' AND hidden_locally = 0;
            """
        ) { stmt in
            try bindText(stmt, 1, json)
            try bindOptionalText(stmt, 2, content.text)
            try bindText(stmt, 3, clientMessageId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            changed = sqlite3_changes(db!) > 0
        }
        return changed
    }

    /// Store the latest edit while the original POST may already be in flight.
    /// `payloadText` is intentionally untouched.
    func setPendingEdit(clientMessageId: String, content: MessageContent, revision: Int64) throws -> Bool {
        let json = (try? encoder.encode(content)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
        var changed = false
        try withStatement(
            """
            UPDATE messages SET content_json = ?, pending_edit_text = ?, pending_edit_revision = ?
            WHERE client_message_id = ? AND server_id IS NULL AND send_attempted = 1
              AND status IN ('pending','failed') AND kind = 'content' AND hidden_locally = 0;
            """
        ) { stmt in
            try bindText(stmt, 1, json)
            try bindOptionalText(stmt, 2, content.text)
            sqlite3_bind_int64(stmt, 3, revision)
            try bindText(stmt, 4, clientMessageId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            changed = sqlite3_changes(db!) > 0
        }
        return changed
    }

    func clearPendingEdit(clientMessageId: String) throws {
        try withStatement("UPDATE messages SET pending_edit_text = NULL, pending_edit_revision = 0 WHERE client_message_id = ?;") { stmt in
            try bindText(stmt, 1, clientMessageId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    // MARK: - Chat-list preview

    /// Server membership alone is not local history. Retain readable/restored
    /// content, pending sends, new arrivals (including decrypt errors), and
    /// conversations deliberately opened on this device. One bulk indexed query.
    func visibleChatIDs(historyCutoff: Int64, ownerId: String? = nil) throws -> Set<String> {
        var result = Set<String>()
        try withStatement("""
            SELECT DISTINCT chat_id FROM messages
            WHERE kind='content' AND hidden_locally=0
              AND (sequence IS NULL OR sequence>? OR status NOT IN ('decrypt_failed','identity_changed'))
            UNION SELECT chat_id FROM chat_workspace WHERE name='opened_on_device' AND value='1'
            UNION SELECT chat_id FROM call_activity WHERE owner_id=? AND visible=1 AND chat_id IS NOT NULL;
            """) { stmt in
            sqlite3_bind_int64(stmt, 1, max(0, historyCutoff))
            try bindText(stmt, 2, ownerId ?? "")
            while sqlite3_step(stmt) == SQLITE_ROW {
                result.insert(String(cString: sqlite3_column_text(stmt, 0)))
            }
        }
        return result
    }

    func latestActivityByChat() throws -> [String: Date] {
        var result: [String: Date] = [:]
        try withStatement("SELECT chat_id, MAX(created_at) FROM messages WHERE kind = 'content' AND hidden_locally = 0 GROUP BY chat_id;") { stmt in
            while sqlite3_step(stmt) == SQLITE_ROW {
                result[String(cString: sqlite3_column_text(stmt, 0))] = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 1))
            }
        }
        return result
    }

    /// The latest displayable preview per chat, in one bulk query (never a
    /// per-chat round-trip). Mirrors Android's `ChatDao` preview subqueries:
    /// latest `content` message, excluding locally-hidden rows and
    /// decrypt-failed / identity-changed statuses. Pending (sequence NULL)
    /// messages sort first as the newest.
    func latestPreviewByChat() throws -> [String: MessagePreview] {
        var result: [String: MessagePreview] = [:]
        try withStatement(
            """
            SELECT chat_id, content_json, delete_sequence
            FROM (
                SELECT chat_id, content_json, delete_sequence,
                       ROW_NUMBER() OVER (
                           PARTITION BY chat_id
                           ORDER BY COALESCE(sequence, 9223372036854775807) DESC,
                                    created_at DESC, client_message_id DESC
                       ) AS preview_rank
                FROM messages
                WHERE kind = 'content' AND hidden_locally = 0 AND delete_sequence = 0
                  AND status NOT IN ('decrypt_failed', 'identity_changed')
            )
            WHERE preview_rank = 1
            """
        ) { stmt in
            while sqlite3_step(stmt) == SQLITE_ROW {
                let chatId = String(cString: sqlite3_column_text(stmt, 0))
                let contentJSON = String(cString: sqlite3_column_text(stmt, 1))
                let deleted = sqlite3_column_int64(stmt, 2) != 0
                if let preview = Self.preview(contentJSON: contentJSON, deleted: deleted) {
                    result[chatId] = preview
                }
            }
        }
        return result
    }

    /// Maps a message's stored `content_json` (+ delete tombstone) to a
    /// human-safe preview. Never surfaces raw JSON / object keys / UUIDs.
    private static func preview(contentJSON: String, deleted: Bool) -> MessagePreview? {
        if deleted { return .deleted }
        guard let data = contentJSON.data(using: .utf8),
              var content = try? JSONDecoder().decode(MessageContent.self, from: data) else {
            return nil
        }
        // Legacy interop: some attachment payloads arrive as JSON-in-text.
        if content.conferenceInvite != nil { return .conferenceInvitation }
        if content.kind == MessageContent.kindText,
           let raw = content.text, raw.hasPrefix("{"),
           MessageContentCodec.decode(Data(raw.utf8)).kind == MessageContent.kindAttachment {
            content = MessageContentCodec.decode(Data(raw.utf8))
        }
        // A direct (phone-to-phone) file offer reads as a file, not its English fallback text.
        if let offer = content.directFile { return .file(offer.filename) }
        if DirectFilePreviewText.isFallback(content.text) { return .file(nil) }
        if let attachment = content.attachment {
            if attachment.mimeType.lowercased().hasPrefix("video/") { return .video }
            switch attachment.mediaType {
            case AttachmentMetadata.mediaTypeImage: return .image
            case AttachmentMetadata.mediaTypeVoice: return .voice
            default: return .file(attachment.filename)
            }
        }
        if let text = content.text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .text(text)
        }
        return nil
    }

    // MARK: - Lookups

    func message(byClientMessageId clientMessageId: String) throws -> DecryptedChatMessage? {
        var result: DecryptedChatMessage?
        try withStatement(
            "SELECT \(MessageStore.messageColumns) FROM messages WHERE client_message_id = ?;"
        ) { stmt in
            try bindText(stmt, 1, clientMessageId)
            if sqlite3_step(stmt) == SQLITE_ROW {
                result = try decodeRow(stmt)
            }
        }
        return result
    }

    /// Looks up a message by its SERVER id — lifecycle events address their
    /// target via `target_message_id` (the server-assigned id), not the
    /// locally-generated clientMessageId (Android's `findByServerId`).
    func message(byServerId serverId: String) throws -> DecryptedChatMessage? {
        var result: DecryptedChatMessage?
        try withStatement(
            "SELECT \(MessageStore.messageColumns) FROM messages WHERE server_id = ? LIMIT 2;"
        ) { stmt in
            try bindText(stmt, 1, serverId)
            if sqlite3_step(stmt) == SQLITE_ROW {
                result = try decodeRow(stmt)
                // Never authorize one row and then mutate multiple rows sharing
                // a server-controlled id, including collisions in old databases.
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
        }
        return result
    }

    // MARK: - Lifecycle effects (mirror Android MessageDao, Этап I)

    /// Applies an edit event's new text to its target, but ONLY if this is a
    /// genuinely newer edit than whatever was last applied AND the target
    /// isn't already tombstoned (Android `applyEdit`, Этап I п.41/43/44 —
    /// deterministic by global sequence, never by WS/sync arrival order).
    /// Returns the number of rows updated (0 if the target is absent or the
    /// guard rejected it).
    func applyEdit(targetServerId: String, newText: String, sequence: Int64) throws -> Int {
        guard let target = try message(byServerId: targetServerId),
              sequence > target.lastEditSequence,
              target.deleteSequence == 0 else { return 0 }
        var content = target.content
        content.text = newText
        content.kind = MessageContent.kindText
        content.attachment = nil
        let contentJSON = (try? encoder.encode(content)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
        try withStatement(
            """
            UPDATE messages SET content_json = ?, last_edit_sequence = ?
            WHERE server_id = ? AND ? > last_edit_sequence AND delete_sequence = 0;
            """
        ) { stmt in
            try bindText(stmt, 1, contentJSON)
            sqlite3_bind_int64(stmt, 2, sequence)
            try bindText(stmt, 3, targetServerId)
            sqlite3_bind_int64(stmt, 4, sequence)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return lastChanges()
    }

    /// Tombstones the target for "delete for everyone" — guarded the same way
    /// as `applyEdit` (only a genuinely newer sequence takes effect;
    /// idempotent against a duplicate delete event). Returns rows updated.
    func applyDelete(targetServerId: String, sequence: Int64) throws -> Int {
        guard try message(byServerId: targetServerId) != nil else { return 0 }
        try withStatement(
            """
            UPDATE messages SET delete_sequence = ?
            WHERE server_id = ? AND ? > delete_sequence;
            """
        ) { stmt in
            sqlite3_bind_int64(stmt, 1, sequence)
            try bindText(stmt, 2, targetServerId)
            sqlite3_bind_int64(stmt, 3, sequence)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return lastChanges()
    }

    /// Sender-side OPTIMISTIC edit display (Android `setOptimisticText`,
    /// Этап I п.36) — keyed by the sender's own clientMessageId and
    /// unguarded, unlike `applyEdit` (sequence-guarded, remote events).
    func setOptimisticText(clientMessageId: String, newText: String) throws {
        guard let target = try message(byClientMessageId: clientMessageId) else { return }
        var content = target.content
        content.text = newText
        content.kind = MessageContent.kindText
        content.attachment = nil
        let contentJSON = (try? encoder.encode(content)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
        try withStatement(
            "UPDATE messages SET content_json = ? WHERE client_message_id = ?;"
        ) { stmt in
            try bindText(stmt, 1, contentJSON)
            try bindText(stmt, 2, clientMessageId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    /// Sender-side optimistic tombstone/rollback (Android `setOptimisticDeleted`,
    /// Этап I п.36).
    func setOptimisticDeleted(clientMessageId: String, deleted: Bool) throws {
        try withStatement(
            "UPDATE messages SET delete_sequence = ? WHERE client_message_id = ?;"
        ) { stmt in
            sqlite3_bind_int64(stmt, 1, deleted ? 1 : 0)
            try bindText(stmt, 2, clientMessageId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    /// "Delete for me" — local-only, never touches `delete_sequence` (Android
    /// `hideLocally`, Этап I п.15).
    func hideLocally(clientMessageId: String) throws {
        try withStatement(
            "UPDATE messages SET hidden_locally = 1, status = CASE WHEN server_id IS NULL AND status = 'pending' THEN 'failed' ELSE status END WHERE client_message_id = ?;"
        ) { stmt in
            try bindText(stmt, 1, clientMessageId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    /// A tap captures bounded IDs. ACKed/foreign/hidden rows are rejected by
    /// the same UPDATE that hides them; no stale UI snapshot can delete history.
    func cancelUnsentMessages(chatId: String, owner: String, ids: [String]) throws -> [String] {
        var seen = Set<String>()
        let captured = Array(ids.filter { !$0.isEmpty && seen.insert($0).inserted }.prefix(10))
        guard !captured.isEmpty else { return [] }
        try execute("BEGIN IMMEDIATE;")
        do {
            var cancelled: [String] = []
            for id in captured {
                var changed = false
                try withStatement(
                    """
                    UPDATE messages SET hidden_locally=1,status='failed',pending_edit_text=NULL,pending_edit_revision=0
                    WHERE client_message_id=? AND chat_id=? AND sender_id=? AND server_id IS NULL
                      AND status IN ('pending','failed') AND hidden_locally=0 AND delete_sequence=0 AND kind='content';
                    """
                ) { stmt in
                    try bindText(stmt, 1, id); try bindText(stmt, 2, chatId); try bindText(stmt, 3, owner)
                    guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
                    changed = sqlite3_changes(db!) > 0
                }
                if changed { cancelled.append(id) }
            }
            try execute("COMMIT;")
            return cancelled
        } catch { try? execute("ROLLBACK;"); throw error }
    }

    // MARK: - Reactions (mirror Android ReactionDao, Этап I п.18/20/42)

    func reaction(targetMessageId: String, userId: String) throws -> ReactionRow? {
        var result: ReactionRow?
        try withStatement(
            "SELECT target_message_id, user_id, emoji, last_reaction_sequence FROM reactions WHERE target_message_id = ? AND user_id = ?;"
        ) { stmt in
            try bindText(stmt, 1, targetMessageId)
            try bindText(stmt, 2, userId)
            if sqlite3_step(stmt) == SQLITE_ROW {
                result = decodeReactionRow(stmt)
            }
        }
        return result
    }

    func reactionCurrentSequence(targetMessageId: String, userId: String) throws -> Int64 {
        try reaction(targetMessageId: targetMessageId, userId: userId)?.lastReactionSequence ?? 0
    }

    /// One row per (target, user): the composite PK enforces "0 or 1 active
    /// reaction per user per message" at the schema level. `emoji = nil` means
    /// "removed" — the row is KEPT so the sequence guard survives the removal
    /// (stops a stale reordered "set" from resurrecting a removed reaction).
    func upsertReaction(targetMessageId: String, userId: String, emoji: String?, lastReactionSequence: Int64) throws {
        try withStatement(
            """
            INSERT INTO reactions
            (target_message_id, user_id, emoji, last_reaction_sequence)
            SELECT ?, ?, ?, ? WHERE EXISTS (
                SELECT 1 FROM messages WHERE server_id = ? AND delete_sequence = 0
            )
            ON CONFLICT(target_message_id, user_id) DO UPDATE SET
                emoji = excluded.emoji, last_reaction_sequence = excluded.last_reaction_sequence
            WHERE excluded.last_reaction_sequence > reactions.last_reaction_sequence;
            """
        ) { stmt in
            try bindText(stmt, 1, targetMessageId)
            try bindText(stmt, 2, userId)
            try bindOptionalText(stmt, 3, emoji)
            sqlite3_bind_int64(stmt, 4, lastReactionSequence)
            try bindText(stmt, 5, targetMessageId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    func deleteReactions(forMessage targetMessageId: String) throws {
        try withStatement("DELETE FROM reactions WHERE target_message_id = ?;") { stmt in
            try bindText(stmt, 1, targetMessageId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    /// Active (emoji != NULL) reactions for every message in a chat — the
    /// reaction feed the UI merges into message bubbles (Android's
    /// `observeActiveForChat`).
    func activeReactions(forChatId chatId: String) throws -> [ReactionRow] {
        var result: [ReactionRow] = []
        try withStatement(
            """
            SELECT r.target_message_id, r.user_id, r.emoji, r.last_reaction_sequence
            FROM reactions r
            JOIN messages m ON m.server_id = r.target_message_id
            WHERE m.chat_id = ? AND r.emoji IS NOT NULL
            ORDER BY r.target_message_id, r.last_reaction_sequence, r.user_id;
            """
        ) { stmt in
            try bindText(stmt, 1, chatId)
            while sqlite3_step(stmt) == SQLITE_ROW {
                result.append(decodeReactionRow(stmt))
            }
        }
        for event in try pendingLifecycle(chatId: chatId) {
            guard let target = event.targetMessageId else { continue }
            if event.kind == "delete" { result.removeAll { $0.targetMessageId == target }; continue }
            guard event.kind == "reaction" else { continue }
            result.removeAll { $0.targetMessageId == target && $0.userId == event.senderId }
            if event.content.reactionAction != MessageContent.reactionActionRemove, let emoji = event.content.reaction {
                result.append(ReactionRow(targetMessageId: target, userId: event.senderId, emoji: emoji, lastReactionSequence: 0))
            }
        }
        return result
    }

    /// UI snapshots need reactions only for their loaded message window. Keep
    /// the all-chat overload above for history/export consumers. Chunked bound
    /// IDs also support a reply jump or a large already-paged-in window.
    func activeReactions(forChatId chatId: String, targetServerIds: Set<String>) throws -> [ReactionRow] {
        guard !targetServerIds.isEmpty else { return [] }
        let ids = targetServerIds.sorted()
        var result: [ReactionRow] = []
        var pending: [(rowId: Int64, message: DecryptedChatMessage)] = []
        for start in stride(from: 0, to: ids.count, by: 400) {
            try Task.checkCancellation()
            let chunk = Array(ids[start..<min(ids.count, start + 400)])
            let placeholders = Array(repeating: "?", count: chunk.count).joined(separator: ",")
            try withStatement(
                """
                SELECT r.target_message_id, r.user_id, r.emoji, r.last_reaction_sequence
                FROM reactions r
                JOIN messages m INDEXED BY idx_messages_server ON m.server_id = r.target_message_id
                WHERE m.chat_id = ? AND r.target_message_id IN (\(placeholders)) AND r.emoji IS NOT NULL
                ORDER BY r.target_message_id, r.last_reaction_sequence, r.user_id;
                """
            ) { stmt in
                try bindText(stmt, 1, chatId)
                for (index, id) in chunk.enumerated() { try bindText(stmt, Int32(index + 2), id) }
                var step = sqlite3_step(stmt)
                while step == SQLITE_ROW {
                    try Task.checkCancellation()
                    result.append(decodeReactionRow(stmt))
                    step = sqlite3_step(stmt)
                }
                guard step == SQLITE_DONE else { throw StoreError.stepFailed }
            }
            try withStatement("SELECT \(Self.messageColumns),rowid FROM messages INDEXED BY idx_messages_target WHERE chat_id=? AND target_message_id IN (\(placeholders)) AND kind IN ('edit','delete','reaction') AND status='pending' AND hidden_locally=0 ORDER BY created_at,rowid;") { stmt in
                try bindText(stmt, 1, chatId)
                for (index, id) in chunk.enumerated() { try bindText(stmt, Int32(index + 2), id) }
                var step = sqlite3_step(stmt)
                while step == SQLITE_ROW {
                    try Task.checkCancellation()
                    pending.append((sqlite3_column_int64(stmt, sqlite3_column_count(stmt) - 1), try decodeRow(stmt)))
                    step = sqlite3_step(stmt)
                }
                guard step == SQLITE_DONE else { throw StoreError.stepFailed }
            }
        }
        // Reassemble exactly the all-chat query's order before applying durable
        // intents. An identical timestamp is ordered by the persistent rowid.
        result.sort {
            if $0.targetMessageId != $1.targetMessageId { return $0.targetMessageId < $1.targetMessageId }
            if $0.lastReactionSequence != $1.lastReactionSequence { return $0.lastReactionSequence < $1.lastReactionSequence }
            return $0.userId < $1.userId
        }
        pending.sort { $0.message.createdAt == $1.message.createdAt ? $0.rowId < $1.rowId : $0.message.createdAt < $1.message.createdAt }
        for item in pending {
            let event = item.message
            guard let target = event.targetMessageId else { continue }
            if event.kind == "delete" { result.removeAll { $0.targetMessageId == target }; continue }
            guard event.kind == "reaction" else { continue }
            result.removeAll { $0.targetMessageId == target && $0.userId == event.senderId }
            if event.content.reactionAction != MessageContent.reactionActionRemove, let emoji = event.content.reaction {
                result.append(ReactionRow(targetMessageId: target, userId: event.senderId, emoji: emoji, lastReactionSequence: 0))
            }
        }
        return result
    }

    func failedLifecycle(chatId: String) throws -> [DecryptedChatMessage] {
        var result: [DecryptedChatMessage] = []
        try withStatement("SELECT \(Self.messageColumns) FROM messages WHERE chat_id=? AND kind IN ('edit','delete','reaction') AND status='failed' AND hidden_locally=0 ORDER BY created_at,rowid;") { stmt in
            try bindText(stmt, 1, chatId)
            while sqlite3_step(stmt) == SQLITE_ROW { result.append(try decodeRow(stmt)) }
        }
        return result
    }

    private func pendingLifecycle(chatId: String) throws -> [DecryptedChatMessage] {
        var result: [DecryptedChatMessage] = []
        try withStatement("SELECT \(Self.messageColumns) FROM messages WHERE chat_id=? AND kind IN ('edit','delete','reaction') AND status='pending' AND hidden_locally=0 ORDER BY created_at,rowid;") { stmt in
            try bindText(stmt, 1, chatId)
            while sqlite3_step(stmt) == SQLITE_ROW { result.append(try decodeRow(stmt)) }
        }
        return result
    }

    // MARK: - Pending decrypt retention (P1-4)

    /// P1-4: write the visible decrypt-failed placeholder AND the encrypted
    /// retry record in ONE transaction, so the durable sync cursor can only
    /// advance past a message that is EITHER decrypted OR durably retained.
    func upsertDecryptFailed(_ message: DecryptedChatMessage, pending: PendingDecrypt) throws {
        workspaceQueue.sync {}
        try execute("BEGIN IMMEDIATE;")
        do {
            try writeMessage(message)
            try insertPendingDecrypt(pending)
            try execute("COMMIT;")
        } catch {
            try? execute("ROLLBACK;")
            throw error
        }
    }

    func insertPendingDecrypt(_ pending: PendingDecrypt) throws {
        try withStatement(
            """
            INSERT INTO pending_decrypts
            (client_message_id, server_id, chat_id, sender_id, sender_device_id, sequence, created_at, message_type, ciphertext, kind, target_message_id, retry_count, last_error_class, notification_batch_alert)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(client_message_id) DO UPDATE SET
                ciphertext = excluded.ciphertext,
                retry_count = MAX(pending_decrypts.retry_count, excluded.retry_count),
                last_error_class = excluded.last_error_class,
                notification_batch_alert = COALESCE(pending_decrypts.notification_batch_alert, excluded.notification_batch_alert);
            """
        ) { stmt in
            try bindText(stmt, 1, pending.clientMessageId)
            try bindText(stmt, 2, pending.serverId)
            try bindText(stmt, 3, pending.chatId)
            try bindText(stmt, 4, pending.senderId)
            try bindText(stmt, 5, pending.senderDeviceId)
            sqlite3_bind_int64(stmt, 6, pending.sequence)
            sqlite3_bind_double(stmt, 7, pending.createdAt.timeIntervalSince1970)
            sqlite3_bind_int64(stmt, 8, Int64(pending.messageType))
            try bindText(stmt, 9, pending.ciphertext)
            try bindText(stmt, 10, pending.kind)
            try bindOptionalText(stmt, 11, pending.targetMessageId)
            sqlite3_bind_int64(stmt, 12, Int64(pending.retryCount))
            try bindOptionalText(stmt, 13, pending.lastErrorClass)
            if let alert = pending.notificationBatchAlert { sqlite3_bind_int(stmt, 14, alert ? 1 : 0) }
            else { sqlite3_bind_null(stmt, 14) }
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    func deletePendingDecrypt(clientMessageId: String) throws {
        try withStatement("DELETE FROM pending_decrypts WHERE client_message_id = ?;") { stmt in
            try bindText(stmt, 1, clientMessageId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    func bumpPendingDecrypt(clientMessageId: String, retryCount: Int, lastErrorClass: String?) throws {
        try withStatement("UPDATE pending_decrypts SET retry_count = ?, last_error_class = COALESCE(?, last_error_class) WHERE client_message_id = ?;") { stmt in
            sqlite3_bind_int64(stmt, 1, Int64(retryCount))
            try bindOptionalText(stmt, 2, lastErrorClass)
            try bindText(stmt, 3, clientMessageId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    func pendingDecrypts() throws -> [PendingDecrypt] {
        try pendingDecrypts(senderId: nil)
    }

    func pendingDecrypts(senderId: String?) throws -> [PendingDecrypt] {
        let columns = "client_message_id, server_id, chat_id, sender_id, sender_device_id, sequence, created_at, message_type, ciphertext, kind, target_message_id, retry_count, last_error_class, notification_batch_alert"
        let sql = senderId == nil
            ? "SELECT \(columns) FROM pending_decrypts ORDER BY sequence ASC;"
            : "SELECT \(columns) FROM pending_decrypts WHERE sender_id = ? ORDER BY sequence ASC;"
        var result: [PendingDecrypt] = []
        try withStatement(sql) { stmt in
            if let senderId { try bindText(stmt, 1, senderId) }
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW {
                result.append(try decodePendingDecrypt(stmt))
                step = sqlite3_step(stmt)
            }
            guard step == SQLITE_DONE else { throw StoreError.stepFailed }
        }
        return result
    }

    private struct PendingDecryptRetryCursor: Codable {
        let sequence: Int64
        let clientMessageId: String
    }

    /// A durable round-robin cursor, independent of diagnostic retry counts.
    /// Two indexed, limited ranges wrap once without materializing the queue.
    func pendingDecryptRetryBatch(senderId: String?, limit: Int = 128) throws -> [PendingDecrypt] {
        guard (1...128).contains(limit) else { throw StoreError.stepFailed }
        let name = senderId.map { "sender:" + $0 } ?? "all"
        let saved = try chatValue(chatId: "__decrypt_retry__", name: name)
        // An unreadable cursor restarts the rotation instead of stalling automatic retries.
        let cursor = saved.flatMap { try? JSONDecoder().decode(PendingDecryptRetryCursor.self, from: Data($0.utf8)) }
        let columns = "client_message_id, server_id, chat_id, sender_id, sender_device_id, sequence, created_at, message_type, ciphertext, kind, target_message_id, retry_count, last_error_class, notification_batch_alert"
        let eligible = "COALESCE(last_error_class,'') NOT IN ('permanent_duplicate','permanent_malformed_ciphertext','permanent_unsupported_type')"
        var result: [PendingDecrypt] = []
        for wrapping in [false, true] {
            if wrapping && (cursor == nil || result.count == limit) { break }
            var sql = "SELECT \(columns) FROM pending_decrypts WHERE \(eligible)"
            if senderId != nil { sql += " AND sender_id = ?" }
            if cursor != nil { sql += wrapping ? " AND (sequence,client_message_id) <= (?,?)" : " AND (sequence,client_message_id) > (?,?)" }
            sql += " ORDER BY sequence ASC, client_message_id ASC LIMIT ?;"
            try withStatement(sql) { stmt in
                var index: Int32 = 1
                if let senderId { try bindText(stmt, index, senderId); index += 1 }
                if let cursor {
                    sqlite3_bind_int64(stmt, index, cursor.sequence); index += 1
                    try bindText(stmt, index, cursor.clientMessageId); index += 1
                }
                sqlite3_bind_int64(stmt, index, Int64(limit - result.count))
                var step = sqlite3_step(stmt)
                while step == SQLITE_ROW {
                    result.append(try decodePendingDecrypt(stmt))
                    step = sqlite3_step(stmt)
                }
                guard step == SQLITE_DONE else { throw StoreError.stepFailed }
            }
        }
        return result
    }

    /// Save before the attempt: cancellation, a storage error or a process exit
    /// must not repeatedly pin the head of the next automatic batch.
    func advancePendingDecryptRetryCursor(_ entry: PendingDecrypt, senderId: String?) throws {
        let cursor = PendingDecryptRetryCursor(sequence: entry.sequence, clientMessageId: entry.clientMessageId)
        let value = String(decoding: try JSONEncoder().encode(cursor), as: UTF8.self)
        try saveChatValue(chatId: "__decrypt_retry__", name: senderId.map { "sender:" + $0 } ?? "all", value: value)
    }

    private func decodePendingDecrypt(_ stmt: OpaquePointer) throws -> PendingDecrypt {
        var pending = PendingDecrypt(
            clientMessageId: String(cString: sqlite3_column_text(stmt, 0)),
            serverId: String(cString: sqlite3_column_text(stmt, 1)),
            chatId: String(cString: sqlite3_column_text(stmt, 2)),
            senderId: String(cString: sqlite3_column_text(stmt, 3)),
            senderDeviceId: String(cString: sqlite3_column_text(stmt, 4)),
            sequence: sqlite3_column_int64(stmt, 5),
            createdAt: Date(timeIntervalSince1970: sqlite3_column_double(stmt, 6)),
            messageType: Int(sqlite3_column_int64(stmt, 7)),
            ciphertext: String(cString: sqlite3_column_text(stmt, 8)),
            kind: String(cString: sqlite3_column_text(stmt, 9)),
            targetMessageId: columnOptionalText(stmt, 10)
        )
        pending.retryCount = Int(sqlite3_column_int64(stmt, 11))
        pending.lastErrorClass = columnOptionalText(stmt, 12)
        pending.notificationBatchAlert = sqlite3_column_type(stmt, 13) == SQLITE_NULL ? nil : sqlite3_column_int(stmt, 13) != 0
        return pending
    }

    // MARK: - Helpers

    // Capture before finalize/rollback replaces the connection's diagnostic.
    // SQLite reports the engine error, never our SQL, bindings or message bodies.
    private func sqliteFailure(_ operation: String, handle: OpaquePointer? = nil) -> StoreError {
        let connection = handle ?? db
        return .sqliteFailed(operation: operation, code: sqlite3_errcode(connection),
            extendedCode: sqlite3_extended_errcode(connection), message: String(cString: sqlite3_errmsg(connection)))
    }

    private func execute(_ sql: String) throws {
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else {
            throw sqliteFailure("execute")
        }
        if sql.contains("DELETE FROM") { erasureCheckpoint.request() }
    }

    private func withStatement(
        _ sql: String,
        _ body: (OpaquePointer) throws -> Void
    ) throws {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            let error = sqliteFailure("prepare")
            if let stmt { sqlite3_finalize(stmt) }
            throw error
        }
        defer { sqlite3_finalize(stmt) }
        do {
            try body(stmt)
        } catch StoreError.stepFailed {
            let code = sqlite3_errcode(db)
            if code != SQLITE_OK && code != SQLITE_ROW && code != SQLITE_DONE {
                throw sqliteFailure("step")
            }
            throw StoreError.stepFailed
        }
        if sql.contains("DELETE FROM") || (sql.contains("UPDATE messages") &&
            (sql.contains("delete_sequence") || sql.contains("hidden_locally"))) {
            erasureCheckpoint.request()
        }
    }

    private func decodeRow(_ stmt: OpaquePointer) throws -> DecryptedChatMessage {
        let clientMessageId = String(cString: sqlite3_column_text(stmt, 0))
        let serverId = columnOptionalText(stmt, 1)
        let chatId = String(cString: sqlite3_column_text(stmt, 2))
        let senderId = String(cString: sqlite3_column_text(stmt, 3))
        let sequence: Int64?
        if sqlite3_column_type(stmt, 4) == SQLITE_NULL {
            sequence = nil
        } else {
            sequence = sqlite3_column_int64(stmt, 4)
        }
        let createdAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 5))
        let kind = String(cString: sqlite3_column_text(stmt, 6))
        let targetMessageId = columnOptionalText(stmt, 7)
        let status = MessageStatus(rawValue: String(cString: sqlite3_column_text(stmt, 8))) ?? .decryptFailed
        let contentJSON = String(cString: sqlite3_column_text(stmt, 9))
        let content = (try? decoder.decode(MessageContent.self, from: Data(contentJSON.utf8)))
            ?? MessageContent(kind: MessageContent.kindText, text: contentJSON)
        let lastEditSequence = sqlite3_column_int64(stmt, 10)
        let deleteSequence = sqlite3_column_int64(stmt, 11)
        let hiddenLocally = sqlite3_column_int64(stmt, 12) != 0
        let payloadText = columnOptionalText(stmt, 13)
        let pendingEditText = columnOptionalText(stmt, 14)
        let pendingEditRevision = sqlite3_column_int64(stmt, 15)
        let sendAttempted = sqlite3_column_int64(stmt, 16) != 0

        var message = DecryptedChatMessage(
            clientMessageId: clientMessageId,
            serverId: serverId,
            chatId: chatId,
            senderId: senderId,
            sequence: sequence,
            createdAt: createdAt,
            kind: kind,
            targetMessageId: targetMessageId,
            status: status,
            content: content
        )
        message.lastEditSequence = lastEditSequence
        message.deleteSequence = deleteSequence
        message.hiddenLocally = hiddenLocally
        message.payloadText = payloadText
        message.pendingEditText = pendingEditText
        message.pendingEditRevision = pendingEditRevision
        message.sendAttempted = sendAttempted
        return message
    }

    private func decodeReactionRow(_ stmt: OpaquePointer) -> ReactionRow {
        let targetMessageId = String(cString: sqlite3_column_text(stmt, 0))
        let userId = String(cString: sqlite3_column_text(stmt, 1))
        let emoji = columnOptionalText(stmt, 2)
        let lastReactionSequence = sqlite3_column_int64(stmt, 3)
        return ReactionRow(
            targetMessageId: targetMessageId,
            userId: userId,
            emoji: emoji,
            lastReactionSequence: lastReactionSequence
        )
    }

    /// Returns the number of rows modified by the most recent statement.
    private func lastChanges() -> Int {
        Int(sqlite3_changes(db))
    }

    /// Adds a column to an existing table if it isn't already present (safe
    /// re-run; used for forward migration of pre-lifecycle databases).
    private func ensureColumn(table: String, column: String, definition: String) throws {
        var exists = false
        try withStatement("PRAGMA table_info(\(table));") { stmt in
            while sqlite3_step(stmt) == SQLITE_ROW {
                let name = String(cString: sqlite3_column_text(stmt, 1))
                if name == column { exists = true }
            }
        }
        if !exists {
            try execute("ALTER TABLE \(table) ADD COLUMN \(column) \(definition);")
        }
    }

    private func columnOptionalText(_ stmt: OpaquePointer, _ index: Int32) -> String? {
        guard sqlite3_column_type(stmt, index) != SQLITE_NULL else { return nil }
        return String(cString: sqlite3_column_text(stmt, index))
    }

    private func bindText(_ stmt: OpaquePointer, _ index: Int32, _ value: String) throws {
        guard sqlite3_bind_text(stmt, index, (value as NSString).utf8String, -1, sqliteTransient) == SQLITE_OK else {
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

    private func bindOptionalInt64(_ stmt: OpaquePointer, _ index: Int32, _ value: Int64?) throws {
        if let value {
            sqlite3_bind_int64(stmt, index, value)
        } else {
            sqlite3_bind_null(stmt, index)
        }
    }
}

/// Debounced WAL truncation on a separate connection; no recurring timer or UI work.
private final class MessageErasureCheckpoint: @unchecked Sendable {
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
