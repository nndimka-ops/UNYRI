import Foundation
import SQLite3
#if canImport(CryptoKit)
import CryptoKit
#endif
#if canImport(CryptoKit)
import CryptoKit
#endif

// Standalone transport double: these tests exercise native SQLite ownership,
// revision admission and restart journalling, never real network services.
final class HistoryBackupAPI {
    struct MediaObject { let blobId: String; let bytes: Int64; let sha256: String; let uploadUrl: String?; let downloadUrl: String? }
    var state: HistoryBackupState
    var statusCalls = 0
    var changeOnSecond = false
    var restoredSnapshots: [String] = []
    func restoreComplete(_ backupId: String) async throws { restoredSnapshots.append(backupId) }
    var complete = true
    var deletions: [String: Int64] = [:]
    init(_ state: HistoryBackupState) { self.state = state }
    func cancel() {}
    func status() async throws -> HistoryBackupState {
        statusCalls += 1
        if changeOnSecond && statusCalls >= 2 { return HistoryBackupState(serverId: state.serverId, revision: state.revision + 1, desiredEnabled: false, active: state.active, staged: nil) }
        return state
    }
    func media(_ entry: HistoryMediaEntry) async throws -> MediaObject { throw TestFailure.unexpectedMedia }
    func serverDeletions(_ ids: [String]) async throws -> [String: Int64] { deletions.filter { ids.contains($0.key) } }
    var deletedCalls = Set<String>()
    func serverCallDeletions(_ ids: [String]) async throws -> Set<String> { deletedCalls.intersection(ids) }
    func sourceComplete(_ ids: [String], calls: [String] = []) async throws -> Bool { complete }
    func liveMediaURL(_ id: String) async throws -> String { throw TestFailure.unexpectedMedia }
    func startMedia(_ entry: HistoryMediaEntry, revision: Int64) async throws -> MediaObject { throw TestFailure.unexpectedMedia }
    func sealMedia(_ entry: HistoryMediaEntry, revision: Int64) async throws -> MediaObject { throw TestFailure.unexpectedMedia }
    func receiveMedia(url: String, entry: HistoryMediaEntry, root: URL, check: @escaping () throws -> Void) async throws -> URL { throw TestFailure.unexpectedMedia }
    func uploadMedia(url: String, file: URL, bytes: Int64) async throws { throw TestFailure.unexpectedMedia }
}
enum APIError: Error { case http(Int) }
enum TestFailure: Error { case unexpectedMedia }
enum ISO8601Cocoa { static func parse(_ value: String) -> Date? { ISO8601DateFormatter().date(from: value) } }

#if !canImport(CryptoKit)
// Compile-only doubles. No cryptographic success is simulated by these tests.
struct SymmetricKey { init(data: Data) {} }
enum AES { enum GCM { struct Nonce { init(data: Data) throws {} } } }
#endif
struct AttachmentIntegrityError: Error {}
enum AttachmentCrypto {
    static func decryptV1(ciphertextFile: URL, plaintextFile: URL, key: SymmetricKey, nonce: AES.GCM.Nonce, expectedSha256: Data) throws { throw TestFailure.unexpectedMedia }
    static func decryptV2(ciphertextFile: URL, plaintextFile: URL, keyset: Data, expectedSha256: Data) throws { throw TestFailure.unexpectedMedia }
}
#if !canImport(CryptoKit)
private struct NoDigest: HistoryMediaDigest {
    mutating func update(_ bytes: Data) {}
    mutating func finish() -> String { "" }
}
extension HistoryMediaSpool { convenience init(root: URL) { self.init(root: root, digest: { NoDigest() }) } }
#endif

@main struct NativeOwnerContracts {
    static func id(_ n: Int) -> String { String(format: "00000000-0000-4000-8000-%012d", n) }

    /// Persist the exact old ACK state: confirmed authenticated event, unchanged
    /// target projection. Calling today's upsert would immediately fix the fixture.
    static func oldACK(database: URL, number: Int, chat: String, sender: String, sequence: Int64,
                       kind: String, targetClient: String, targetServer: String, payload: Data) throws {
        let authenticated = try MessageContentCodec.authenticate(payload, chatId: chat, clientMessageId: id(number),
            operation: kind, targetMessageId: targetServer, targetClientMessageId: targetClient)
        let json = String(decoding: try JSONEncoder().encode(MessageContentCodec.decode(authenticated)), as: UTF8.self)
            .replacingOccurrences(of: "'", with: "''")
        var db: OpaquePointer?
        precondition(sqlite3_open(database.path, &db) == SQLITE_OK)
        defer { sqlite3_close(db) }
        let statement = "INSERT INTO messages(client_message_id,server_id,chat_id,sender_id,sequence,created_at,kind,target_message_id,status,content_json) VALUES('\(id(number))','\(id(number + 10000))','\(chat)','\(sender)',\(sequence),\(number),'\(kind)','\(targetServer)','sent','\(json)');"
        precondition(sqlite3_exec(db, statement, nil, nil, nil) == SQLITE_OK)
    }

