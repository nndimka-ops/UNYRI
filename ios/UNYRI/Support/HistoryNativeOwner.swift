import Foundation
#if canImport(CryptoKit)
import CryptoKit
#endif
#if canImport(SwiftUI)
import SwiftUI
#endif

/// Invalidated synchronously when the owning account is shut down. Background
/// file/SQLite work checks this lease, never a mutable global current account.
final class HistoryAccountLease: @unchecked Sendable {
    private let lock = NSLock()
    private var active = true
    private var operation = false
    func begin() throws {
        lock.lock(); defer { lock.unlock() }
        guard active else { throw CancellationError() }
        guard !operation else { throw HistoryBackupCoordinator.Failure.busy }; operation = true
    }
    func end() { lock.lock(); operation = false; lock.unlock() }
    func invalidate() { lock.lock(); active = false; lock.unlock() }
    func checkActive() throws {
        lock.lock(); let valid = active; lock.unlock()
        guard valid else { throw CancellationError() }
    }
    func check() throws { try checkActive(); try Task.checkCancellation() }
}

@MainActor
final class HistoryBackupAccess {
    let make: (String, HistoryBackupAPI) -> HistoryNativeOwner
    let lease: HistoryAccountLease
    var canUseCamera: () -> Bool = { true }
    private final class WeakOwner { weak var value: HistoryNativeOwner?; init(_ value: HistoryNativeOwner) { self.value = value } }
    private var owners: [WeakOwner] = []
    init(lease: HistoryAccountLease, make: @escaping (String, HistoryBackupAPI) -> HistoryNativeOwner) { self.lease = lease; self.make = make }
    func owner(server: String, remote: HistoryBackupAPI) -> HistoryNativeOwner {
        owners.removeAll { $0.value == nil }
        let owner = make(server, remote); owners.append(WeakOwner(owner)); return owner
    }
    func stop() { lease.invalidate(); for owner in owners { if let value = owner.value { Task { await value.cancelNetwork() } } } }
    func drain() async { for owner in owners { await owner.value?.barrier() } }
}
#if canImport(SwiftUI)
private struct HistoryBackupAccessKey: EnvironmentKey { static let defaultValue: HistoryBackupAccess? = nil }
extension EnvironmentValues {
    var historyBackupAccess: HistoryBackupAccess? {
        get { self[HistoryBackupAccessKey.self] }
        set { self[HistoryBackupAccessKey.self] = newValue }
    }
}
#endif

