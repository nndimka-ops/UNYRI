import Foundation
import SQLite3

enum APIError: Error { case missingSession }
enum UnyriErrorText { static func message(_ error: Error) -> String? { error is CancellationError ? nil : String(describing: error) } }
enum SignalError: Error { case untrustedIdentity(String), replay }
@MainActor enum UnyriDiag {
    static func decryptFailed(identityChanged: Bool) { preconditionFailure("A consumed packet was decrypted again") }
}
struct EventProbe<Value> { func send(_ value: Value) {} }

@MainActor final class ConsumedSignal {
    let plaintext: Data
    var decryptions = 0
    var afterDecrypt: (() throws -> Void)?
    var finishedReceiveIDs: [String] = []
    let consumed = AsyncStream<Void>.makeStream()
    init(_ content: MessageContent) throws { plaintext = try JSONEncoder().encode(content) }
    func resolveSignalDeviceId(senderId: String, senderDeviceId: String) async throws -> UInt32 { 1 }
    func receiveID(chatID: String, sequence: Int64, context: [String]) throws -> String { try JSONEncoder().encode(context).base64EncodedString() }
    func recoveredReceive(id: String) async throws -> Data? { nil }
    func finishReceive(id: String) async throws { finishedReceiveIDs.append(id) }
    func decryptReceived(ciphertextBase64: String, messageType: Int, senderUserId: String, senderSignalDeviceId: UInt32, receiveID: String? = nil, receiveMetadata: Data = Data()) async throws -> Data {
        guard decryptions == 0 else { throw SignalError.replay }
        decryptions += 1; consumed.continuation.yield(())
        try afterDecrypt?()
        return plaintext
    }
}

@MainActor final class Ingestion {
    struct Auth { let localSecurity: LocalSecurityDecisions? }
    let auth: Auth
    let store: MessageStore
    let attachmentStore: AttachmentStore
    let sessionManager: ConsumedSignal
    let contentDeletions = EventProbe<(chatId: String, targetServerId: String)>()
    var authorizeIncoming: ((MessageDto) async throws -> Void)? = { _ in }
    var onDirectFileOffer: ((String, DirectFileOffer) -> Void)?
    init(_ store: MessageStore, _ attachments: AttachmentStore, _ signal: ConsumedSignal) {
        var decisions: Data?
        auth = Auth(localSecurity: LocalSecurityDecisions(origin: "https://fixture.invalid", userID: "owner",
            read: { decisions }, write: { decisions = $0 }, remove: { decisions = nil }))
        self.store = store; attachmentStore = attachments; sessionManager = signal
        attachmentStore.startFenceReclamation()
    }
    func requireSession() throws { try Task.checkCancellation() }
    // PRODUCTION_INGESTION
    func receive(_ dto: MessageDto, expectStored: Bool = true) async throws {
        let result = try await ingestOne(dto, token: "fixture", myUserId: "owner")
        precondition(expectStored ? result.finalStatus == .delivered : result.finalStatus == nil)
    }
    func receiveExcluded(_ dto: MessageDto) async throws {
        let result = try await ingestOne(dto, token: "fixture", myUserId: "owner")
        precondition(!result.changed && result.finalStatus == nil && !result.allowsDeliveryReceipt,
            "Security exclusion stored content or permitted a delivery receipt")
    }
}