    @MainActor static func verifyLegacyExportProjection(in root: URL, remote: HistoryBackupAPI) async throws {
        let directory = root.appendingPathComponent("legacy-export")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let database = directory.appendingPathComponent("messages.sqlite3")
        let store = try MessageStore(databaseURL: database)
        let chat = id(8000), author = id(8001)
        for index in 0..<3 {
            try store.upsert(DecryptedChatMessage(clientMessageId: id(8010 + index), serverId: id(8020 + index),
                chatId: chat, senderId: author, sequence: Int64(index + 1), createdAt: Date(timeIntervalSince1970: 1),
                kind: "content", targetMessageId: nil, status: .sent,
                content: MessageContent(kind: "text", text: "Old private text \(index)"), hiddenLocally: index == 2))
        }
        try oldACK(database: database, number: 8100, chat: chat, sender: author, sequence: 10, kind: "edit",
            targetClient: id(8010), targetServer: id(8020), payload: MessageContentCodec.encodeEdit("Edited before backup"))
        try oldACK(database: database, number: 8101, chat: chat, sender: author, sequence: 11, kind: "reaction",
            targetClient: id(8010), targetServer: id(8020), payload: MessageContentCodec.encodeReaction("❤️"))
        try oldACK(database: database, number: 8102, chat: chat, sender: author, sequence: 12, kind: "delete",
            targetClient: id(8011), targetServer: id(8021), payload: MessageContentCodec.encodeDelete())
        try oldACK(database: database, number: 8103, chat: chat, sender: author, sequence: 13, kind: "delete",
            targetClient: id(8999), targetServer: id(8020), payload: MessageContentCodec.encodeDelete())
        let stale = try store.exportHistoryArchive(server: id(1), owner: author, snapshot: id(8200), assertCurrentAndQuiescent: {})
        precondition(stale.messages.first(where: { $0.id == id(8021) })?.content != nil,
            "Fixture must reproduce the old unrepaired deleted payload")
        let local = HistoryNativeOwner(server: id(1), owner: author, directory: directory, remote: remote,
            lease: HistoryAccountLease(), sourceReady: {}, current: {}, updated: { _ in })
        let archive = try await local.export(snapshot: id(8200))
        let edited = archive.messages.first(where: { $0.id == id(8020) })!
        let deleted = archive.messages.first(where: { $0.id == id(8021) })!
        let hidden = archive.messages.first(where: { $0.id == id(8022) })!
        precondition(MessageContentCodec.decode(Data(edited.content!.utf8)).text == "Edited before backup" && edited.editedThrough == 10)
        precondition(deleted.content == nil && deleted.deletedThrough == 12)
        precondition(hidden.content == nil && hidden.hidden)
        precondition(archive.reactions.first(where: { $0.target == id(8020) })?.emoji == "❤️")
        precondition(archive.reactions.first(where: { $0.target == id(8020) })?.sequence == 11)
        let encoded = String(decoding: try archive.encoded(), as: UTF8.self)
        precondition(!encoded.contains("Old private text") && !encoded.contains("lifecycle_projection_v1") && !encoded.contains("upperRow"))
        let repeated = try await local.export(snapshot: id(8200))
        precondition(repeated == archive, "Export repair is idempotent and its rowid cursor stays device-local")
        print("PASS: automatic-style export without UI repairs old ACKed edit/delete/reaction, preserves hidden rows and excludes local repair cursors")
    }

