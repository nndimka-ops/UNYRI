import Foundation
import SQLite3

private let attachmentSqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// A container UUID may change; device prefix, server, account and cache suffix
/// must not. This never searches other accounts or adopts arbitrary basenames.
private struct AttachmentCacheRelocation {
    let root: URL
    private let components: [String]
    private let containerEnd: Int
    private let tail: [String]

    init?(databaseURL: URL) {
        let raw = databaseURL.deletingLastPathComponent().appendingPathComponent("attachments", isDirectory: true).standardizedFileURL
        let canonical = raw.resolvingSymlinksInPath()
        let parts = canonical.pathComponents
        guard parts.count >= 10 else { return nil }
        let suffix = Array(parts.suffix(6)), end = parts.count - 6
        guard suffix[0] == "Library", suffix[1] == "Application Support", suffix[2] == "COCO",
              !suffix[3].isEmpty, UUID(uuidString: suffix[4]) != nil, suffix[5] == "attachments",
              end >= 4, Array(parts[(end - 4)..<(end - 1)]) == ["Containers", "Data", "Application"],
              UUID(uuidString: parts[end - 1]) != nil else { return nil }
        // System /var canonicalization is allowed; links inside the account
        // hierarchy are not. Validate these directories once, not per row.
        var directory = raw
        for _ in suffix {
            guard let values = try? directory.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]),
                  values.isDirectory == true, values.isSymbolicLink != true else { return nil }
            directory = directory.deletingLastPathComponent()
        }
        root = canonical; components = parts; containerEnd = end; tail = suffix
    }

    func existingFile(for stored: String) -> String? {
        guard stored.hasPrefix("/"), !stored.contains("\0"), !stored.contains("\\"), !stored.contains("//"),
              !stored.split(separator: "/").contains(where: { $0 == "." || $0 == ".." }),
              !FileManager.default.fileExists(atPath: stored) else { return nil }
        let old = URL(fileURLWithPath: stored).standardizedFileURL.resolvingSymlinksInPath().pathComponents
        guard old.count > containerEnd + tail.count,
              Array(old.prefix(containerEnd - 1)) == Array(components.prefix(containerEnd - 1)),
              UUID(uuidString: old[containerEnd - 1]) != nil,
              old[containerEnd - 1] != components[containerEnd - 1],
              Array(old[containerEnd..<(containerEnd + tail.count)]) == tail else { return nil }
        let relative = old.dropFirst(containerEnd + tail.count)
        var candidate = root
        for component in relative {
            candidate.appendPathComponent(component)
            guard let values = try? candidate.resourceValues(forKeys: [.isSymbolicLinkKey]),
                  values.isSymbolicLink != true else { return nil }
        }
        let resolved = candidate.resolvingSymlinksInPath()
        guard resolved.path == candidate.path, resolved.path.hasPrefix(root.path + "/"),
              let values = try? resolved.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey]),
              values.isRegularFile == true, values.isSymbolicLink != true else { return nil }
        return resolved.path
    }
}

/// Account-scoped SQLite store for attachment rows (mirrors Android's
/// `AttachmentEntity`/`AttachmentDao`). The AES key/nonce/keyset/sha256 live
/// here in plain columns exactly as Android does — consistent with the
/// accepted threat model where the same database already holds decrypted
/// message text (see AttachmentEntity.kt's threat-model note).
final class AttachmentStore {
    enum StoreError: Error, LocalizedError {
        case openFailed
        case stepFailed
        case bindingFailed

        var errorDescription: String? {
            switch self {
            case .openFailed: return "Could not open the local attachment store."
            case .stepFailed: return "Local attachment store query failed."
            case .bindingFailed: return "Local attachment store write failed."
            }
        }
    }

    private var db: OpaquePointer?
    private let databaseURL: URL
    private let pathQueue = DispatchQueue(label: "unyri.attachment-paths", qos: .utility)
    // Accessed only on pathQueue. One account-scoped snapshot, never an
    // unbounded cache of every conversation visited.
    private var pathReader: AttachmentStore?
    private var pathSnapshot: (chat: String, version: Int64, ids: Set<String>?, paths: [String: String])?
    private final class PathCancellation {
        private let lock = NSLock()
        private var cancelled = false
        func cancel() { lock.lock(); cancelled = true; lock.unlock() }
        func check() throws {
            lock.lock(); let value = cancelled; lock.unlock()
            if value { throw CancellationError() }
        }
    }