@MainActor enum VoiceTranscriber {
    static func forget(_ ids: [String]) {}
    static func forgetForDeletion(_ ids: [String]) throws {}
}
@MainActor final class PurgeDownloads { func cancelAndWait(_ ids: [String]) async {} }
@MainActor final class PurgeAdapter {
    let store: AttachmentStore
    private var cacheBoundary: AttachmentCacheBoundary { AttachmentCacheBoundary(root: cacheDirectory) }
    let cacheDirectory: URL
    let downloads = PurgeDownloads()
    init(_ store: AttachmentStore, directory: URL) { self.store = store; cacheDirectory = directory.appendingPathComponent("attachments") }
    func requireSession() throws { try Task.checkCancellation() }
    func finishSessionCacheWrites() async {}
    func clearUploadMeter(_ id: String) {}
    // PRODUCTION_ATTACHMENT_PURGES
}
@MainActor final class DeletionDirectFiles {
    func offerMessageRemoved(_ id: String) {}
    func chatRemoved(_ id: String) {}
}
@MainActor final class DeletionOutbox {
    func cancelMedia(_ id: String) {}
    func cancelDeletedChat(_ chat: String) async throws {}
}
/// Presentation/outbox/download seams are inert; the actual repository and
/// manager purge callers run against the same real stores as ingestion.
@MainActor final class JournalCleanupProbe { func discardReceiveJournals(chatID: String, through: Int64? = nil) async throws {} }
@MainActor final class DeletionCallers {
    let ingestor = JournalCleanupProbe()
    let store: MessageStore
    let attachmentManager: PurgeAdapter
    let outbox = DeletionOutbox()
    var directFiles: DeletionDirectFiles?
    var pendingWorkspace: [String: [String: String]] = [:], draftSnapshot: [String: [String: String]] = [:]
    var activeDraftWrites: [UUID: Task<Void, Never>] = [:]
    var scrollBookmarks: [String: String] = [:], visibleReadSequences: [String: Int64] = [:]
    var currentChatId: String?, visibleChatId: String?, serverPagingChatId: String?, loadedHistoryChatId: String?
    var messages: [DecryptedChatMessage] = [], failedActions: [String] = []
    var scheduledDates: [String: Date] = [:], cachedAttachmentPaths: [String: String] = [:], reactionsByMessage: [String: String] = [:]
    var historyRequest = 0, isLoading = false
    var attachmentCacheRevision: UInt64 = 0
    init(_ store: MessageStore, _ attachments: AttachmentStore, directory: URL) {
        self.store = store; attachmentManager = PurgeAdapter(attachments, directory: directory)
    }
    func requireSession() throws { try Task.checkCancellation() }
    func discardUnownedDraftSource(_ url: URL) {}
    func invalidatePinnedPreviews() {}
    func reload() {}
    // PRODUCTION_DELETION_CALLERS
}

final class HeldWrite: @unchecked Sendable {
    let began = AsyncStream<Void>.makeStream()
    private let resume = AsyncStream<Void>.makeStream()
    func pause() async { began.continuation.yield(()); for await _ in resume.stream {} }
    func release() { resume.continuation.finish() }
}

@MainActor final class StagingAdapter {
    let attachments: AttachmentStore
    var stages = 0
    init(_ attachments: AttachmentStore) { self.attachments = attachments }
    func stageDraftFile(_ source: URL, destination: URL, isVideo: Bool, coordinated: Bool, maximumBytes: Int64?) async throws -> URL {
        stages += 1; return destination
    }
    func waitForDraftReferences(for urls: [URL]) async throws { try await attachments.waitForDraftReferences() }
    func referencesLocalFile(_ url: URL) throws -> Bool { try attachments.referencesLocalPath(url.path) }
}

@MainActor final class RecoveryCallers {
    struct Auth { let sessionGeneration = 1 }
    let auth = Auth(), generation = 1
    let store: MessageStore
    let attachmentManager: StagingAdapter
    let draftSourceRecovery: DraftSourceRecovery
    var recentFileCopies: [UUID: Task<Void, Error>] = [:]
    var recentFileCopiesRetired = false
    var errorMessage: String?
    init(_ store: MessageStore, _ attachments: AttachmentStore) {
        self.store = store; attachmentManager = StagingAdapter(attachments)
        draftSourceRecovery = store.startDraftSourceRecovery(attachmentReferences: attachments.draftSourceReferences())
    }
    func requireSession() throws { try Task.checkCancellation() }
    // PRODUCTION_RECOVERY_CALLERS
}

final class HeldInspection: @unchecked Sendable {
    let began = AsyncStream<Void>.makeStream()
    let release = DispatchSemaphore(value: 0)
    func pause() {
        precondition(!Thread.isMainThread)
        began.continuation.yield(())
        precondition(release.wait(timeout: .now() + 10) == .success, "Held scan timed out")
    }
}

