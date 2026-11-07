import Combine
import Foundation
import UIKit

/// Per-chat message view model: REST history + send orchestration on top of
/// the Signal layer and a persistent local message store. Mirrors Android's
/// `MessageRepositoryImpl`; decryption/dedup/store is delegated to the shared
/// `MessageIngestor` (Android's `MessageIngestionPipeline`) and the durable
/// send/retry path to `MessageOutbox`, so WebSocket push, `/sync` catch-up and
/// history pagination all funnel through one decrypt path and one outbox.
@MainActor
final class MessagesRepository: ObservableObject {
    weak var directFiles: DirectFileTransfers? {
        didSet {
            directComposerChanges = directFiles?.composerBatchChanges.sink { [weak self] chat in
                guard let self, self.generation == self.auth.sessionGeneration else { return }
                self.composerDirectRevisions[chat, default: 0] += 1
                if let error = self.directFiles?.composerBatchError(chatId: chat) { self.reportError(UnyriL10n.string(error)) }
            }
        }
    }
    private var directComposerChanges: AnyCancellable?
    @Published private(set) var composerDirectRevisions: [String: Int] = [:]

    private(set) var messagesRevision: UInt64 = 0
    // Intrinsic-size inputs published independently of the message array.
    // Updated only at snapshot publication, never for per-chunk progress.
    private(set) var intrinsicAuxiliaryRevision: UInt64 = 0
    @Published private(set) var messages: [DecryptedChatMessage] = [] { didSet { messagesRevision &+= 1 } }
    @Published private(set) var failedActions: [DecryptedChatMessage] = []
    @Published private(set) var loadedHistoryChatId: String?
    // Raw, account/chat-scoped previews are resolved on the existing read lane,
    // independently of visible-message equality. Rendering never opens SQLite.
    @Published private var pinnedPreviewSnapshot = ChatPinnedPreviewSnapshot.empty
    private var pinnedPreviewSessionIsCurrent: Bool {
        generation == auth.sessionGeneration && ownerId == auth.currentUser?.id && ownerServer == auth.authenticatedServerURL
    }
    func pinnedPreviews(chatId: String) -> ChatPinnedPreviewSnapshot {
        guard pinnedPreviewSessionIsCurrent, currentChatId == chatId,
              pinnedPreviewSnapshot.chatId == chatId else { return .empty }
        return pinnedPreviewSnapshot
    }
    private func invalidatePinnedPreviews() {
        if pinnedPreviewSnapshot != .empty { pinnedPreviewSnapshot = .empty }
    }
    private func publishPinnedPreviews(_ snapshot: ChatPinnedPreviewSnapshot, chatId: String, revision: Int) {
        guard pinnedPreviewSessionIsCurrent, !Task.isCancelled, revision == reloadRevision,
              currentChatId == chatId, visibleChatId == chatId, snapshot.chatId == chatId else { return }
        if pinnedPreviewSnapshot != snapshot { pinnedPreviewSnapshot = snapshot }
    }
    func refreshImportedHistory(in chats: Set<String>) {
        guard pinnedPreviewSessionIsCurrent else { return }
        repairedLifecycleChats.subtract(chats)
        guard let chat = currentChatId, chats.contains(chat) else { return }
        invalidatePinnedPreviews()
        reload()
    }
    private func observedMessageChanges(_ touched: Set<String>) {
        guard pinnedPreviewSessionIsCurrent, let chatId = currentChatId, touched.contains(chatId) else { return }
        guard visibleChatId == chatId else {
            // Hidden chats still ingest edits/deletes. Never re-present an old
            // pinned text on return while the normal snapshot refresh is queued.
            invalidatePinnedPreviews()
            return
        }
        reload()
        Task { await self.markReadLatest(chatId: chatId) }
    }
    // Presentation-only bookmarks belong to this authenticated repository.
    private var scrollBookmarks: [String: (anchor: String?, followsLatest: Bool, offset: ChatReaderBookmarkOffset?)] = [:]
    func cachedMessages(chatId: String) -> [DecryptedChatMessage] {
        guard (try? store.legacyLifecycleRepairIsComplete(chatId: chatId)) == true else { return [] }
        return (try? store.messages(forChatId: chatId)) ?? []
    }
    func searchCachedMessages(query: String) async throws -> [DecryptedChatMessage] {
        try requireSession()
        let chats = try await store.readAsync { try $0.legacyLifecycleChatIDs() }
        for chat in chats {
            try requireSession()
            try await store.repairLegacyLifecycle(chatId: chat)
        }
        let result = try await store.searchVisibleMessagesAsync(query: query)
        try requireSession()
        return result
    }
    func locateMessage(chatId: String, id: String) async -> String? {
        if let local = localMessage(id: id), local.chatId == chatId, !local.hiddenLocally, !local.isDeletedForEveryone {
            guard await includeLocalAnchor(chatId: chatId, id: local.clientMessageId) else { return nil }
            return local.clientMessageId
        }
        for _ in 0..<100 {
            guard !Task.isCancelled, currentChatId == chatId, hasMore else { break }
            let cursor = nextBeforeSequence
            await loadOlder(chatId: chatId)
            if let local = localMessage(id: id), local.chatId == chatId, !local.hiddenLocally, !local.isDeletedForEveryone {
                guard await includeLocalAnchor(chatId: chatId, id: local.clientMessageId) else { return nil }
                return local.clientMessageId
            }
            if cursor == nextBeforeSequence { break }
        }
        return nil
    }
    private var localHistoryLimit = 200
    private var localPagingChatId: String?
    private var serverHasMore = false
    @Published private(set) var hasMoreLocalHistory = false
    private func includeLocalAnchor(chatId: String, id: String) async -> Bool {
        guard currentChatId == chatId, generation == auth.sessionGeneration else { return false }
        guard let size = try? await store.readAsync({ try $0.messageWindowSize(chatId: chatId, anchor: id) }) else { return false }
        guard currentChatId == chatId, generation == auth.sessionGeneration, !Task.isCancelled else { return false }
        localHistoryLimit = max(localHistoryLimit, min(Int(Int32.max) - 1, size + 50))
        reload()
        await reloadTask?.value
        return messages.contains { $0.clientMessageId == id }
    }
    private func admitTextAction(_ raw: String?, caption: Bool = false) -> Bool {
        do {
            if caption { _ = try MessageTextPolicy.caption(raw) }
            else { _ = try MessageTextPolicy.outgoing(raw ?? "") }
            return true
        } catch {
            errorMessage = UnyriL10n.string(caption
                ? "Caption is too long or contains unsupported characters. Your caption and selected items have been kept."
                : "Text is too long or contains unsupported characters. Your draft has been kept.")
            return false
        }
    }
    /// Preflight the whole captured selection before starting any transfer.
    /// Each ordinary forward still performs its own live admission afterward.
    func validateForwardSelection(_ sources: [DecryptedChatMessage]) -> Bool {
        guard generation == auth.sessionGeneration else { return false }
        do {
            for source in sources {
                guard source.content.directFile == nil else { throw MessageTextPolicyError.invalid }
                var content = source.content
                content.replySnapshot = nil
                _ = try MessageContentCodec.admittedText(content)
            }
            return true
        } catch {
            errorMessage = UnyriL10n.string("One selected message is too long or contains unsupported characters. Nothing was sent.")
            return false
        }
    }
    func forward(_ source: DecryptedChatMessage, to destination: ChatSummary, albumId: String? = nil) async -> Bool {
        guard source.content.directFile == nil, !source.isDeletedForEveryone, source.status != .decryptFailed, source.status != .identityChanged else { return false }
        do {
            let id = UUID().uuidString.lowercased()
            var content = source.content
            content.replySnapshot = nil; content.forwarded = true; content.albumId = albumId
            content = try MessageContentCodec.admittedText(content)
            if let attachment = content.attachment {
                guard let file = await downloadAttachment(attachmentId: attachment.attachmentId, origin: .forward) else { return false }
                if attachment.mediaType == AttachmentMetadata.mediaTypeImage {
                    content.attachment = try await attachmentManager.prepareAndUploadImage(fileURL: file, chatId: destination.chatId, clientMessageId: id)
                } else if attachment.mediaType == AttachmentMetadata.mediaTypeVoice {
                    content.attachment = try await attachmentManager.prepareAndUploadVoice(fileURL: file, durationMs: attachment.voice?.durationMs ?? 0, chatId: destination.chatId, clientMessageId: id)
                } else {
                    content.attachment = try await attachmentManager.prepareAndUploadFile(fileURL: file, chatId: destination.chatId, clientMessageId: id)
                }
            }
            try requireSession()
            let queued = outbox.enqueueContent(chatId: destination.chatId, otherUserId: destination.otherUser?.id, content: content, targetMessageId: nil, clientMessageId: id)
            reload(); return queued
        } catch {
            errorMessage = error is MessageTextPolicyError ? UnyriL10n.string(source.content.attachment == nil ? "Text is too long or contains unsupported characters. Your draft has been kept." : "Caption is too long or contains unsupported characters. Your caption and selected items have been kept.") : error.localizedDescription
            return false
        }
    }
    @Published private(set) var scheduledDates: [String: Date] = [:]
    @Published private(set) var queuedMediaPreviews: [String: QueuedMediaPreview] = [:]
    func queuedMediaPreview(chatId: String, id: String) -> QueuedMediaPreview? {
        guard generation == auth.sessionGeneration, loadedHistoryChatId == chatId else { return nil }
        return queuedMediaPreviews[id]
    }
    @Published private(set) var cachedAttachmentPaths: [String: String] = [:]
    func scheduledDate(chatId: String, id: String) -> Date? { loadedHistoryChatId == chatId ? scheduledDates[id] : nil }
    func scheduleText(chatId: String, otherUserId: String?, text: String, due: Date) -> Bool {
        guard generation == auth.sessionGeneration, admitTextAction(text) else { return false }
        let queued = outbox.enqueueScheduled(chatId: chatId, otherUserId: otherUserId, text: text, due: due)
        if !queued { reportError(UnyriL10n.string("Could not schedule the message. Check the date and try again.")) }
        reload(); return queued
    }
    func queueMedia(id: String, chatId: String, otherUserId: String?, url: URL, kind: String, caption: String? = nil, duration: Int64 = 0, albumId: String? = nil, albumItemIndex: Int? = nil) -> Bool {
        guard generation == auth.sessionGeneration, admitTextAction(caption, caption: true) else { return false }
        let queued = outbox.enqueueMedia(chatId: chatId, otherUserId: otherUserId, id: id, url: url, kind: kind, caption: caption, duration: duration, albumId: albumId, albumItemIndex: albumItemIndex)
        if !queued { reportError(UnyriL10n.string("Could not save media to the outbox.")) }
        reload(); return queued
    }
    func queueVisualMedia(id: String, chatId: String, otherUserId: String?, url: URL, kind: String, caption: String?, albumId: String?, albumItemIndex: Int? = nil) async -> Bool {
        guard generation == auth.sessionGeneration, admitTextAction(caption, caption: true) else { return false }
        // Drain already dispatched/pending draft writes without blocking MainActor
        // inside the outbox's synchronous transaction-ordering barrier.
        if draftWrite != nil || !pendingWorkspace.isEmpty { await flushDraftValues() }
        guard generation == auth.sessionGeneration, !Task.isCancelled else { return false }
        let queued = await outbox.enqueueVisualMedia(chatId: chatId, otherUserId: otherUserId, id: id, url: url, kind: kind, caption: caption, albumId: albumId, albumItemIndex: albumItemIndex)
        guard generation == auth.sessionGeneration, !Task.isCancelled else { return false }
        if !queued { reportError(UnyriL10n.string("Could not save media to the outbox.")) }
        // The outbox change stream reloads the owning chat. Album admission
        // publishes this stream once for its durably accepted subset.
        return queued
    }
    func beginMediaAdmissionBatch() { outbox.beginMediaAdmissionBatch() }
    func endMediaAdmissionBatch() { outbox.endMediaAdmissionBatch() }
    @Published private(set) var preparingMediaChats = Set<String>()
    @Published private(set) var mediaDraftRevisions: [String: Int] = [:]
    @Published private(set) var mediaPreparationErrors: [String: String] = [:]
    private var mediaPreparationOwnership = MediaPreparationOwnership()
    private var mediaPreparationTasks: [String: Task<Void, Never>] = [:]