    @MainActor static func verifyLegacyExportVerification(in root: URL, remote: HistoryBackupAPI) async throws {
        let directory = root.appendingPathComponent("legacy-export-verification")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let database = directory.appendingPathComponent("messages.sqlite3")
        let store = try MessageStore(databaseURL: database)
        let chat = id(8300), author = id(8301), client = id(8302), server = id(8303)
        try store.upsert(DecryptedChatMessage(clientMessageId: client, serverId: server, chatId: chat, senderId: author,
            sequence: 1, createdAt: Date(timeIntervalSince1970: 1), kind: "content", targetMessageId: nil, status: .sent,
            content: MessageContent(kind: "text", text: "Captured before verification")))
        var injected = false
        let local = HistoryNativeOwner(server: id(1), owner: author, directory: directory, remote: remote,
            lease: HistoryAccountLease(), sourceReady: {}, current: {}, updated: { _ in }, exportCalls: { archive in
                if !injected {
                    // An already captured archive must not be blessed while its
                    // native source still has a confirmed, unapplied deletion.
                    try oldACK(database: database, number: 8310, chat: chat, sender: author, sequence: 10, kind: "delete",
                        targetClient: client, targetServer: server, payload: MessageContentCodec.encodeDelete())
                    try store.saveChatValue(chatId: chat, name: "lifecycle_projection_v1", value: nil)
                    injected = true
                }
                return archive
            })
        let captured = try await local.export(snapshot: id(8320))
        precondition(captured.messages.first?.content != nil)
        do { try await local.verifyBackupReady(captured); preconditionFailure("Unrepaired confirmed deletion was accepted as an unchanged source") }
        catch HistoryNativeOwner.Failure.changed { }
        let repaired = try store.message(byServerId: server)
        precondition(repaired?.isDeletedForEveryone == true)
        print("PASS: final backup verification repairs legacy projections before checking captured-source equality")
    }

    @MainActor static func verifyLegacyExportRetirement(in root: URL, remote: HistoryBackupAPI) async throws {
        let directory = root.appendingPathComponent("legacy-export-retirement")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let database = directory.appendingPathComponent("messages.sqlite3")
        let store = try MessageStore(databaseURL: database)
        let chat = id(8400), author = id(8401), client = id(8402), server = id(8403)
        try store.upsert(DecryptedChatMessage(clientMessageId: client, serverId: server, chatId: chat, senderId: author,
            sequence: 1, createdAt: Date(timeIntervalSince1970: 1), kind: "content", targetMessageId: nil, status: .sent,
            content: MessageContent(kind: "text", text: "Retirement fixture")))
        for index in 0..<64 {
            try oldACK(database: database, number: 8500 + index, chat: chat, sender: author,
                sequence: Int64(100 + index), kind: "reaction", targetClient: client, targetServer: server,
                payload: MessageContentCodec.encodeReaction(index % 2 == 0 ? "👍" : "❤️"))
        }
        let lease = HistoryAccountLease()
        let local = HistoryNativeOwner(server: id(1), owner: author, directory: directory, remote: remote,
            lease: lease, sourceReady: {}, current: {
                if try store.chatValue(chatId: chat, name: "lifecycle_projection_v1") != nil { lease.invalidate() }
            }, updated: { _ in })
        do { _ = try await local.export(snapshot: id(8600)); preconditionFailure("Retired export returned private history") }
        catch is CancellationError { }
        await local.barrier()
        let checkpoint = try store.chatValue(chatId: chat, name: "lifecycle_projection_v1")!
        let progress = try JSONSerialization.jsonObject(with: Data(checkpoint.utf8)) as! [String: Any]
        precondition((progress["complete"] as? Bool) == false)
        let sequence = (progress["sequence"] as! NSNumber).int64Value
        precondition(sequence >= 100 && sequence <= 131, "Retirement must stop between bounded export-repair chunks")
        let reopened = HistoryNativeOwner(server: id(1), owner: author, directory: directory, remote: remote,
            lease: HistoryAccountLease(), sourceReady: {}, current: {}, updated: { _ in })
        let resumed = try await reopened.export(snapshot: id(8600))
        precondition(resumed.reactions.first?.sequence == 163 && resumed.reactions.first?.emoji == "❤️")
        print("PASS: retired export fails closed after a bounded checkpoint; a fresh owner resumes repair safely")
    }

