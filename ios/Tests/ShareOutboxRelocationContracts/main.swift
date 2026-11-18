import Foundation

// Bundle/UI localization is a seam; contracts compare the production message
// keys. Keep the executable Foundation/SQLite-only, without SwiftUI or UIKit.
enum UnyriL10n { static func string(_ key: String) -> String { key } }

// PRODUCTION_VISUAL
enum APIError: Error { case network(String), server(String, String), http(Int) }
enum AttachmentIntegrityError: Error { case fixture }

/// Native codecs/network are the seam. The real outbox must pass the relocated
/// URL to this uploader; testing only a path helper would miss that integration.
@MainActor final class SourceReader {
    let expected: URL
    let messageStore: MessageStore
    let store: AttachmentStore
    let cacheDirectory: URL
    let interrupt: Bool
    var calls = 0
    var uploadedKind: String?
    var uploadedFilename: String?
    var uploadedMime: String?
    var uploadedDuration: Int64?
    var verifyJournal = false
    init(expected: URL, store: MessageStore, attachments: AttachmentStore, interrupt: Bool) {
        self.expected = expected; messageStore = store; self.store = attachments; self.interrupt = interrupt
        cacheDirectory = expected.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("attachments")
    }
    enum Stage { case sent }
    var resumed = 0
    func requireSession() throws { try Task.checkCancellation() }
    func setUploadStage(_ id: String, _ stage: Stage) { resumed += 1 }
    func discardPreparedVideo(clientMessageId: String) {}
    func finishSendMeter(_ id: String) {}
    func uploadMultipart(record: AttachmentRecord, file: URL, onProgress: @escaping (Int) -> Void) async throws {
        preconditionFailure("Prepared upload must reuse its uploaded attachment record")
    }
    // PRODUCTION_ATTACHMENT_METHODS
    private func read(_ url: URL, chat: String, id: String, kind: String) throws -> AttachmentMetadata {
        precondition(url == expected, "Actual outbox preparation opened an old/foreign container")
        let raw = try messageStore.chatValue(chatId: chat, name: "upload:" + id)!
        let persisted = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as! [String: Any]
        precondition(URL(string: persisted["url"] as! String) == expected, "Repair was not durable before preparation")
        let bytes = try Data(contentsOf: url)
        precondition(bytes == Data([1, 2, 3]), "Old-container decoy or wrong account source was read")
        calls += 1
        if interrupt { throw CancellationError() }
        if verifyJournal {
            // Exercise the exact gate that rejected inline forwarding: both
            // the pending content row and upload journal must already exist.
            try store.upsert(AttachmentRecord(attachmentId: id, clientMessageId: id, chatId: chat, mediaType: kind,
                mimeType: "application/octet-stream", filename: nil, size: 3, width: nil, height: nil, durationMs: uploadedDuration,
                thumbnailBase64: nil, localPath: url.path, downloadState: "downloaded", uploadState: "uploaded",
                progress: 100, objectKey: "test/" + id, encryptedSize: 31, encryptionVersion: 1,
                keyBase64: nil, nonceBase64: nil, keysetBase64: nil, sha256Base64: "fixture"), requiresJournal: true)
            let persistedAttachment = try store.attachment(byId: id)
            precondition(persistedAttachment != nil, "Forward upload was rejected by the real attachment journal gate")
        }
        return AttachmentMetadata(attachmentId: id, mediaType: kind, objectKey: "test/" + id,
            size: 3, encryptedSize: 31, mimeType: kind == "image" ? "image/jpeg" : "application/octet-stream",
            encryption: EncryptionInfo(sha256: "fixture"), filename: url.lastPathComponent, image: nil, voice: nil, thumbnail: nil)
    }
    func prepareAndUploadImage(fileURL: URL, chatId: String, clientMessageId: String, highQuality: Bool = false) async throws -> AttachmentMetadata {
        uploadedKind = highQuality ? "image_high" : "image"
        return try read(fileURL, chat: chatId, id: clientMessageId, kind: "image")
    }
    func prepareAndUploadVideo(fileURL: URL, chatId: String, clientMessageId: String, highQuality: Bool = false) async throws -> AttachmentMetadata {
        uploadedKind = "video"
        return try read(fileURL, chat: chatId, id: clientMessageId, kind: "file")
    }
    func prepareAndUploadFile(fileURL: URL, chatId: String, clientMessageId: String, filename: String? = nil, mimeType: String? = nil) async throws -> AttachmentMetadata {
        uploadedKind = "file"; uploadedFilename = filename; uploadedMime = mimeType
        var result = try read(fileURL, chat: chatId, id: clientMessageId, kind: "file")
        result.filename = filename ?? result.filename; result.mimeType = mimeType ?? result.mimeType
        return result
    }
    func prepareAndUploadVoice(fileURL: URL, durationMs: Int64, chatId: String, clientMessageId: String) async throws -> AttachmentMetadata {
        uploadedKind = "voice"; uploadedDuration = durationMs
        var result = try read(fileURL, chat: chatId, id: clientMessageId, kind: "voice")
        result.voice = VoiceInfo(durationMs: durationMs)
        return result
    }
    func prepareAndUploadVideoNote(fileURL: URL, durationMs: Int64, chatId: String, clientMessageId: String) async throws -> AttachmentMetadata {
        uploadedKind = "video_note"; uploadedDuration = durationMs
        var result = try read(fileURL, chat: chatId, id: clientMessageId, kind: "file")
        result.mimeType = "video/mp4"
        return result.markedVideoNote(durationMs: durationMs)
    }
}