    init(databaseURL: URL, readOnly: Bool = false) throws {
        self.databaseURL = databaseURL
        var handle: OpaquePointer?
        let flags = (readOnly ? SQLITE_OPEN_READONLY : SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE) | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(databaseURL.path, &handle, flags, nil) == SQLITE_OK else {
            throw StoreError.openFailed
        }
        db = handle
        sqlite3_busy_timeout(handle, 1000)
        if readOnly { return }
        try execute(
            """
            CREATE TABLE IF NOT EXISTS deleted_chats (chat_id TEXT PRIMARY KEY NOT NULL);
            CREATE TABLE IF NOT EXISTS attachments (
                attachment_id TEXT PRIMARY KEY NOT NULL,
                client_message_id TEXT NOT NULL,
                chat_id TEXT NOT NULL,
                media_type TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                filename TEXT,
                size INTEGER NOT NULL,
                width INTEGER,
                height INTEGER,
                duration_ms INTEGER,
                thumbnail_base64 TEXT,
                local_path TEXT,
                download_state TEXT NOT NULL,
                upload_state TEXT NOT NULL,
                progress INTEGER NOT NULL,
                object_key TEXT NOT NULL,
                encrypted_size INTEGER NOT NULL,
                encryption_version INTEGER NOT NULL,
                key_base64 TEXT,
                nonce_base64 TEXT,
                keyset_base64 TEXT,
                sha256_base64 TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_attachments_message ON attachments(client_message_id);
            CREATE INDEX IF NOT EXISTS idx_attachments_chat ON attachments(chat_id);
            CREATE INDEX IF NOT EXISTS idx_attachments_local_path ON attachments(local_path);
            CREATE TABLE IF NOT EXISTS attachment_store_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
            """
        )
        do { try relocateCachePathsAfterContainerMove() }
        catch { sqlite3_close(handle); db = nil; throw error }
    }

    deinit {
        if let db { sqlite3_close(db) }
    }

    // MARK: - Writes