    @MainActor static func verifyLegacyTextRestore(in root: URL, remote: HistoryBackupAPI) async throws {
        let directory = root.appendingPathComponent("legacy-text-v1")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var archive = try HistoryArchive.decode(Data(contentsOf: URL(fileURLWithPath:
            "shared/test-vectors/history-archive-v1.json")), server: id(1), owner: id(2))
        archive.clearedThrough = 1; archive.chatClears = [id(6): 3]; archive.deletedChats = [id(13)]
        archive.reactions = [HistoryArchiveReaction(target: id(5), user: id(7), sequence: 20, emoji: "❤️")]
        var unknown = archive.messages[0]; unknown.client = id(804); unknown.id = id(805); unknown.chat = id(806); unknown.sequence = 11
        archive.messages.append(unknown)
        let store = try MessageStore(databaseURL: directory.appendingPathComponent("messages.sqlite3"))
        try store.upsert(DecryptedChatMessage(clientMessageId: id(810), serverId: nil, chatId: id(6), senderId: id(2),
            sequence: nil, createdAt: Date(timeIntervalSince1970: 10), kind: "content", targetMessageId: nil, status: .pending,
            content: MessageContent(kind: "text", text: "Offline pending")))
        let pendingBefore = try store.message(byClientMessageId: id(810))
        let mediaFixture = try HistoryArchive.decode(Data(contentsOf: URL(fileURLWithPath:
            "shared/test-vectors/history-archive-v2-complete.json")), server: id(1), owner: id(2))
        let attachment = try JSONDecoder().decode(MessageContent.self, from: Data(mediaFixture.messages[0].content!.utf8))
        var failed = DecryptedChatMessage(clientMessageId: id(811), serverId: nil, chatId: id(6), senderId: id(2),
            sequence: nil, createdAt: Date(timeIntervalSince1970: 11), kind: "content", targetMessageId: nil, status: .failed, content: attachment)
        failed.sendAttempted = true; failed.payloadText = "frozen encrypted attachment metadata"; failed.pendingEditRevision = 3
        try store.upsert(failed)
        let failedBefore = try store.message(byClientMessageId: id(811))
        let chats = try ChatStore(databaseURL: directory.appendingPathComponent("chats.sqlite3"))
        let known = ChatSummary(chatId: id(6), chatType: "direct", otherUser: PublicUser(id: id(7), username: "known",
            displayName: "Known peer", bio: nil, avatarUrl: nil, profileVersion: 0, avatarVersion: 0, hasAvatar: false),
            createdAt: "1970-01-01T00:00:01Z", lastMessageAt: nil, otherMemberDeliveredSequence: 0, otherMemberReadSequence: 0, lastPreview: nil)
        try chats.upsertChats([known])
        var preparations = 0
        let local = HistoryNativeOwner(server: id(1), owner: id(2), directory: directory, remote: remote,
            lease: HistoryAccountLease(), sourceReady: {
                preparations += 1
                if preparations == 2 {
                    try store.upsert(DecryptedChatMessage(clientMessageId: id(820), serverId: id(821), chatId: id(6), senderId: id(7),
                        sequence: 30, createdAt: Date(timeIntervalSince1970: 20), kind: "content", targetMessageId: nil, status: .delivered,
                        content: MessageContent(kind: "text", text: "Concurrent arrival")))
                }
            }, current: {}, updated: { _ in })
        remote.deletions = [:]; remote.deletedCalls = []
        try await local.restoreVerified(archive)
        let rows = try store.messages(forChatId: id(6))
        precondition(rows.count == 4 && rows.contains(where: { $0.content.text == "Concurrent arrival" }))
        precondition(rows.contains(where: { $0.content.text == "Offline pending" && $0.status == .pending }))
        precondition(rows.contains(where: { $0.content.text == "Привет" }))
        let pendingAfter = try store.message(byClientMessageId: id(810)), failedAfter = try store.message(byClientMessageId: id(811))
        precondition(pendingAfter == pendingBefore && failedAfter == failedBefore,
            "Restore must preserve IDs, status, attachment references and frozen outbox state verbatim")
        let reaction = try store.reaction(targetMessageId: id(5), userId: id(7))
        precondition(reaction?.emoji == "❤️")
        let globalClear = try store.chatValue(chatId: "__server_history__", name: "cleared_through_sequence")
        let personalClear = try store.chatValue(chatId: id(6), name: "personal_clear")
        let deletedChat = try store.isChatDeleted(id(13))
        precondition(globalClear == "1" && personalClear == "3" && deletedChat)
        let restoredChats = try chats.loadChats()
        precondition(restoredChats.first(where: { $0.chatId == id(6) })?.otherUser?.id == known.otherUser?.id)
        precondition(restoredChats.first(where: { $0.chatId == id(806) })?.chatType == "group")
        // The regular authorized-chat refresh uses upsertChats. A neutral
        // legacy shell must accept its actual server type/peer on that path.
        let actual = ChatSummary(chatId: id(806), chatType: "direct", otherUser: known.otherUser,
            createdAt: known.createdAt, lastMessageAt: nil, otherMemberDeliveredSequence: 0, otherMemberReadSequence: 0, lastPreview: nil)
        try chats.upsertChats([actual])
        let refreshed = try chats.loadChats().first(where: { $0.chatId == id(806) })
        precondition(refreshed?.chatType == "direct" && refreshed?.otherUser?.id == known.otherUser?.id)
        let media = try HistoryArchive.decode(Data(contentsOf: URL(fileURLWithPath:
            "shared/test-vectors/history-archive-v2-complete.json")), server: id(1), owner: id(2)).withoutMedia()
        do { try await local.restoreVerified(media); preconditionFailure("Accepted legacy media without durable manifest") }
        catch HistoryNativeOwner.Failure.unavailableMedia { }
        print("PASS: native v1 text restore preserves reaction/clear/deletion policy, known chat, offline send and concurrent arrival; manifestless attachments fail explicitly")
    }