@MainActor final class OutboxReplay {
    struct Auth { let sessionGeneration: UInt64 = 1 }
    let auth = Auth()
    let generation: UInt64 = 1
    let store: MessageStore
    let attachmentManager: SourceReader?
    var activeMedia: [String: Task<AttachmentMetadata, Error>] = [:]
    var onMediaActivity: ((String, String) -> Void)?
    struct Changes { func send(_ value: Set<String>) {} }
    let changes = Changes()
    struct QueuedMessageCancelled: Error {}
    func requireSession() throws { try Task.checkCancellation() }
    var admissions: [String] = []
    func rememberPeer(_ peer: String?, chatId: String) {}
    func triggerFlush() {}
    func publishMediaAdmission(chatId: String) { admissions.append(chatId) }
    func makePending(clientMessageId: String, chatId: String, content: MessageContent, targetMessageId: String?) -> DecryptedChatMessage {
        DecryptedChatMessage(clientMessageId: clientMessageId, serverId: nil, chatId: chatId, senderId: "me",
            sequence: nil, createdAt: Date(), kind: "content", targetMessageId: targetMessageId, status: .pending, content: content)
    }
    init(store: MessageStore, reader: SourceReader?) { self.store = store; attachmentManager = reader }
    // PRODUCTION_OUTBOX
    func verifyLegacyJob() throws {
        // Literal pre-fix JSON: no new optional fields, including waveform.
        let raw = #"{"url":"file:///old/draft-media/source","kind":"file","caption":"old","duration":0}"#
        let job = try JSONDecoder().decode(MediaJob.self, from: Data(raw.utf8))
        precondition(job.forwarded == nil && job.filename == nil && job.mimeType == nil && job.waveform == nil)
        precondition(job.kind == "file" && job.caption == "old")
    }
    func seed(_ id: String, kind: String, url: URL) throws {
        let job = MediaJob(url: url, kind: kind, caption: "caption", duration: 42,
            albumId: "album", albumItemIndex: 0)
        let row = DecryptedChatMessage(clientMessageId: id, serverId: nil, chatId: "chat", senderId: "me",
            sequence: nil, createdAt: Date(), kind: "content", targetMessageId: nil,
            status: .pending, content: MessageContent(kind: "text", text: "waiting"))
        try store.upsert(row, mediaJob: String(decoding: JSONEncoder().encode(job), as: UTF8.self))
    }
    func replay(_ id: String) async throws -> DecryptedChatMessage {
        try await prepareMedia(store.message(byClientMessageId: id)!)
    }
    func finish(_ id: String) async throws {
        var row = try store.message(byClientMessageId: id)!
        row.status = .sent; try store.upsert(row)
        await releaseMediaSource(id)
    }
}

