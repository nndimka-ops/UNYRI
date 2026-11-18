import Foundation
import SQLite3
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

private let attachmentSqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// The migration's SQL publication and incoming metadata share one background
/// lane. Filesystem inspection never occupies it. Other DB owners may contend;
/// incoming writes retry asynchronously with their plaintext already journaled.
private final class AttachmentMetadataWriter: @unchecked Sendable {
    final class Pending: @unchecked Sendable {
        let id = UUID()
        let message: String
        let chat: String
        let sequence: Int64?
        private let lock = NSLock()
        private var purged = false
        init(message: String, chat: String, sequence: Int64?) { self.message = message; self.chat = chat; self.sequence = sequence }
        func invalidate() { lock.lock(); purged = true; lock.unlock() }
        func check() throws {
            lock.lock(); let value = purged; lock.unlock()
            if value { throw AttachmentStore.StoreError.deleted }
        }
    }
    let queue = DispatchQueue(label: "unyri.attachment-metadata", qos: .userInitiated)
    private let lock = NSLock()
    private var retired = false
    private var pending: [UUID: Pending] = [:]
    func register(message: String, chat: String, sequence: Int64?) throws -> Pending {
        lock.lock(); defer { lock.unlock() }
        guard !retired else { throw CancellationError() }
        let write = Pending(message: message, chat: chat, sequence: sequence)
        pending[write.id] = write
        return write
    }
    func finish(_ write: Pending) { lock.lock(); pending[write.id] = nil; lock.unlock() }
    func source(_ message: String) -> (chat: String, sequence: Int64?)? {
        lock.lock(); defer { lock.unlock() }
        return pending.values.first { $0.message == message }.map { ($0.chat, $0.sequence) }
    }
    func activeMessages() -> Set<String> {
        lock.lock(); defer { lock.unlock() }; return Set(pending.values.map(\.message))
    }
    func invalidate(messages: Set<String>? = nil, chat: String? = nil) {
        lock.lock()
        let writes = pending.values.filter { messages?.contains($0.message) == true || chat == $0.chat }
        lock.unlock()
        for write in writes { write.invalidate() }
    }
    func check() throws {
        lock.lock(); let value = retired; lock.unlock()
        if value { throw CancellationError() }
    }
    func retire() { lock.lock(); retired = true; lock.unlock() }
    func drain(_ close: @escaping @Sendable () -> Void) async {
        await withCheckedContinuation { continuation in queue.async { close(); continuation.resume() } }
    }
    func publish<T>(_ operation: () throws -> T) throws -> T {
        try queue.sync { try check(); return try operation() }
    }
}

/// A container UUID may change; device prefix, server, account and local suffix
/// must not. This never searches other accounts or adopts arbitrary basenames.
private struct AttachmentCacheRelocation {
    let root: URL
    private let physicalRootPath: String
    private let draft: Bool
    private let components: [String]
    private let containerEnd: Int
    private let tail: [String]

    init?(databaseURL: URL, directory: String = "attachments") {
        guard ["attachments", "draft-media"].contains(directory) else { return nil }
        let raw = databaseURL.deletingLastPathComponent().appendingPathComponent(directory, isDirectory: true)
        guard let physicalRoot = Self.physicalPath(raw.path) else { return nil }
        // Keep integ's persisted-path/marker spelling. Physical paths are for
        // ownership comparisons, not a replacement representation in SQLite.
        let canonical = raw.standardizedFileURL.resolvingSymlinksInPath()
        let parts = (physicalRoot as NSString).pathComponents
        guard parts.count >= 10 else { return nil }
        let suffix = Array(parts.suffix(6)), end = parts.count - 6
        guard suffix[0] == "Library", suffix[1] == "Application Support", suffix[2] == "COCO",
              !suffix[3].isEmpty, UUID(uuidString: suffix[4]) != nil, suffix[5] == directory,
              end >= 4, Array(parts[(end - 4)..<(end - 1)]) == ["Containers", "Data", "Application"],
              UUID(uuidString: parts[end - 1]) != nil else { return nil }
        // System /var canonicalization is allowed; links inside the account
        // hierarchy are not. Validate these directories once, not per row.
        var ancestor = raw
        for index in suffix.indices {
            do {
                let values = try ancestor.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
                guard values.isDirectory == true, values.isSymbolicLink != true else { return nil }
            } catch let error as CocoaError where ancestor == raw && directory == "draft-media" &&
                (error.code == .fileNoSuchFile || error.code == .fileReadNoSuchFile) {
                // A draft identity remains owned even while its file/root is
                // absent. Still validate every existing account parent below.
                guard index == 0 else { return nil }
            } catch { return nil }
            ancestor = ancestor.deletingLastPathComponent()
        }
        root = canonical; physicalRootPath = physicalRoot
        components = parts; containerEnd = end; tail = suffix; draft = directory == "draft-media"
    }