    @MainActor static func verifyImportAdmission(in root: URL, remote: HistoryBackupAPI, archive: HistoryArchive) async throws {
        for collision in [false, true] {
            let directory = root.appendingPathComponent(collision ? "pending-client-collision" : "pending-lifecycle")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let store = try MessageStore(databaseURL: directory.appendingPathComponent("messages.sqlite3"))
            let client = collision ? archive.messages[0].client : id(950)
            try store.upsert(DecryptedChatMessage(clientMessageId: client, serverId: nil, chatId: id(6), senderId: id(2),
                sequence: nil, createdAt: Date(timeIntervalSince1970: 20), kind: collision ? "content" : "edit",
                targetMessageId: collision ? nil : id(5), status: .pending, content: MessageContent(kind: "text", text: "Preserve my intent")))
            let before = try store.message(byClientMessageId: client)
            var callWrites = 0
            let owner = HistoryNativeOwner(server: id(1), owner: id(2), directory: directory, remote: remote,
                lease: HistoryAccountLease(), sourceReady: {}, current: {}, updated: { _ in }, restoreCalls: { _ in callWrites += 1 })
            do { try await owner.restoreVerified(archive); preconditionFailure("Accepted pending lifecycle or client-ID collision") }
            catch HistoryArchive.Failure.conflict { }
            let after = try store.message(byClientMessageId: client)
            let clear = try store.chatValue(chatId: "__server_history__", name: "cleared_through_sequence")
            precondition(before == after && clear == nil && callWrites == 0)
            precondition(!FileManager.default.fileExists(atPath: directory.appendingPathComponent("attachments.sqlite3").path))
            precondition(!FileManager.default.fileExists(atPath: directory.appendingPathComponent("history-restored").path))
        }
        print("PASS: pending lifecycle and same-client pending collision fail before call/cache writes; message and policy transaction rolls back")
    }