/// All archive encoding, media hashing/decryption and native database work runs
/// on this actor's executor using its own SQLite connections, never MainActor.
actor HistoryNativeOwner: HistoryBackupLocal {
    enum Failure: Error { case incompleteSource, unavailableMedia, changed, insufficientSpace }
    let server: String
    let owner: String
    let directory: URL
    let remote: HistoryBackupAPI
    let lease: HistoryAccountLease
    let sourceReady: @MainActor () async throws -> Void
    let current: @MainActor () throws -> Void
    let updated: @MainActor (Set<String>) async -> Void
    let exportCalls: @MainActor (HistoryArchive) async throws -> HistoryArchive
    let restoreCalls: @MainActor (HistoryArchive) async throws -> Void
    private var captured: HistoryArchive?
    private var verified: [String: URL] = [:]
    private var prepared = Set<String>()
    private var activeCalls = 0
    var spoolRoot: URL { directory.appendingPathComponent("history-media", isDirectory: true) }
    var clearRoot: URL { directory.appendingPathComponent("attachments", isDirectory: true) }
    init(server: String, owner: String, directory: URL, remote: HistoryBackupAPI, lease: HistoryAccountLease,
         sourceReady: @escaping @MainActor () async throws -> Void, current: @escaping @MainActor () throws -> Void,
         updated: @escaping @MainActor (Set<String>) async -> Void,
         exportCalls: @escaping @MainActor (HistoryArchive) async throws -> HistoryArchive = { $0 },
         restoreCalls: @escaping @MainActor (HistoryArchive) async throws -> Void = { _ in }) {
        self.server = server; self.owner = owner; self.directory = directory; self.remote = remote; self.lease = lease
        self.sourceReady = sourceReady; self.current = current; self.updated = updated
        self.exportCalls = exportCalls; self.restoreCalls = restoreCalls
    }
    func cancelNetwork() { remote.cancel() }
    func barrier() async {
        while activeCalls > 0 { try? await Task.sleep(nanoseconds: 10_000_000) }
    }
    func assertCurrent() async throws { try lease.check(); try await current(); try lease.check() }
    private func messages() throws -> MessageStore { try lease.check(); return try MessageStore(databaseURL: directory.appendingPathComponent("messages.sqlite3")) }

    /// Backups may run before any chat/list was opened. Repair confirmed old
    /// lifecycle rows on this actor's own connection before exporting content.
    /// Each transaction checks the lease for every event and immediately before
    /// COMMIT; yielding between chunks keeps retirement/current-account checks live.
    private func preparedStoreForExport() async throws -> MessageStore {
        try await assertCurrent()
        let store = try messages()
        let chats = try store.legacyLifecycleChatIDs()
        for chatId in chats {
            while true {
                try await assertCurrent()
                let complete = try store.repairLegacyLifecycleBatch(chatId: chatId, checkCancellation: lease.check)
                try lease.check()
                if complete { break }
                await Task.yield()
            }
        }
        try await assertCurrent()
        return store
    }

    func export(snapshot: String) async throws -> HistoryArchive {
        activeCalls += 1; defer { activeCalls -= 1 }
        try await assertCurrent()
        try await sourceReady(); try await assertCurrent()
        let store = try await preparedStoreForExport()
        let value = try await exportCalls(store.exportHistoryArchive(server: server, owner: owner, snapshot: snapshot, assertCurrentAndQuiescent: lease.check))
        try await assertCurrent()
        captured = value; verified.removeAll(); prepared.removeAll()
        return value
    }
    private func metadata(_ archive: HistoryArchive) throws -> [String: AttachmentMetadata] {
        var result: [String: AttachmentMetadata] = [:]
        for row in archive.messages {
            guard let payload = row.content else { continue }
            let content = try JSONDecoder().decode(MessageContent.self, from: Data(payload.utf8))
            if let value = content.attachment {
                try HistoryArchive.validateId(value.attachmentId)
                if let previous = result[value.attachmentId], previous != value { throw HistoryArchive.Failure.conflict }
                guard value.size >= 0, value.size <= HistoryMediaEntry.maxFileBytes else { throw HistoryArchive.Failure.invalidArchive }
                result[value.attachmentId] = value
            }
        }
        return result
    }
    private func requireSpace(_ values: [String: AttachmentMetadata]) throws {
        var needed: Int64 = 16 * 1024 * 1024
        for a in values.values {
            guard a.encryptedSize > 0, a.encryptedSize <= HistoryMediaEntry.maxFileBytes else { throw AttachmentIntegrityError() }
            needed += a.encryptedSize * 2 + a.size
        }
        let attributes = try FileManager.default.attributesOfFileSystem(forPath: directory.path)
        guard let free = attributes[.systemFreeSize] as? NSNumber, free.int64Value >= needed else { throw Failure.insufficientSpace }
    }
    private func plannedEntry(id: String, bytes: Int64, hash: String) throws -> HistoryMediaEntry {
        try lease.check()
        let path = directory.appendingPathComponent("history-upload-plan.json")
        var entries: [String: HistoryMediaEntry] = [:]
        if FileManager.default.fileExists(atPath: path.path) {
            let size = try FileManager.default.attributesOfItem(atPath: path.path)[.size] as? NSNumber
            guard let size, size.intValue <= HistoryArchive.maxBytes else { throw HistoryArchive.Failure.invalidArchive }
            entries = try JSONDecoder().decode([String: HistoryMediaEntry].self, from: Data(contentsOf: path))
            guard entries.count <= 50_000 else { throw HistoryArchive.Failure.invalidArchive }
        }
        if let old = entries[id], old.bytes == bytes, old.sha256 == hash { try old.validate(); return old }
        let value = HistoryMediaEntry(attachment: id, blob: UUID().uuidString.lowercased(), bytes: bytes, sha256: hash)
        try value.validate(); entries[id] = value
        guard entries.count <= 50_000 else { throw HistoryArchive.Failure.invalidArchive }
        let data = try JSONEncoder().encode(entries)
        guard data.count <= HistoryArchive.maxBytes else { throw HistoryArchive.Failure.invalidArchive }
        try lease.check(); try data.write(to: path, options: .atomic)
        return value
    }
    private func sealedIfPresent(_ entry: HistoryMediaEntry) async throws -> HistoryBackupAPI.MediaObject? {
        do { return try await remote.media(entry) }
        catch APIError.http(404) { return nil }
    }
    private func matches(_ object: HistoryBackupAPI.MediaObject, _ entry: HistoryMediaEntry) throws {
        guard object.blobId == entry.blob, object.bytes == entry.bytes, object.sha256 == entry.sha256 else { throw AttachmentIntegrityError() }
    }
    private func fetch(_ entry: HistoryMediaEntry, url: String) async throws -> URL {
        try await assertCurrent()
        let file = try await remote.receiveMedia(url: url, entry: entry, root: spoolRoot, check: lease.check)
        try await assertCurrent(); return file
    }
    private func decrypt(_ entry: HistoryMediaEntry, metadata a: AttachmentMetadata, file: URL) throws -> URL {
        try lease.check()
        guard let digest = Data(base64Encoded: a.encryption.sha256), digest.count == 32,
              digest.map({ String(format: "%02x", $0) }).joined() == entry.sha256,
              a.encryptedSize == entry.bytes else { throw AttachmentIntegrityError() }
        try FileManager.default.createDirectory(at: clearRoot, withIntermediateDirectories: true)
        let extensionByMime = ["image/jpeg":"jpg", "image/png":"png", "image/webp":"webp", "video/mp4":"mp4", "audio/mp4":"m4a", "audio/ogg":"ogg", "application/pdf":"pdf"]
        let output = clearRoot.appendingPathComponent("history-\(entry.blob)-\(UUID().uuidString)." + (extensionByMime[a.mimeType] ?? "bin"))
        do {
            if a.encryption.version == 1 {
                guard let k = a.encryption.key.flatMap({ Data(base64Encoded: $0) }), k.count == 32,
                      let n = a.encryption.nonce.flatMap({ Data(base64Encoded: $0) }), n.count == 12 else { throw AttachmentIntegrityError() }
                try AttachmentCrypto.decryptV1(ciphertextFile: file, plaintextFile: output,
                    key: SymmetricKey(data: k), nonce: AES.GCM.Nonce(data: n), expectedSha256: digest)
            } else if a.encryption.version == 2, let keyset = a.encryption.keyset.flatMap({ Data(base64Encoded: $0) }) {
                try AttachmentCrypto.decryptV2(ciphertextFile: file, plaintextFile: output, keyset: keyset, expectedSha256: digest)
            } else { throw AttachmentIntegrityError() }
            try lease.check()
            let size = try FileManager.default.attributesOfItem(atPath: output.path)[.size] as? NSNumber
            guard size?.int64Value == a.size else { throw AttachmentIntegrityError() }
            return output
        } catch { try? FileManager.default.removeItem(at: output); throw error }
    }
    func prepareBackup(_ input: HistoryArchive) async throws -> HistoryArchive {
        activeCalls += 1; defer { activeCalls -= 1 }
        try await assertCurrent()
        guard try await remote.sourceComplete(input.messages.map(\.id), calls: (input.calls ?? []).map(\.id)) else { throw Failure.incompleteSource }
        let values = try metadata(input)
        try requireSpace(values)
        let previous = Dictionary(uniqueKeysWithValues: (input.media ?? []).map { ($0.attachment, $0) })
        let status = try await remote.status()
        guard status.serverId == server else { throw HistoryBackupCoordinator.Failure.changed }
        var entries: [HistoryMediaEntry] = []
        for id in values.keys.sorted() {
            try await assertCurrent()
            let a = values[id]!
            guard let digest = Data(base64Encoded: a.encryption.sha256), digest.count == 32 else { throw AttachmentIntegrityError() }
            let hash = digest.map { String(format: "%02x", $0) }.joined()
            let entry: HistoryMediaEntry
            if let old = previous[id], old.bytes == a.encryptedSize, old.sha256 == hash {
                entry = old
                let object = try await remote.media(entry); try matches(object, entry)
                guard let url = object.downloadUrl else { throw Failure.unavailableMedia }
                let file = try await fetch(entry, url: url)
                let plain = try decrypt(entry, metadata: a, file: file)
                try? FileManager.default.removeItem(at: plain)
                verified[id] = file
            } else {
                entry = try plannedEntry(id: id, bytes: a.encryptedSize, hash: hash)
                try entry.validate()
                if let sealed = try await sealedIfPresent(entry) {
                    try matches(sealed, entry)
                    guard let download = sealed.downloadUrl else { throw Failure.unavailableMedia }
                    let file = try await fetch(entry, url: download)
                    let plain = try decrypt(entry, metadata: a, file: file)
                    try? FileManager.default.removeItem(at: plain)
                    verified[id] = file
                    prepared.insert(id); entries.append(entry)
                    continue
                }
                // Never reconstruct ciphertext using an old key/nonce. Either a
                // live encrypted object or a prior authenticated copy must exist.
                let source: String
                do { source = try await remote.liveMediaURL(id) } catch APIError.http(404) { throw Failure.unavailableMedia }
                let file = try await fetch(entry, url: source)
                let plain = try decrypt(entry, metadata: a, file: file)
                try? FileManager.default.removeItem(at: plain)
                let start = try await remote.startMedia(entry, revision: status.revision); try matches(start, entry)
                guard let upload = start.uploadUrl else { throw Failure.unavailableMedia }
                try await remote.uploadMedia(url: upload, file: file, bytes: entry.bytes)
                let sealed = try await remote.sealMedia(entry, revision: status.revision); try matches(sealed, entry)
                guard let download = sealed.downloadUrl else { throw Failure.unavailableMedia }
                // Read-back uses a distinct spool so cached source cannot mask a
                // corrupt/missing immutable server copy.
                let readback = spoolRoot.appendingPathComponent("readback", isDirectory: true)
                let checked = try await remote.receiveMedia(url: download, entry: entry, root: readback, check: lease.check)
                try HistoryMediaSpool(root: readback).verify(checked, entry: entry, assertCurrent: lease.check)
                verified[id] = file
            }
            prepared.insert(id); entries.append(entry)
        }
        var result = input; result.format = 2; result.media = entries
        let chats = try ChatStore(databaseURL: directory.appendingPathComponent("chats.sqlite3")).loadChats()
        var descriptors = Dictionary(uniqueKeysWithValues: (input.chats ?? []).map { ($0.id, $0) })
        let required = Set(result.messages.map(\.chat)).union((result.calls ?? []).filter(\.chatVisible).compactMap(\.chat))
        for chat in chats where required.contains(chat.chatId) {
            guard let date = ISO8601Cocoa.parse(chat.createdAt) else { throw HistoryArchive.Failure.invalidArchive }
            descriptors[chat.chatId] = HistoryArchiveChat(id: chat.chatId, type: chat.chatType,
                createdAtMs: Int64(date.timeIntervalSince1970 * 1000), peer: chat.otherUser.map {
                    HistoryArchivePeer(id: $0.id, username: $0.username, displayName: $0.displayName)
                })
        }
        result.chats = descriptors.values.filter { required.contains($0.id) }.sorted { $0.id < $1.id }
        try result.validate(server: server, owner: owner)
        try result.validateBackupChatDescriptors()
        return result
    }
    func verifyBackupReady(_ archive: HistoryArchive) async throws {
        activeCalls += 1; defer { activeCalls -= 1 }
        try await assertCurrent()
        guard try await remote.sourceComplete(archive.messages.map(\.id), calls: (archive.calls ?? []).map(\.id)) else { throw Failure.incompleteSource }
        guard let captured else { throw Failure.incompleteSource }
        let store = try await preparedStoreForExport()
        let latest = try await exportCalls(store.exportHistoryArchive(server: server, owner: owner, snapshot: captured.snapshot, assertCurrentAndQuiescent: lease.check))
        try await assertCurrent()
        guard latest == captured else { throw Failure.changed }
        let values = try metadata(archive)
        _ = try validateHistoryMedia(archive.media ?? [], required: Set(values.keys), byteLimit: Int64.max)
        for entry in archive.media ?? [] {
            guard prepared.contains(entry.attachment), let file = verified[entry.attachment] else { throw Failure.unavailableMedia }
            try HistoryMediaSpool(root: spoolRoot).verify(file, entry: entry, assertCurrent: lease.check)
        }
    }
    private func serverProjection(_ archive: HistoryArchive) async throws -> HistoryArchive {
        let ids = archive.messages.map(\.id)
        var deleted: [String: Int64] = [:]
        for start in stride(from: 0, to: ids.count, by: 500) {
            try await assertCurrent()
            let page = try await remote.serverDeletions(Array(ids[start..<min(start + 500, ids.count)]))
            deleted.merge(page, uniquingKeysWith: max)
        }
        var projected = try archive.applyingServerDeletions(deleted)
        let callIds = (archive.calls ?? []).map(\.id)
        var deletedCalls = archive.callDeletedIds ?? []
        for start in stride(from: 0, to: callIds.count, by: 500) {
            try await assertCurrent()
            deletedCalls.formUnion(try await remote.serverCallDeletions(Array(callIds[start..<min(start + 500, callIds.count)])))
        }
        projected.calls = projected.calls?.filter { !deletedCalls.contains($0.id) }
        if projected.calls?.isEmpty == true { projected.calls = nil }
        projected.callDeletedIds = deletedCalls.isEmpty ? nil : deletedCalls
        try projected.validate(server: server, owner: owner)
        return projected
    }
    private func restorableArchive(_ input: HistoryArchive) throws -> HistoryArchive {
        if input.format == 1 {
            guard try input.mediaIdentities().isEmpty else { throw Failure.unavailableMedia }
            let required = Set(input.messages.map(\.chat))
            let known = try ChatStore(databaseURL: directory.appendingPathComponent("chats.sqlite3")).loadChats()
                .filter { required.contains($0.chatId) }.map { chat -> HistoryArchiveChat in
                    guard let date = ISO8601Cocoa.parse(chat.createdAt) else { throw HistoryArchive.Failure.invalidArchive }
                    return HistoryArchiveChat(id: chat.chatId, type: chat.chatType,
                        createdAtMs: Int64(date.timeIntervalSince1970 * 1000), peer: chat.otherUser.map {
                            HistoryArchivePeer(id: $0.id, username: $0.username, displayName: $0.displayName)
                        })
                }
            return try input.restoringLegacyTextHistory(chats: known)
        }
        return try input.restoringCallChatDescriptors()
    }
    func restoreVerified(_ input: HistoryArchive) async throws {
        activeCalls += 1; defer { activeCalls -= 1 }
        try await assertCurrent()
        try input.validate(server: server, owner: owner)
        // Refresh clear/deletion policy and current chat metadata before
        // normalizing older archives. This never requires readable old text.
        try await sourceReady()
        try await assertCurrent()
        var archive = try restorableArchive(input)
        try archive.validateCompleteMedia()
        // Only an explicit Restore/Continue enters this method. Reuse a
        // journal for this verified snapshot; opening settings never imports it.
        if try await resumePendingRestore(snapshot: archive.snapshot) { return }
        archive = try await serverProjection(archive)
        let initial = try await remote.status()
        guard initial.serverId == server, initial.active?.backupId == archive.snapshot else { throw Failure.changed }
        let values = try metadata(archive)
        try requireSpace(values)
        _ = try validateHistoryMedia(archive.media ?? [], required: Set(values.keys), byteLimit: Int64.max)
        var plaintext: [String: URL] = [:]
        var journalled = false
        defer {
            if !journalled { for file in plaintext.values { try? FileManager.default.removeItem(at: file) } }
        }
        for entry in archive.media ?? [] {
            let object = try await remote.media(entry); try matches(object, entry)
            guard let url = object.downloadUrl, let a = values[entry.attachment] else { throw Failure.unavailableMedia }
            let file = try await fetch(entry, url: url)
            plaintext[entry.attachment] = try decrypt(entry, metadata: a, file: file)
        }
        try await assertCurrent()
        try await sourceReady()
        try await assertCurrent()
        archive = try await serverProjection(archive)
        let latest = try await remote.status()
        guard latest.serverId == server, latest.revision == initial.revision, latest.active == initial.active else { throw Failure.changed }
        // The authenticated manifest is journalled before import; a killed app
        // can finish cache hydration on the next explicit Restore attempt.
        let journal = directory.appendingPathComponent("history-restore.json")
        try archive.encoded().write(to: journal, options: .atomic)
        journalled = true
        try await applyRestore(archive, plaintext: plaintext)
    }
    private func applyRestore(_ archive: HistoryArchive, plaintext: [String: URL]) async throws {
        let values = try metadata(archive)
        let store = try messages()
        let journal = directory.appendingPathComponent("history-restore.json")
        let touched = try store.importHistoryArchive(archive, server: server, owner: owner, assertCurrentAndQuiescent: lease.check)
        try await restoreCalls(archive); try await assertCurrent()
        // The local call importer may apply newer offline deletions/clears.
        // Use its admitted rows, not the old snapshot, when creating chat shells.
        let admitted = try await exportCalls(archive); try await assertCurrent()
        let attachments = try AttachmentStore(databaseURL: directory.appendingPathComponent("attachments.sqlite3"))
        let chats = try ChatStore(databaseURL: directory.appendingPathComponent("chats.sqlite3"))
        try HistoryAttachmentRestorer.resume(messages: store, attachments: attachments, applyDeletedChat: chats.deleteChat,
            removeCachedFile: { path in
                let file = URL(fileURLWithPath: path).standardizedFileURL.resolvingSymlinksInPath()
                guard file.path.hasPrefix(self.clearRoot.standardizedFileURL.path + "/") else { return }
                if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
            }, assertCurrent: lease.check)
        let known = Set(try chats.loadChats().map(\.chatId))
        for descriptor in archive.chats ?? [] where !known.contains(descriptor.id) && !archive.deletedChats.contains(descriptor.id) {
            try lease.check()
            // Import merges newer local deletion policy. The old descriptor
            // must not recreate a chat shell that the merged store suppresses.
            guard try !store.isChatDeleted(descriptor.id) else { continue }
            let hasCall = (admitted.calls ?? []).contains { $0.chat == descriptor.id && $0.chatVisible }
            guard try !store.messages(forChatId: descriptor.id).isEmpty || hasCall else { continue }
            let peer = descriptor.peer.map { PublicUser(id: $0.id, username: $0.username, displayName: $0.displayName,
                bio: nil, avatarUrl: nil, profileVersion: 0, avatarVersion: 0, hasAvatar: false) }
            let date = ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: Double(descriptor.createdAtMs) / 1000))
            try chats.upsertChats([ChatSummary(chatId: descriptor.id, chatType: descriptor.type, otherUser: peer,
                createdAt: date, lastMessageAt: nil, otherMemberDeliveredSequence: 0, otherMemberReadSequence: 0, lastPreview: nil)])
        }
        for (id, file) in plaintext {
            try lease.check()
            guard let row = try attachments.attachment(byId: id), let a = values[id],
                  row.sha256Base64 == a.encryption.sha256, row.encryptedSize == a.encryptedSize,
                  let message = try store.message(byClientMessageId: row.clientMessageId),
                  !message.hiddenLocally, !message.isDeletedForEveryone else { continue }
            try attachments.markDownloaded(attachmentId: id, localPath: file.path)
        }
        try lease.check()
        try Data("verified".utf8).write(to: directory.appendingPathComponent("history-restored"), options: .atomic)
        try await remote.restoreComplete(archive.snapshot)
        try await assertCurrent()
        try FileManager.default.removeItem(at: journal)
        try await assertCurrent(); await updated(touched.union((archive.calls ?? []).compactMap(\.chat)))
    }
    /// An interrupted native commit is replayed from the already authenticated
    /// local manifest. Ciphertext is rehashed and AEAD verified before hydration.
    @discardableResult
    func resumePendingRestore(snapshot: String? = nil) async throws -> Bool {
        activeCalls += 1; defer { activeCalls -= 1 }
        try await assertCurrent()
        let journal = directory.appendingPathComponent("history-restore.json")
        guard FileManager.default.fileExists(atPath: journal.path) else { return false }
        let size = try FileManager.default.attributesOfItem(atPath: journal.path)[.size] as? NSNumber
        guard let size, size.intValue <= HistoryArchive.maxBytes else { throw HistoryArchive.Failure.invalidArchive }
        var archive = try HistoryArchive.decode(Data(contentsOf: journal), server: server, owner: owner)
        if let snapshot, archive.snapshot != snapshot { return false }
        let initial = try await remote.status()
        try await assertCurrent()
        guard initial.serverId == server, initial.active?.backupId == archive.snapshot else { throw Failure.changed }
        try await sourceReady()
        try await assertCurrent()
        archive = try restorableArchive(archive)
        try archive.validateCompleteMedia()
        archive = try await serverProjection(archive)
        let values = try metadata(archive)
        _ = try validateHistoryMedia(archive.media ?? [], required: Set(values.keys), byteLimit: Int64.max)
        var plaintext: [String: URL] = [:]
        for entry in archive.media ?? [] {
            guard let a = values[entry.attachment] else { throw AttachmentIntegrityError() }
            let file = spoolRoot.appendingPathComponent("\(entry.blob)-\(entry.sha256).cipher")
            try HistoryMediaSpool(root: spoolRoot).verify(file, entry: entry, assertCurrent: lease.check)
            plaintext[entry.attachment] = try decrypt(entry, metadata: a, file: file)
        }
        try await sourceReady()
        try await assertCurrent()
        archive = try await serverProjection(archive)
        let latest = try await remote.status()
        try await assertCurrent()
        guard latest.serverId == server, latest.revision == initial.revision, latest.active == initial.active else { throw Failure.changed }
        try await applyRestore(archive, plaintext: plaintext)
        return true
    }
    func cleanTemporaryFiles() {
        guard (try? lease.checkActive()) != nil else { return }
        // A journal needs ciphertext for retry, but never an unreferenced
        // plaintext from a failed hydration attempt. Keep only native caches.
        if !FileManager.default.fileExists(atPath: directory.appendingPathComponent("history-restore.json").path) {
            try? FileManager.default.removeItem(at: spoolRoot)
        }
        guard let attachments = try? AttachmentStore(databaseURL: directory.appendingPathComponent("attachments.sqlite3")),
              let rows = try? attachments.allAttachments(),
              let files = try? FileManager.default.contentsOfDirectory(at: clearRoot, includingPropertiesForKeys: nil) else { return }
        let referenced = Set(rows.compactMap(\.localPath))
        // Each generated path is unique. Preserve everything referenced by native rows.
        for file in files where file.lastPathComponent.hasPrefix("history-") {
            if !referenced.contains(file.path) {
                try? FileManager.default.removeItem(at: file)
            }
        }
    }

}