    /// iOS may move an app's data container during an update. Only repair
    /// existing files inside this exact server/account cache, before readers
    /// start. The marker makes ordinary launches a single metadata query.
    private func relocateCachePathsAfterContainerMove() throws {
        guard let relocation = AttachmentCacheRelocation(databaseURL: databaseURL) else { return }
        var previous: String?
        try withStatement("SELECT value FROM attachment_store_metadata WHERE key='cache_root_v1';") { statement in
            if sqlite3_step(statement) == SQLITE_ROW { previous = columnOptionalText(statement, 0) }
        }
        guard previous != relocation.root.path else { return }
        try execute("BEGIN IMMEDIATE;")
        do {
            var cursor = Int64.min
            while true {
                var batch: [(row: Int64, path: String)] = []
                try withStatement("SELECT rowid,local_path FROM attachments WHERE rowid>? AND local_path IS NOT NULL ORDER BY rowid LIMIT 256;") { statement in
                    guard sqlite3_bind_int64(statement, 1, cursor) == SQLITE_OK else { throw StoreError.bindingFailed }
                    var step = sqlite3_step(statement)
                    while step == SQLITE_ROW {
                        batch.append((sqlite3_column_int64(statement, 0), String(cString: sqlite3_column_text(statement, 1))))
                        step = sqlite3_step(statement)
                    }
                    guard step == SQLITE_DONE else { throw StoreError.stepFailed }
                }
                guard let last = batch.last else { break }
                for entry in batch {
                    guard let repaired = relocation.existingFile(for: entry.path) else { continue }
                    try withStatement("UPDATE attachments SET local_path=? WHERE rowid=? AND local_path=?;") { statement in
                        try bindText(statement, 1, repaired)
                        guard sqlite3_bind_int64(statement, 2, entry.row) == SQLITE_OK else { throw StoreError.bindingFailed }
                        try bindText(statement, 3, entry.path)
                        guard sqlite3_step(statement) == SQLITE_DONE else { throw StoreError.stepFailed }
                    }
                }
                cursor = last.row
            }
            try withStatement("INSERT OR REPLACE INTO attachment_store_metadata(key,value) VALUES ('cache_root_v1',?);") { statement in
                try bindText(statement, 1, relocation.root.path)
                guard sqlite3_step(statement) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
            try execute("COMMIT;")
        } catch { try? execute("ROLLBACK;"); throw error }
    }

    func upsert(_ attachment: AttachmentRecord) throws {
        var deleted = false
        try withStatement("SELECT 1 FROM deleted_chats WHERE chat_id=?;") { stmt in
            try bindText(stmt, 1, attachment.chatId)
            let step = sqlite3_step(stmt)
            guard step == SQLITE_ROW || step == SQLITE_DONE else { throw StoreError.stepFailed }
            deleted = step == SQLITE_ROW
        }
        guard !deleted else { return }
        try withStatement(
            """
            INSERT OR REPLACE INTO attachments
            (attachment_id, client_message_id, chat_id, media_type, mime_type, filename, size, width, height, duration_ms, thumbnail_base64, local_path, download_state, upload_state, progress, object_key, encrypted_size, encryption_version, key_base64, nonce_base64, keyset_base64, sha256_base64)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
            """
        ) { stmt in
            try bindText(stmt, 1, attachment.attachmentId)
            try bindText(stmt, 2, attachment.clientMessageId)
            try bindText(stmt, 3, attachment.chatId)
            try bindText(stmt, 4, attachment.mediaType)
            try bindText(stmt, 5, attachment.mimeType)
            try bindOptionalText(stmt, 6, attachment.filename)
            sqlite3_bind_int64(stmt, 7, attachment.size)
            try bindOptionalInt64(stmt, 8, attachment.width.map(Int64.init))
            try bindOptionalInt64(stmt, 9, attachment.height.map(Int64.init))
            try bindOptionalInt64(stmt, 10, attachment.durationMs)
            try bindOptionalText(stmt, 11, attachment.thumbnailBase64)
            try bindOptionalText(stmt, 12, attachment.localPath)
            try bindText(stmt, 13, attachment.downloadState)
            try bindText(stmt, 14, attachment.uploadState)
            sqlite3_bind_int64(stmt, 15, Int64(attachment.progress))
            try bindText(stmt, 16, attachment.objectKey)
            sqlite3_bind_int64(stmt, 17, attachment.encryptedSize)
            sqlite3_bind_int64(stmt, 18, Int64(attachment.encryptionVersion))
            try bindOptionalText(stmt, 19, attachment.keyBase64)
            try bindOptionalText(stmt, 20, attachment.nonceBase64)
            try bindOptionalText(stmt, 21, attachment.keysetBase64)
            try bindText(stmt, 22, attachment.sha256Base64)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    func updateUploadState(attachmentId: String, state: String, progress: Int) throws {
        try withStatement(
            "UPDATE attachments SET upload_state = ?, progress = ? WHERE attachment_id = ?;"
        ) { stmt in
            try bindText(stmt, 1, state)
            sqlite3_bind_int64(stmt, 2, Int64(progress))
            try bindText(stmt, 3, attachmentId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    func updateDownloadState(attachmentId: String, state: String, progress: Int) throws {
        try withStatement(
            "UPDATE attachments SET download_state = ?, progress = ? WHERE attachment_id = ?;"
        ) { stmt in
            try bindText(stmt, 1, state)
            sqlite3_bind_int64(stmt, 2, Int64(progress))
            try bindText(stmt, 3, attachmentId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    func markDownloaded(attachmentId: String, localPath: String) throws {
        try withStatement(
            "UPDATE attachments SET download_state = 'downloaded', local_path = ?, progress = 100 WHERE attachment_id = ?;"
        ) { stmt in
            try bindText(stmt, 1, localPath)
            try bindText(stmt, 2, attachmentId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    /// Clears the local decrypted cache reference (the caller deletes the
    /// actual file first) — mirrors Android's `clearLocalCache`.
    func clearLocalCache(attachmentId: String) throws {
        try withStatement(
            "UPDATE attachments SET download_state = 'not_downloaded', local_path = NULL, progress = 0 WHERE attachment_id = ?;"
        ) { stmt in
            try bindText(stmt, 1, attachmentId)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
        }
    }

    // MARK: - Reads

    func attachment(byId attachmentId: String) throws -> AttachmentRecord? {
        try query("SELECT * FROM attachments WHERE attachment_id = ?;", binding: { try bindText($0, 1, attachmentId) }).first
    }

    func referencesLocalPath(_ path: String) throws -> Bool {
        var found = false
        try withStatement("SELECT 1 FROM attachments WHERE local_path = ? LIMIT 1;") { statement in
            try bindText(statement, 1, path)
            let step = sqlite3_step(statement)
            guard step == SQLITE_ROW || step == SQLITE_DONE else { throw StoreError.stepFailed }
            found = step == SQLITE_ROW
        }
        return found
    }

    func deleteRecords(_ ids: Set<String>) throws {
        for id in ids {
            try withStatement("DELETE FROM attachments WHERE client_message_id=?;") { stmt in
                try bindText(stmt, 1, id)
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
            }
        }
    }
    func deleteChat(_ chatId: String) throws {
        try execute("BEGIN IMMEDIATE;")
        do {
            for sql in ["INSERT OR IGNORE INTO deleted_chats(chat_id) VALUES (?);", "DELETE FROM attachments WHERE chat_id=?;"] {
                try withStatement(sql) { stmt in
                    try bindText(stmt, 1, chatId)
                    guard sqlite3_step(stmt) == SQLITE_DONE else { throw StoreError.stepFailed }
                }
            }
            try execute("COMMIT;")
        } catch { try? execute("ROLLBACK;"); throw error }
    }

    func cachedPathsAsync(chatId: String, attachmentIDs: Set<String>? = nil) async throws -> [String: String] {
        try Task.checkCancellation()
        if attachmentIDs?.isEmpty == true { return [:] }
        let cancellation = PathCancellation()
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                pathQueue.async {
                    do {
                        try cancellation.check()
                        if self.pathReader == nil {
                            self.pathReader = try AttachmentStore(databaseURL: self.databaseURL, readOnly: true)
                        }
                        guard let reader = self.pathReader else { throw StoreError.openFailed }
                        // data_version belongs to this persistent reader connection;
                        // it changes when another connection commits, including
                        // downloads, deletion, cache cleanup and history import.
                        let version = try reader.pathDataVersion()
                        if let snapshot = self.pathSnapshot, snapshot.chat == chatId,
                           snapshot.version == version, snapshot.ids == attachmentIDs {
                            try cancellation.check()
                            continuation.resume(returning: snapshot.paths)
                            return
                        }
                        var paths: [String: String] = [:]
                        for row in try reader.cachedPathCandidates(chatId: chatId, attachmentIDs: attachmentIDs) {
                            try cancellation.check()
                            if FileManager.default.fileExists(atPath: row.path) {
                                paths[row.id] = row.path
                            }
                        }
                        try cancellation.check()
                        if version == (try reader.pathDataVersion()) {
                            self.pathSnapshot = (chatId, version, attachmentIDs, paths)
                        }
                        continuation.resume(returning: paths)
                    } catch { continuation.resume(throwing: error) }
                }
            }
        }, onCancel: { cancellation.cancel() })
    }

    func invalidatePathSnapshot() {
        // Revalidate actual files when a conversation becomes visible again.
        // App-managed file removal also updates SQLite and data_version.
        pathQueue.async { self.pathSnapshot = nil }
    }

    private func pathDataVersion() throws -> Int64 {
        var value: Int64 = 0
        try withStatement("PRAGMA data_version;") { statement in
            guard sqlite3_step(statement) == SQLITE_ROW else { throw StoreError.stepFailed }
            value = sqlite3_column_int64(statement, 0)
        }
        return value
    }

    /// Read only IDs/paths needed by the loaded UI portion, never thumbnail,
    /// encryption metadata or the complete attachment records of a long chat.
    private func cachedPathCandidates(chatId: String, attachmentIDs: Set<String>?) throws -> [(id: String, path: String)] {
        var result: [(id: String, path: String)] = []
        func read(_ ids: [String]?) throws {
            let filter = ids.map { " AND attachment_id IN (" + Array(repeating: "?", count: $0.count).joined(separator: ",") + ")" } ?? ""
            try withStatement("SELECT attachment_id,local_path FROM attachments WHERE chat_id=? AND local_path IS NOT NULL" + filter + ";") { statement in
                try bindText(statement, 1, chatId)
                for (index, id) in (ids ?? []).enumerated() { try bindText(statement, Int32(index + 2), id) }
                var step = sqlite3_step(statement)
                while step == SQLITE_ROW {
                    result.append((String(cString: sqlite3_column_text(statement, 0)), String(cString: sqlite3_column_text(statement, 1))))
                    step = sqlite3_step(statement)
                }
                guard step == SQLITE_DONE else { throw StoreError.stepFailed }
            }
        }
        if let attachmentIDs {
            let ids = attachmentIDs.sorted()
            // Stay below SQLite's conservative bind-variable limit for an
            // explicitly expanded history or a restored old bookmark.
            for start in stride(from: 0, to: ids.count, by: 400) {
                try read(Array(ids[start..<min(start + 400, ids.count)]))
            }
        } else { try read(nil) }
        return result
    }

    func attachment(byClientMessageId clientMessageId: String) throws -> AttachmentRecord? {
        try query("SELECT * FROM attachments WHERE client_message_id = ? ORDER BY rowid DESC LIMIT 1;", binding: { try bindText($0, 1, clientMessageId) }).first
    }

    /// Local media index for a chat. Attachments are inserted by the single
    /// message-ingestion pipeline, so this never needs a second server query
    /// or a parallel metadata cache.
    func attachments(chatId: String) throws -> [AttachmentRecord] {
        try query(
            "SELECT * FROM attachments WHERE chat_id = ? ORDER BY rowid DESC;",
            binding: { try bindText($0, 1, chatId) }
        )
    }

    /// Every cached attachment row (used by the Data & Storage screen for
    /// local media accounting + cache cleanup).
    func allAttachmentsAsync() async throws -> [AttachmentRecord] {
        let file = databaseURL
        return try await Task.detached(priority: .utility) {
            let reader = try AttachmentStore(databaseURL: file, readOnly: true)
            return try reader.allAttachments()
        }.value
    }
    func allAttachments() throws -> [AttachmentRecord] {
        try query("SELECT * FROM attachments ORDER BY rowid;", binding: { _ in })
    }

    // MARK: - Helpers

    private func query(
        _ sql: String,
        binding: (OpaquePointer) throws -> Void
    ) throws -> [AttachmentRecord] {
        var result: [AttachmentRecord] = []
        try withStatement(sql) { stmt in
            try binding(stmt)
            while sqlite3_step(stmt) == SQLITE_ROW {
                result.append(decodeRow(stmt))
            }
        }
        return result
    }

    private func decodeRow(_ stmt: OpaquePointer) -> AttachmentRecord {
        let attachmentId = String(cString: sqlite3_column_text(stmt, 0))
        let clientMessageId = String(cString: sqlite3_column_text(stmt, 1))
        let chatId = String(cString: sqlite3_column_text(stmt, 2))
        let mediaType = String(cString: sqlite3_column_text(stmt, 3))
        let mimeType = String(cString: sqlite3_column_text(stmt, 4))
        let filename = columnOptionalText(stmt, 5)
        let size = sqlite3_column_int64(stmt, 6)
        let width = sqlite3_column_type(stmt, 7) == SQLITE_NULL ? nil : Int(sqlite3_column_int64(stmt, 7))
        let height = sqlite3_column_type(stmt, 8) == SQLITE_NULL ? nil : Int(sqlite3_column_int64(stmt, 8))
        let durationMs = sqlite3_column_type(stmt, 9) == SQLITE_NULL ? nil : sqlite3_column_int64(stmt, 9)
        let thumbnailBase64 = columnOptionalText(stmt, 10)
        let localPath = columnOptionalText(stmt, 11)
        let downloadState = String(cString: sqlite3_column_text(stmt, 12))
        let uploadState = String(cString: sqlite3_column_text(stmt, 13))
        let progress = Int(sqlite3_column_int64(stmt, 14))
        let objectKey = String(cString: sqlite3_column_text(stmt, 15))
        let encryptedSize = sqlite3_column_int64(stmt, 16)
        let encryptionVersion = Int(sqlite3_column_int64(stmt, 17))
        let keyBase64 = columnOptionalText(stmt, 18)
        let nonceBase64 = columnOptionalText(stmt, 19)
        let keysetBase64 = columnOptionalText(stmt, 20)
        let sha256Base64 = String(cString: sqlite3_column_text(stmt, 21))
        return AttachmentRecord(
            attachmentId: attachmentId,
            clientMessageId: clientMessageId,
            chatId: chatId,
            mediaType: mediaType,
            mimeType: mimeType,
            filename: filename,
            size: size,
            width: width,
            height: height,
            durationMs: durationMs,
            thumbnailBase64: thumbnailBase64,
            localPath: localPath,
            downloadState: downloadState,
            uploadState: uploadState,
            progress: progress,
            objectKey: objectKey,
            encryptedSize: encryptedSize,
            encryptionVersion: encryptionVersion,
            keyBase64: keyBase64,
            nonceBase64: nonceBase64,
            keysetBase64: keysetBase64,
            sha256Base64: sha256Base64
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
        guard sqlite3_bind_text(stmt, index, (value as NSString).utf8String, -1, attachmentSqliteTransient) == SQLITE_OK else {
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