    func storedMediaDrafts(chatId: String) -> [StoredMediaDraft] {
        guard let raw = chatValue(chatId: chatId, name: "mediaDrafts"), let data = raw.data(using: .utf8) else { return [] }
        return (try? JSONDecoder().decode([StoredMediaDraft].self, from: data)) ?? []
    }
    func acknowledgeMediaPreparationError(chatId: String) { mediaPreparationErrors[chatId] = nil }

    /// One bounded off-main recovery read per chat appearance, not from SwiftUI body.
    func loadComposerBatch(chatId: String) async throws -> MediaComposerBatch? {
        let expectedGeneration = generation; let owner = auth.currentUser?.id
        do {
            let value = try await store.readAsync { reader -> MediaComposerBatch? in
                guard try !reader.isChatDeleted(chatId),
                      let raw = try reader.chatValue(chatId: chatId, name: MediaComposerBatch.workspaceKey) else { return nil }
                guard raw.utf8.count <= 1024 * 1024,
                      var batch = try? JSONDecoder().decode(MediaComposerBatch.self, from: Data(raw.utf8)), batch.isValid else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                // A crash can happen after outbox commit but before the journal update.
                // Hidden/cancelled rows also count: never resurrect a user-cancelled member.
                for member in batch.members {
                    if let message = try reader.message(byClientMessageId: member.id) {
                        guard message.chatId == chatId, message.senderId == owner, message.kind == "content",
                              message.content.albumId == batch.intent.albumId, message.content.albumItemIndex == member.index else {
                            throw CocoaError(.fileReadCorruptFile)
                        }
                        batch.accepted(member.id)
                    }
                }
                return batch
            }
            guard expectedGeneration == auth.sessionGeneration, !Task.isCancelled else { throw CancellationError() }
            // A delayed old read must never delete a newer Send journal. Completed metadata is
            // harmless and is replaced by the next explicitly captured Send. Caller owns UI retirement.
            return value
        } catch {
            if expectedGeneration == auth.sessionGeneration, !(error is CancellationError) {
                reportError(UnyriL10n.string("Could not restore the remaining attachments. Your saved draft has been kept."))
            }
            throw error
        }
    }
    @discardableResult func saveComposerBatch(_ batch: MediaComposerBatch?, chatId: String) -> Bool {
        guard generation == auth.sessionGeneration else { return false }
        if let batch {
            guard batch.isValid else { return false }
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
            guard let bytes = try? encoder.encode(batch), bytes.count <= 1024 * 1024 else { return false }
            return saveChatValue(chatId: chatId, name: MediaComposerBatch.workspaceKey, value: String(decoding: bytes, as: UTF8.self))
        }
        return saveChatValue(chatId: chatId, name: MediaComposerBatch.workspaceKey, value: nil)
    }
    /// A whole displayed album deletion also revokes its still-unoffered members.
    /// Read/write stay on this account's main actor; no await may release a ticket first.
    @discardableResult func stopComposerRemainderForDeletion(chatId: String, albumIDs: [String], clientMessageIds: [String]) -> Bool {
        guard !albumIDs.isEmpty else { return true }
        guard generation == auth.sessionGeneration, let owner = auth.currentUser?.id else { return false }
        do {
            guard try !store.isChatDeleted(chatId) else { return false }
            guard let raw = try store.chatValue(chatId: chatId, name: MediaComposerBatch.workspaceKey) else { return true }
            guard raw.utf8.count <= 1024 * 1024,
                  let batch = try? JSONDecoder().decode(MediaComposerBatch.self, from: Data(raw.utf8)), batch.isValid else {
                throw CocoaError(.fileReadCorruptFile)
            }
            guard batch.directSend == true, let albumID = batch.intent.albumId, albumIDs.contains(albumID) else { return true }
            let selected = Set(clientMessageIds)
            let matches = batch.members.filter { selected.contains($0.id) }
            guard !matches.isEmpty else { return true }
            for member in matches {
                guard let message = try store.message(byClientMessageId: member.id),
                      message.chatId == chatId, message.senderId == owner, message.kind == "content",
                      message.content.albumId == batch.intent.albumId, message.content.albumItemIndex == member.index,
                      message.content.directFile != nil else { throw CocoaError(.fileReadCorruptFile) }
            }
            // Do not save a caller's old snapshot: remove only the authoritative matching journal.
            try store.saveChatValue(chatId: chatId, name: MediaComposerBatch.workspaceKey, value: nil)
            directFiles?.composerDraftChanged(chatId: chatId)
            composerDirectRevisions[chatId, default: 0] += 1
            let residualURLs = batch.pending.flatMap { member -> [URL] in
                var urls: [URL] = []
                if member.source.owned, let url = member.source.url { urls.append(url) }
                if let url = member.prepared?.url { urls.append(url) }
                return urls
            }
            for url in Set(residualURLs) { discardUnownedDraftSource(url) }
            return true
        } catch {
            reportError(UnyriL10n.string("Could not stop the queued files. Your saved selection has been kept."))
            return false
        }
    }

