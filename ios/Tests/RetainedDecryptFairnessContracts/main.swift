import Foundation
import SQLite3

// PRODUCTION_MODELS
// Only these libsignal cases are used by the extracted classification method.
enum SignalError: Error { case untrustedIdentity(String), duplicatedMessage(String) }
enum FixtureError: Error { case retained }
private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

@MainActor final class MessageStore {
    // PRODUCTION_STORE_ERROR
    private var db: OpaquePointer?
    private let databaseURL: URL
    private lazy var erasureCheckpoint = MessageErasureCheckpoint(url: databaseURL)
    private let workspaceQueue = DispatchQueue(label: "retry-contract-workspace")
    var statuses: [String: MessageStatus] = [:]
    init(_ url: URL) throws {
        databaseURL = url
        guard sqlite3_open(url.path, &db) == SQLITE_OK else { throw StoreError.openFailed }
        try execute("""
// PRODUCTION_SCHEMA
        """)
    }
    deinit { sqlite3_close(db) }
    func isChatDeleted(_ chat: String) throws -> Bool { false }
    func status(forClientMessageId id: String) throws -> MessageStatus? { statuses[id] ?? .decryptFailed }
    // PRODUCTION_STORE
}

// PRODUCTION_ERASURE_CHECKPOINT

@MainActor final class Auth {
    var sessionGeneration: UInt64 = 1
    struct User { let id: String }
    var currentUser: User? = .init(id: "owner")
    func currentAccessToken() async throws -> String? { "fixture" }
}
@MainActor final class Changes {
    var chats = Set<String>()
    func send(_ value: Set<String>) { chats.formUnion(value) }
}
@MainActor final class Subject {
    let store: MessageStore
    let auth = Auth(), changes = Changes()
    let generation: UInt64 = 1
    var lastAutomaticRetry: [String: Date] = [:]
    var retryTail: Task<Void, Never>?
    var retryRequests = 0
    var attempts: [String] = []
    var recoverable = Set<String>()
    var failures: [String: Error] = [:]
    var cancelAfter = Int.max
    init(_ store: MessageStore) { self.store = store }
    // The decrypt seam re-inserts failed envelopes just as ingestOne does.
    // Selection, scheduling, error classification and all SQLite operations
    // below are verbatim production code, not a model of the fix.
    private func ingestOne(_ dto: MessageDto, token: String?, myUserId: String) async throws -> IngestOutcome {
        attempts.append(dto.clientMessageId)
        if attempts.count > cancelAfter { throw CancellationError() }
        if let error = failures[dto.clientMessageId] {
            var pending = Main.entry(dto.clientMessageId, sequence: dto.sequence, sender: dto.senderId)
            pending.lastErrorClass = String(describing: type(of: error))
            try store.insertPendingDecrypt(pending)
            store.statuses[dto.clientMessageId] = .decryptFailed
            return IngestOutcome(changed: false, priorStatus: .decryptFailed, finalStatus: .decryptFailed, retainedError: error)
        }
        if recoverable.contains(dto.clientMessageId) {
            store.statuses[dto.clientMessageId] = .delivered
        } else {
            try store.insertPendingDecrypt(Main.entry(dto.clientMessageId, sequence: dto.sequence, sender: dto.senderId))
            store.statuses[dto.clientMessageId] = .decryptFailed
        }
        return IngestOutcome(changed: false, priorStatus: .decryptFailed, finalStatus: store.statuses[dto.clientMessageId])
    }
    // PRODUCTION_RETRY
}

