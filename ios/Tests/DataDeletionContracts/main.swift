import Foundation
import SQLite3

@main
struct DataDeletionContracts {
    static func sql(_ url: URL, _ statement: String) throws {
        var db: OpaquePointer?
        guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READWRITE, nil) == SQLITE_OK else { fatalError("test DB open") }
        defer { sqlite3_close(db) }
        // This fixture connection writes too; secure_delete is per connection.
        guard sqlite3_exec(db, "PRAGMA secure_delete=ON;", nil, nil, nil) == SQLITE_OK else { fatalError("test secure delete") }
        guard sqlite3_exec(db, statement, nil, nil, nil) == SQLITE_OK else {
            fatalError("test SQL: \(String(cString: sqlite3_errmsg(db)))")
        }
    }

    static func message(_ id: String, sequence: Int64, reply: String? = nil) -> DecryptedChatMessage {
        var result = DecryptedChatMessage(clientMessageId: id, serverId: "server-" + id,
            chatId: "chat", senderId: "sender", sequence: sequence, createdAt: Date(),
            kind: "content", targetMessageId: reply, status: .sent,
            content: MessageContent(kind: MessageContent.kindText, text: "erasure-secret-" + id,
                replySnapshot: ReplySnapshot(senderId: "sender", contentType: "text", preview: "erasure-secret-snapshot", sequenceHint: nil)))
        result.payloadText = "erasure-secret-payload-" + id
        result.pendingEditText = "erasure-secret-edit-" + id
        return result
    }

    static func requireScrubbed(_ row: DecryptedChatMessage?) {
        guard let row else { fatalError("UI tombstone must remain") }
        precondition(row.content.text == nil && row.content.attachment == nil && row.content.replySnapshot == nil)
        precondition(row.payloadText == nil && row.pendingEditText == nil)
    }

    static func requireErasedBytes(_ database: URL, needles: [String]) throws {
        // Wait only in the contract for the production finite deletion debounce.
        Thread.sleep(forTimeInterval: 1.5)
        let manager = FileManager.default
        precondition(manager.fileExists(atPath: database.path), "Erasure proof requires the live database")
        precondition(!manager.fileExists(atPath: database.path + ".erase-pending"), "Checkpoint did not complete")
        for suffix in ["", "-wal", "-shm"] {
            let file = URL(fileURLWithPath: database.path + suffix)
            if manager.fileExists(atPath: file.path) {
                let data = try Data(contentsOf: file)
                if suffix == "-wal" { precondition(data.isEmpty, "Checkpoint did not truncate the WAL") }
                for needle in needles {
                    precondition(data.range(of: Data(needle.utf8)) == nil, "Deleted bytes remain after checkpoint in \(file.lastPathComponent)")
                }
            }
        }
    }

    static func main() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("unyri-erasure-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let database = root.appendingPathComponent("messages.sqlite3")
        let store = try MessageStore(databaseURL: database)
        try sql(database, "CREATE TRIGGER fixture_write_failure BEFORE INSERT ON messages WHEN NEW.client_message_id='diagnostic-fixture' BEGIN SELECT RAISE(ABORT,'fixture failure'); END;")
        do {
            try store.upsert(message("diagnostic-fixture", sequence: 1))
            preconditionFailure("A failed SQL step must throw")
        } catch MessageStore.StoreError.sqliteFailed(let operation, let code, let extendedCode, let diagnostic) {
            precondition(operation == "step" && code == SQLITE_CONSTRAINT && extendedCode == 1811)
            precondition(diagnostic == "fixture failure", "SQLite diagnostics must omit bound message data")
        }
        try sql(database, "DROP TRIGGER fixture_write_failure;")
        try store.upsert(message("everyone", sequence: 1))
        try store.upsert(message("reply", sequence: 2, reply: "server-everyone"))
        _ = try store.applyDelete(targetServerId: "server-everyone", sequence: 3)
        requireScrubbed(try store.message(byClientMessageId: "everyone"))
        let reply = try store.message(byClientMessageId: "reply")
        precondition(reply?.content.replySnapshot == nil, "Surviving reply retained a deleted target snapshot")
        try store.upsert(message("everyone", sequence: 1))
        requireScrubbed(try store.message(byClientMessageId: "everyone"))
        try store.upsert(message("mine", sequence: 4))
        try store.hideLocally(clientMessageId: "mine")
        requireScrubbed(try store.message(byClientMessageId: "mine"))
        try store.upsert(message("optimistic", sequence: 5))
        try store.setOptimisticDeleted(clientMessageId: "optimistic", deleted: true)
        requireScrubbed(try store.message(byClientMessageId: "optimistic"))
        let jobs = try store.historyCleanupJobs()
        precondition(Set(jobs.map(\.client)).isSuperset(of: ["everyone", "mine", "optimistic"]))
        // Check while tombstones and a live reply still occupy the page too.
        try withExtendedLifetime(store) {
            try requireErasedBytes(database, needles: ["erasure-secret-everyone", "erasure-secret-mine", "erasure-secret-optimistic"])
        }
        // A historical soft-delete must be scrubbed by opening the upgraded DB.
        try sql(database, "DROP TRIGGER erase_message_content_INSERT; DROP TRIGGER erase_message_content_UPDATE; UPDATE messages SET content_json='{\"kind\":\"text\",\"text\":\"legacy-delete-secret\"}',payload_text='legacy-delete-secret' WHERE client_message_id='mine';")
        let upgraded = try MessageStore(databaseURL: database)
        requireScrubbed(try upgraded.message(byClientMessageId: "mine"))
        try upgraded.clearPersonalChat("chat", throughSequence: 5)
        let cleared = try upgraded.messages(forChatId: "chat")
        precondition(cleared.isEmpty)
        try upgraded.upsert(message("late", sequence: 4))
        let late = try upgraded.message(byClientMessageId: "late")
        precondition(late == nil, "A delayed sync resurrected cleared text")
        try upgraded.upsert(message("after-clear", sequence: 9))
        try sql(database, "INSERT INTO pending_decrypts(client_message_id,server_id,chat_id,sender_id,sender_device_id,sequence,created_at,message_type,ciphertext,kind) VALUES('after-clear','server-after-clear','chat','sender','device',9,0,1,'erasure-secret-ciphertext','content'),('pending-only','server-pending-only','chat','sender','device',10,0,1,'erasure-secret-pending-only','content');")
        _ = try upgraded.purgeDeletedChat("chat")
        let deleted = try upgraded.message(byClientMessageId: "after-clear")
        precondition(deleted == nil)
        let retained = try upgraded.pendingDecrypts()
        precondition(retained.isEmpty, "Deleted chat retained ciphertext with or without a message projection")
        try withExtendedLifetime((store, upgraded)) {
            try requireErasedBytes(database, needles: ["erasure-secret", "legacy-delete-secret"])
        }
        print("PASS: production iOS erasure, tombstones, replay, upgrade, clear/chat delete and WAL contracts")
    }
}