    func recoverInterruptedMediaPreparation(chatId: String) {
        guard !preparingMediaChats.contains(chatId), chatValue(chatId: chatId, name: "mediaImportPending") == "1" else { return }
        _ = saveChatValue(chatId: chatId, name: "mediaImportPending", value: nil)
        if chatValue(chatId: chatId, name: "mediaDirectSendPending") != nil {
            _ = saveChatValue(chatId: chatId, name: "mediaDirectSendPending", value: nil)
            mediaPreparationErrors[chatId] = UnyriL10n.string("Sending was interrupted. Items already added remain in the chat. Select the remaining items again.")
        } else {
            mediaPreparationErrors[chatId] = UnyriL10n.string("Media preparation was interrupted. Saved items are still available; choose any missing items again.")
        }
    }
    func cancelMediaPreparation(chatId: String) {
        mediaPreparationOwnership.cancel(chat: chatId)
        mediaPreparationTasks.removeValue(forKey: chatId)?.cancel()
        preparingMediaChats.remove(chatId)
        _ = saveChatValue(chatId: chatId, name: "mediaImportPending", value: nil)
        _ = saveChatValue(chatId: chatId, name: "mediaDirectSendPending", value: nil)
        mediaDraftRevisions[chatId, default: 0] += 1
    }
    @discardableResult
    func startMediaPreparation(chatId: String, count: Int, asFile: Bool = false,
                               sendIntent: MediaSendIntent? = nil, otherUserId: String? = nil,
                               itemKinds: [String]? = nil, composerBatch: MediaComposerBatch? = nil,
                                prepared: ((Int, StoredMediaDraft) -> Void)? = nil,
                                admitted: ((Int, StoredMediaDraft?) -> Void)? = nil, finished: (() -> Void)? = nil,
                               prepare: @escaping (Int) async throws -> StoredMediaDraft) -> Bool {
        guard generation == auth.sessionGeneration, count > 0, itemKinds == nil || itemKinds?.count == count else { return false }
        if let sendIntent, !admitTextAction(sendIntent.caption, caption: true) { return false }
        if let composerBatch, !composerBatch.isValid || composerBatch.pending.count != count { return false }
        guard let token = mediaPreparationOwnership.begin(chat: chatId) else { return false }
        guard saveChatValue(chatId: chatId, name: "mediaImportPending", value: "1") else {
            mediaPreparationOwnership.cancel(chat: chatId); return false
        }
        if sendIntent != nil, !saveChatValue(chatId: chatId, name: "mediaDirectSendPending", value: "1") {
            _ = saveChatValue(chatId: chatId, name: "mediaImportPending", value: nil)
            mediaPreparationOwnership.cancel(chat: chatId); return false
        }
        mediaPreparationErrors[chatId] = nil
        preparingMediaChats.insert(chatId)
        mediaPreparationTasks[chatId] = Task { [self] in
            var recovery = composerBatch
            let members = composerBatch?.pending
            // The composer path must use the same durable admission scope as
            // the older media picker. Publishing every member separately used
            // to repeatedly reshape a one-cell row into a two-cell album row.
            if sendIntent != nil { beginMediaAdmissionBatch() }
            defer {
                if sendIntent != nil { endMediaAdmissionBatch() }
                finished?()
                if mediaPreparationOwnership.finish(chat: chatId, token: token) {
                    mediaPreparationTasks[chatId] = nil
                    preparingMediaChats.remove(chatId)
                    _ = saveChatValue(chatId: chatId, name: "mediaImportPending", value: nil)
                    _ = saveChatValue(chatId: chatId, name: "mediaDirectSendPending", value: nil)
                    mediaDraftRevisions[chatId, default: 0] += 1
                }
            }
            do {
                for index in 0..<count {
                    try requireSession()
                    let member = members?[index]
                    if let member, let existing = try store.message(byClientMessageId: member.id) {
                        guard existing.chatId == chatId, existing.senderId == auth.currentUser?.id,
                              existing.kind == "content", existing.content.albumId == composerBatch?.intent.albumId,
                              existing.content.albumItemIndex == member.index else { throw CocoaError(.fileReadCorruptFile) }
                        recovery?.accepted(member.id); _ = saveComposerBatch(recovery, chatId: chatId)
                        admitted?(index, member.prepared)
                        continue
                    }
                    var item = try await prepare(index)
                    if let member { item.id = member.id }
                    guard mediaPreparationOwnership.owns(chat: chatId, token: token),
                          generation == auth.sessionGeneration, !Task.isCancelled else {
                        discardUnownedDraftSource(item.url); throw CancellationError()
                    }
                    if let member, recovery != nil {
                        recovery?.prepared(item, for: member.id)
                        guard saveComposerBatch(recovery, chatId: chatId) else {
                            discardUnownedDraftSource(item.url); throw CocoaError(.fileWriteUnknown)
                        }
                        prepared?(index, item)
                    }
                    if let sendIntent {
                        let kind = itemKinds?[index] ?? sendIntent.kind(isVideo: item.isVideo)
                        let accepted = await queueVisualMedia(id: item.id, chatId: chatId, otherUserId: otherUserId,
                            url: item.url, kind: kind,
                            caption: sendIntent.caption(at: member?.index ?? index), albumId: sendIntent.albumId, albumItemIndex: member?.index ?? index)
                        guard accepted else {
                            discardUnownedDraftSource(item.url)
                            try requireSession()
                            throw CocoaError(.fileWriteUnknown)
                        }
                        if let member {
                            recovery?.accepted(member.id)
                            // Outbox row is authoritative if this best-effort follow-up is interrupted.
                            _ = saveComposerBatch(recovery, chatId: chatId)
                        }
                        admitted?(index, item)
                        continue
                    }
                    let encoded: Data
                    if asFile {
                        encoded = try JSONEncoder().encode(StoredFileDraft(id: item.id, url: item.url, size: Int64(item.bytes)))
                    } else {
                        var drafts = storedMediaDrafts(chatId: chatId)
                        drafts.append(item)
                        encoded = try JSONEncoder().encode(drafts)
                    }
                    guard saveChatValue(chatId: chatId, name: asFile ? "fileDraft" : "mediaDrafts", value: String(decoding: encoded, as: UTF8.self)) else {
                        discardUnownedDraftSource(item.url); throw CocoaError(.fileWriteUnknown)
                    }
                    mediaDraftRevisions[chatId, default: 0] += 1
                }
            } catch is CancellationError {
                // Explicit cancellation/account teardown never becomes a stuck UI flag.
            } catch {
                if mediaPreparationOwnership.owns(chat: chatId, token: token), generation == auth.sessionGeneration {
                    let key = sendIntent == nil ? MediaPreparationFailure.messageKey(error)
                        : (composerBatch == nil ? "Could not add all attachments. Items already added remain in the chat. Select the remaining items again."
                           : "Could not add all attachments. Items already added remain in the chat. The remaining items are kept for retry.")
                    mediaPreparationErrors[chatId] = UnyriL10n.string(key) + " [\(MediaPreparationFailure.diagnosticCode(error))]"
                }
            }
        }
        return true
    }