@main struct Main {
    static func key(_ n: Int) -> String { String(format: "%04d", n) }
    static func entry(_ client: String, sequence: Int64, sender: String = "peer") -> PendingDecrypt {
        PendingDecrypt(clientMessageId: client, serverId: "server-" + client, chatId: "chat",
            senderId: sender, senderDeviceId: "device", sequence: sequence,
            createdAt: Date(timeIntervalSince1970: 1), messageType: 2, ciphertext: "fixture",
            kind: "content", targetMessageId: nil, lastErrorClass: nil)
    }
    @MainActor static func main() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("retry.sqlite3")
        // Unknown but permanently failing packets must rotate even when their
        // retry counters saturate; fairness never relies on diagnostics.
        do {
            let store = try MessageStore(file)
            for n in 1...129 {
                var row = entry(key(n), sequence: Int64(n)); row.retryCount = Int.max
                try store.insertPendingDecrypt(row)
            }
            let subject = Subject(store); subject.recoverable = [key(129)]
            await subject.retryPendingDecrypts(automatic: true)
            precondition(subject.attempts == (1...128).map(key))
            let retained = try store.pendingDecrypts()
            precondition(retained.count == 129 && retained.allSatisfy { $0.retryCount == Int.max })
        }
        do {
            // A fresh store/session must resume beyond the persistent prefix.
            let store = try MessageStore(file), subject = Subject(store)
            subject.recoverable = [key(129)]
            await subject.retryPendingDecrypts(automatic: true)
            precondition(subject.attempts.first == key(129) && subject.attempts.count == 128)
            precondition(subject.changes.chats == ["chat"])
            let retained = try store.pendingDecrypts()
            precondition(retained.count == 128 && !retained.contains { $0.clientMessageId == key(129) })
        }

        let tied = try MessageStore(directory.appendingPathComponent("ties.sqlite3"))
        for n in 1...130 { try tied.insertPendingDecrypt(entry(key(n), sequence: 7, sender: n == 130 ? "other" : "peer")) }
        let first = try tied.pendingDecryptRetryBatch(senderId: "peer")
        precondition(first.count == 128 && first.last?.clientMessageId == key(128))
        for row in first { try tied.advancePendingDecryptRetryCursor(row, senderId: "peer") }
        let wrapped = try tied.pendingDecryptRetryBatch(senderId: "peer")
        precondition(wrapped.count == 128 && wrapped.first?.clientMessageId == key(129) && Set(wrapped.map(\.clientMessageId)).count == 128)
        let global = try tied.pendingDecryptRetryBatch(senderId: nil)
        let other = try tied.pendingDecryptRetryBatch(senderId: "other")
        precondition(global.first?.clientMessageId == key(1) && other.map(\.clientMessageId) == [key(130)])

        let permanent = try MessageStore(directory.appendingPathComponent("permanent.sqlite3"))
        for n in 1...3 { try permanent.insertPendingDecrypt(entry(key(n), sequence: Int64(n))) }
        let classifier = Subject(permanent)
        classifier.failures = [key(1): SignalError.duplicatedMessage("fixture"),
            key(2): SignalDecryptionError.malformedCiphertext,
            key(3): SignalDecryptionError.unsupportedMessageType(99)]
        await classifier.retryPendingDecrypts(automatic: true)
        let eligible = try permanent.pendingDecryptRetryBatch(senderId: nil)
        let retained = try permanent.pendingDecrypts()
        precondition(eligible.isEmpty && retained.count == 3)
        let explicit = Subject(permanent); explicit.recoverable = Set((1...3).map(key))
        await explicit.retryPendingDecrypts(senderId: "peer")
        let afterExplicit = try permanent.pendingDecrypts()
        precondition(explicit.attempts.count == 3 && afterExplicit.isEmpty)

        let cancelled = try MessageStore(directory.appendingPathComponent("cancel.sqlite3"))
        for n in 1...129 { try cancelled.insertPendingDecrypt(entry(key(n), sequence: Int64(n))) }
        let interrupted = Subject(cancelled); interrupted.cancelAfter = 1
        await interrupted.retryPendingDecrypts(automatic: true)
        precondition(interrupted.attempts == [key(1), key(2)])
        let next = try cancelled.pendingDecryptRetryBatch(senderId: nil)
        precondition(next.first?.clientMessageId == key(3), "Cancellation must not pin the next trigger to the old prefix")
        let stale = Subject(cancelled); stale.auth.sessionGeneration += 1
        await stale.retryPendingDecrypts(automatic: true)
        precondition(stale.attempts.isEmpty)

        await ServerWorkDeadline.$uptime.withValue(0) {
            let expired = Subject(cancelled)
            await expired.retryPendingDecrypts(automatic: true)
            precondition(expired.attempts.isEmpty)
        }
        print("Retained decrypt bounded selection, durable fairness, permanent failures and explicit retry contracts passed")
    }
}