@main struct ShareRecoveryConcurrencyContracts {
    static let owner = "11111111-1111-4111-8111-111111111111"
    static let chat = "22222222-2222-4222-8222-222222222222"
    static func account(_ container: URL) -> URL {
        container.appendingPathComponent("Library/Application Support/COCO/server/" + owner)
    }
    static func row(_ id: String, path: String? = nil, client: String? = nil) -> AttachmentRecord {
        AttachmentRecord(attachmentId: id, clientMessageId: client ?? id, chatId: chat, mediaType: "voice", mimeType: "audio/mp4",
            filename: nil, size: 3, width: nil, height: nil, durationMs: 42, thumbnailBase64: nil, localPath: path,
            downloadState: "downloaded", uploadState: "uploaded", progress: 100, objectKey: "voice/" + id,
            encryptedSize: 31, encryptionVersion: 1, keyBase64: nil, nonceBase64: nil, keysetBase64: nil, sha256Base64: "fixture")
    }
    static func fixture(_ root: URL, hooks: AttachmentStore.MigrationHooks = .init(), incomingHooks: AttachmentStore.IncomingWriteHooks = .init()) throws -> (URL, AttachmentStore, MessageStore) {
        let apps = root.appendingPathComponent(UUID().uuidString + "/Containers/Data/Application")
        let old = apps.appendingPathComponent(UUID().uuidString), moved = apps.appendingPathComponent(UUID().uuidString)
        let source = account(old).appendingPathComponent("draft-media/" + UUID().uuidString + "/voice.m4a")
        try FileManager.default.createDirectory(at: source.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: account(old).appendingPathComponent("attachments"), withIntermediateDirectories: true)
        try Data([1, 2, 3]).write(to: source)
        do { let seed = try AttachmentStore(databaseURL: account(old).appendingPathComponent("attachments.sqlite3")); try seed.upsert(row("old-voice", path: source.path)) }
        try FileManager.default.moveItem(at: old, to: moved)
        let directory = account(moved)
        return (directory, try AttachmentStore(databaseURL: directory.appendingPathComponent("attachments.sqlite3"), draftMigrationHooks: hooks, incomingWriteHooks: incomingHooks),
            try MessageStore(databaseURL: directory.appendingPathComponent("messages.sqlite3")))
    }
    static func packet(_ sequence: Int64 = 1, sender: String = "peer") -> MessageDto {
        MessageDto(id: UUID().uuidString, chatId: chat, clientMessageId: UUID().uuidString, senderId: sender,
            senderDeviceId: "device", sequence: sequence, createdAt: Date(), messageType: 1, ciphertext: "consumed-packet",
            kind: "content", targetMessageId: nil)
    }
    static func content(_ id: String) -> MessageContent {
        MessageContent(kind: MessageContent.kindAttachment, text: "retained caption", attachment: attachmentMetadata(from: row(id)))
    }
    static func sql(_ database: URL, _ command: String) throws {
        var handle: OpaquePointer?
        precondition(sqlite3_open(database.path, &handle) == SQLITE_OK)
        defer { sqlite3_close(handle) }
        guard sqlite3_exec(handle, command, nil, nil, nil) == SQLITE_OK else { throw AttachmentStore.StoreError.stepFailed }
    }

    @MainActor static func securityExclusion(_ root: URL) async throws {
        for reason in ["blocked", "revoked"] {
            for timing in ["before", "afterDecrypt"] {
                let (_, attachments, messages) = try fixture(root)
                let dto = packet(sender: reason == "revoked" ? "owner" : "peer")
                let attachmentID = "excluded-" + reason + "-" + timing
                let signal = try ConsumedSignal(content(attachmentID))
                let ingestion = Ingestion(messages, attachments, signal)
                let security = ingestion.auth.localSecurity!
                let exclude: () throws -> Void = {
                    if reason == "blocked" { try security.setBlocked(dto.senderId, blocked: true) }
                    else { try security.revoke(.init(userId: dto.senderId, serverDeviceId: dto.senderDeviceId,
                        signalDeviceId: nil, identityFingerprint: nil)) }
                }
                if timing == "before" { try exclude() } else { signal.afterDecrypt = exclude }
                try await ingestion.receiveExcluded(dto)
                let stored = try messages.message(byClientMessageId: dto.clientMessageId)
                let metadata = try attachments.attachment(byId: attachmentID)
                let journal = try messages.chatValue(chatId: chat, name: "restore_attachment:" + dto.clientMessageId)
                let exclusion = try messages.chatValue(chatId: chat, name: "security_excluded:" + dto.clientMessageId)
                let expectedDecryptions = timing == "before" ? 0 : 1
                precondition(signal.decryptions == expectedDecryptions && stored == nil && metadata == nil && journal == nil && exclusion == "1",
                    "Blocked/revoked ingress reached persistence or lost its durable exclusion")
                precondition(signal.finishedReceiveIDs.count == 1, "Excluded ingress did not retire its receive journal")
                // A fresh decision store has no block/revocation; the real
                // message-store exclusion must still prevent replay delivery.
                try await Ingestion(messages, attachments, signal).receiveExcluded(dto)
                precondition(signal.decryptions == expectedDecryptions && signal.finishedReceiveIDs.count == 2,
                    "Durable security exclusion allowed replay after decision-store replacement")
            }
        }
        print("PASS: real local blocks/revocations exclude ingress before/after decryption, deny receipts and persist across decision-store replacement")
    }