    func stageDraftFile(_ source: URL, isVideo: Bool = false, coordinated: Bool = false) async throws -> URL { try requireSession(); return try await attachmentManager.stageDraftFile(source, destination: store.draftFileDestination(source), isVideo: isVideo, coordinated: coordinated) }
    func draftFileSize(_ source: URL) async throws -> Int64? { try requireSession(); return try await attachmentManager.draftFileSize(source) }
    private var pendingWorkspace: [String: [String: String]] = [:]
    private var draftSnapshot: [String: [String: String]] = [:]
    private var draftDebounce: Task<Void, Never>?
    private var draftWrite: Task<Void, Never>?
    private var draftWriteID: UUID?
    private var activeDraftWrites: [UUID: Task<Void, Never>] = [:]
    func chatValue(chatId: String, name: String) -> String? {
        draftSnapshot[chatId]?[name] ?? (try? store.chatValue(chatId: chatId, name: name))
    }
    func saveDraftValues(chatId: String, values: [String: String]) {
        guard generation == auth.sessionGeneration else { return }
        pendingWorkspace[chatId, default: [:]].merge(values, uniquingKeysWith: { _, latest in latest })
        draftSnapshot[chatId, default: [:]].merge(values, uniquingKeysWith: { _, latest in latest })
        draftDebounce?.cancel()
        draftDebounce = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: 300_000_000) } catch { return }
            await self?.flushDraftValues()
        }
    }
    func flushDraftValues() async {
        let backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Save chat drafts", expirationHandler: nil)
        defer { if backgroundTask != .invalid { UIApplication.shared.endBackgroundTask(backgroundTask) } }
        draftDebounce?.cancel(); draftDebounce = nil
        await drainCurrentDraftWrites()
        guard generation == auth.sessionGeneration else { pendingWorkspace.removeAll(); return }
        guard !pendingWorkspace.isEmpty else { return }
        let batch = pendingWorkspace
        pendingWorkspace.removeAll()
        let write = store.startWorkspaceWrite(batch)
        let id = UUID()
        let operation = Task { [self] in
            do { try await write.value }
            catch {
                if generation == auth.sessionGeneration {
                    for (chat, values) in batch {
                        for (name, value) in values where draftSnapshot[chat]?[name] == value && pendingWorkspace[chat]?[name] == nil {
                            pendingWorkspace[chat, default: [:]][name] = value
                        }
                    }
                    errorMessage = error.localizedDescription
                }
            }
        }
        draftWrite = operation; draftWriteID = id; activeDraftWrites[id] = operation
        await operation.value
        activeDraftWrites[id] = nil
        if draftWriteID == id { draftWrite = nil; draftWriteID = nil }
        await drainCurrentDraftWrites()
    }
    private func drainCurrentDraftWrites() async {
        // A concurrent flush can replace W1 with W2 while this caller awaits W1.
        // Retire only the exact completed pointer, then await any replacement.
        while let operation = draftWrite {
            let id = draftWriteID
            await operation.value
            if draftWriteID == id { draftWrite = nil; draftWriteID = nil }
        }
    }
    func finishSessionDraftWrites() async {
        reloadTask?.cancel()
        mediaPreparationOwnership.clear()
        for task in mediaPreparationTasks.values { task.cancel() }
        mediaPreparationTasks.removeAll(); preparingMediaChats.removeAll()
        mediaPreparationErrors.removeAll(); mediaDraftRevisions.removeAll()
        invalidatePinnedPreviews()
        draftDebounce?.cancel(); draftDebounce = nil; pendingWorkspace.removeAll()
        await store.finishLegacyLifecycleRepairs()
        for operation in Array(activeDraftWrites.values) { await operation.value }
    }
    func discardUnownedDraftSource(_ url: URL) {
        guard generation == auth.sessionGeneration else { return }
        do { try store.removeUnownedDraftSource(url, attachmentReferenced: attachmentManager.referencesLocalFile(url)) }
        catch { errorMessage = error.localizedDescription }
    }
    @discardableResult func saveChatValue(chatId: String, name: String, value: String?) -> Bool {
        guard generation == auth.sessionGeneration else { return false }
        do {
            guard try !store.isChatDeleted(chatId) else { return false }
            try store.saveChatValue(chatId: chatId, name: name, value: value)
            pendingWorkspace[chatId]?[name] = nil
            draftSnapshot[chatId]?[name] = value
            if name == "pins", pinnedPreviewSessionIsCurrent, currentChatId == chatId {
                // Pin/unpin feedback uses already validated in-memory messages;
                // the following existing reload remains authoritative.
                let known = loadedHistoryChatId == chatId ? messages : []
                pinnedPreviewSnapshot = pinnedPreviewSnapshot.replacingSelection(chatId: chatId,
                    raw: value, loadedMessages: known)
                reload()
            }
            return true
        }
        catch { errorMessage = error.localizedDescription; return false }
    }
    func localMessage(id: String) -> DecryptedChatMessage? {
        let message = (try? store.message(byClientMessageId: id)) ?? (try? store.message(byServerId: id))
        guard let message, (try? store.legacyLifecycleRepairIsComplete(chatId: message.chatId)) == true else { return nil }
        return message
    }
    func scrollBookmark(chatId: String) -> (anchor: String?, followsLatest: Bool, offset: ChatReaderBookmarkOffset?)? { scrollBookmarks[chatId] }
    func rememberScroll(chatId: String, anchor: String?, followsLatest: Bool, offset: ChatReaderBookmarkOffset? = nil) {
        guard generation == auth.sessionGeneration else { return }
        let offset = followsLatest ? nil : offset
        if let previous = scrollBookmarks[chatId], previous.anchor == anchor, previous.followsLatest == followsLatest, previous.offset == offset { return }
        if scrollBookmarks[chatId] == nil, scrollBookmarks.count >= 100, let oldest = scrollBookmarks.keys.first {
            scrollBookmarks.removeValue(forKey: oldest)
        }
        scrollBookmarks[chatId] = (anchor, followsLatest, offset)
    }
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String? { didSet { errorIsConnectivity = false } }
    @Published private(set) var errorIsConnectivity = false
    @Published private(set) var hasMore = false
    @Published private(set) var nextBeforeSequence: Int64?
    /// Active reaction summaries keyed by the target message's SERVER id —
    /// merged into bubbles at render time (Android combines the message flow
    /// with the active-reactions flow).
    @Published private(set) var reactionsByMessage: [String: [ReactionSummary]] = [:]

    private let api: MessagesAPI
    private let auth: AuthRepository
    private let store: MessageStore
    private let ingestor: MessageIngestor
    private let outbox: MessageOutbox
    private let attachmentManager: AttachmentManager

    private var currentChatId: String?
    func boundedChatWasCleared(_ chat: String, cancelled: Set<String>) {
        guard generation == auth.sessionGeneration else { return }
        pendingWorkspace[chat] = nil; draftSnapshot[chat] = nil
        cancelMediaPreparation(chatId:chat)
        for id in cancelled { outbox.cancelMedia(id) }
        scrollBookmarks[chat] = nil
        if currentChatId == chat { reload() }
        Task { [weak self] in
            guard let self else { return }
            do {
                try self.requireSession()
                let cutoff = try self.store.personalCutoff(chat)
                _ = try await self.clearPersonalChat(chat, through:cutoff)
            } catch { /* Durable personal_cleanup markers retry on reconciliation. */ }
        }
    }
    func clearPersonalChat(_ chatId: String, through: Int64) async throws -> Bool {
        try requireSession()
        let ids = try store.personalRemovedIDs(chatId, through: through)
        let cleanupDrafts = try store.workspaceValues(chatId:chatId).filter { $0.key.hasPrefix("clear_draft_cleanup:") }
        if try store.personalCutoff(chatId) >= through, ids.isEmpty, cleanupDrafts.isEmpty { return false }
        // Persist the boundary before awaiting file cleanup: late envelopes cannot return.
        try store.clearPersonalChat(chatId, throughSequence: through)
        for id in ids { directFiles?.offerMessageRemoved(id) }
        for id in ids {
            if let message = try store.message(byClientMessageId:id), message.hiddenLocally, message.sequence == nil { outbox.cancelMedia(id) }
        }
        _ = try await attachmentManager.purgePersonalChat(chatId, messageIDs: ids)
        try requireSession()
        for id in ids { try store.saveChatValue(chatId: chatId, name: "personal_cleanup:" + id, value: nil) }
        for (key, raw) in cleanupDrafts {
            let values = try JSONDecoder().decode([String:String].self,from:Data(raw.utf8))
            var urls = Set<URL>()
            func collect(_ object: Any) {
                if let string = object as? String {
                    if let url = URL(string:string), url.isFileURL { urls.insert(url) }
                    else if (string as NSString).isAbsolutePath { urls.insert(URL(fileURLWithPath:string)) }
                } else if let array = object as? [Any] { array.forEach(collect) }
                else if let object = object as? [String:Any] { object.values.forEach(collect) }
            }
            for name in ["mediaDrafts","fileDraft","voiceDraft"] {
                if let value = values[name], let object = try? JSONSerialization.jsonObject(with:Data(value.utf8)) { collect(object) }
            }
            for url in urls { try store.removeUnownedDraftSource(url,attachmentReferenced:attachmentManager.referencesLocalFile(url)) }
            try store.saveChatValue(chatId:chatId,name:key,value:nil)
        }
        if currentChatId == chatId {
            messages.removeAll { $0.sequence.map { $0 <= through } ?? false }
            invalidatePinnedPreviews()
            reload()
        }
        attachmentCacheRevision &+= 1
        return true
    }
    func purgeDeletedChat(_ chatId: String) async throws {
        try requireSession()
        try store.markChatDeleted(chatId)
        directFiles?.chatRemoved(chatId)
        pendingWorkspace[chatId] = nil; draftSnapshot[chatId] = nil
        for operation in Array(activeDraftWrites.values) { await operation.value }
        try requireSession()
        try await outbox.cancelDeletedChat(chatId)
        let attachmentURLs = try await attachmentManager.purgeDeletedChat(chatId)
        try requireSession()
        let draftURLs = try store.purgeDeletedChat(chatId)
        for url in attachmentURLs.union(draftURLs) { discardUnownedDraftSource(url) }
        scrollBookmarks[chatId] = nil; visibleReadSequences[chatId] = nil
        if visibleChatId == chatId { visibleChatId = nil }
        if currentChatId == chatId {
            currentChatId = nil; historyRequest += 1; isLoading = false
            messages = []; failedActions = []; scheduledDates = [:]; cachedAttachmentPaths = [:]; reactionsByMessage = [:]
            invalidatePinnedPreviews()
            loadedHistoryChatId = nil
        }
        attachmentCacheRevision &+= 1
    }
    /// The chat currently being viewed — used by the foreground message banner
    /// to suppress itself when the incoming message belongs to the open chat.
    private var visibleChatId: String?
    var isCallExpanded = false {
        didSet {
            if !isCallExpanded, let chat = visibleChatId { Task { await markReadLatest(chatId: chat) } }
        }
    }
    private var visibleReadSequences: [String: Int64] = [:]
    func setVisibleReadSequence(chatId: String, sequence: Int64) {
        guard activeChatId == chatId else { return }
        guard visibleReadSequences[chatId] != sequence else { return }
        visibleReadSequences[chatId] = sequence
        Task { await markReadLatest(chatId: chatId) }
    }
    private var readPresentationAllowed: Bool {
        let identity = auth.currentUser.map { auth.authenticatedServerURL.absoluteString + "|" + $0.id + "|" + String(auth.sessionGeneration) }
        return UnyriContentVisibilityPolicy.allowsRead(
            sessionCurrent: generation == auth.sessionGeneration && ownerId == auth.currentUser?.id && ownerServer == auth.authenticatedServerURL,
            foreground: UIApplication.shared.applicationState == .active,
            shielded: UnyriAppLock.shared.requiresShield(owner: identity))
    }
    var activeChatId: String? { isCallExpanded || !readPresentationAllowed ? nil : visibleChatId }

    func presentationVisibilityChanged() async {
        guard readPresentationAllowed else { return }
        if let chat = activeChatId { await markReadLatest(chatId: chat) }
        await flushReadReceipts()
    }

    func setChatVisible(_ chatId: String, visible: Bool) {
        if visible {
            let returning = visibleChatId != chatId
            visibleChatId = chatId
            if returning { attachmentManager.invalidatePathSnapshot() }
            // Ingestion continues off-screen; rebuild presentation only on return.
            if returning, currentChatId == chatId { reload() }
            Task { await markReadLatest(chatId: chatId) }
        }
        else if visibleChatId == chatId {
            visibleChatId = nil
            reloadRevision += 1 // Reject an in-flight snapshot before attachment scanning/publication.
            reloadTask?.cancel()
            reloadTask = nil
            reloadWorkerID &+= 1
        }
    }
    private let generation: UInt64
    private let ownerId: String
    private let ownerServer: URL
    private var historyRequest = 0
    private var readWatermarks: [String: Int64] = [:]
    private var flushingReadReceipts = false
    private var receiptRetryTask: Task<Void, Never>?
    var onReadStateChange: (() -> Void)?

    func applyAccountReadReceipt(chatId: String, sequence: Int64) {
        guard generation == auth.sessionGeneration, sequence > 0 else { return }
        do {
            if try store.markLocallyRead(chatId: chatId, sequence: sequence) { onReadStateChange?() }
            readWatermarks[chatId] = max(readWatermarks[chatId] ?? 0, sequence)
        } catch { /* A later receipt or local read can retry persistence. */ }
    }
    private var changesCancellable: AnyCancellable?
    private var reloadTask: Task<Void, Never>?
    private var reloadWorkerID: UInt64 = 0
    private var reloadRevision = 0
    // Successful publication key; no Published state or UI work on this marker.
    private var publishedSnapshot: (chatId: String, revision: Int, windowLimit: Int, anchor: String?)?
    private var repairedLifecycleChats = Set<String>()
    private var diagnosedPendingRows = Set<String>()

    init(
        api: MessagesAPI,
        auth: AuthRepository,
        store: MessageStore,
        ingestor: MessageIngestor,
        outbox: MessageOutbox,
        attachmentManager: AttachmentManager
    ) {
        self.api = api
        self.auth = auth
        self.store = store
        self.ingestor = ingestor
        self.outbox = outbox
        self.attachmentManager = attachmentManager
        self.generation = auth.sessionGeneration
        self.ownerId = auth.currentUser?.id ?? ""
        self.ownerServer = auth.authenticatedServerURL
        receiptRetryTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let generation = self?.generation, generation == self?.auth.sessionGeneration else { return }
                await self?.flushReadReceipts()
                do { try await Task.sleep(nanoseconds: 15_000_000_000) }
                catch { return }
            }
        }

        // Realtime path: when the shared ingestor persists a WS/sync message,
        // or the outbox reconciles a send, reload the open chat from the store
        // (the iOS equivalent of Android observing the Room flow).
        changesCancellable = ingestor.changes
            .merge(with: outbox.changes)
            .sink { [weak self] touched in
                self?.observedMessageChanges(touched)
            }
    }

    // MARK: - History

    func loadHistory(chatId: String) async {
        guard generation == auth.sessionGeneration else { return }
        #if os(iOS)
        let performanceSpan = PerformanceRecorder.shared.begin(.historyLoad, ["direction": 0])
        var performanceSuccess = false
        defer { PerformanceRecorder.shared.end(performanceSpan, success: performanceSuccess) }
        #endif
        historyRequest += 1
        let request = historyRequest
        if currentChatId != chatId {
            localHistoryLimit = 200
            hasMoreLocalHistory = false
            loadedHistoryChatId = nil
            messages = []; failedActions = []
            reactionsByMessage = [:]
            invalidatePinnedPreviews()
        }
        currentChatId = chatId
        // Preserve the last reading position before publishing the first page.
        if let bookmark = scrollBookmarks[chatId], !bookmark.followsLatest, let anchor = bookmark.anchor {
            if let size = try? await store.readAsync({ try $0.bookmarkWindowSize(chatId: chatId, anchor: anchor) }) {
                guard request == historyRequest, generation == auth.sessionGeneration, !Task.isCancelled else { return }
                localHistoryLimit = max(localHistoryLimit, min(Int(Int32.max) - 1, size + 50))
            }
        }
        guard request == historyRequest, currentChatId == chatId,
              generation == auth.sessionGeneration, !Task.isCancelled else { return }
        hasMore = false
        serverHasMore = false
        nextBeforeSequence = nil
        reload() // Cached messages stay readable even when the server is offline.
        isLoading = true
        defer { if request == historyRequest { isLoading = false } }
        errorMessage = nil
        do {
            try await ingestor.ensureBootstrap()
            let response = try await api.listMessages(
                accessToken: try await auth.currentAccessToken(),
                chatId: chatId,
                beforeSequence: nil,
                limit: 50
            )
            try requireSession()
            guard request == historyRequest else { return }
            let ingestCompleted = await ingestor.ingest(response.messages, advanceCursor: false)
            guard request == historyRequest else { return }
            serverHasMore = response.hasMore
            hasMore = serverHasMore || hasMoreLocalHistory
            nextBeforeSequence = response.nextBeforeSequence
            reloadAfterHistoryIngest(chatId: chatId, ingestCompleted: ingestCompleted)
            await confirmDelivery(chatId: chatId)
            await markReadLatest(chatId: chatId)
            #if os(iOS)
            performanceSuccess = true
            #endif
        } catch {
            guard request == historyRequest, generation == auth.sessionGeneration else { return }
            errorMessage = (error as? APIError)?.errorDescription ?? error.localizedDescription
            if case .network = error as? APIError { errorIsConnectivity = true }
        }
    }

    func loadOlder(chatId: String) async {
        guard hasMore, currentChatId == chatId,
              generation == auth.sessionGeneration else { return }
        if hasMoreLocalHistory {
            // Cached pages must remain available while the initial network
            // request is slow or offline. Do not share its loading lock.
            guard localPagingChatId != chatId else { return }
            localPagingChatId = chatId
            defer { if localPagingChatId == chatId { localPagingChatId = nil } }
            localHistoryLimit = min(Int(Int32.max) - 1, localHistoryLimit + 200)
            reload()
            await reloadTask?.value
            return
        }
        guard !isLoading, let before = nextBeforeSequence else { return }
        #if os(iOS)
        let performanceSpan = PerformanceRecorder.shared.begin(.historyLoad, ["direction": 1])
        var performanceSuccess = false
        defer { PerformanceRecorder.shared.end(performanceSpan, success: performanceSuccess) }
        #endif
        let request = historyRequest
        isLoading = true
        defer { if request == historyRequest { isLoading = false } }
        do {
            let response = try await api.listMessages(
                accessToken: try await auth.currentAccessToken(),
                chatId: chatId,
                beforeSequence: before,
                limit: 50
            )
            try requireSession()
            guard request == historyRequest else { return }
            _ = await ingestor.ingest(response.messages, advanceCursor: false)
            guard request == historyRequest else { return }
            serverHasMore = response.hasMore
            hasMore = serverHasMore || hasMoreLocalHistory
            nextBeforeSequence = response.nextBeforeSequence
            localHistoryLimit = min(Int(Int32.max) - 1, localHistoryLimit + 200)
            reload()
            await confirmDelivery(chatId: chatId)
            #if os(iOS)
            performanceSuccess = true
            #endif
        } catch {
            // Keep the already-loaded page; a later retry can resume.
        }
    }

    // MARK: - Send

    /// Fast-path foreground refresh for the open chat. The WebSocket + /sync
    /// layer is the primary realtime source; this 3s REST refresh only covers
    /// the open screen while the app is active (and is superseded by the
    /// changes path once the shared transport is connected).
    func refreshLatest(chatId: String) async {
        guard currentChatId == chatId, !isLoading, generation == auth.sessionGeneration else { return }
        do {
            try await ingestor.ensureBootstrap()
            let token = try await auth.currentAccessToken()
            try requireSession()
            let response = try await api.listMessages(accessToken: token, chatId: chatId, beforeSequence: nil, limit: 50)
            try requireSession()
            guard currentChatId == chatId else { return }
            let ingestCompleted = await ingestor.ingest(response.messages, advanceCursor: false)
            guard currentChatId == chatId else { return }
            errorMessage = nil
            reloadAfterHistoryIngest(chatId: chatId, ingestCompleted: ingestCompleted)
            await confirmDelivery(chatId: chatId)
            await markReadLatest(chatId: chatId)
        } catch {
            // Preserve cached history and pagination when connectivity is lost.
        }
    }

    @discardableResult
    func sendText(chatId: String, otherUserId: String?, text: String) async -> Bool {
        #if os(iOS)
        let span = PerformanceRecorder.shared.begin(.messageSend)
        var success = false
        defer { PerformanceRecorder.shared.end(span, success: success) }
        #endif
        guard generation == auth.sessionGeneration else { return false }
        guard admitTextAction(text) else { return false }
        let queued = outbox.enqueueText(chatId: chatId, otherUserId: otherUserId, text: text)
        #if os(iOS)
        success = queued
        #endif
        if !queued { errorMessage = "Could not save the message. Your draft has been kept." }
        reload()
        return queued
    }

    /// Queues exactly one logical invitation through the normal durable Signal
    /// outbox. The ID/capability do not change when transport is retried.
    @discardableResult
    func sendConferenceInvite(chat: ChatSummary, metadata: ConferenceInvitationMetadata) -> Bool {
        guard generation == auth.sessionGeneration, chat.chatType == "direct", chat.isBlocked != true,
              (try? metadata.validated(currentServer: AppConfig.apiBaseURL)) != nil else { return false }
        let content = MessageContent(kind: MessageContent.kindText,
            text: UnyriL10n.string("Conference invitation"), conferenceInvite: metadata)
        let queued = outbox.enqueueContent(chatId: chat.chatId, otherUserId: chat.otherUser?.id,
            content: content, targetMessageId: nil)
        if !queued { errorMessage = "Could not save the message. Your draft has been kept." }
        reload()
        return queued
    }

    /// Surfaces a transient error to the open chat screen (e.g. a failed
    /// attachment prepare/upload).
    func reportError(_ message: String) {
        errorMessage = message
    }

    /// Sends a message whose content is an ALREADY-uploaded/completed
    /// attachment. The durable outbox delivers it exactly like a text message
    /// (the attachment's `attachment_id` is the only server-visible reference;
    /// crypto metadata stays inside the Signal-encrypted content).
    @discardableResult
    func sendAttachment(
        chatId: String,
        otherUserId: String?,
        clientMessageId: String,
        attachment: AttachmentMetadata,
        caption: String? = nil
    ) async -> Bool {
        guard generation == auth.sessionGeneration, !Task.isCancelled else { return false }
        guard admitTextAction(caption, caption: true) else { return false }
        let content = MessageContent(kind: MessageContent.kindAttachment, text: caption, attachment: attachment)
        let queued = outbox.enqueueContent(
            chatId: chatId,
            otherUserId: otherUserId,
            content: content,
            targetMessageId: nil,
            clientMessageId: clientMessageId
        )
        reload()
        return queued
    }

    /// Downloads (if needed), verifies and decrypts an attachment to a local
    /// file — tap-to-download, served from cache on repeat calls.
    func cancelDownloadAttachment(_ id: String) { attachmentManager.cancelDownload(id) }
    @Published private(set) var attachmentCacheRevision = 0
    func invalidateLocalMediaCache() {
        cachedAttachmentPaths = cachedAttachmentPaths.filter { FileManager.default.fileExists(atPath: $0.value) }
        attachmentCacheRevision &+= 1
    }
    func downloadAttachment(attachmentId: String, origin: AttachmentDownloadOrigin = .unspecified) async -> URL? {
        guard !Task.isCancelled, generation == auth.sessionGeneration else { return nil }
        do {
            let url = try await attachmentManager.download(attachmentId: attachmentId, origin: origin)
            guard !Task.isCancelled, generation == auth.sessionGeneration else { return nil }
            cachedAttachmentPaths[attachmentId] = url.path
            attachmentCacheRevision &+= 1
            return url
        } catch {
            return nil
        }
    }

    /// Synchronous lookup of an already-cached decrypted local path (nil when
    /// not yet downloaded or the file is gone) — bubble affordance state.
    func cachedLocalPath(attachmentId: String) -> String? {
        guard generation == auth.sessionGeneration else { return nil }
        return cachedAttachmentPaths[attachmentId]
    }

    func galleryAttachments(chatId: String) -> [AttachmentRecord] {
        guard generation == auth.sessionGeneration else { return [] }
        return attachmentManager.galleryAttachments(chatId: chatId)
    }

    /// Re-sends a failed message with the SAME `clientMessageId` (server
    /// idempotency) and the same plaintext. Envelopes are re-encrypted, which
    /// is safe: the server dedups on `(sender_device_id, client_message_id)`
    /// and discards a duplicate's new envelopes.
    func retrySend(clientMessageId: String, chatId: String, otherUserId: String?, content: MessageContent, targetMessageId: String? = nil) async {
        guard generation == auth.sessionGeneration else { return }
        outbox.retry(clientMessageId: clientMessageId, chatId: chatId,
            otherUserId: otherUserId, content: content, targetMessageId: targetMessageId)
        reload()
    }

    // MARK: - Message lifecycle (mirror Android MessageRepositoryImpl, Этап I)

    /// Replies with `text`, quoting the target via a self-contained snapshot
    /// kept inside the SAME Signal-encrypted content (so rendering a reply
    /// never depends on the original message still being present). Sent via
    /// the durable outbox like any content message.
    @discardableResult
    func reply(chatId: String, otherUserId: String?, text: String, replyToClientMessageId: String) async -> Bool {
        guard generation == auth.sessionGeneration else { return false }
        guard let target = try? store.message(byClientMessageId: replyToClientMessageId) else {
            errorMessage = "Reply target not found locally."
            return false
        }
        guard let targetServerId = target.serverId else {
            errorMessage = "Cannot reply to a message that hasn't been sent yet."
            return false
        }
        guard admitTextAction(text) else { return false }
        let snapshot = buildReplySnapshot(for: target)
        let content = MessageContent(
            kind: MessageContent.kindText,
            text: text,
            replySnapshot: snapshot
        )
        let queued = outbox.enqueueContent(
            chatId: chatId,
            otherUserId: otherUserId,
            content: content,
            targetMessageId: targetServerId
        )
        if !queued { errorMessage = "Could not save the reply. Your draft has been kept." }
        reload()
        return queued
    }

    /// Persist the local edit intent; confirmed state changes only on acceptance.
    /// Before the first POST, the edit is simply the text. After an attempt may
    /// have reached the server, the original payload is frozen and the latest
    /// text becomes a durable intent applied after `serverId` arrives.
    func editMessage(clientMessageId: String, newText: String) async -> Bool {
        guard generation == auth.sessionGeneration else { return false }
        guard let target = try? store.message(byClientMessageId: clientMessageId) else {
            errorMessage = "Message not found."
            return false
        }
        guard target.kind == "content", target.deleteSequence == 0, !target.hiddenLocally,
              target.content.conferenceInvite == nil, target.content.directFile == nil else {
            errorMessage = "Message cannot be edited."
            return false
        }
        let trimmed: String
        do { trimmed = try MessageTextPolicy.outgoing(newText) }
        catch { _ = admitTextAction(newText); return false }

        if let targetServerId = target.serverId {
            do {
                try outbox.enqueueLifecycle(
                    chatId: target.chatId,
                    targetServerId: targetServerId,
                    kind: "edit",
                    plaintext: MessageContentCodec.encodeEdit(trimmed)
                )
                try requireSession()
            } catch {
                guard generation == auth.sessionGeneration else { return false }
                errorMessage = (error as? APIError)?.errorDescription ?? error.localizedDescription
                if case .network = error as? APIError { errorIsConnectivity = true }
                reload()
                return false
            }
            reload()
            return true
        }

        // Editing media replacement is out of scope; text captions on an
        // unsent attachment are not silently rewritten.
        guard target.content.attachment == nil else {
            errorMessage = "Attachments cannot be edited before sending."
            return false
        }
        var content = target.content
        content.text = trimmed
        do {
            if try store.updateTextBeforeSend(clientMessageId: clientMessageId, content: content) {
                reload()
                return true
            }
            // Legacy rows may be marked attempted without a frozen payload.
            // Freeze the current text before replacing the visible text.
            if target.payloadText == nil {
                try store.beginSend(clientMessageId: clientMessageId, fallbackText: target.content.text)
            }
            if try store.setPendingEdit(clientMessageId: clientMessageId, content: content,
                                       revision: target.pendingEditRevision + 1) {
                reload()
                return true
            }
            errorMessage = "Message cannot be edited."
            return false
        } catch {
            guard generation == auth.sessionGeneration else { return false }
            errorMessage = error.localizedDescription
            reload()
            return false
        }
    }

    /// Hide through a durable local intent, then commit the server tombstone.
    @discardableResult func deleteForEveryone(clientMessageId: String) async -> Bool {
        guard generation == auth.sessionGeneration else { return false }
        guard let target = try? store.message(byClientMessageId: clientMessageId) else { return false }
        guard let targetServerId = target.serverId else {
            return deleteForMe(clientMessageId: clientMessageId)
        }
        do {
            try store.clearPendingEdit(clientMessageId: clientMessageId)
            try outbox.enqueueLifecycle(
                chatId: target.chatId,
                targetServerId: targetServerId,
                kind: "delete",
                plaintext: MessageContentCodec.encodeDelete()
            )
            directFiles?.offerMessageRemoved(clientMessageId)
            try requireSession()
        } catch {
            guard generation == auth.sessionGeneration else { return false }
            errorMessage = (error as? APIError)?.errorDescription ?? error.localizedDescription
            if case .network = error as? APIError { errorIsConnectivity = true }
            reload()
            return false
        }
        reload()
        return true
    }

    /// "Delete for me" — local-only, never leaves the device (Этап I п.15).
    func dismissError() { errorMessage = nil }

    @discardableResult func deleteForMe(clientMessageId: String) -> Bool {
        guard generation == auth.sessionGeneration else { return false }
        do {
            let wasPending = try store.message(byClientMessageId: clientMessageId)?.status == .pending
            try store.clearPendingEdit(clientMessageId: clientMessageId)
            try store.hideLocally(clientMessageId: clientMessageId)
            directFiles?.offerMessageRemoved(clientMessageId)
            if wasPending { outbox.cancelMedia(clientMessageId) }
            reload()
            return true
        } catch {
            errorMessage = UnyriL10n.string("Could not cancel or remove the message. Try again.", comment: "")
            return false
        }
    }

    /// Cancellation is local outbox work, distinct from deleting delivered messages.
    @discardableResult func cancelUnsentMessages(chatId: String, clientMessageIds: [String]) -> Bool {
        guard generation == auth.sessionGeneration, let owner = auth.currentUser?.id else { return false }
        do {
            let cancelled = try store.cancelUnsentMessages(chatId: chatId, owner: owner, ids: clientMessageIds)
            // Only committed cancellations may retire workers/source ownership.
            // Includes failed/retry jobs as well as active preparation/upload.
            for id in cancelled { outbox.cancelMedia(id) }
            reload()
            return true
        } catch {
            errorMessage = UnyriL10n.string("Could not cancel or remove the message. Try again.", comment: "")
            return false
        }
    }

    func setReaction(clientMessageId: String, emoji: String) async {
        await applyOwnReaction(clientMessageId: clientMessageId, emoji: emoji, removing: false)
    }

    func removeReaction(clientMessageId: String) async {
        await applyOwnReaction(clientMessageId: clientMessageId, emoji: nil, removing: true)
    }

    private func applyOwnReaction(clientMessageId: String, emoji: String?, removing: Bool) async {
        #if os(iOS)
        let reactionOperation = PerformanceRecorder.shared.operation(clientMessageId)
        PerformanceRecorder.shared.record(.action, ["phase": 4, "operation": reactionOperation, "status": removing ? 0 : 1])
        #endif
        guard generation == auth.sessionGeneration else { return }
        guard let target = try? store.message(byClientMessageId: clientMessageId) else { return }
        // A reaction needs a server target; it must never cancel an outbox row.
        guard let targetServerId = target.serverId else { return }
        do {
            let plaintext = removing
                ? MessageContentCodec.encodeReactionRemoval()
                : MessageContentCodec.encodeReaction(emoji ?? "")
            try outbox.enqueueLifecycle(
                chatId: target.chatId,
                targetServerId: targetServerId,
                kind: "reaction",
                plaintext: plaintext
            )
            try requireSession()
            #if os(iOS)
            PerformanceRecorder.shared.record(.action, ["phase": 5, "operation": reactionOperation, "success": 1])
            #endif
        } catch {
            #if os(iOS)
            PerformanceRecorder.shared.record(.action, ["phase": 6, "operation": reactionOperation, "success": 0])
            #endif
            guard generation == auth.sessionGeneration else { return }
            errorMessage = (error as? APIError)?.errorDescription ?? error.localizedDescription
            if case .network = error as? APIError { errorIsConnectivity = true }
        }
        reload()
    }

    func reactions(forMessageServerId serverId: String?) -> [ReactionSummary] {
        guard let serverId else { return [] }
        return reactionsByMessage[serverId] ?? []
    }

    /// Android's `buildReplySnapshot`: sender + content-type + a short preview
    /// (truncated text, or a per-media-type label) + the target's sequence as
    /// a jump hint. No server dependency on the original message.
    private func buildReplySnapshot(for target: DecryptedChatMessage) -> ReplySnapshot {
        let contentType: String
        let preview: String
        switch target.content.kind {
        case MessageContent.kindAttachment:
            switch target.content.attachment?.mediaType {
            case AttachmentMetadata.mediaTypeImage:
                contentType = ReplySnapshot.contentTypeImage
                preview = "Photo"
            case AttachmentMetadata.mediaTypeVoice:
                contentType = ReplySnapshot.contentTypeVoice
                preview = "Voice message"
            default:
                contentType = ReplySnapshot.contentTypeFile
                preview = "File"
            }
        default:
            contentType = ReplySnapshot.contentTypeText
            preview = MessageTextPolicy.replyPreview(target.content.conferenceInvite != nil ? UnyriL10n.string(ConferenceInvitationMetadata.fallbackText) : target.content.text ?? "")
        }
        return ReplySnapshot(
            senderId: target.senderId,
            contentType: contentType,
            preview: preview,
            sequenceHint: target.sequence
        )
    }

    // MARK: - Helpers

    /// Acknowledge durable plaintext, never failed decryptions. The server's
    /// cumulative watermark is monotonic; failures retry on the next refresh.
    private func confirmDelivery(chatId: String) async {
        guard generation == auth.sessionGeneration else { return }
        // History, realtime ingest and the retry worker share the same ACK
        // cursor and in-flight request, rather than confirming independently.
        await ingestor.confirmDelivery(chatId: chatId)
    }

    /// Marks every OTHER-party message up to the newest visible sequence as
    /// read (the server's forward-only cumulative watermark). Called from the
    /// history load, the 3s foreground refresh, and the realtime changes path;
    /// the local watermark suppresses duplicate POSTs.
    func markReadLatest(chatId: String) async {
        guard generation == auth.sessionGeneration, activeChatId == chatId else { return }
        do {
            let upperBound = visibleReadSequences[chatId] ?? 0
            guard upperBound > 0 else { return }
            try requireSession()
            guard activeChatId == chatId else { return }
            // Viewing an unavailable placeholder clears its badge, without using
            // that placeholder as the remote read-receipt target.
            // A receipt/progress update does not mean that the user read a new
            // message. Retry unchanged durable cursors through the existing
            // periodic/reconnect worker instead of scanning every chat again.
            guard try store.markLocallyRead(chatId: chatId, sequence: upperBound) else { return }
            onReadStateChange?()
            await flushReadReceipts()
        } catch {
            // Receipt transport must not hide already saved messages.
        }
    }

    /// Local read cursors survive process death and retry even when their chat is closed.
    func flushReadReceipts() async {
        guard generation == auth.sessionGeneration, !flushingReadReceipts else { return }
        flushingReadReceipts = true
        defer { flushingReadReceipts = false }
        do {
            // A locked or inactive screen cannot read messages. Durable own-read
            // cursors stay queued; delivery confirmation below remains independent.
            if readPresentationAllowed {
                let pending = try await store.readAsync { try $0.localReadWatermarks() }
                try requireSession()
                for (chat, pendingSequence) in pending {
                    guard readPresentationAllowed else { break }
                    do {
                        let owner = ownerId
                        let sequence = try await store.readAsync {
                            try $0.receiptSequence(chatId: chat, ownerId: owner, deliveredOnly: false, upperBound: pendingSequence)
                        }
                        try requireSession()
                        guard readPresentationAllowed else { break }
                        guard sequence > (readWatermarks[chat] ?? 0) else { continue }
                        let token = try await auth.currentAccessToken()
                        try requireSession()
                        guard readPresentationAllowed else { break }
                        _ = try await api.markRead(accessToken: token, chatId: chat, sequence: sequence)
                        try requireSession()
                        readWatermarks[chat] = max(readWatermarks[chat] ?? 0, sequence)
                    } catch {
                        guard generation == auth.sessionGeneration, !Task.isCancelled else { return }
                    }
                }
            }
            let chats = try await store.readAsync { Array(try $0.latestActivityByChat().keys) }
            try requireSession()
            for chat in chats { await confirmDelivery(chatId: chat) }
        } catch { /* Durable cursors remain queued. */ }
    }

    /// A duplicate history page need not rebuild a snapshot already published
    /// for every known change. Active or failed reads keep the existing retry/coalescing path.
    private func reloadAfterHistoryIngest(chatId: String, ingestCompleted: Bool) {
        let anchor = scrollBookmarks[chatId]?.followsLatest == false ? messages.first?.clientMessageId : nil
        if ingestCompleted, pinnedPreviewSessionIsCurrent, currentChatId == chatId, visibleChatId == chatId,
           loadedHistoryChatId == chatId, reloadTask == nil, let published = publishedSnapshot,
           published.chatId == chatId, published.revision == reloadRevision,
           published.windowLimit == localHistoryLimit, published.anchor == anchor { return }
        reload()
    }

    private func reload() {
        reloadRevision += 1
        guard let chatId = currentChatId, visibleChatId == chatId else { return }
        guard reloadTask == nil else { return }
        reloadWorkerID &+= 1
        let workerID = reloadWorkerID
        reloadTask = Task { [weak self] in
            guard let self else { return }
            defer { if self.reloadWorkerID == workerID { self.reloadTask = nil } }
            while !Task.isCancelled, let chatId = self.currentChatId, self.visibleChatId == chatId,
                  self.generation == self.auth.sessionGeneration {
                #if os(iOS)
                let performanceSpan = PerformanceRecorder.shared.begin(.uiUpdate)
                var performanceSuccess = false
                var performanceItems = 0
                var performanceOutcome = 0
                var stageOperation = 1.0
                var stageSpan = PerformanceRecorder.shared.begin(.uiUpdate, ["operation": stageOperation])
                defer { PerformanceRecorder.shared.end(performanceSpan, success: performanceSuccess, metrics: ["items": Double(performanceItems), "status": Double(performanceOutcome)]) }
                #endif
                let revision = self.reloadRevision
                let owner = self.ownerId
                let windowLimit = self.localHistoryLimit
                // Incoming messages must not evict the top of the loaded
                // portion while the person is reading older history.
                let preservedAnchor: String? = self.scrollBookmarks[chatId]?.followsLatest == false
                    ? self.messages.first?.clientMessageId : nil
                do {
                    if !self.repairedLifecycleChats.contains(chatId) {
                        try await self.store.repairLegacyLifecycle(chatId: chatId)
                        try self.requireSession()
                        self.repairedLifecycleChats.insert(chatId)
                    }
                    let snapshot = try await self.store.readAsync { reader in
                        let raw = try {
                            #if os(iOS)
                            let span = PerformanceRecorder.shared.begin(.uiUpdate, ["operation": 4])
                            var succeeded = false
                            defer { PerformanceRecorder.shared.end(span, success: succeeded) }
                            #endif
                            let anchorSize = try preservedAnchor.flatMap { try reader.messageWindowSize(chatId: chatId, anchor: $0) } ?? 0
                            let window = try reader.messageWindow(forChatId: chatId, limit: max(windowLimit, anchorSize))
                            let targets = Set(window.messages.compactMap(\.serverId))
                            let value = (try reader.workspaceValues(chatId: chatId), window,
                                         try reader.activeReactions(forChatId: chatId, targetServerIds: targets), try reader.failedLifecycle(chatId: chatId))
                            #if os(iOS)
                            succeeded = true
                            #endif
                            return value
                        }()
                        #if os(iOS)
                        let prepareSpan = PerformanceRecorder.shared.begin(.uiUpdate, ["operation": 5])
                        defer { PerformanceRecorder.shared.end(prepareSpan) }
                        #endif
                        let workspace = raw.0
                        let schedule: [String: Date] = Dictionary(uniqueKeysWithValues: workspace.compactMap { name, raw in
                            guard name.hasPrefix("schedule:") else { return nil }
                            let date = Double(raw).flatMap { $0.isFinite ? Date(timeIntervalSince1970: $0 / 1000) : nil } ?? .distantFuture
                            return (String(name.dropFirst(9)), date)
                        })
                        return (raw.1.messages,
                         Self.summarize(raw.2, myUserId: owner),
                         schedule, raw.3,
                         QueuedMediaPreview.decodeWorkspace(workspace), raw.1.hasMore,
                          try ChatPinnedPreviewSnapshot.read(reader: reader, chatId: chatId, workspace: workspace))
                    }
                    #if os(iOS)
                    PerformanceRecorder.shared.end(stageSpan, metrics: ["operation": stageOperation, "items": Double(snapshot.0.count)])
                    stageSpan = nil
                    #endif
                    try self.requireSession()
                    // Newer events already invalidated this snapshot. Do not scan all cached
                    // attachment paths for a result that cannot be presented anyway.
                    guard revision == self.reloadRevision, chatId == self.currentChatId,
                          self.visibleChatId == chatId else {
                        #if os(iOS)
                        performanceOutcome = 2 // Superseded snapshot, not a database failure.
                        #endif
                        continue
                    }
                    #if os(iOS)
                    stageOperation = 2
                    stageSpan = PerformanceRecorder.shared.begin(.uiUpdate, ["operation": stageOperation])
                    #endif
                    let attachmentIDs = Set(snapshot.0.compactMap { $0.content.attachment?.attachmentId })
                    let paths = try await self.attachmentManager.cachedPathsAsync(chatId: chatId, attachmentIDs: attachmentIDs)
                    #if os(iOS)
                    PerformanceRecorder.shared.end(stageSpan, metrics: ["operation": stageOperation, "items": Double(paths.count)])
                    stageSpan = nil
                    #endif
                    try self.requireSession()
                    if revision == self.reloadRevision && chatId == self.currentChatId && self.visibleChatId == chatId {
                        #if os(iOS)
                        stageOperation = 3
                        stageSpan = PerformanceRecorder.shared.begin(.uiUpdate, ["operation": stageOperation])
                        #endif
                        let previewsChanged = self.queuedMediaPreviews != snapshot.4
                        let schedulesChanged = self.scheduledDates != snapshot.2
                        let reactionsChanged = self.reactionsByMessage != snapshot.1
                        if MessageIntrinsicGeometry.requiresRevision(previewsChanged: previewsChanged,
                            oldPreviews: self.queuedMediaPreviews, newPreviews: snapshot.4,
                            schedulesChanged: schedulesChanged, reactionsChanged: reactionsChanged,
                            oldReactions: self.reactionsByMessage, newReactions: snapshot.1) {
                            self.intrinsicAuxiliaryRevision &+= 1
                        }
                        if previewsChanged { self.queuedMediaPreviews = snapshot.4 }
                        if schedulesChanged { self.scheduledDates = snapshot.2 }
                        if self.cachedAttachmentPaths != paths { self.cachedAttachmentPaths = paths; self.attachmentCacheRevision &+= 1 }
                        if self.failedActions != snapshot.3 { self.failedActions = snapshot.3 }
                        self.publishPinnedPreviews(snapshot.6, chatId: chatId, revision: revision)
                        if self.messages != snapshot.0 { self.messages = snapshot.0 }
                        if self.hasMoreLocalHistory != snapshot.5 { self.hasMoreLocalHistory = snapshot.5 }
                        let more = self.serverHasMore || snapshot.5
                        if self.hasMore != more { self.hasMore = more }
                        #if os(iOS)
                        // Publication is not proof that a GPU frame was displayed.
                        if PerformanceRecorder.shared.isEnabled {
                            let pendingIDs = Set(snapshot.0.lazy.filter { $0.status == .pending }.map(\.clientMessageId))
                            for id in pendingIDs.subtracting(self.diagnosedPendingRows) {
                                PerformanceRecorder.shared.record(.action, ["trace": PerformanceRecorder.shared.operation(id), "phase": 2])
                            }
                            self.diagnosedPendingRows = pendingIDs
                        } else { self.diagnosedPendingRows.removeAll() }
                        #endif
                        if reactionsChanged { self.reactionsByMessage = snapshot.1 }
                        if self.loadedHistoryChatId != chatId { self.loadedHistoryChatId = chatId }
                        self.publishedSnapshot = (chatId, revision, windowLimit, preservedAnchor)
                        #if os(iOS)
                        PerformanceRecorder.shared.end(stageSpan, metrics: ["operation": stageOperation, "items": Double(snapshot.0.count)])
                        stageSpan = nil
                        performanceSuccess = true; performanceItems = snapshot.0.count; performanceOutcome = 1
                        #endif
                    } else {
                        #if os(iOS)
                        performanceOutcome = 2
                        #endif
                    }
                } catch {
                    #if os(iOS)
                    PerformanceRecorder.shared.end(stageSpan, success: false, metrics: ["operation": stageOperation])
                    performanceOutcome = error is CancellationError ? 4 : 3
                    #endif
                    /* Keep the last usable snapshot on a transient read failure. */
                }
                if revision == self.reloadRevision { break }
            }
        }
    }

    func retryFailedAction(_ action: DecryptedChatMessage) {
        guard generation == auth.sessionGeneration, action.chatId == currentChatId else { return }
        outbox.retry(clientMessageId: action.clientMessageId, chatId: action.chatId,
                     otherUserId: nil, content: action.content, targetMessageId: action.targetMessageId)
        reload()
    }

    /// Android's combine(observeMessages, observeActiveForChat) mapping:
    /// group active reactions by emoji per target, count users, flag ours.
    nonisolated private static func summarize(_ rows: [ReactionRow], myUserId: String) -> [String: [ReactionSummary]] {
        var byTarget: [String: [String: [ReactionRow]]] = [:]
        var emojiOrder: [String: [String]] = [:]
        for row in rows {
            guard let emoji = row.emoji, MessageTextPolicy.isDisplayableReaction(emoji) else { continue }
            if byTarget[row.targetMessageId]?[emoji] == nil {
                emojiOrder[row.targetMessageId, default: []].append(emoji)
            }
            byTarget[row.targetMessageId, default: [:]][emoji, default: []].append(row)
        }
        var result: [String: [ReactionSummary]] = [:]
        for (target, byEmoji) in byTarget {
            result[target] = (emojiOrder[target] ?? []).compactMap { emoji in
                guard let users = byEmoji[emoji] else { return nil }
                return ReactionSummary(emoji: emoji, count: users.count, isMine: users.contains { $0.userId == myUserId })
            }
        }
        return result
    }

    private func requireSession() throws {
        try Task.checkCancellation()
        guard generation == auth.sessionGeneration else { throw CancellationError() }
    }
}