    func relocatedPath(for stored: String) -> String? {
        guard stored.hasPrefix("/"), !stored.contains("\0"), !stored.contains("\\"), !stored.contains("//"),
              !stored.split(separator: "/").contains(where: { $0 == "." || $0 == ".." }),
              draft || !FileManager.default.fileExists(atPath: stored) else { return nil }
        guard let physicalStored = Self.physicalPath(stored) else { return nil }
        let old = (physicalStored as NSString).pathComponents
        guard old.count > containerEnd + tail.count,
              Array(old.prefix(containerEnd - 1)) == Array(components.prefix(containerEnd - 1)),
              UUID(uuidString: old[containerEnd - 1]) != nil,
              draft || old[containerEnd - 1] != components[containerEnd - 1],
              Array(old[containerEnd..<(containerEnd + tail.count)]) == tail else { return nil }
        let relative = old.dropFirst(containerEnd + tail.count)
        if draft {
            guard relative.count == 2, let folder = relative.first,
                  UUID(uuidString: folder.hasPrefix("share-") ? String(folder.dropFirst(6)) : folder) != nil else { return nil }
        }
        var candidate = root
        for component in relative {
            candidate.appendPathComponent(component)
            do {
                let values = try candidate.resourceValues(forKeys: [.isSymbolicLinkKey])
                guard values.isSymbolicLink != true else { return nil }
            } catch let error as CocoaError where draft && (error.code == .fileNoSuchFile || error.code == .fileReadNoSuchFile) {
                continue
            } catch { return nil }
        }
        guard let resolvedPath = Self.physicalPath(candidate.path),
              resolvedPath.hasPrefix(physicalRootPath + "/") else { return nil }
        let resolved = candidate.resolvingSymlinksInPath()
        // Foundation can choose another exact OS alias. It must still denote
        // the physically inspected file before that spelling is persisted.
        guard let normalizedPhysical = Self.physicalPath(resolved.path),
              normalizedPhysical == resolvedPath else { return nil }
        // Outbox jobs rebase UUID draft identities whether or not plaintext is
        // currently present. Keep attachment ownership in exactly that namespace.
        // Use the canonical metadata root's spelling even if a leaf disappears.
        if draft { return candidate.path }
        guard let values = try? resolved.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey]),
              values.isRegularFile == true, values.isSymbolicLink != true else { return nil }
        return candidate.path
    }

    /// A moved-away container has no leaf for Foundation to resolve. Normalize
    /// its nearest existing ancestor, then append the inspected missing suffix.
    /// Match the media boundary: only exact OS aliases above the path are links.
    private static func physicalPath(_ path: String) -> String? {
        guard path.hasPrefix("/"), !path.contains("\0"), !path.contains("\\"),
              !path.contains("//"),
              !path.split(separator: "/").contains(where: { (part: Substring) -> Bool in part == "." || part == ".." }) else { return nil }
        #if canImport(Darwin) || canImport(Glibc)
        var inspected = ""
        for component in path.split(separator: "/") {
            inspected += "/" + String(component)
            var info = stat()
            guard lstat(inspected, &info) == 0 else {
                guard errno == ENOENT else { return nil }
                continue
            }
            guard info.st_mode & mode_t(S_IFMT) == mode_t(S_IFLNK) else { continue }
            guard let destination = try? FileManager.default.destinationOfSymbolicLink(atPath: inspected),
                  ["/var", "/tmp"].contains(inspected),
                  ["/private" + inspected, "private" + inspected].contains(destination),
                  path.hasPrefix(inspected + "/") else { return nil }
        }
        var ancestor = path
        var missing: [String] = []
        while true {
            if let result = realpath(ancestor, nil) {
                defer { free(result) }
                let base = String(cString: result)
                let prefix = base == "/" && !missing.isEmpty ? "" : base
                let parts = [prefix] + Array(missing.reversed())
                return parts.joined(separator: "/")
            }
            guard errno == ENOENT,
                  let slash = ancestor.lastIndex(of: "/"), ancestor != "/" else { return nil }
            missing.append(String(ancestor[ancestor.index(after: slash)...]))
            ancestor = slash == ancestor.startIndex ? "/" : String(ancestor[..<slash])
        }
        #else
        return URL(fileURLWithPath: path).standardizedFileURL.resolvingSymlinksInPath().path
        #endif
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
        case draftReferencesNotReady
        case busy
        case deleted

        var errorDescription: String? {
            switch self {
            case .openFailed: return "Could not open the local attachment store."
            case .stepFailed, .busy, .deleted: return "Local attachment store query failed."
            case .bindingFailed: return "Local attachment store write failed."
            case .draftReferencesNotReady: return "Draft attachment ownership is not ready."
            }
        }
    }

    private var db: OpaquePointer?
    private let databaseURL: URL
    private let draftMigration: DraftReferenceMigration
    private let metadataWriter: AttachmentMetadataWriter
    // Accessed only on metadataWriter.queue; never shares the UI connection.
    private var incomingWriter: AttachmentStore?
    private var messageSourceAttached = false
    private var cutoffSnapshotImported = false
    struct IncomingWriteHooks: Sendable {
        var beforeRetry: (@Sendable () async -> Void)? = nil
        var afterPersist: (@Sendable () async -> Void)? = nil
    }
    private let incomingWriteHooks: IncomingWriteHooks
    /// Creates its own read connection on the recovery task, never sharing the main
    /// connection's transaction state. Only staged paths are relevant to the sweep.
    func draftSourceReferences() -> @Sendable () async throws -> Set<URL> {
        let database = databaseURL
        let migration = draftMigration
        return {
            try await migration.wait()
            try Task.checkCancellation()
            let reader = try AttachmentStore(databaseURL: database, readOnly: true)
            var urls = Set<URL>()
            try reader.withStatement("SELECT DISTINCT local_path FROM attachments WHERE local_path LIKE '%/draft-media/%' LIMIT 100001;") { statement in
                var step = sqlite3_step(statement)
                while step == SQLITE_ROW {
                    try Task.checkCancellation()
                    urls.insert(URL(fileURLWithPath: String(cString: sqlite3_column_text(statement, 0))))
                    guard urls.count <= 100_000 else { throw StoreError.stepFailed }
                    step = sqlite3_step(statement)
                }
                guard step == SQLITE_DONE else { throw StoreError.stepFailed }
            }
            return urls
        }
    }
    private let pathQueue = DispatchQueue(label: "unyri.attachment-paths", qos: .userInitiated)
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

    /// Scan hooks run on the dedicated worker; the waiter hook observes an
    /// attached consumer. None run during synchronous store opening.
    struct MigrationHooks: Sendable {
        var beforeMigration: (@Sendable () throws -> Void)? = nil
        var willInspectPath: (@Sendable (String) -> Void)? = nil
        var beforeCommit: (@Sendable () throws -> Void)? = nil
        var didRegisterWaiter: (@Sendable () -> Void)? = nil
    }
    /// Only draft-source replay and ownership/deletion decisions use this gate.
    /// Opening the store and ordinary attachment readers never start or await it.
    func waitForDraftReferences() async throws { try await draftMigration.wait() }
    func cancelAndDrainDraftReferences() async {
        metadataWriter.retire()
        await draftMigration.cancelAndDrain()
        await metadataWriter.drain { self.incomingWriter = nil }
    }

    private final class DraftReferenceMigration: @unchecked Sendable {
        private let database: URL
        private let hooks: MigrationHooks
        private let writer: AttachmentMetadataWriter
        private let lock = NSLock()
        private var task: Task<Void, Never>?
        private var result: Result<Void, Error>?
        private var retired = false
        private var waiters: [UUID: AsyncThrowingStream<Void, Error>.Continuation] = [:]
        init(database: URL, hooks: MigrationHooks, writer: AttachmentMetadataWriter) {
            self.database = database; self.hooks = hooks; self.writer = writer
        }
        var isReady: Bool {
            lock.lock(); defer { lock.unlock() }
            if case .success? = result { return !retired }
            return false
        }
        func wait() async throws {
            try Task.checkCancellation()
            let id = UUID()
            let stream = AsyncThrowingStream<Void, Error> { self.register(id, $0) }
            // A cancelled consumer stops immediately without cancelling the
            // account's shared worker or keeping a background task suspended.
            try await withTaskCancellationHandler(operation: {
                for try await _ in stream {}
                try Task.checkCancellation()
            }, onCancel: { self.cancelWaiter(id) })
        }
        private func register(_ id: UUID, _ continuation: AsyncThrowingStream<Void, Error>.Continuation) {
            lock.lock()
            if retired { lock.unlock(); continuation.finish(throwing: CancellationError()); return }
            if case .success? = result { lock.unlock(); continuation.finish(); return }
            // A transient SQLite failure leaves no marker or partial writes.
            // Retry only when another narrow consumer explicitly asks for it.
            result = nil
            waiters[id] = continuation
            if task == nil {
                task = Task.detached(priority: .utility) { [self] in
                    let outcome: Result<Void, Error> = Result { try self.migrate() }
                    self.finish(outcome)
                }
            }
            lock.unlock()
            hooks.didRegisterWaiter?()
        }
        private func migrate() throws {
            // This scope closes the connection BEFORE publishing completion or
            // allowing account retirement to finish its explicit worker drain.
            try Task.checkCancellation()
            let worker = try AttachmentStore(databaseURL: database, readOnly: false, migrate: false, metadataWriter: writer, busyTimeout: 0)
            try worker.relocateDraftReferencesAfterContainerMove(hooks: hooks)
        }
        private func complete(_ continuation: AsyncThrowingStream<Void, Error>.Continuation, _ result: Result<Void, Error>) {
            switch result {
            case .success: continuation.finish()
            case .failure(let error): continuation.finish(throwing: error)
            }
        }
        private func finish(_ outcome: Result<Void, Error>) {
            lock.lock()
            result = outcome; task = nil
            let pending = Array(waiters.values); waiters.removeAll()
            lock.unlock()
            for continuation in pending { complete(continuation, outcome) }
        }
        private func cancelWaiter(_ id: UUID) {
            lock.lock(); let continuation = waiters.removeValue(forKey: id); lock.unlock()
            continuation?.finish(throwing: CancellationError())
        }
        private func retire() -> Task<Void, Never>? {
            lock.lock()
            retired = true
            let running = task
            running?.cancel()
            let pending = Array(waiters.values); waiters.removeAll()
            lock.unlock()
            for continuation in pending { continuation.finish(throwing: CancellationError()) }
            return running
        }
        func cancelAndDrain() async {
            let running = retire()
            await running?.value
        }
    }

    convenience init(databaseURL: URL, readOnly: Bool = false, draftMigrationHooks: MigrationHooks = .init(), incomingWriteHooks: IncomingWriteHooks = .init()) throws {
        try self.init(databaseURL: databaseURL, readOnly: readOnly, migrate: !readOnly, draftMigrationHooks: draftMigrationHooks, incomingWriteHooks: incomingWriteHooks)
    }
    private init(databaseURL: URL, readOnly: Bool, migrate: Bool, draftMigrationHooks: MigrationHooks = .init(), incomingWriteHooks: IncomingWriteHooks = .init(), metadataWriter: AttachmentMetadataWriter = .init(), busyTimeout: Int32 = 1000) throws {
        self.databaseURL = databaseURL
        self.incomingWriteHooks = incomingWriteHooks
        self.metadataWriter = metadataWriter
        self.draftMigration = DraftReferenceMigration(database: databaseURL, hooks: draftMigrationHooks, writer: metadataWriter)
        var handle: OpaquePointer?
        let flags = (readOnly ? SQLITE_OPEN_READONLY : SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE) | SQLITE_OPEN_FULLMUTEX | SQLITE_OPEN_URI
        guard sqlite3_open_v2(databaseURL.path, &handle, flags, nil) == SQLITE_OK else {
            if let handle { sqlite3_close(handle) }
            throw StoreError.openFailed
        }
        db = handle
        sqlite3_busy_timeout(handle, busyTimeout)
        if readOnly { return }
        do {
          try execute(
            """
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS deleted_chats (chat_id TEXT PRIMARY KEY NOT NULL);
            CREATE TABLE IF NOT EXISTS deleted_attachment_messages (client_message_id TEXT PRIMARY KEY NOT NULL, chat_id TEXT, sequence INTEGER);
            CREATE TABLE IF NOT EXISTS attachment_clear_cutoffs (chat_id TEXT PRIMARY KEY NOT NULL, through_sequence INTEGER NOT NULL);
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
          // Constant schema work only; old fence rows are reclaimed off-main.
          var fenceColumns = Set<String>()
          try withStatement("PRAGMA table_info(deleted_attachment_messages);") { stmt in
              var step = sqlite3_step(stmt)
              while step == SQLITE_ROW { fenceColumns.insert(String(cString: sqlite3_column_text(stmt, 1))); step = sqlite3_step(stmt) }
              guard step == SQLITE_DONE else { throw queryError() }
          }
          if !fenceColumns.contains("chat_id") { try execute("ALTER TABLE deleted_attachment_messages ADD COLUMN chat_id TEXT;") }
          if !fenceColumns.contains("sequence") { try execute("ALTER TABLE deleted_attachment_messages ADD COLUMN sequence INTEGER;") }
          if migrate { try relocateCachePathsAfterContainerMove() }
        }
        catch { sqlite3_close(handle); db = nil; throw error }
    }

    deinit {
        if let db { sqlite3_close(db) }
    }

    /// Read-only account-local source. URI mode prevents recreating an erased
    /// message DB. No transaction/read snapshot is retained between operations.
    private func attachMessageSource() throws -> Bool {
        if messageSourceAttached { return true }
        let source = databaseURL.deletingLastPathComponent().appendingPathComponent("messages.sqlite3")
        guard FileManager.default.fileExists(atPath: source.path) else { return false }
        try withStatement("ATTACH DATABASE ? AS attachment_messages;") { stmt in
            try bindText(stmt, 1, source.absoluteString + "?mode=ro")
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
        }
        messageSourceAttached = true
        return true
    }

    private func journalSource(_ message: String, chat: String? = nil) throws -> (chat: String, sequence: Int64?)? {
        guard messageSourceAttached else { return nil }
        var result: (String, Int64?)?
        let select = """
            SELECT w.chat_id,m.sequence FROM attachment_messages.chat_workspace w
            LEFT JOIN attachment_messages.messages m ON m.client_message_id=:source_message
            WHERE
            """
        let scope = chat == nil ? "" : " w.chat_id=:source_chat AND "
        try withStatement(select + scope + """
            (w.name='restore_attachment:' || :source_message OR (w.name='upload:' || :source_message AND m.kind='content' AND m.hidden_locally=0 AND m.delete_sequence=0))
            LIMIT 1;
            """) { stmt in
            try bindText(stmt, 1, message)
            if let chat { try bindText(stmt, 2, chat) }
            let step = sqlite3_step(stmt)
            guard step == SQLITE_ROW || step == SQLITE_DONE else { throw queryError() }
            if step == SQLITE_ROW { result = (String(cString: sqlite3_column_text(stmt, 0)), sqlite3_column_type(stmt, 1) == SQLITE_NULL ? nil : sqlite3_column_int64(stmt, 1)) }
        }
        return result
    }

    /// One finite background turn at launch, write completion or journal removal.
    /// Legacy orphan fences are deleted in bounded SQL batches, never on launch's
    /// MainActor and never by an idle timer. Protected fences remain untouched.
    private func scheduleFenceReclamation(message: String? = nil) {
        metadataWriter.queue.async {
            do {
                try self.metadataWriter.check()
                let writer = try self.metadataBackgroundWriter()
                if try writer.reclaimFenceBatch(message: message, active: self.metadataWriter.activeMessages()) {
                    self.scheduleFenceReclamation(message: message)
                }
            } catch { /* Fail closed; the next real event or launch retries. */ }
        }
    }
    private func metadataBackgroundWriter() throws -> AttachmentStore {
        if incomingWriter == nil {
            incomingWriter = try AttachmentStore(databaseURL: databaseURL, readOnly: false, migrate: false, metadataWriter: metadataWriter, busyTimeout: 0)
        }
        return incomingWriter!
    }
    func attachmentJournalConsumed(_ message: String) { scheduleFenceReclamation(message: message) }
    /// Synchronous journal-replay owners already hold their account operation
    /// lease. Reclaim just this ID within that lease, without spawning work that
    /// could outlive a backup/restore owner's retirement drain.
    func reclaimConsumedFence(_ message: String, chatId: String) throws {
        guard try attachMessageSource() else { return }
        try metadataWriter.publish {
            guard metadataWriter.source(message) == nil, try journalSource(message, chat: chatId) == nil else { return }
            try withStatement("DELETE FROM deleted_attachment_messages WHERE client_message_id=?;") { stmt in
                try bindText(stmt, 1, message); guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
            }
        }
    }
    func startFenceReclamation() { scheduleFenceReclamation() }
    func reclaimDeletionFences() async throws {
        var more = true
        while more {
            try Task.checkCancellation()
            more = try await withCheckedThrowingContinuation { continuation in
                metadataWriter.queue.async {
                    do {
                        try self.metadataWriter.check()
                        let writer = try self.metadataBackgroundWriter()
                        continuation.resume(returning: try writer.reclaimFenceBatch(message: nil, active: self.metadataWriter.activeMessages()))
                    } catch { continuation.resume(throwing: error) }
                }
            }
        }
    }
    private func reclaimFenceBatch(message: String?, active: Set<String>) throws -> Bool {
        var exists = false
        // The usual journal/write completion has no fence of its own. Do not
        // rebuild the account's journal snapshot just because some other ID
        // still has an outstanding replay source.
        let existence = message == nil ? "SELECT 1 FROM deleted_attachment_messages LIMIT 1;" : "SELECT 1 FROM deleted_attachment_messages WHERE client_message_id=?;"
        try withStatement(existence) { stmt in
            if let message { try bindText(stmt, 1, message) }
            let step = sqlite3_step(stmt)
            guard step == SQLITE_ROW || step == SQLITE_DONE else { throw queryError() }
            exists = step == SQLITE_ROW
        }
        if let message, active.contains(message) { return false }
        guard exists, try attachMessageSource() else { return false }
        if !cutoffSnapshotImported {
            // Inspect the potentially large workspace without holding the
            // attachment DB's writer lock; publish only its compact boundaries.
            try execute("PRAGMA temp_store=FILE; DROP TABLE IF EXISTS temp.attachment_cutoff_snapshot; CREATE TEMP TABLE attachment_cutoff_snapshot AS SELECT chat_id,CAST(value AS INTEGER) AS through_sequence FROM attachment_messages.chat_workspace WHERE name='personal_clear';")
            try execute("INSERT INTO attachment_clear_cutoffs(chat_id,through_sequence) SELECT chat_id,through_sequence FROM temp.attachment_cutoff_snapshot w WHERE NOT EXISTS(SELECT 1 FROM deleted_chats d WHERE d.chat_id=w.chat_id) ON CONFLICT(chat_id) DO UPDATE SET through_sequence=MAX(through_sequence,excluded.through_sequence);")
            try execute("DROP TABLE temp.attachment_cutoff_snapshot;")
            cutoffSnapshotImported = true
        }
        // Index the journal names once per turn. In particular, upgrading old
        // chat-less fences must not scan the workspace once for every fence.
        try execute("""
            PRAGMA temp_store=FILE;
            CREATE TEMP TABLE IF NOT EXISTS active_metadata_sources (message TEXT PRIMARY KEY);
            DELETE FROM temp.active_metadata_sources;
            CREATE TEMP TABLE IF NOT EXISTS attachment_journal_sources (message TEXT PRIMARY KEY, chat TEXT, sequence INTEGER);
            DELETE FROM temp.attachment_journal_sources;
            INSERT OR REPLACE INTO temp.attachment_journal_sources
              SELECT substr(w.name,20),w.chat_id,m.sequence FROM attachment_messages.chat_workspace w
              LEFT JOIN attachment_messages.messages m ON m.client_message_id=substr(w.name,20)
              WHERE substr(w.name,1,19)='restore_attachment:';
            INSERT OR IGNORE INTO temp.attachment_journal_sources
              SELECT substr(w.name,8),w.chat_id,m.sequence FROM attachment_messages.chat_workspace w
              JOIN attachment_messages.messages m ON m.client_message_id=substr(w.name,8)
              WHERE substr(w.name,1,7)='upload:' AND m.kind='content' AND m.hidden_locally=0 AND m.delete_sequence=0;
            """)
        for id in active {
            try withStatement("INSERT INTO temp.active_metadata_sources(message) VALUES (?);") { stmt in
                try bindText(stmt, 1, id); guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
            }
        }
        var ids: [String] = []
        try withStatement("""
            SELECT f.client_message_id FROM deleted_attachment_messages f
              LEFT JOIN temp.attachment_journal_sources j ON j.message=f.client_message_id
            WHERE (? IS NULL OR f.client_message_id=?)
              AND NOT EXISTS (SELECT 1 FROM temp.active_metadata_sources p WHERE p.message=f.client_message_id) AND (
                EXISTS (SELECT 1 FROM deleted_chats d WHERE d.chat_id=COALESCE(f.chat_id,j.chat))
                OR (COALESCE(f.sequence,j.sequence)<=COALESCE((SELECT through_sequence FROM attachment_clear_cutoffs WHERE chat_id=COALESCE(f.chat_id,j.chat)),-1))
                OR j.message IS NULL)
            LIMIT 256;
            """) { stmt in
            try bindOptionalText(stmt, 1, message); try bindOptionalText(stmt, 2, message)
            var step = sqlite3_step(stmt)
            while step == SQLITE_ROW { ids.append(String(cString: sqlite3_column_text(stmt, 0))); step = sqlite3_step(stmt) }
            guard step == SQLITE_DONE else { throw queryError() }
        }
        guard !ids.isEmpty else { return false }
        var removed = 0
        try execute("BEGIN IMMEDIATE;")
        do {
            for id in ids {
                // A new journal must not lose protection between selection and
                // deletion. Incoming/replay insertion also validates that owner.
                if metadataWriter.source(id) != nil { continue }
                var source: (chat: String, sequence: Int64?)?
                try withStatement("SELECT chat,sequence FROM temp.attachment_journal_sources WHERE message=?;") { stmt in
                    try bindText(stmt, 1, id)
                    let step = sqlite3_step(stmt)
                    guard step == SQLITE_ROW || step == SQLITE_DONE else { throw queryError() }
                    if step == SQLITE_ROW { source = (String(cString: sqlite3_column_text(stmt, 0)), sqlite3_column_type(stmt, 1) == SQLITE_NULL ? nil : sqlite3_column_int64(stmt, 1)) }
                }
                if let source {
                    var covered = false
                    try withStatement("SELECT EXISTS(SELECT 1 FROM deleted_chats WHERE chat_id=?) OR EXISTS(SELECT 1 FROM deleted_attachment_messages f JOIN attachment_clear_cutoffs c ON c.chat_id=f.chat_id WHERE f.client_message_id=? AND f.sequence<=c.through_sequence) OR COALESCE(? <= (SELECT through_sequence FROM attachment_clear_cutoffs WHERE chat_id=?),0);") { stmt in
                        try bindText(stmt, 1, source.chat); try bindText(stmt, 2, id); try bindOptionalInt64(stmt, 3, source.sequence); try bindText(stmt, 4, source.chat)
                        guard sqlite3_step(stmt) == SQLITE_ROW else { throw queryError() }; covered = sqlite3_column_int(stmt, 0) != 0
                    }
                    if !covered { continue }
                }
                try withStatement("DELETE FROM deleted_attachment_messages WHERE client_message_id=?;") { stmt in
                    try bindText(stmt, 1, id); guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
                    removed += Int(sqlite3_changes(db))
                }
            }
            try execute("COMMIT;")
        } catch { try? execute("ROLLBACK;"); throw error }
        // A protected batch must not spin. The next ownership-change event
        // schedules another turn; selection already excludes stable owners.
        return ids.count == 256 && removed > 0
    }

    // MARK: - Writes

    /// Existing v1 cache migration stays synchronous, as on main.
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
                    guard let repaired = relocation.relocatedPath(for: entry.path) else { continue }
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

    /// Lazy, account-scoped v2 pass. The existing synchronous v1 cache pass and
    /// its marker are independent: an unchanged cache history is never rescanned.
    private func relocateDraftReferencesAfterContainerMove(hooks: MigrationHooks) throws {
        guard let relocation = AttachmentCacheRelocation(databaseURL: databaseURL, directory: "draft-media") else { return }
        let accountRoot = databaseURL.deletingLastPathComponent().standardizedFileURL.resolvingSymlinksInPath().path
        var previous: String?
        try withStatement("SELECT value FROM attachment_store_metadata WHERE key='local_roots_v2';") { statement in
            let step = sqlite3_step(statement)
            guard step == SQLITE_ROW || step == SQLITE_DONE else { throw queryError() }
            if step == SQLITE_ROW { previous = columnOptionalText(statement, 0) }
        }
        guard previous != accountRoot else { return }
        try Task.checkCancellation()
        try hooks.beforeMigration?()
        // Stage a bounded batch at a time in this connection's TEMP database.
        // No main-database write lock or read transaction spans filesystem work.
        try execute("PRAGMA temp_store=FILE; CREATE TEMP TABLE draft_reference_repairs (source_rowid INTEGER PRIMARY KEY, attachment_id TEXT NOT NULL, original TEXT NOT NULL, proposed TEXT NOT NULL);")
        defer { try? execute("DROP TABLE IF EXISTS temp.draft_reference_repairs;") }
        var cursor = Int64.min
        while true {
            try Task.checkCancellation()
            var batch: [(row: Int64, id: String, path: String)] = []
            try withStatement("SELECT rowid,attachment_id,local_path FROM attachments WHERE rowid>? AND local_path LIKE '%/draft-media/%' ORDER BY rowid LIMIT 256;") { statement in
                guard sqlite3_bind_int64(statement, 1, cursor) == SQLITE_OK else { throw StoreError.bindingFailed }
                var step = sqlite3_step(statement)
                while step == SQLITE_ROW {
                    batch.append((sqlite3_column_int64(statement, 0), String(cString: sqlite3_column_text(statement, 1)), String(cString: sqlite3_column_text(statement, 2))))
                    step = sqlite3_step(statement)
                }
                guard step == SQLITE_DONE else { throw queryError() }
            }
            guard let last = batch.last else { break }
            for entry in batch {
                try Task.checkCancellation()
                hooks.willInspectPath?(entry.path)
                guard let repaired = relocation.relocatedPath(for: entry.path), repaired != entry.path else { continue }
                try withStatement("INSERT INTO temp.draft_reference_repairs(source_rowid,attachment_id,original,proposed) VALUES (?,?,?,?);") { statement in
                    guard sqlite3_bind_int64(statement, 1, entry.row) == SQLITE_OK else { throw StoreError.bindingFailed }
                    try bindText(statement, 2, entry.id); try bindText(statement, 3, entry.path); try bindText(statement, 4, repaired)
                    guard sqlite3_step(statement) == SQLITE_DONE else { throw queryError() }
                }
            }
            cursor = last.row
        }
        // A held scan/publication test hook must never hold SQLite's writer lock.
        try hooks.beforeCommit?()
        try Task.checkCancellation()
        try metadataWriter.publish {
            try Task.checkCancellation()
            try execute("BEGIN IMMEDIATE;")
            do {
                // CAS protects rows deleted/replaced/updated during inspection.
                // New writers normalize their own draft identity before insert.
                try execute("""
                    UPDATE attachments SET local_path=(
                        SELECT proposed FROM temp.draft_reference_repairs r WHERE r.source_rowid=attachments.rowid
                    ) WHERE rowid IN (SELECT source_rowid FROM temp.draft_reference_repairs)
                      AND EXISTS (SELECT 1 FROM temp.draft_reference_repairs r WHERE r.source_rowid=attachments.rowid
                          AND r.attachment_id=attachments.attachment_id AND r.original=attachments.local_path);
                    """)
                try withStatement("INSERT OR REPLACE INTO attachment_store_metadata(key,value) VALUES ('local_roots_v2',?);") { statement in
                    try bindText(statement, 1, accountRoot)
                    guard sqlite3_step(statement) == SQLITE_DONE else { throw queryError() }
                }
                try Task.checkCancellation()
                try execute("COMMIT;")
            } catch { try? execute("ROLLBACK;"); throw error }
        }
    }

    func upsert(_ attachment: AttachmentRecord, preservingExisting: Bool = false, sourceSequence: Int64? = nil, requiresJournal: Bool = false) throws {
        var attachment = attachment
        if let path = attachment.localPath { attachment.localPath = normalizedDraftPath(path) }
        let sql = """
            INSERT OR REPLACE INTO attachments
            (attachment_id, client_message_id, chat_id, media_type, mime_type, filename, size, width, height, duration_ms, thumbnail_base64, local_path, download_state, upload_state, progress, object_key, encrypted_size, encryption_version, key_base64, nonce_base64, keyset_base64, sha256_base64)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            WHERE NOT EXISTS (SELECT 1 FROM deleted_chats WHERE chat_id=:source_chat)
              AND NOT EXISTS (SELECT 1 FROM deleted_attachment_messages WHERE client_message_id=:source_message)
              AND (:source_sequence IS NULL OR :source_sequence > COALESCE((SELECT through_sequence FROM attachment_clear_cutoffs WHERE chat_id=:source_chat),-1))
            """
        let sourceGuard = """
            AND EXISTS (
                SELECT 1 FROM attachment_messages.messages m JOIN attachment_messages.chat_workspace j
                  ON j.chat_id=m.chat_id AND j.name IN ('restore_attachment:' || m.client_message_id,'upload:' || m.client_message_id)
                WHERE m.client_message_id=:source_message AND m.chat_id=:source_chat AND m.kind='content'
                  AND m.hidden_locally=0 AND m.delete_sequence=0
                  AND NOT EXISTS (SELECT 1 FROM attachment_messages.deleted_chats d WHERE d.chat_id=m.chat_id)
                  AND (COALESCE(m.sequence,:source_sequence) IS NULL OR COALESCE(m.sequence,:source_sequence) > MAX(
                    COALESCE((SELECT through_sequence FROM attachment_clear_cutoffs WHERE chat_id=m.chat_id),-1),
                    COALESCE((SELECT CAST(value AS INTEGER) FROM attachment_messages.chat_workspace WHERE chat_id=m.chat_id AND name='personal_clear'),-1),
                    COALESCE((SELECT CAST(value AS INTEGER) FROM attachment_messages.chat_workspace WHERE chat_id='__server_history__' AND name='cleared_through_sequence'),-1)))
            )
            """
        if requiresJournal, try !attachMessageSource() { throw StoreError.deleted }
        let guarded = sql + (requiresJournal ? "\n" + sourceGuard : "") + ";"
        try withStatement(preservingExisting ? guarded.replacingOccurrences(of: "INSERT OR REPLACE", with: "INSERT OR IGNORE") : guarded) { stmt in
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
            try bindText(stmt, 23, attachment.chatId)
            try bindText(stmt, 24, attachment.clientMessageId)
            try bindOptionalInt64(stmt, 25, sourceSequence)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
        }
    }

    /// A consumed Signal packet must already have a durable plaintext/journal
    /// before calling this. Contention never blocks MainActor or re-decrypts it.
    func upsertIncoming(_ attachment: AttachmentRecord, sequence: Int64? = nil) async throws {
        let cancellation = PathCancellation()
        let pending = try metadataWriter.register(message: attachment.clientMessageId, chat: attachment.chatId, sequence: sequence)
        defer { metadataWriter.finish(pending); scheduleFenceReclamation(message: attachment.clientMessageId) }
        try await withTaskCancellationHandler(operation: {
            var retries = 0
            while true {
                try Task.checkCancellation()
                try metadataWriter.check(); try pending.check()
                do {
                    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                        metadataWriter.queue.async {
                            do {
                                try cancellation.check(); try self.metadataWriter.check()
                                try pending.check()
                                if self.incomingWriter == nil {
                                    self.incomingWriter = try AttachmentStore(databaseURL: self.databaseURL, readOnly: false,
                                        migrate: false, metadataWriter: self.metadataWriter, busyTimeout: 0)
                                }
                                try self.incomingWriter!.upsert(attachment, preservingExisting: true, sourceSequence: sequence, requiresJournal: true)
                                continuation.resume()
                            } catch { continuation.resume(throwing: error) }
                        }
                    }
                    try Task.checkCancellation()
                    try pending.check()
                    await incomingWriteHooks.afterPersist?()
                    try Task.checkCancellation(); try metadataWriter.check(); try pending.check()
                    return
                } catch StoreError.busy where retries < 8 {
                    retries += 1
                    await incomingWriteHooks.beforeRetry?()
                    try metadataWriter.check(); try pending.check()
                    try await Task.sleep(nanoseconds: UInt64(min(retries, 5)) * 50_000_000)
                }
            }
        }, onCancel: { cancellation.cancel() })
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
            try bindText(stmt, 1, normalizedDraftPath(localPath))
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

    /// Thumbnails outside the current timeline window (pins/gallery) resolve by ID
    /// on a read connection, rather than trusting an old AttachmentRecord snapshot.
    func localPathAsync(attachmentId: String) async throws -> String? {
        let file = databaseURL
        return try await Task.detached(priority: .utility) {
            try Task.checkCancellation()
            let reader = try AttachmentStore(databaseURL: file, readOnly: true)
            guard let path = try reader.attachment(byId: attachmentId)?.localPath,
                  FileManager.default.fileExists(atPath: path) else { return nil }
            return path
        }.value
    }

    /// Bounded repair for one known row (also used by restore-journal replay).
    /// This does not scan the store or assert that indexed ownership is ready.
    func normalizedDraftPath(_ path: String) -> String {
        guard path.contains("/draft-media/") else { return path }
        return AttachmentCacheRelocation(databaseURL: databaseURL, directory: "draft-media")?.relocatedPath(for: path) ?? path
    }
    func referencesLocalPath(_ path: String) throws -> Bool {
        // Never answer "unowned" from rows that still carry the old container.
        // Async source cleanup awaits the gate; an accidental bypass fails closed.
        if path.contains("/draft-media/"), !draftMigration.isReady { throw StoreError.draftReferencesNotReady }
        var found = false
        // Writes/migration normalize stored draft paths; normalize the lookup as
        // well, including callers retaining a URL from an older container.
        try withStatement("SELECT 1 FROM attachments WHERE local_path IN (?, ?) LIMIT 1;") { statement in
            try bindText(statement, 1, path)
            try bindText(statement, 2, normalizedDraftPath(path))
            let step = sqlite3_step(statement)
            guard step == SQLITE_ROW || step == SQLITE_DONE else { throw StoreError.stepFailed }
            found = step == SQLITE_ROW
        }
        return found
    }

    func deleteRecords(_ ids: Set<String>, chatId: String? = nil, throughSequence: Int64? = nil) throws {
        guard !ids.isEmpty || (chatId != nil && throughSequence != nil) else { return }
        invalidateIncomingWrites(messageIDs: ids)
        let sourceAvailable = try attachMessageSource()
        try metadataWriter.publish {
            try execute("BEGIN IMMEDIATE;")
            do {
                if let chatId, let throughSequence {
                    try withStatement("INSERT INTO attachment_clear_cutoffs(chat_id,through_sequence) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM deleted_chats WHERE chat_id=?) ON CONFLICT(chat_id) DO UPDATE SET through_sequence=MAX(through_sequence,excluded.through_sequence);") { stmt in
                        try bindText(stmt, 1, chatId); sqlite3_bind_int64(stmt, 2, throughSequence); try bindText(stmt, 3, chatId)
                        guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
                    }
                    try withStatement("DELETE FROM deleted_attachment_messages WHERE chat_id=? AND sequence<=(SELECT through_sequence FROM attachment_clear_cutoffs WHERE chat_id=?);") { stmt in
                        try bindText(stmt, 1, chatId); try bindText(stmt, 2, chatId)
                        guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
                    }
                }
                for id in ids {
                    if chatId != nil && throughSequence != nil {
                        // The clear owns these IDs, including old cleanup IDs.
                        // One monotonic cutoff replaces every individual fence.
                        try withStatement("DELETE FROM deleted_attachment_messages WHERE client_message_id=?;") { stmt in
                            try bindText(stmt, 1, id); guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
                        }
                    } else {
                        let old = try attachment(byClientMessageId: id)
                        let pending = metadataWriter.source(id)
                        let journal = try journalSource(id, chat: chatId ?? pending?.chat ?? old?.chatId)
                        if pending != nil || journal != nil || old != nil {
                            let chat = chatId ?? pending?.chat ?? journal?.chat ?? old?.chatId
                            let sequence = pending?.sequence ?? journal?.sequence
                            try withStatement("INSERT INTO deleted_attachment_messages(client_message_id,chat_id,sequence) SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM deleted_chats WHERE chat_id=?) ON CONFLICT(client_message_id) DO UPDATE SET chat_id=COALESCE(excluded.chat_id,chat_id),sequence=COALESCE(excluded.sequence,sequence);") { stmt in
                                try bindText(stmt, 1, id); try bindOptionalText(stmt, 2, chat); try bindOptionalInt64(stmt, 3, sequence); try bindOptionalText(stmt, 4, chat)
                                guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
                            }
                        }
                    }
                    for sql in ["DELETE FROM attachments WHERE client_message_id=?;"] {
                        try withStatement(sql) { stmt in
                            try bindText(stmt, 1, id)
                            guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
                        }
                    }
                    if throughSequence == nil, sourceAvailable, metadataWriter.source(id) == nil, try journalSource(id, chat: chatId) == nil {
                        try withStatement("DELETE FROM deleted_attachment_messages WHERE client_message_id=?;") { stmt in
                            try bindText(stmt, 1, id); guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
                        }
                    }
                }
                try execute("COMMIT;")
            } catch { try? execute("ROLLBACK;"); throw error }
        }
    }
    func invalidateIncomingWrites(messageIDs: Set<String>) { metadataWriter.invalidate(messages: messageIDs) }
    func invalidateIncomingWrites(chatId: String) { metadataWriter.invalidate(chat: chatId) }

    func deleteChat(_ chatId: String) throws {
        invalidateIncomingWrites(chatId: chatId)
        let sourceAvailable = try attachMessageSource()
        try metadataWriter.publish {
            try execute("BEGIN IMMEDIATE;")
            do {
                for sql in ["INSERT OR IGNORE INTO deleted_chats(chat_id) VALUES (?);", "DELETE FROM attachments WHERE chat_id=?;"] {
                    try withStatement(sql) { stmt in
                        try bindText(stmt, 1, chatId)
                        guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
                    }
                }
                for sql in ["DELETE FROM deleted_attachment_messages WHERE chat_id=?;", "DELETE FROM attachment_clear_cutoffs WHERE chat_id=?;"] {
                    try withStatement(sql) { stmt in
                        try bindText(stmt, 1, chatId); guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
                    }
                }
                if sourceAvailable {
                    // Legacy fences did not store their chat. The journal can
                    // still identify them; journal-free legacy rows sweep at launch.
                    try withStatement("DELETE FROM deleted_attachment_messages WHERE client_message_id IN (SELECT substr(name,20) FROM attachment_messages.chat_workspace WHERE chat_id=? AND name LIKE 'restore_attachment:%' UNION SELECT substr(name,8) FROM attachment_messages.chat_workspace WHERE chat_id=? AND name LIKE 'upload:%');") { stmt in
                        try bindText(stmt, 1, chatId); try bindText(stmt, 2, chatId); guard sqlite3_step(stmt) == SQLITE_DONE else { throw queryError() }
                    }
                }
                try execute("COMMIT;")
            } catch { try? execute("ROLLBACK;"); throw error }
        }
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
    /// Synchronous variant for a handful of rows on the calling connection (the first
    /// window of a chat), so the rows render already knowing which files are local.
    func cachedPaths(chatId: String, attachmentIDs: Set<String>) throws -> [String: String] {
        guard !attachmentIDs.isEmpty else { return [:] }
        var paths: [String: String] = [:]
        for row in try cachedPathCandidates(chatId: chatId, attachmentIDs: attachmentIDs)
        where FileManager.default.fileExists(atPath: row.path) {
            paths[row.id] = row.path
        }
        return paths
    }

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

    /// Every row of one message (a retried upload can leave an older row).
    func attachments(clientMessageId: String) throws -> [AttachmentRecord] {
        try query("SELECT * FROM attachments WHERE client_message_id = ?;", binding: { try bindText($0, 1, clientMessageId) })
    }

    /// Local media index for a chat. Attachments are inserted by the single
    /// message-ingestion pipeline, so this never needs a second server query
    /// or a parallel metadata cache.
    /// The chat's media purge has finished (`deleteChat` records the chat in the same
    /// transaction that removes its rows).
    func isChatDeleted(_ chatId: String) throws -> Bool {
        var found = false
        try withStatement("SELECT 1 FROM deleted_chats WHERE chat_id = ? LIMIT 1;") { stmt in
            try bindText(stmt, 1, chatId)
            found = sqlite3_step(stmt) == SQLITE_ROW
        }
        return found
    }

    func attachments(chatId: String) throws -> [AttachmentRecord] {
        try query(
            "SELECT * FROM attachments WHERE chat_id = ? ORDER BY rowid DESC;",
            binding: { try bindText($0, 1, chatId) }
        )
    }

    /// The chat media gallery's rows, read on a private read-only connection
    /// so a long chat's media index is never decoded on the main thread.
    func attachmentsAsync(chatId: String) async throws -> [AttachmentRecord] {
        let file = databaseURL
        return try await Task.detached(priority: .userInitiated) {
            let reader = try AttachmentStore(databaseURL: file, readOnly: true)
            return try reader.attachments(chatId: chatId)
        }.value
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
            throw queryError()
        }
    }

    private func withStatement(
        _ sql: String,
        _ body: (OpaquePointer) throws -> Void
    ) throws {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw queryError()
        }
        defer { sqlite3_finalize(stmt) }
        do { try body(stmt) }
        catch { if isBusy { throw StoreError.busy }; throw error }
    }

    private var isBusy: Bool { [SQLITE_BUSY, SQLITE_LOCKED].contains(sqlite3_errcode(db)) }
    private func queryError() -> StoreError { isBusy ? .busy : .stepFailed }

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