    @MainActor static func concurrentIngestion(_ root: URL) async throws {
        for phase in ["inspect", "publish"] {
            let held = HeldInspection()
            let hooks = phase == "inspect"
                ? AttachmentStore.MigrationHooks(willInspectPath: { _ in held.pause() })
                : AttachmentStore.MigrationHooks(beforeCommit: { held.pause() })
            let (_, attachments, messages) = try fixture(root, hooks: hooks)
            let migration = Task { try await attachments.waitForDraftReferences() }
            for await _ in held.began.stream { break }
            // A real MainActor outgoing write and incoming Signal ingestion both
            // complete while filesystem inspection/publication is held indefinitely.
            try attachments.upsert(row("outgoing"))
            // A real incoming echo has a committed owner and journal too.
            let echo = packet(), echoSignal = try ConsumedSignal(content("old-voice"))
            try await Ingestion(messages, attachments, echoSignal).receive(echo)
            let own = try attachments.attachment(byId: "old-voice")
            precondition(own?.localPath != nil, "Incoming echo discarded an existing local original")
            let dto = packet(2), signal = try ConsumedSignal(content("incoming-" + phase))
            let ingestion = Ingestion(messages, attachments, signal)
            try await ingestion.receive(dto)
            let stored = try messages.message(byClientMessageId: dto.clientMessageId)
            let metadata = try attachments.attachment(byId: "incoming-" + phase)
            let journal = try messages.chatValue(chatId: chat, name: "restore_attachment:" + dto.clientMessageId)
            precondition(stored?.content.text == "retained caption" && stored?.status == .delivered && metadata != nil && journal == nil)
            precondition(signal.decryptions == 1)
            // A live update after inspection must win over the staged CAS repair.
            try attachments.upsert(row("old-voice", path: nil))
            held.release.signal(); try await migration.value
            let changed = try attachments.attachment(byId: "old-voice")
            precondition(changed?.localPath == nil, "Staged migration overwrote a live metadata change")
            try await ingestion.receive(dto)
            precondition(signal.decryptions == 1, "Successful replay consumed the Signal packet twice")
        }
        print("PASS: production ingestion and live metadata writes proceed during held filesystem inspection/publication; CAS preserves live edits")
    }