// Publication-time projection only. No row-render scan and no upload-progress observation.
private enum MessageIntrinsicGeometry {
    struct Preview: Equatable {
        let kindClass: String
        let filename: String?
        let caption: String?
        let visualWidth: Int?
        let visualHeight: Int?
        let isVisual: Bool
    }
    struct Reaction: Equatable { let emoji: String; let count: Int }
    static func previews(_ values: [String: QueuedMediaPreview]) -> [String: Preview] {
        values.mapValues { value in
            Preview(kindClass: ["image", "image_high", "video", "video_high"].contains(value.kind) ? "visual" : value.kind,
                filename: value.kind == "file" ? value.url.lastPathComponent : nil, caption: value.caption,
                visualWidth: value.visual?.width, visualHeight: value.visual?.height, isVisual: value.isVisual)
        }
    }
    static func reactions(_ values: [String: [ReactionSummary]]) -> [String: [Reaction]] {
        values.mapValues { $0.map { Reaction(emoji: $0.emoji, count: $0.count) } }
    }
    static func requiresRevision(previewsChanged: Bool,
                                 oldPreviews: [String: QueuedMediaPreview], newPreviews: [String: QueuedMediaPreview],
                                 schedulesChanged: Bool, reactionsChanged: Bool,
                                 oldReactions: [String: [ReactionSummary]], newReactions: [String: [ReactionSummary]]) -> Bool {
        schedulesChanged || (previewsChanged && previews(oldPreviews) != previews(newPreviews)) ||
            (reactionsChanged && reactions(oldReactions) != reactions(newReactions))
    }
}