    @MainActor static func main() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("coco-native-owner-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let row = HistoryArchiveMessage(client: id(4), id: id(5), chat: id(6), sender: id(2), sequence: 10,
            createdAtMs: 1000, content: "{\"kind\":\"text\",\"text\":\"Recovered\"}")
        var archive = HistoryArchive(server: id(1), owner: id(2), snapshot: id(3), messages: [row])
        archive.format = 2; archive.media = []
        archive.chats = [HistoryArchiveChat(id: id(6), type: "saved", createdAtMs: 1000, peer: nil)]
        let info = HistoryBackupInfo(backupId: id(3), formatVersion: 2, sha256: String(repeating: "a", count: 64), bytes: 100, createdAt: "x", verifiedAt: "x")
        let remote = HistoryBackupAPI(HistoryBackupState(serverId: id(1), revision: 2, desiredEnabled: true, active: info, staged: nil))
        let lease = HistoryAccountLease()
        var admissions = 0
        let owner = HistoryNativeOwner(server: id(1), owner: id(2), directory: root, remote: remote, lease: lease,
            sourceReady: { admissions += 1 }, current: {}, updated: { _ in })
        // Remote replacement during media verification must precede all native writes.
        remote.changeOnSecond = true
        do { try await owner.restoreVerified(archive); fatalError("Accepted changed revision") } catch HistoryNativeOwner.Failure.changed {}
        precondition(!FileManager.default.fileExists(atPath: root.appendingPathComponent("history-restored").path))
        precondition(remote.restoredSnapshots.isEmpty)
        remote.changeOnSecond = false; remote.statusCalls = 0
        try await owner.restoreVerified(archive)
        precondition(remote.restoredSnapshots == [archive.snapshot])
        let messages = try MessageStore(databaseURL: root.appendingPathComponent("messages.sqlite3"))
        let restored = try messages.message(byClientMessageId: id(4))
        precondition(restored?.content.text == "Recovered")
        let chats = try ChatStore(databaseURL: root.appendingPathComponent("chats.sqlite3"))
        let visibleChats = try chats.loadChats().map(\.chatId)
        precondition(visibleChats == [id(6)])
        precondition(admissions >= 4)
        // Restart after native commit: durable journal replay is idempotent.
        try archive.encoded().write(to: root.appendingPathComponent("history-restore.json"), options: .atomic)
        try await owner.resumePendingRestore()
        precondition(!FileManager.default.fileExists(atPath: root.appendingPathComponent("history-restore.json").path))
        let restoredRows = try messages.messages(forChatId: id(6))
        precondition(restoredRows.count == 1)
        // Journal admission has the same snapshot/revision/account fences as
        // an ordinary explicit restore, before any message or call import.
        for changedDuringCheck in [false, true] {
            let pendingRoot = root.appendingPathComponent(changedDuringCheck ? "pending-race" : "pending-replaced")
            try FileManager.default.createDirectory(at: pendingRoot, withIntermediateDirectories: true)
            try archive.encoded().write(to: pendingRoot.appendingPathComponent("history-restore.json"), options: .atomic)
            let pendingRemote = HistoryBackupAPI(remote.state)
            if changedDuringCheck { pendingRemote.changeOnSecond = true }
            else {
                let replacement = HistoryBackupInfo(backupId: id(30), formatVersion: 2, sha256: info.sha256,
                    bytes: info.bytes, createdAt: "x", verifiedAt: "x")
                pendingRemote.state = HistoryBackupState(serverId: id(1), revision: 3, desiredEnabled: true, active: replacement, staged: nil)
            }
            var importedCallSets = 0
            let pendingOwner = HistoryNativeOwner(server: id(1), owner: id(2), directory: pendingRoot, remote: pendingRemote,
                lease: HistoryAccountLease(), sourceReady: {}, current: {}, updated: { _ in },
                restoreCalls: { _ in importedCallSets += 1 })
            do { try await pendingOwner.resumePendingRestore(); preconditionFailure("Accepted replaced or racing journal") }
            catch HistoryNativeOwner.Failure.changed { }
            precondition(!FileManager.default.fileExists(atPath: pendingRoot.appendingPathComponent("messages.sqlite3").path))
            precondition(importedCallSets == 0 && pendingRemote.restoredSnapshots.isEmpty)
        }
        // Explicit Restore resumes the admitted journal and remains idempotent.
        try archive.encoded().write(to: root.appendingPathComponent("history-restore.json"), options: .atomic)
        try await owner.restoreVerified(archive)
        precondition(!FileManager.default.fileExists(atPath: root.appendingPathComponent("history-restore.json").path))
        let explicitRetryRows = try messages.messages(forChatId: id(6))
        precondition(explicitRetryRows.count == 1)
        // Failed journal hydration may leave plaintext; cleanup preserves the
        // retry ciphertext while removing files not referenced by native rows.
        try archive.encoded().write(to: root.appendingPathComponent("history-restore.json"), options: .atomic)
        let spool = root.appendingPathComponent("history-media")
        let clear = root.appendingPathComponent("attachments")
        try FileManager.default.createDirectory(at: spool, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: clear, withIntermediateDirectories: true)
        let retainedCipher = spool.appendingPathComponent("retry.cipher")
        let orphan = clear.appendingPathComponent("history-failed-attempt.bin")
        try Data([1]).write(to: retainedCipher); try Data([2]).write(to: orphan)
        await owner.cleanTemporaryFiles()
        precondition(FileManager.default.fileExists(atPath: retainedCipher.path) && !FileManager.default.fileExists(atPath: orphan.path))
        try await owner.resumePendingRestore()
        print("PASS: explicit journal retry rejects replaced/racing snapshots before import and cleans orphan plaintext")
        // A call-store failure after the message transaction is recoverable,
        // but these independent stores are not one atomic database commit.
        let interruptedRoot = root.appendingPathComponent("interrupted-call-store")
        try FileManager.default.createDirectory(at: interruptedRoot, withIntermediateDirectories: true)
        var failCalls = true
        let interruptedOwner = HistoryNativeOwner(server: id(1), owner: id(2), directory: interruptedRoot, remote: remote,
            lease: HistoryAccountLease(), sourceReady: {}, current: {}, updated: { _ in }, restoreCalls: { _ in
                if failCalls { throw TestFailure.unexpectedMedia }
            })
        do { try await interruptedOwner.restoreVerified(archive); preconditionFailure("Accepted failed call-store write") }
        catch TestFailure.unexpectedMedia { }
        let interruptedStore = try MessageStore(databaseURL: interruptedRoot.appendingPathComponent("messages.sqlite3"))
        let committedBeforeCallFailure = try interruptedStore.messages(forChatId: id(6))
        precondition(committedBeforeCallFailure.count == 1)
        precondition(!FileManager.default.fileExists(atPath: interruptedRoot.appendingPathComponent("history-restored").path))
        precondition(FileManager.default.fileExists(atPath: interruptedRoot.appendingPathComponent("history-restore.json").path))
        failCalls = false
        try await interruptedOwner.restoreVerified(archive)
        let recoveredAfterCallFailure = try interruptedStore.messages(forChatId: id(6))
        precondition(recoveredAfterCallFailure.count == 1)
        precondition(!FileManager.default.fileExists(atPath: interruptedRoot.appendingPathComponent("history-restore.json").path))
        print("PASS: injected post-message call-store failure retains journal and explicit retry finishes once without duplicates")
        try messages.upsertReaction(targetMessageId: id(5), userId: id(9), emoji: "z", lastReactionSequence: 12)
        try messages.upsertReaction(targetMessageId: id(5), userId: id(8), emoji: "a", lastReactionSequence: 11)
        let exported = try await owner.export(snapshot: id(7))
        precondition(exported.reactions.map(\.user) == [id(8), id(9)], "Export reaction order must be canonical")
        for iteration in 0..<50 {
            let reopened = try MessageStore(databaseURL: root.appendingPathComponent("messages.sqlite3"))
            let repeated = try reopened.exportHistoryArchive(server: id(1), owner: id(2), snapshot: id(7),
                assertCurrentAndQuiescent: {})
            precondition(repeated == exported, "Fresh connection export changed at iteration \(iteration)")
        }
        print("PASS: 50 fresh-connection deterministic history exports")
        remote.complete = false
        do { _ = try await owner.prepareBackup(exported); fatalError("Accepted incomplete server history") } catch HistoryNativeOwner.Failure.incompleteSource {}
        remote.complete = true
        let prepared = try await owner.prepareBackup(exported)
        do { try await owner.verifyBackupReady(prepared) }
        catch { fatalError("Unchanged source verification failed: \(error)") }
        print("PASS: unchanged prepared source verification")
        remote.complete = false
        do { try await owner.verifyBackupReady(prepared); fatalError("Accepted newly incomplete source") } catch HistoryNativeOwner.Failure.incompleteSource {}
        remote.complete = true
        _ = try messages.applyEdit(targetServerId: id(5), newText: "Changed", sequence: 11)
        do { try await owner.verifyBackupReady(prepared); fatalError("Accepted changed native source") } catch HistoryNativeOwner.Failure.changed {}
        remote.deletions = [id(5): 20]
        try await owner.restoreVerified(archive)
        let deleted = try messages.message(byClientMessageId: id(4))
        precondition(deleted?.isDeletedForEveryone == true)
        // An older backup descriptor must not resurrect a locally deleted chat.
        _ = try messages.purgeDeletedChat(id(6))
        try chats.deleteChat(id(6))
        remote.deletions = [:]
        try await owner.restoreVerified(archive)
        let afterDeletedChatRestore = try chats.loadChats()
        let afterDeletedChatMessages = try messages.messages(forChatId: id(6))
        precondition(afterDeletedChatRestore.isEmpty && afterDeletedChatMessages.isEmpty)
        try archive.encoded().write(to: root.appendingPathComponent("history-restore.json"), options: .atomic)
        try await owner.resumePendingRestore()
        let afterDeletedChatReplay = try chats.loadChats()
        precondition(afterDeletedChatReplay.isEmpty)
        // Clears keep existing chat metadata, but must not synthesize a missing
        // shell when every archived message is suppressed by newer policy.
        for global in [false, true] {
            let clearedRoot = root.appendingPathComponent(global ? "global-clear" : "personal-clear")
            try FileManager.default.createDirectory(at: clearedRoot, withIntermediateDirectories: true)
            let clearedMessages = try MessageStore(databaseURL: clearedRoot.appendingPathComponent("messages.sqlite3"))
            if global { _ = try clearedMessages.clearServerHistory(throughSequence: 10) }
            else { try clearedMessages.clearPersonalChat(id(6), throughSequence: 10) }
            let clearedOwner = HistoryNativeOwner(server: id(1), owner: id(2), directory: clearedRoot, remote: remote,
                lease: HistoryAccountLease(), sourceReady: {}, current: {}, updated: { _ in })
            try await clearedOwner.restoreVerified(archive)
            let clearedChats = try ChatStore(databaseURL: clearedRoot.appendingPathComponent("chats.sqlite3")).loadChats()
            let clearedRows = try clearedMessages.messages(forChatId: id(6))
            precondition(clearedChats.isEmpty && clearedRows.isEmpty)
        }
        // Call-only histories are hydrated from the authenticated archive;
        // server tombstones are checked before their native import callback.
        var callArchive = archive; callArchive.messages = []; callArchive.reactions = []
        callArchive.calls = [HistoryArchiveCall(id: id(20), caller: id(2), callee: id(21), mediaKind: "audio",
            createdAtMs: 1000, acceptedAtMs: nil, connectedAtMs: nil, endedAtMs: 2000, endReason: "missed",
            chat: id(22), chatVisible: true, peer: HistoryArchivePeer(id: id(21), username: "peer", displayName: "Peer"))]
        callArchive.chats = [HistoryArchiveChat(id: id(22), type: "direct", createdAtMs: 1000, peer: callArchive.calls![0].peer)]
        let callRoot = root.appendingPathComponent("call-only")
        try FileManager.default.createDirectory(at: callRoot, withIntermediateDirectories: true)
        var importedCalls: [HistoryArchiveCall] = [], notified = Set<String>()
        let callOwner = HistoryNativeOwner(server: id(1), owner: id(2), directory: callRoot, remote: remote,
            lease: HistoryAccountLease(), sourceReady: {}, current: {}, updated: { notified = $0 },
            restoreCalls: { importedCalls = $0.calls ?? [] })
        remote.deletions = [:]; remote.deletedCalls = []
        try await callOwner.restoreVerified(callArchive)
        precondition(importedCalls.count == 1 && notified.contains(id(22)))
        let callChats = try ChatStore(databaseURL: callRoot.appendingPathComponent("chats.sqlite3")).loadChats()
        precondition(callChats.map(\.chatId) == [id(22)])
        let deletedCallRoot = root.appendingPathComponent("deleted-call-only")
        try FileManager.default.createDirectory(at: deletedCallRoot, withIntermediateDirectories: true)
        var deletedImported = [HistoryArchiveCall]()
        let deletedCallOwner = HistoryNativeOwner(server: id(1), owner: id(2), directory: deletedCallRoot, remote: remote,
            lease: HistoryAccountLease(), sourceReady: {}, current: {}, updated: { _ in },
            restoreCalls: { deletedImported = $0.calls ?? [] })
        remote.deletedCalls = [id(20)]
        try await deletedCallOwner.restoreVerified(callArchive)
        precondition(deletedImported.isEmpty)
        let deletedCallChats = try ChatStore(databaseURL: deletedCallRoot.appendingPathComponent("chats.sqlite3")).loadChats()
        precondition(deletedCallChats.isEmpty, "Deleted archived call cannot create an empty chat")
        let legacyCalls = try HistoryArchive.decode(Data(contentsOf: URL(fileURLWithPath:
            "shared/test-vectors/history-archive-v2-call-only-legacy.json")), server: id(1), owner: id(2))
        let legacyRoot = root.appendingPathComponent("legacy-call-only")
        try FileManager.default.createDirectory(at: legacyRoot, withIntermediateDirectories: true)
        var legacyImported: HistoryArchive?
        let legacyOwner = HistoryNativeOwner(server: id(1), owner: id(2), directory: legacyRoot, remote: remote,
            lease: HistoryAccountLease(), sourceReady: {}, current: {}, updated: { _ in },
            restoreCalls: { legacyImported = $0 })
        try await legacyOwner.restoreVerified(legacyCalls)
        let legacyChats = try ChatStore(databaseURL: legacyRoot.appendingPathComponent("chats.sqlite3")).loadChats()
        precondition(Set(legacyChats.map(\.chatId)) == [id(6), id(14)])
        precondition(legacyImported?.calls?.count == 3 && legacyImported?.callDeletedIds == legacyCalls.callDeletedIds)
        precondition(legacyImported?.callClearedBeforeMs == legacyCalls.callClearedBeforeMs && legacyImported?.callChatClears == legacyCalls.callChatClears)
        print("PASS: native legacy call-only restore imports three calls and creates both authenticated chat shells")
        print("PASS: call-only restore and server deletion admission before import")
        try await verifyLegacyTextRestore(in: root, remote: remote)
        try await verifyImportAdmission(in: root, remote: remote, archive: archive)
        try await verifyLegacyExportProjection(in: root, remote: remote)
        try await verifyLegacyExportVerification(in: root, remote: remote)
        try await verifyLegacyExportRetirement(in: root, remote: remote)
        lease.invalidate()
        do { try await owner.restoreVerified(archive); fatalError("Accepted retired account") } catch is CancellationError {}
        print("Native history owner contracts passed: remote revision, SQLite restore, chat visibility, journal replay, source changes, account retirement")
    }
}