    @MainActor static func plaintextSurvivesMetadataFailure(_ root: URL) async throws {
        for busy in [true, false] {
            let (directory, attachments, messages) = try fixture(root)
            let database = directory.appendingPathComponent("attachments.sqlite3")
            var blocker: OpaquePointer?
            if busy {
                precondition(sqlite3_open(database.path, &blocker) == SQLITE_OK)
                precondition(sqlite3_exec(blocker, "BEGIN IMMEDIATE;", nil, nil, nil) == SQLITE_OK)
            } else {
                try sql(database, "CREATE TRIGGER reject_metadata BEFORE INSERT ON attachments BEGIN SELECT RAISE(ABORT,'fixture'); END;")
            }
            let dto = packet(), signal = try ConsumedSignal(content("contended"))
            let ingestion = Ingestion(messages, attachments, signal)
            let receive = Task { try await ingestion.receive(dto) }
            for await _ in signal.consumed.stream { break }
            // MainActor is free, and plaintext is committed before the metadata
            // worker can contend, time out, fail, or observe cancellation.
            let saved = try messages.message(byClientMessageId: dto.clientMessageId)
            let journal = try messages.chatValue(chatId: chat, name: "restore_attachment:" + dto.clientMessageId)
            precondition(saved?.content.text == "retained caption" && saved?.status == .delivered && journal != nil)
            do { try await receive.value; preconditionFailure("Metadata failure was not exercised") }
            catch AttachmentStore.StoreError.busy { precondition(busy) }
            catch AttachmentStore.StoreError.stepFailed { precondition(!busy) }
            if let blocker { sqlite3_exec(blocker, "ROLLBACK;", nil, nil, nil); sqlite3_close(blocker) }
            else { try sql(database, "DROP TRIGGER reject_metadata;") }
            // Reconstruct real stores/ingestion to exercise crash/relaunch dedupe.
            let reopenedMessages = try MessageStore(databaseURL: directory.appendingPathComponent("messages.sqlite3"))
            let reopenedAttachments = try AttachmentStore(databaseURL: database)
            let replay = Ingestion(reopenedMessages, reopenedAttachments, signal)
            try await replay.receive(dto)
            let repaired = try reopenedAttachments.attachment(byId: "contended")
            let retired = try reopenedMessages.chatValue(chatId: chat, name: "restore_attachment:" + dto.clientMessageId)
            precondition(signal.decryptions == 1 && repaired != nil && retired == nil, "Durable plaintext/journal did not recover metadata without decryption")
        }
        print("PASS: real SQLite busy/rejected metadata writes preserve plaintext + journal; relaunch repairs without another Signal decrypt")
    }

    @MainActor static func indirectCancellation(_ root: URL) async throws {
        let held = HeldInspection()
        let (_, attachments, messages) = try fixture(root, hooks: .init(willInspectPath: { _ in held.pause() }))
        let callers = RecoveryCallers(messages, attachments)
        for await _ in held.began.stream { break }
        var recoveryFinished = false, copies = 0
        let recoveryObserver = Task { try await callers.draftSourceRecovery.value; recoveryFinished = true }
        let source = root.appendingPathComponent("input.png")
        let copyQueue = DispatchQueue(label: "contract.copy")
        let shareWait = Task { try await callers.waitForDraftSourceRecovery() }
        let stage = Task { _ = try await callers.stageDraftFile(source) }
        let copy = Task { try await callers.copyRecentFiles(sources: [source], on: copyQueue, copy: { _ in preconditionFailure("Cancelled copy ran") }); copies += 1 }

        let shareRoot = root.appendingPathComponent("pending-share")
        let account = String(repeating: "a", count: 32)
        let job = ShareJob(jobId: UUID().uuidString, createdAt: Date().timeIntervalSince1970, accountKey: account,
            target: .init(chatId: chat, type: "saved", peerUserId: nil, titleAtShare: "Saved"), caption: nil, albumId: nil,
            items: [.init(id: UUID().uuidString, kind: .file, name: "file.txt", bytes: 3, albumItemIndex: 0)])
        let jobDirectory = shareRoot.appendingPathComponent("ShareJobs/" + account + ".fixture.account/" + job.jobId + ".job")
        try ShareContainer.directory(jobDirectory.appendingPathComponent("items/" + job.items[0].id))
        try ShareContainer.directory(jobDirectory.appendingPathComponent("events"))
        try ShareContainer.write(job.encoded(), to: jobDirectory.appendingPathComponent("manifest.json"))
        try Data([1, 2, 3]).write(to: ShareJobStore.source(job.items[0], in: jobDirectory))
        var admitted = false, backgroundEnded = false
        let processor = ShareJobProcessor(dependencies: .init(current: { true }, accountKey: account, registeredKeys: { [account] },
            targets: { [.init(chatId: chat, type: "saved", title: "Saved", peerUserId: nil)] }, inspect: { _, _ in .missing },
            adopt: { _, url in try await callers.waitForDraftSourceRecovery(); return url },
            enqueue: { _, _, _ in admitted = true; return true }, notice: { _ in }, flush: {}), root: { shareRoot })
        let processing = Task {
            defer { backgroundEnded = true }
            try await processor.process()
        }
        while callers.draftSourceRecovery.waitingConsumerCount < 4 { await Task.yield() }
        shareWait.cancel(); stage.cancel(); copy.cancel(); processing.cancel()
        for task in [shareWait, stage, copy] {
            do { try await task.value; preconditionFailure("Cancelled indirect waiter completed") } catch is CancellationError {}
        }
        // The processor intentionally consumes CancellationError and returns;
        // either path must unwind its background-task scope before scan release.
        do { try await processing.value } catch is CancellationError {}
        precondition(backgroundEnded && !recoveryFinished && !admitted && callers.attachmentManager.stages == 0 && copies == 0,
            "Share/composer cancellation waited for shared recovery or performed side effects")
        // A new noncancelled consumer still joins the SAME recovery owner.
        let survivor = Task { try await callers.waitForDraftSourceRecovery() }
        while callers.draftSourceRecovery.waitingConsumerCount < 1 { await Task.yield() }
        held.release.signal(); try await survivor.value; try await recoveryObserver.value
        precondition(recoveryFinished)
        print("PASS: production share processor, repository wait, staging and recent-copy callers cancel before recovery completes; shared recovery survives")
    }