struct ChatSummary {
    struct Peer { let id: String }
    let chatId: String
    var otherUser: Peer? = nil
}
enum UnyriErrorText {
    static func message(_ error: Error) -> String? { error is CancellationError ? nil : "friendly error" }
}

/// Download/native file copying are seams; forwarding itself is extracted
/// verbatim so a regression back to inline upload cannot pass this contract.
@MainActor final class ForwardRepository {
    let generation: UInt64 = 1
    let auth = OutboxReplay.Auth()
    let store: MessageStore
    let outbox: OutboxReplay
    let sourceURL: URL
    var errorMessage: String?
    var downloadFails = false
    var stageFails = false
    var copies = 0
    init(store: MessageStore, outbox: OutboxReplay, sourceURL: URL) {
        self.store = store; self.outbox = outbox; self.sourceURL = sourceURL
    }
    func requireSession() throws { try Task.checkCancellation() }
    func reload() {}
    func reportError(_ text: String) { errorMessage = text }
    func downloadAttachment(attachmentId: String, origin: AttachmentDownloadOrigin) async -> URL? {
        precondition(origin == .forward)
        return downloadFails ? nil : sourceURL
    }
    func stageDraftFile(_ source: URL, isVideo: Bool) async throws -> URL {
        if stageFails { throw CocoaError(.fileWriteUnknown) }
        // The production staging helper must protect the downloaded source
        // while awaiting recovery/copying, including a concurrent cache sweep.
        let removed = try MediaCacheLease.removeIfUnleased(source, grace: 0)
        precondition(!removed, "Forward did not lease its downloaded source")
        let destination = store.draftFileDestination(source)
        try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: source, to: destination)
        copies += 1
        return destination
    }
    func discardUnownedDraftSource(_ url: URL) { try? store.removeUnownedDraftSource(url, attachmentReferenced: false) }
    // PRODUCTION_FORWARD
}