// One current chat only; stores raw messages so locale and display preferences
// remain render-time inputs. No formatted text or cross-account global cache.
struct ChatPinnedPreviewSnapshot: Equatable {
    let chatId: String?
    let ids: Set<String>
    let messages: [String: DecryptedChatMessage]
    let orderedIDs: [String]
    static let empty = ChatPinnedPreviewSnapshot(chatId: nil, ids: [], messages: [:], orderedIDs: [])

    private static func selectedIDs(_ raw: String?) -> Set<String> {
        guard let raw else { return [] }
        return (try? JSONDecoder().decode(Set<String>.self, from: Data(raw.utf8))) ?? []
    }
    private static func make(chatId: String, ids: Set<String>,
                             lookup: (String) -> DecryptedChatMessage?) -> Self {
        var values: [String: DecryptedChatMessage] = [:]
        for id in ids {
            guard let message = lookup(id), message.chatId == chatId,
                  !message.hiddenLocally, !message.isDeletedForEveryone else { continue }
            values[id] = message
        }
        let ordered = values.keys.sorted {
            let left = values[$0]!.createdAt, right = values[$1]!.createdAt
            return left == right ? $0 < $1 : left > right
        }
        return Self(chatId: chatId, ids: ids, messages: values, orderedIDs: ordered)
    }
    static func read(reader: MessageStore, chatId: String, workspace: [String: String]) throws -> Self {
        let ids = selectedIDs(workspace["pins"])
        guard !ids.isEmpty else { return Self(chatId: chatId, ids: [], messages: [:], orderedIDs: []) }
        // Preserve the legacy projection gate once per scoped snapshot, not on
        // every pin/row/scroll render. The owning reload repairs before reading.
        guard try reader.legacyLifecycleRepairIsComplete(chatId: chatId) else {
            return Self(chatId: chatId, ids: ids, messages: [:], orderedIDs: [])
        }
        return make(chatId: chatId, ids: ids) { id in
            (try? reader.message(byClientMessageId: id)) ?? (try? reader.message(byServerId: id))
        }
    }
    func replacingSelection(chatId: String, raw: String?, loadedMessages: [DecryptedChatMessage]) -> Self {
        var known = self.chatId == chatId ? messages : [:]
        for message in loadedMessages where message.chatId == chatId {
            known[message.clientMessageId] = message
            if let server = message.serverId { known[server] = message }
        }
        return Self.make(chatId: chatId, ids: Self.selectedIDs(raw)) { known[$0] }
    }
}