    @MainActor static func deletionDuringRetry(_ root: URL) async throws {
        for mode in ["personal", "chat", "message"] {
            let wholeChat = mode == "chat"
            let held = HeldWrite()
            let (directory, attachments, messages) = try fixture(root, incomingHooks: .init(beforeRetry: { await held.pause() }))
            let database = directory.appendingPathComponent("attachments.sqlite3")
            var blocker: OpaquePointer?
            precondition(sqlite3_open(database.path, &blocker) == SQLITE_OK)
            precondition(sqlite3_exec(blocker, "BEGIN IMMEDIATE;", nil, nil, nil) == SQLITE_OK)
            let dto = packet(), signal = try ConsumedSignal(content("delete-during-retry"))
            let ingestion = Ingestion(messages, attachments, signal)
            let receive = Task { try await ingestion.receive(dto, expectStored: false) }
            for await _ in held.began.stream { break }
            sqlite3_exec(blocker, "ROLLBACK;", nil, nil, nil); sqlite3_close(blocker)
            let pendingMessage = try messages.message(byClientMessageId: dto.clientMessageId)
            let absent = try attachments.attachment(byId: "delete-during-retry")
            precondition(pendingMessage != nil && absent == nil, "Fixture missed plaintext-before-metadata retry")
            let deletion = DeletionCallers(messages, attachments, directory: directory)
            if wholeChat { try await deletion.purgeDeletedChat(chat) }
            else if mode == "personal" { let changed = try await deletion.clearPersonalChat(chat, through: dto.sequence); precondition(changed) }
            else {
                var hidden = try messages.message(byClientMessageId: dto.clientMessageId)!
                hidden.hiddenLocally = true; try messages.upsert(hidden)
                _ = try await deletion.attachmentManager.purgeMessageMedia(clientMessageId: dto.clientMessageId)
            }
            held.release(); try await receive.value
            let removed = try messages.message(byClientMessageId: dto.clientMessageId)
            let metadata = try attachments.attachment(byId: "delete-during-retry")
            let journal = try messages.chatValue(chatId: chat, name: "restore_attachment:" + dto.clientMessageId)
            let messageRemoved = mode == "message" ? removed?.hiddenLocally == true : removed == nil
            precondition(messageRemoved && metadata == nil && journal == nil && signal.decryptions == 1,
                "Retry resurrected metadata after the production purge or retired its journal too soon")
            // Even a new store/writer cannot reinsert metadata after the purge.
            let reopened = try AttachmentStore(databaseURL: database)
            let stale = row("delete-during-retry", client: dto.clientMessageId)
            try await reopened.upsertIncoming(stale, sequence: dto.sequence)
            let replayed = try reopened.attachment(byId: stale.attachmentId)
            precondition(replayed == nil, "A durable deletion fence did not survive reopening")
            let newer = packet(dto.sequence + 1), newerSignal = try ConsumedSignal(content("newer"))
            try await Ingestion(messages, attachments, newerSignal).receive(newer, expectStored: !wholeChat)
            precondition(newerSignal.decryptions == (wholeChat ? 0 : 1), "Personal purge invalidated a newer message or deleted chat decrypted again")
        }
        print("PASS: real repository + manager personal/chat/message purges invalidate sleeping metadata retries and durable tombstones block reinsertion")
    }