@main struct ShareOutboxRelocationContracts {
    @MainActor static func forwardContracts(_ root: URL) async throws {
        let fm = FileManager.default
        for kind in ["image_high", "voice", "video_note", "file-video", "file-document"] {
            let directory = root.appendingPathComponent("forward-" + kind)
            try fm.createDirectory(at: directory, withIntermediateDirectories: true)
            let cache = directory.appendingPathComponent("cached-source")
            try Data([1, 2, 3]).write(to: cache)
            let store = try MessageStore(databaseURL: directory.appendingPathComponent("messages.sqlite3"))
            let outbox = OutboxReplay(store: store, reader: nil)
            try outbox.verifyLegacyJob()
            let repository = ForwardRepository(store: store, outbox: outbox, sourceURL: cache)
            let video = kind == "video_note" || kind == "file-video"
            let mime = video ? "video/mp4" : (kind == "image_high" ? "image/jpeg" : (kind == "voice" ? "audio/mp4" : "application/pdf"))
            let name = "Оригинал." + (video ? "mp4" : (kind == "image_high" ? "jpg" : (kind == "voice" ? "m4a" : "pdf")))
            let metadata = AttachmentMetadata(attachmentId: "source", mediaType: kind == "image_high" ? "image" : (kind == "voice" ? "voice" : "file"),
                objectKey: "original", size: 3, encryptedSize: 31, mimeType: mime,
                encryption: EncryptionInfo(sha256: "fixture"), filename: name, image: nil,
                voice: kind == "voice" ? VoiceInfo(durationMs: 1234, waveform: "AQID") : nil, thumbnail: nil,
                video: video ? VideoInfo(durationMs: 2345, note: kind == "video_note") : nil)
            var source = outbox.makePending(clientMessageId: "source", chatId: "original-chat",
                content: MessageContent(kind: "attachment", text: "caption", attachment: metadata, albumId: "old-album", albumItemIndex: 2), targetMessageId: nil)
            source.status = .sent
            let destination = ChatSummary(chatId: "chat")
            // Comment is admitted first, as in the Forward sheet's send loop.
            precondition(outbox.enqueueContent(chatId: "chat", otherUserId: nil, content: MessageContent(kind: "text", text: "comment"),
                targetMessageId: nil, clientMessageId: "comment"))
            let accepted = await repository.forward(source, to: destination, albumId: "new-album", clientMessageId: "forward")
            precondition(accepted && repository.errorMessage == nil && outbox.admissions == ["chat"])
            let raw = try store.chatValue(chatId: "chat", name: "upload:forward")!
            let job = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as! [String: Any]
            let expectedKind = kind.hasPrefix("file-") ? "file" : kind
            precondition(job["kind"] as? String == expectedKind && job["forwarded"] as? Bool == true)
            precondition(job["filename"] as? String == metadata.filename && job["mimeType"] as? String == metadata.mimeType)
            precondition(job["caption"] as? String == "caption" && job["albumId"] as? String == "new-album" && job["albumItemIndex"] as? Int == 2)
            let staged = URL(string: job["url"] as! String)!
            precondition(staged != cache && store.draftSourceRelativePath(staged) != nil)
            let pending = try store.message(byClientMessageId: "forward")!
            precondition(pending.status == .pending && pending.content.forwarded && pending.content.attachment == nil)
            let comment = try store.message(byClientMessageId: "comment")!
            precondition(comment.createdAt <= pending.createdAt, "Forward moved ahead of its comment")
            // Same stable ID must return success without another download/copy.
            repository.downloadFails = true
            let repeated = await repository.forward(source, to: destination, albumId: "new-album", clientMessageId: "forward")
            precondition(repeated && repository.copies == 1 && outbox.admissions.count == 1)
            let failed = await repository.forward(source, to: destination, clientMessageId: "download-failure")
            precondition(!failed && repository.errorMessage == UnyriL10n.string("Could not download the attachment to forward. Check your connection and try again."))
            let rejected = try store.message(byClientMessageId: "download-failure")
            precondition(rejected == nil)
            repository.downloadFails = false; repository.stageFails = true
            let copyFailed = await repository.forward(source, to: destination, clientMessageId: "copy-failure")
            let rejectedCopy = try store.message(byClientMessageId: "copy-failure")
            precondition(!copyFailed && rejectedCopy == nil && repository.copies == 1)
            repository.stageFails = false
            try fm.removeItem(at: cache) // Cache eviction cannot remove the job's source.
            let attachments = try AttachmentStore(databaseURL: directory.appendingPathComponent("attachments.sqlite3"))
            let uploader = SourceReader(expected: staged, store: store, attachments: attachments, interrupt: false)
            uploader.verifyJournal = true
            let replay = OutboxReplay(store: store, reader: uploader)
            let prepared = try await replay.replay("forward")
            precondition(uploader.uploadedKind == expectedKind && prepared.content.forwarded)
            precondition(prepared.content.text == "caption" && prepared.content.albumId == "new-album" && prepared.content.albumItemIndex == 2)
            if expectedKind == "file" {
                precondition(uploader.uploadedFilename == metadata.filename && uploader.uploadedMime == metadata.mimeType)
            } else if kind == "voice" {
                precondition(uploader.uploadedDuration == 1234 && prepared.content.attachment?.voice?.waveform == "AQID")
                // Replay with persisted metadata still restores the original waveform.
                let again = try await replay.replay("forward")
                precondition(again.content.attachment?.voice?.waveform == "AQID" && uploader.calls == 1)
                // Crash after attachment upload but before saving job.metadata:
                // resumeUpload rebuilds metadata from the record without waveform.
                let uploadedRaw = try store.chatValue(chatId: "chat", name: "upload:forward")!
                var interrupted = try JSONSerialization.jsonObject(with: Data(uploadedRaw.utf8)) as! [String: Any]
                interrupted.removeValue(forKey: "metadata")
                try store.saveChatValue(chatId: "chat", name: "upload:forward",
                    value: String(decoding: JSONSerialization.data(withJSONObject: interrupted), as: UTF8.self))
                let resumed = try await replay.replay("forward")
                precondition(resumed.content.attachment?.voice?.waveform == "AQID" && uploader.calls == 1 && uploader.resumed == 1)
            } else if kind == "video_note" {
                precondition(uploader.uploadedDuration == 2345 && prepared.content.attachment?.isVideoNote == true)
            }
            // Text-only forwards retain enqueueContent and never stage a file.
            source.content = MessageContent(kind: "text", text: "text only")
            let text = await repository.forward(source, to: destination, clientMessageId: "text-forward")
            let textRow = try store.message(byClientMessageId: "text-forward")!
            precondition(text && textRow.content.forwarded && textRow.content.text == "text only" && repository.copies == 1)
        }
        print("PASS: production forward -> enqueueMedia -> journal-gated preparation: all media kinds, original name/MIME, forwarded, waveform, caption/album, comment order, stable retry, owned source, friendly download failure, legacy MediaJob JSON")
    }
    final class HeldMigration: @unchecked Sendable {
        let committing = AsyncStream<Void>.makeStream()
        let registered = AsyncStream<Void>.makeStream()
        let release = DispatchSemaphore(value: 0)
        func beforeCommit() throws {
            precondition(!Thread.isMainThread)
            committing.continuation.yield(())
            guard release.wait(timeout: .now() + 5) == .success else { throw CocoaError(.userCancelled) }
        }
    }
    @MainActor static func seed(_ container: URL, id: String, kind: String, parent: String) throws {
        let source = container.appendingPathComponent("draft-media/" + parent + "/source")
        try FileManager.default.createDirectory(at: source.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data([1, 2, 3]).write(to: source)
        let store = try MessageStore(databaseURL: container.appendingPathComponent("messages.sqlite3"))
        // The persisted production job has no relative field, as in 0.4.16.
        try OutboxReplay(store: store, reader: nil).seed(id, kind: kind, url: source)
    }
    @MainActor static func attempt(_ container: URL, id: String, parent: String, interrupt: Bool) async throws {
        let source = container.appendingPathComponent("draft-media/" + parent + "/source")
        let store = try MessageStore(databaseURL: container.appendingPathComponent("messages.sqlite3"))
        let attachments = try AttachmentStore(databaseURL: container.appendingPathComponent("attachments.sqlite3"))
        let reader = SourceReader(expected: source, store: store, attachments: attachments, interrupt: interrupt)
        let outbox = OutboxReplay(store: store, reader: reader)
        do {
            let row = try await outbox.replay(id)
            precondition(!interrupt && row.clientMessageId == id && row.status == .pending)
            precondition(row.content.text == "caption" && row.content.albumId == "album" && row.content.albumItemIndex == 0)
            precondition(row.content.attachment?.attachmentId == id, "Preparation lost durable attachment identity")
        } catch is CancellationError { precondition(interrupt, "Unexpected replay cancellation") }
        precondition(reader.calls == 1, "Real preparation never read the relocated source")
    }
    static func account(_ container: URL) -> URL {
        container.appendingPathComponent("Library/Application Support/COCO/same-server/11111111-1111-4111-8111-111111111111")
    }
    @MainActor static func seedVoice(_ container: URL, id: String, parent: String, preparedMetadata: Bool) async throws {
        let directory = account(container)
        let source = directory.appendingPathComponent("draft-media/" + parent + "/voice.m4a")
        try FileManager.default.createDirectory(at: source.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: directory.appendingPathComponent("attachments"), withIntermediateDirectories: true)
        try Data([1, 2, 3]).write(to: source)
        let messages = try MessageStore(databaseURL: directory.appendingPathComponent("messages.sqlite3"))
        let attachments = try AttachmentStore(databaseURL: directory.appendingPathComponent("attachments.sqlite3"))
        let reader = SourceReader(expected: source, store: messages, attachments: attachments, interrupt: false)
        let outbox = OutboxReplay(store: messages, reader: reader)
        try outbox.seed(id, kind: "voice", url: source)
        // The voice uploader keeps its ORIGINAL draft as the attachment's local
        // recording. Simulate its durable uploaded row, before/after job metadata.
        try attachments.upsert(AttachmentRecord(attachmentId: id, clientMessageId: id, chatId: "chat", mediaType: "voice",
            mimeType: "audio/mp4", filename: nil, size: 3, width: nil, height: nil, durationMs: 42, thumbnailBase64: nil,
            localPath: source.path, downloadState: "downloaded", uploadState: "uploaded", progress: 100,
            objectKey: "voice/" + id, encryptedSize: 31, encryptionVersion: 1, keyBase64: nil, nonceBase64: nil,
            keysetBase64: nil, sha256Base64: "voice-checksum"))
        if preparedMetadata { _ = try await outbox.replay(id) }
    }
    @MainActor static func replayAndCleanVoice(_ container: URL, oldSource: URL, id: String, parent: String, preparedMetadata: Bool) async throws {
        let directory = account(container)
        let source = directory.appendingPathComponent("draft-media/" + parent + "/voice.m4a").resolvingSymlinksInPath()
        let attachments = try AttachmentStore(databaseURL: directory.appendingPathComponent("attachments.sqlite3"))
        let messages = try MessageStore(databaseURL: directory.appendingPathComponent("messages.sqlite3"))
        if preparedMetadata {
            // Cold startup replays restore journals BEFORE the lazy draft pass.
            // It must preserve the relocated original without a readiness wait.
            let row = try messages.message(byClientMessageId: id)!
            try messages.saveChatValue(chatId: "chat", name: "restore_attachment:" + id,
                value: String(decoding: JSONEncoder().encode(row.content), as: UTF8.self))
            try HistoryAttachmentRestorer.resume(messages: messages, attachments: attachments,
                applyDeletedChat: { _ in }, removeCachedFile: { _ in }) {}
            let restored = try attachments.attachment(byId: id)
            precondition(restored?.localPath == source.path, "Synchronous restore journal dropped a relocated voice source")
        }
        let reader = SourceReader(expected: source, store: messages, attachments: attachments, interrupt: false)
        let outbox = OutboxReplay(store: messages, reader: reader)
        // Unprepared replay awaits normalization before opening its source;
        // opening general readers is no longer the gate.
        let replayed = try await outbox.replay(id)
        // Already-prepared metadata can send without touching plaintext. Its
        // source cleanup is the consumer that must normalize attachment owners.
        try await outbox.finish(id)
        let migrated = try attachments.attachment(byId: id)!
        precondition(migrated.localPath == source.path && migrated.objectKey == "voice/" + id && migrated.sha256Base64 == "voice-checksum")
        let ownsCurrent = try attachments.referencesLocalPath(source.path)
        let ownsOld = try attachments.referencesLocalPath(oldSource.path)
        precondition(ownsCurrent && ownsOld, "Attachment ownership must normalize both old and current URLs")
        // A late writer holding a pre-move row must not reintroduce the bad path.
        var stale = migrated; stale.localPath = oldSource.path
        try attachments.upsert(stale)
        let normalized = try attachments.attachment(byId: id)
        precondition(normalized?.localPath == source.path, "A stale upsert undid attachment relocation")
        precondition(replayed.content.attachment?.voice?.durationMs == 42 && reader.calls == 0)
        precondition(preparedMetadata || reader.resumed == 1, "Real resumeUpload did not reuse the uploaded voice record")
        let releasedJob = try messages.chatValue(chatId: "chat", name: "upload:" + id)
        precondition(releasedJob == nil, "Actual releaseMediaSource never retired the upload journal")
        let bytes = try Data(contentsOf: source)
        precondition(bytes == Data([1, 2, 3]), "Cleanup deleted the attachment-owned recording after relocation")
        try attachments.deleteRecords([id])
        try messages.removeUnownedDraftSource(source, attachmentReferenced: attachments.referencesLocalPath(source.path))
        precondition(!FileManager.default.fileExists(atPath: source.path), "Last attachment owner released but the recording leaked")
    }
    @MainActor static func main() async throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: root) }
        try await forwardContracts(root)
        for kind in ["image", "image_high", "video", "video_high", "file", "voice", "video_note"] {
            let id = UUID().uuidString.lowercased(), parent = "share-" + UUID().uuidString.lowercased()
            let old = root.appendingPathComponent(kind + "-old"), moved = root.appendingPathComponent(kind + "-moved")
            let again = root.appendingPathComponent(kind + "-again")
            try seed(old, id: id, kind: kind, parent: parent)
            try fm.moveItem(at: old, to: moved)
            // The obsolete path can still exist. It must never be used for replay.
            let decoy = old.appendingPathComponent("draft-media/" + parent + "/source")
            try fm.createDirectory(at: decoy.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data([9]).write(to: decoy)
            try await attempt(moved, id: id, parent: parent, interrupt: true)
            try fm.moveItem(at: moved, to: again)
            try await attempt(again, id: id, parent: parent, interrupt: false)
        }
        let application = root.appendingPathComponent("Containers/Data/Application")
        // Real prepared replay proceeds immediately; only its real source
        // cleanup waits, preserving voice ownership until the draft pass commits.
        do {
            let id = UUID().uuidString.lowercased(), parent = UUID().uuidString
            let old = application.appendingPathComponent(UUID().uuidString), moved = application.appendingPathComponent(UUID().uuidString)
            try await seedVoice(old, id: id, parent: parent, preparedMetadata: true)
            let oldSource = account(old).appendingPathComponent("draft-media/" + parent + "/voice.m4a").resolvingSymlinksInPath()
            try fm.moveItem(at: old, to: moved)
            let directory = account(moved)
            let source = directory.appendingPathComponent("draft-media/" + parent + "/voice.m4a")
            let held = HeldMigration()
            let attachments = try AttachmentStore(databaseURL: directory.appendingPathComponent("attachments.sqlite3"),
                draftMigrationHooks: .init(beforeCommit: { try held.beforeCommit() },
                    didRegisterWaiter: { held.registered.continuation.yield(()) }))
            let messages = try MessageStore(databaseURL: directory.appendingPathComponent("messages.sqlite3"))
            let uploader = SourceReader(expected: source, store: messages, attachments: attachments, interrupt: false)
            let outbox = OutboxReplay(store: messages, reader: uploader)
            _ = try await outbox.replay(id)
            let before = try attachments.attachment(byId: id)
            precondition(before?.localPath == oldSource.path, "Prepared metadata replay unexpectedly awaited draft migration")
            var cleaned = false
            let cleanup = Task { @MainActor in try await outbox.finish(id); cleaned = true }
            for await _ in held.committing.stream { break }
            var registrations = held.registered.stream.makeAsyncIterator()
            _ = await registrations.next()
            var recovered = false
            let recovery = messages.startDraftSourceRecovery(attachmentReferences: attachments.draftSourceReferences())
            let recoveryReader = Task { @MainActor in try await recovery.value; recovered = true }
            _ = await registrations.next()
            precondition(!cleaned && !recovered && fm.fileExists(atPath: source.path), "Source cleanup/recovery bypassed the draft gate")
            let stillOwned = try attachments.attachment(byId: id)
            precondition(stillOwned?.localPath == oldSource.path, "General reads waited for draft COMMIT")
            held.release.signal()
            try await cleanup.value
            try await recoveryReader.value
            let normalized = try attachments.attachment(byId: id)
            precondition(cleaned && normalized?.localPath == source.resolvingSymlinksInPath().path && fm.fileExists(atPath: source.path),
                "Real cleanup failed to retain the normalized attachment-owned recording")
        }
        for preparedMetadata in [false, true] {
            let id = UUID().uuidString.lowercased(), parent = UUID().uuidString
            let old = application.appendingPathComponent(UUID().uuidString), moved = application.appendingPathComponent(UUID().uuidString)
            let oldSource = account(old).appendingPathComponent("draft-media/" + parent + "/voice.m4a")
            try await seedVoice(old, id: id, parent: parent, preparedMetadata: preparedMetadata)
            try fm.moveItem(at: old, to: moved)
            try await replayAndCleanVoice(moved, oldSource: oldSource, id: id, parent: parent, preparedMetadata: preparedMetadata)
        }
        print("PASS: prepared voice relocation + real AttachmentStore, resumeUpload and outbox cleanup: metadata before/after preparation, normalized ownership, stale upsert, retain recording until last owner releases")
        print("PASS: production outbox prepareMedia + real SQLite: every media kind, two real container moves, legacy absolute jobs, old-path decoy, crash after durable repair, caption/album/client identity")
    }
}