    @MainActor static func revalidateAfterPersistence(_ root: URL) async throws {
        for reason in ["personal", "server", "hidden"] {
            let held = HeldWrite()
            let (_, attachments, messages) = try fixture(root, incomingHooks: .init(afterPersist: { await held.pause() }))
            let dto = packet(), signal = try ConsumedSignal(content("late-row"))
            let ingestion = Ingestion(messages, attachments, signal)
            let receive = Task { try await ingestion.receive(dto, expectStored: false) }
            for await _ in held.began.stream { break }
            let landed = try attachments.attachment(byId: "late-row")
            precondition(landed != nil)
            if reason == "personal" {
                // Advance the cutoff while retaining the row: cutoff, not just
                // row absence, must reject a stale visible projection.
                try messages.saveChatValue(chatId: chat, name: "personal_clear", value: String(dto.sequence))
            } else if reason == "server" {
                try messages.saveChatValue(chatId: "__server_history__", name: "cleared_through_sequence", value: String(dto.sequence))
            } else {
                var row = try messages.message(byClientMessageId: dto.clientMessageId)!
                row.hiddenLocally = true; try messages.upsert(row)
            }
            held.release(); try await receive.value
            let removed = try attachments.attachment(byId: "late-row")
            let journal = try messages.chatValue(chatId: chat, name: "restore_attachment:" + dto.clientMessageId)
            precondition(removed == nil && journal == nil, "Post-write visibility/cutoff revalidation left late metadata")
        }
        print("PASS: production ingestion removes late rows before retiring journals after personal/server cutoffs or local hiding")
    }

    static func scalar(_ database: URL, _ statement: String) throws -> Int64 {
        var handle: OpaquePointer?, query: OpaquePointer?
        guard sqlite3_open_v2(database.path, &handle, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else { throw AttachmentStore.StoreError.openFailed }
        defer { sqlite3_close(handle) }
        guard sqlite3_prepare_v2(handle, statement, -1, &query, nil) == SQLITE_OK else { throw AttachmentStore.StoreError.stepFailed }
        defer { sqlite3_finalize(query) }
        guard sqlite3_step(query) == SQLITE_ROW else { throw AttachmentStore.StoreError.stepFailed }
        return sqlite3_column_int64(query, 0)
    }

    @MainActor static func boundedFences(_ root: URL) async throws {
        let (directory, attachments, messages) = try fixture(root)
        let database = directory.appendingPathComponent("attachments.sqlite3")
        let deletion = DeletionCallers(messages, attachments, directory: directory)
        for turn in 0..<3 {
            for index in 1...128 {
                let sequence = Int64(turn * 128 + index)
                let dto = packet(sequence)
                let text = DecryptedChatMessage(clientMessageId: dto.clientMessageId, serverId: dto.id, chatId: chat,
                    senderId: "peer", sequence: sequence, createdAt: Date(), kind: "content", targetMessageId: nil,
                    status: .delivered, content: MessageContent(kind: MessageContent.kindText, text: "text only"))
                try messages.upsert(text)
            }
            let changed = try await deletion.clearPersonalChat(chat, through: Int64((turn + 1) * 128))
            precondition(changed)
            let fences = try scalar(database, "SELECT count(*) FROM deleted_attachment_messages;")
            let cuts = try scalar(database, "SELECT count(*) FROM attachment_clear_cutoffs;")
            precondition(fences == 0 && cuts == 1, "Repeated text clears grew message fences instead of one cutoff")
        }
        // Compaction rejects an old owner even after its per-message fence has
        // disappeared, while the same attachment ID is reusable by a new message.
        let old = packet(1)
        let stale = DecryptedChatMessage(clientMessageId: old.clientMessageId, serverId: old.id, chatId: chat,
            senderId: "peer", sequence: 1, createdAt: Date(), kind: "content", targetMessageId: nil,
            status: .delivered, content: content("reused-id"))
        try messages.upsert(stale, journalAttachment: true)
        try await attachments.upsertIncoming(row("reused-id", client: old.clientMessageId), sequence: 1)
        let rejected = try attachments.attachment(byId: "reused-id")
        precondition(rejected == nil, "Compacted cutoff admitted an old metadata source")
        let newer = packet(385), signal = try ConsumedSignal(content("reused-id"))
        try await Ingestion(messages, attachments, signal).receive(newer)
        let admitted = try attachments.attachment(byId: "reused-id")
        precondition(admitted?.clientMessageId == newer.clientMessageId, "Attachment ID was not reusable by a new message")
        // A single-message fence remains while its journal/write owns it.
        let held = HeldWrite()
        let liveStore = try AttachmentStore(databaseURL: database, incomingWriteHooks: .init(afterPersist: { await held.pause() }))
        let single = packet(386), singleSignal = try ConsumedSignal(content("single-fence"))
        let receiving = Task { try await Ingestion(messages, liveStore, singleSignal).receive(single, expectStored: false) }
        for await _ in held.began.stream { break }
        var hidden = try messages.message(byClientMessageId: single.clientMessageId)!
        hidden.hiddenLocally = true; try messages.upsert(hidden)
        let singleDeletion = DeletionCallers(messages, liveStore, directory: directory)
        _ = try await singleDeletion.attachmentManager.purgeMessageMedia(clientMessageId: single.clientMessageId)
        try await liveStore.reclaimDeletionFences()
        let protected = try scalar(database, "SELECT count(*) FROM deleted_attachment_messages;")
        precondition(protected == 1, "A live write/journal lost its fence")
        held.release(); try await receiving.value
        try await liveStore.reclaimDeletionFences()
        let reclaimed = try scalar(database, "SELECT count(*) FROM deleted_attachment_messages;")
        precondition(reclaimed == 0, "Consumed journal left a permanent message fence")
        try await deletion.purgeDeletedChat(chat)
        let afterChat = try scalar(database, "SELECT count(*) FROM deleted_attachment_messages;")
        let afterCuts = try scalar(database, "SELECT count(*) FROM attachment_clear_cutoffs;")
        precondition(afterChat == 0 && afterCuts == 0, "Whole-chat tombstone did not supersede message fences/cutoffs")
        try await liveStore.upsertIncoming(row("single-fence", client: single.clientMessageId), sequence: 386)
        let resurrected = try liveStore.attachment(byId: "single-fence")
        precondition(resurrected == nil, "Chat deletion lost protection after compaction")
        print("PASS: repeated production clears stay bounded; journals retain/reclaim individual fences; compacted boundaries prevent resurrection and permit new-message ID reuse")
    }

    @MainActor static func legacyFenceLaunchSweep(_ root: URL) async throws {
        let (directory, _, _) = try fixture(root)
        let database = directory.appendingPathComponent("attachments.sqlite3")
        // Reproduce the old release's schema and 10,000 permanent text fences.
        try sql(database, "DROP TABLE deleted_attachment_messages; CREATE TABLE deleted_attachment_messages(client_message_id TEXT PRIMARY KEY NOT NULL); WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<10000) INSERT INTO deleted_attachment_messages SELECT 'legacy-' || x FROM n;")
        let launched = try AttachmentStore(databaseURL: database)
        launched.startFenceReclamation()
        try await launched.reclaimDeletionFences()
        let remaining = try scalar(database, "SELECT count(*) FROM deleted_attachment_messages;")
        precondition(remaining == 0, "Launch did not reclaim journal-free legacy fences")
        print("PASS: actual legacy schema upgrade and background launch sweep reclaim 10,000 orphan fences")
    }

    @MainActor static func main() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("share-recovery-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try await securityExclusion(root)
        try await concurrentIngestion(root)
        try await plaintextSurvivesMetadataFailure(root)
        try await indirectCancellation(root)
        try await deletionDuringRetry(root)
        try await revalidateAfterPersistence(root)
        try await boundedFences(root)
        try await legacyFenceLaunchSweep(root)
    }
}
