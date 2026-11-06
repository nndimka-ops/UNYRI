import Combine
import Foundation
import ImageIO
import AVFoundation
import LibSignalClient

/// The single authoritative processor for the durable text-message outbox
/// (mirrors Android's `MessageOutboxProcessor`). Sending is eventual: a tap
/// durably enqueues a PENDING row and triggers a flush; transient failures
/// stay PENDING with exponential backoff; only terminal auth failures (logout)
/// or genuinely unrecoverable errors mark a message FAILED. Exactly-once is
/// guaranteed by the server's `(sender_device_id, client_message_id)`
/// idempotency plus the same-clientMessageId retry rule.
///
/// Account-scoped: captures the session generation once and re-checks it
/// before every attempt and before accepting any response, so a stale flush
/// can never send old-account messages after a switch.
@MainActor
final class MessageOutbox {
    /// Chat ids whose stored messages changed (sent/failed) in the most recent
    /// flush — the chat screen observes this to refresh status indicators.
    let changes = PassthroughSubject<Set<String>, Never>()

    /// Invoked on a terminal auth failure (the session is authoritatively
    /// dead); the app shell logs out.
    var onAuthTerminal: (() -> Void)?
    var onMediaActivity: ((String, String) -> Void)?

    private let attachmentManager: AttachmentManager?
    private let store: MessageStore
    private let ingestor: MessageIngestor
    private let sessionManager: SignalSessionManager
    private let auth: AuthRepository
    private let messagesAPI: MessagesAPI
    private let sessionStore: SessionStore
    private let chatsRepository: ChatsRepository
    private let generation: UInt64

    private var retryCounts: [String: Int] = [:]
    private var nextRetryAt: [String: Date] = [:]
    private var otherUserIdCache: [String: String?] = [:]
    private struct QueuedMessageCancelled: Error {}
    private var isFlushing = false
    private var flushRequested = false
    private var needsRescan = false
    private var retryWake: Task<Void, Never>?
    private var retryWakeDate: Date?
    private var scanCursor: PendingMessageCursor?
    private var scanBlockedChats = Set<String>()
    private var scanBlockedMediaChats = Set<String>()
    private static let pendingPageSize = 256
    private static let maxPagesPerPass = 8
    private var stopFlush = false
    private var activeMedia: [String: Task<AttachmentMetadata, Error>] = [:]
    private var preparingMedia: [String: Task<Void, Never>] = [:]
    private var mediaPreparationFailures: [String: Error] = [:]
    // A single conversion/upload lane bounds encoder, disk and memory use.
    // The delivery lane stays serial, so Signal ratchets are never raced.
    private static let maxMediaPreparations = 1
    private var mediaAdmissionBatch = MediaAdmissionBatch()

    func beginMediaAdmissionBatch() { mediaAdmissionBatch.begin() }

    func endMediaAdmissionBatch() {
        let touched = mediaAdmissionBatch.end()
        guard generation == auth.sessionGeneration, !touched.isEmpty else { return }
        changes.send(touched)
        triggerFlush()
    }

    private func publishMediaAdmission(chatId: String) {
        guard let touched = mediaAdmissionBatch.accepted(chatId: chatId),
              generation == auth.sessionGeneration else { return }
        changes.send(touched)
        triggerFlush()
    }
    func cancelDeletedChat(_ chatId: String) async throws {
        let ids = try store.pendingMessageIDs(chatId: chatId)
        for id in ids { preparingMedia[id]?.cancel(); mediaPreparationFailures[id] = nil }
        let operations = ids.compactMap { activeMedia[$0] }
        operations.forEach { $0.cancel() }
        for operation in operations { _ = try? await operation.value }
        for id in ids { retryCounts[id] = nil; nextRetryAt[id] = nil; attachmentManager?.discardPendingUpload(clientMessageId: id) }
        otherUserIdCache[chatId] = nil
    }
    func cancelMedia(_ id: String) {
        let preparation = preparingMedia[id]
        preparation?.cancel()
        mediaPreparationFailures[id] = nil
        let operation = activeMedia[id]
        operation?.cancel()
        Task { @MainActor [weak self] in
            // A source copy/probe may finish after cancellation. Retire both
            // owners before deleting its encrypted/source workspace files.
            _ = await preparation?.value
            _ = try? await operation?.value
            self?.attachmentManager?.discardPendingUpload(clientMessageId: id)
            self?.releaseMediaSource(id)
        }
    }
    private func releaseMediaSource(_ id: String) {
        guard generation == auth.sessionGeneration,
              let message = try? store.message(byClientMessageId: id),
              message.hiddenLocally || [.sent, .delivered, .read].contains(message.status) else { return }
        attachmentManager?.finishSendMeter(id)
        do {
            guard let raw = try store.chatValue(chatId: message.chatId, name: "upload:" + id) else { return }
            let job = try JSONDecoder().decode(MediaJob.self, from: Data(raw.utf8))
            let referenced = try attachmentManager?.referencesLocalFile(job.url) ?? true
            try store.saveChatValue(chatId: message.chatId, name: "upload:" + id, value: nil)
            try store.removeUnownedDraftSource(job.url, attachmentReferenced: referenced)
        } catch { /* Keep sources when ownership cannot be determined safely. */ }
    }
    private var scheduledWake: Task<Void, Never>?
    private var scheduledWakeDate: Date?
    func stopScheduling() {
        preparingMedia.values.forEach { $0.cancel() }
        activeMedia.values.forEach { $0.cancel() }
        mediaPreparationFailures.removeAll()
        scheduledWake?.cancel(); scheduledWake = nil; scheduledWakeDate = nil
        retryWake?.cancel(); retryWake = nil; retryWakeDate = nil
        flushRequested = false
        scanCursor = nil
        scanBlockedChats.removeAll()
        scanBlockedMediaChats.removeAll()
        needsRescan = false
    }

    init(
        store: MessageStore,
        ingestor: MessageIngestor,
        sessionManager: SignalSessionManager,
        auth: AuthRepository,
        messagesAPI: MessagesAPI,
        sessionStore: SessionStore,
        chatsRepository: ChatsRepository,
        attachmentManager: AttachmentManager? = nil
    ) {
        self.attachmentManager = attachmentManager
        self.store = store
        self.ingestor = ingestor
        self.sessionManager = sessionManager
        self.auth = auth
        self.messagesAPI = messagesAPI
        self.sessionStore = sessionStore
        self.chatsRepository = chatsRepository
        self.generation = auth.sessionGeneration
    }

    func scheduledDate(chatId: String, id: String) -> Date? {
        guard let raw = try? store.chatValue(chatId: chatId, name: "schedule:" + id) else { return nil }
        guard let milliseconds = Double(raw), milliseconds.isFinite else { return .distantFuture }
        return Date(timeIntervalSince1970: milliseconds / 1000)
    }
    func enqueueScheduled(chatId: String, otherUserId: String?, text: String, due: Date) -> Bool {
        guard generation == auth.sessionGeneration, let prepared = try? MessageTextPolicy.outgoing(text),
              due > Date(), due.timeIntervalSinceNow <= 366 * 86400 else { return false }
        let row = makePending(clientMessageId: UUID().uuidString.lowercased(), chatId: chatId,
            content: MessageContent(kind: MessageContent.kindText, text: prepared), targetMessageId: nil)
        do { try store.upsert(row, scheduledAt: String(Int64(due.timeIntervalSince1970 * 1000))) }
        catch { return false }
        otherUserIdCache[chatId] = otherUserId; changes.send([chatId]); triggerFlush(); return true
    }
    private struct MediaJob: Codable {
        var url: URL
        var kind: String
        var caption: String?
        var duration: Int64
        var metadata: AttachmentMetadata?
        var albumId: String?
        var visual: QueuedMediaVisual?
    }
    func enqueueMedia(chatId: String, otherUserId: String?, id: String, url: URL, kind: String, caption: String?, duration: Int64, albumId: String? = nil, visual: QueuedMediaVisual? = nil) -> Bool {
        guard generation == auth.sessionGeneration, ["image", "image_high", "video", "video_high", "file", "voice"].contains(kind) else { return false }
        let preparedCaption: String?
        do { preparedCaption = try MessageTextPolicy.caption(caption) } catch { return false }
        #if os(iOS)
        let trace = PerformanceRecorder.shared.operation(id)
        PerformanceRecorder.shared.record(.action, ["trace": trace, "phase": 0])
        #endif
        do {
            if try store.message(byClientMessageId: id) != nil { return true }
            let job = MediaJob(url: url, kind: kind, caption: preparedCaption, duration: duration, albumId: albumId, visual: visual)
            let data = try JSONEncoder().encode(job)
            let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            let label = preparedCaption.flatMap { $0.isEmpty ? nil : $0 } ?? (url.lastPathComponent + "\n" + ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file) + " · " + UnyriL10n.string("Waiting to send"))
            let message = makePending(clientMessageId: id, chatId: chatId, content: MessageContent(kind: MessageContent.kindText, text: label, albumId: albumId), targetMessageId: nil)
            try store.upsert(message, mediaJob: String(decoding: data, as: UTF8.self))
            #if os(iOS)
            PerformanceRecorder.shared.record(.queue, ["trace": trace, "phase": 1])
            #endif
            otherUserIdCache[chatId] = otherUserId
            publishMediaAdmission(chatId: chatId); return true
        } catch { return false }
    }
    func enqueueVisualMedia(chatId: String, otherUserId: String?, id: String, url: URL, kind: String, caption: String?, albumId: String?) async -> Bool {
        guard generation == auth.sessionGeneration, !Task.isCancelled else { return false }
        do { _ = try MessageTextPolicy.caption(caption) } catch { return false }
        guard ["image", "image_high", "video", "video_high"].contains(kind) else {
            return enqueueMedia(chatId: chatId, otherUserId: otherUserId, id: id, url: url, kind: kind, caption: caption, duration: 0, albumId: albumId)
        }
        let work = Task.detached(priority: .userInitiated) { () async throws -> QueuedMediaVisual in
            try Task.checkCancellation()
            let value: QueuedMediaVisual
            if kind.hasPrefix("video") {
                let asset = AVURLAsset(url: url)
                guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw CocoaError(.fileReadCorruptFile) }
                let size = try await track.load(.naturalSize)
                let transform = try await track.load(.preferredTransform)
                let oriented = size.applying(transform)
                let duration = try await asset.load(.duration).seconds
                guard oriented.width.isFinite, oriented.height.isFinite, abs(oriented.width) <= 100_000,
                      abs(oriented.height) <= 100_000, duration.isFinite, duration >= 0, duration <= 86400 * 365 else { throw CocoaError(.fileReadCorruptFile) }
                value = QueuedMediaVisual(width: Int(abs(oriented.width)), height: Int(abs(oriented.height)), durationMs: Int64(duration * 1000))
            } else {
                guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
                      let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                      let width = properties[kCGImagePropertyPixelWidth] as? Int,
                      let height = properties[kCGImagePropertyPixelHeight] as? Int else { throw CocoaError(.fileReadCorruptFile) }
                value = QueuedMediaVisual.oriented(width: width, height: height, orientation: properties[kCGImagePropertyOrientation] as? Int ?? 1)
            }
            try Task.checkCancellation()
            guard value.valid else { throw CocoaError(.fileReadCorruptFile) }
            return value
        }
        do {
            let visual = try await withTaskCancellationHandler(operation: { try await work.value }, onCancel: { work.cancel() })
            try requireSession()
            return enqueueMedia(chatId: chatId, otherUserId: otherUserId, id: id, url: url, kind: kind, caption: caption, duration: visual.durationMs, albumId: albumId, visual: visual)
        } catch {
            // Preview probing is optional. Unsupported metadata must not block
            // the durable upload; cancellation/session retirement still must.
            do { try requireSession() } catch { return false }
            return enqueueMedia(chatId: chatId, otherUserId: otherUserId, id: id, url: url, kind: kind, caption: caption, duration: 0, albumId: albumId)
        }
    }

    private func prepareMedia(_ message: DecryptedChatMessage) async throws -> DecryptedChatMessage {
        guard try !store.isChatDeleted(message.chatId) else { throw QueuedMessageCancelled() }
        guard let raw = try store.chatValue(chatId: message.chatId, name: "upload:" + message.clientMessageId) else { return message }
        var job = try JSONDecoder().decode(MediaJob.self, from: Data(raw.utf8))
        if job.metadata == nil {
            guard let manager = attachmentManager else { throw APIError.network("Media uploader unavailable") }
            let activity = Task { @MainActor [weak self] in
                while !Task.isCancelled {
                    self?.onMediaActivity?(message.chatId, "uploading")
                    do { try await Task.sleep(nanoseconds: 3_000_000_000) } catch { return }
                }
            }
            defer { activity.cancel(); onMediaActivity?(message.chatId, "idle") }
            let draft = job
            let operation = Task { @MainActor () throws -> AttachmentMetadata in
            if let resumed = try await manager.resumeUpload(clientMessageId: message.clientMessageId) { return resumed }
            switch draft.kind {
            case "image": return try await manager.prepareAndUploadImage(fileURL: draft.url, chatId: message.chatId, clientMessageId: message.clientMessageId)
            case "image_high": return try await manager.prepareAndUploadImage(fileURL: draft.url, chatId: message.chatId, clientMessageId: message.clientMessageId, highQuality: true)
            case "voice": return try await manager.prepareAndUploadVoice(fileURL: draft.url, durationMs: draft.duration, chatId: message.chatId, clientMessageId: message.clientMessageId)
            case "video", "video_high": return try await manager.prepareAndUploadVideo(fileURL: draft.url, chatId: message.chatId, clientMessageId: message.clientMessageId, highQuality: draft.kind == "video_high")
            case "file": return try await manager.prepareAndUploadFile(fileURL: draft.url, chatId: message.chatId, clientMessageId: message.clientMessageId)
            default: throw APIError.network("Unsupported media draft")
            }
            }
            activeMedia[message.clientMessageId] = operation
            defer { activeMedia[message.clientMessageId] = nil }
            job.metadata = try await withTaskCancellationHandler(operation: { try await operation.value }, onCancel: { operation.cancel() })
            try requireSession()
            let encoded = try JSONEncoder().encode(job)
            try store.saveChatValue(chatId: message.chatId, name: "upload:" + message.clientMessageId, value: String(decoding: encoded, as: UTF8.self))
        }
        guard let live = try store.message(byClientMessageId: message.clientMessageId), live.status == .pending, !live.hiddenLocally else { throw QueuedMessageCancelled() }
        var prepared = live
        prepared.content = MessageContent(kind: MessageContent.kindAttachment, text: job.caption, attachment: job.metadata, albumId: job.albumId)
        try store.upsert(prepared)
        changes.send([prepared.chatId])
        return prepared
    }

    // MARK: - Enqueue / retry

    /// Durably enqueues a text message and triggers a background flush. The
    /// optimistic PENDING row is written before any network work so a kill
    /// mid-send never loses the message, and control returns to the UI
    /// immediately (Android's `scope.launch { flush() }` semantics).
    @discardableResult
    func enqueueText(chatId: String, otherUserId: String?, text: String) -> Bool {
        guard let trimmed = try? MessageTextPolicy.outgoing(text) else { return false }
        return enqueueContent(
            chatId: chatId,
            otherUserId: otherUserId,
            content: MessageContent(kind: MessageContent.kindText, text: trimmed),
            targetMessageId: nil
        )
    }

    /// Durably enqueues a content message (plain text or a reply — a reply
    /// carries its snapshot inside `content.replySnapshot` and the reply-to
    /// reference in `targetMessageId`, mirroring Android's `replyMessage`).
    @discardableResult
    func enqueueContent(
        chatId: String,
        otherUserId: String?,
        content: MessageContent,
        targetMessageId: String?,
        clientMessageId: String = UUID().uuidString.lowercased()
    ) -> Bool {
        guard generation == auth.sessionGeneration,
              let admitted = try? MessageContentCodec.admittedText(content) else { return false }
        #if os(iOS)
        let trace = PerformanceRecorder.shared.operation(clientMessageId)
        PerformanceRecorder.shared.record(.action, ["trace": trace, "phase": 0])
        #endif
        let message = makePending(
            clientMessageId: clientMessageId,
            chatId: chatId,
            content: admitted,
            targetMessageId: targetMessageId
        )
        do { try store.upsert(message) } catch { return false }
        #if os(iOS)
        PerformanceRecorder.shared.record(.queue, ["trace": trace, "phase": 1])
        #endif
        otherUserIdCache[chatId] = otherUserId
        changes.send([chatId])
        triggerFlush()
        return true
    }

    /// Re-sends a failed message with the SAME `clientMessageId` (server
    /// idempotency) and the same plaintext. Envelopes are re-encrypted, which
    /// is safe: the server dedups on `(sender_device_id, client_message_id)`.
    /// `targetMessageId` is preserved so a failed REPLY still re-sends as a
    /// reply to the same target.
    func retry(
        clientMessageId: String,
        chatId: String,
        otherUserId: String?,
        content: MessageContent,
        targetMessageId: String? = nil
    ) {
        guard generation == auth.sessionGeneration,
              let live = try? store.message(byClientMessageId: clientMessageId),
              live.chatId == chatId, live.status == .failed, !live.hiddenLocally,
              !live.isDeletedForEveryone else { return }
        do {
            var pending = live
            pending.status = .pending
            try store.upsert(pending)
        } catch { return }
        otherUserIdCache[chatId] = otherUserId
        retryCounts[clientMessageId] = nil
        nextRetryAt[clientMessageId] = nil
        changes.send([chatId])
        triggerFlush()
    }

    /// Connectivity-restored flush: clears every in-memory backoff window then
    /// flushes the whole pending queue in one pass. Used on login and socket
    /// (re)connect, where "retry later" no longer makes sense.
    func forceFlush() async {
        for id in (try? store.completedMediaJobIDs()) ?? [] { releaseMediaSource(id) }
        nextRetryAt.removeAll()
        scanCursor = nil
        scanBlockedChats.removeAll()
        scanBlockedMediaChats.removeAll()
        await flush()
    }

    private func triggerFlush() {
        // A newly admitted text may sort before the scan's current cursor.
        needsRescan = true
        flushRequested = true
        Task { @MainActor [self] in
            await flush()
        }
    }

    // MARK: - Flush

    private func hasMediaJob(_ message: DecryptedChatMessage) -> Bool {
        (try? store.chatValue(chatId: message.chatId, name: "upload:" + message.clientMessageId)) != nil
    }

    /// Preparation may suspend for minutes; it must not own the delivery loop.
    /// Completed metadata is persisted by prepareMedia before the next scan.
    private func deferMediaPreparation(_ message: DecryptedChatMessage) -> Bool {
        let id = message.clientMessageId
        guard mediaPreparationFailures[id] == nil,
              let raw = try? store.chatValue(chatId: message.chatId, name: "upload:" + id),
              let job = try? JSONDecoder().decode(MediaJob.self, from: Data(raw.utf8)), job.metadata == nil else { return false }
        if preparingMedia[id] != nil || preparingMedia.count >= Self.maxMediaPreparations { return true }
        preparingMedia[id] = Task { @MainActor [weak self] in
            guard let self else { return }
            do { _ = try await self.prepareMedia(message) }
            catch {
                if !Task.isCancelled, self.generation == self.auth.sessionGeneration,
                   let live = try? self.store.message(byClientMessageId: id), live.status == .pending, !live.hiddenLocally {
                    self.mediaPreparationFailures[id] = error
                }
            }
            self.preparingMedia[id] = nil
            guard !Task.isCancelled, self.generation == self.auth.sessionGeneration else { return }
            self.triggerFlush()
        }
        return true
    }

    private func flush() async {
        flushRequested = true
        guard !isFlushing else { return }
        isFlushing = true
        defer { isFlushing = false }
        guard generation == auth.sessionGeneration else { return }

        stopFlush = false
        var pages = 0
        var hasMore = false
        repeat {
            if needsRescan {
                scanCursor = nil; scanBlockedChats.removeAll(); scanBlockedMediaChats.removeAll()
                needsRescan = false
            }
            flushRequested = false
            let result = await flushPass(pageBudget: Self.maxPagesPerPass - pages)
            hasMore = result.hasMore
            pages += result.pages
            if result.stopped {
                // The cursor points to the end of the fetched page, not the
                // last successfully processed row. A later retry must rescan.
                scanCursor = nil
                scanBlockedChats.removeAll()
                scanBlockedMediaChats.removeAll()
                return
            }
            if !hasMore {
                scanCursor = nil
                scanBlockedChats.removeAll()
                scanBlockedMediaChats.removeAll()
            }
            if pages >= Self.maxPagesPerPass { break }
        } while flushRequested && generation == auth.sessionGeneration

        if (hasMore || flushRequested) && generation == auth.sessionGeneration {
            // Bounded per pass; do not monopolise the MainActor or busy-loop,
            // but continue promptly so a large queue still drains.
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 100_000_000)
                await self?.flush()
            }
        }
    }

    private struct FlushPassResult {
        let pages: Int
        let hasMore: Bool
        let stopped: Bool
    }

    /// Scan bounded keyset pages, one attempt at a time, preserving per-chat
    /// order. A deferred row blocks only its own chat; other chats continue.
    private func flushPass(pageBudget: Int) async -> FlushPassResult {
        var pages = 0
        var hasMore = false
        var earliestRetry: Date?

        while pages < pageBudget {
            guard generation == auth.sessionGeneration else { return FlushPassResult(pages: pages, hasMore: hasMore, stopped: true) }
            let page: PendingMessagePage
            let cursor = scanCursor
            do {
                page = try await store.readAsync { reader in
                    try reader.pendingMessages(limit: Self.pendingPageSize, after: cursor)
                }
            } catch is CancellationError {
                return FlushPassResult(pages: pages, hasMore: hasMore, stopped: true)
            } catch {
                return FlushPassResult(pages: pages, hasMore: hasMore, stopped: true)
            }
            pages += 1
            hasMore = page.hasMore
            scanCursor = page.next
            if page.messages.isEmpty { break }

            let now = Date()
            for message in page.messages {
                guard generation == auth.sessionGeneration else {
                    return FlushPassResult(pages: pages, hasMore: hasMore, stopped: true)
                }
                if scanBlockedChats.contains(message.chatId) { continue }
                if let scheduled = scheduledDate(chatId: message.chatId, id: message.clientMessageId), scheduled > now {
                    // A future scheduled message is not a failed head of the
                    // immediate queue. Later ordinary messages may send now.
                    earliestRetry = min(earliestRetry ?? scheduled, scheduled)
                    continue
                }
                if let next = nextRetryAt[message.clientMessageId], next > now {
                    if hasMediaJob(message) { scanBlockedMediaChats.insert(message.chatId) }
                    else { scanBlockedChats.insert(message.chatId) }
                    earliestRetry = min(earliestRetry ?? next, next)
                    continue
                }
                guard let live = try? store.message(byClientMessageId: message.clientMessageId),
                      live.status == .pending else { continue }
                if hasMediaJob(live) {
                    if scanBlockedMediaChats.contains(live.chatId) { continue }
                    if deferMediaPreparation(live) {
                        scanBlockedMediaChats.insert(live.chatId)
                        continue
                    }
                }
                do {
                    try await attemptOne(live)
                } catch {
                    // Cancellation, session change or a terminal store error:
                    // stop this pass without converting it into a retry loop.
                    return FlushPassResult(pages: pages, hasMore: hasMore, stopped: true)
                }
                if stopFlush { return FlushPassResult(pages: pages, hasMore: hasMore, stopped: true) }
                if nextRetryAt[message.clientMessageId] != nil {
                    if hasMediaJob(message) { scanBlockedMediaChats.insert(message.chatId) }
                    else { scanBlockedChats.insert(message.chatId) }
                    if let next = nextRetryAt[message.clientMessageId] {
                        earliestRetry = min(earliestRetry ?? next, next)
                    }
                }
            }
            if !page.hasMore { break }
        }

        if let earliestRetry {
            scheduleRetryWake(at: earliestRetry)
        }
        return FlushPassResult(pages: pages, hasMore: hasMore, stopped: false)
    }

    private func scheduleRetryWake(at date: Date) {
        if let existing = retryWakeDate, existing <= date { return }
        retryWake?.cancel()
        retryWakeDate = date
        let delayMs = min(max(1, Int64(date.timeIntervalSinceNow * 1000)), 366 * 86_400_000)
        retryWake = Task { @MainActor [weak self] in
            do { try await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000) } catch { return }
            self?.retryWakeDate = nil
            self?.retryWake = nil
            // The due row may be behind the current keyset scan.
            self?.scanCursor = nil
            self?.scanBlockedChats.removeAll()
            self?.scanBlockedMediaChats.removeAll()
            await self?.flush()
        }
    }

    private func attemptOne(_ message: DecryptedChatMessage) async throws {
        try requireSession()
        // The logical payload is derived once from the persisted message —
        // same `clientMessageId` + same plaintext on every transport retry
        // (Android's `attemptSendEntity`). Signal re-encrypts the ratchet
        // ciphertext per attempt; the server dedups on clientMessageId.
        var message = message
        do {
            if let error = mediaPreparationFailures.removeValue(forKey: message.clientMessageId) { throw error }
            message = try await prepareMedia(message)
            // Freeze the text that belongs to this idempotency key before the
            // first network attempt. Later edits only change the visible text
            // and persist an intent; they never silently replace this payload.
            if message.kind == "content", message.serverId == nil, message.content.attachment == nil {
                try store.beginSend(clientMessageId: message.clientMessageId, fallbackText: message.content.text)
                if let frozen = try store.message(byClientMessageId: message.clientMessageId)?.payloadText {
                    message.content.text = frozen
                }
            }
            try MessageContentCodec.validateSendingText(message.content, frozenText: message.payloadText)
            try MessageTextPolicy.retry(message.pendingEditText, optional: true)
            let payload = MessageSendComposer.payload(for: message)
            let otherUserId = try await resolveOtherUserId(chatId: message.chatId)
            guard let live = try store.message(byClientMessageId: message.clientMessageId), live.status == .pending else { return }
            let response = try await deliver(
                clientMessageId: message.clientMessageId,
                chatId: message.chatId,
                otherUserId: otherUserId,
                plaintext: payload.plaintext,
                kind: payload.kind,
                targetMessageId: payload.targetMessageId,
                attachmentIds: payload.attachmentIds,
                requiresPendingMessage: true
            )
            try requireSession()
            guard let liveAfter = try store.message(byClientMessageId: message.clientMessageId) else {
                releaseMediaSource(message.clientMessageId)
                retryCounts[message.clientMessageId] = nil
                nextRetryAt[message.clientMessageId] = nil
                return
            }
            let sent = reconcileSent(liveAfter, response: response)
            let editRow = try makePendingEditRow(for: liveAfter, response: response)
            try store.upsertSentWithPendingEdit(sent, editRow: editRow)
            if editRow != nil { triggerFlush() }
            releaseMediaSource(message.clientMessageId)
            retryCounts[message.clientMessageId] = nil
            nextRetryAt[message.clientMessageId] = nil
            changes.send([message.chatId])
        } catch is QueuedMessageCancelled {
            releaseMediaSource(message.clientMessageId)
            retryCounts[message.clientMessageId] = nil
            nextRetryAt[message.clientMessageId] = nil
            return
        } catch is CancellationError {
            if let live = try? store.message(byClientMessageId: message.clientMessageId), live.hiddenLocally { return }
            throw CancellationError()
        } catch {
            // A failed response may arrive after logout cleared this account.
            try requireSession()
            let failure: Error
            if let signalError = error as? SignalError, case .untrustedIdentity = signalError {
                failure = OutboxError.identityChanged
            } else {
                failure = error
            }
            let failureClass = OutboxPolicy.classify(failure)
            // A definitive revocation still applies if this individual message
            // was cancelled while its request was in flight.
            if failureClass == .authTerminal {
                stopFlush = true
                onAuthTerminal?()
                return
            }
            // Cancellation/deletion can also win while the request is suspended.
            // Never resurrect the stale pre-request copy or replace a late receipt.
            guard let live = try store.message(byClientMessageId: message.clientMessageId),
                  !live.hiddenLocally, live.status == .pending else {
                retryCounts[message.clientMessageId] = nil
                nextRetryAt[message.clientMessageId] = nil
                releaseMediaSource(message.clientMessageId)
                return
            }
            switch failureClass {
            case .authTerminal:
                return // Handled above, independently of the message's state.
            case .permanent:
                var failed = live
                failed.status = .failed
                // If persistence fails, abort this flush rather than immediately
                // selecting the same still-pending row in a tight loop.
                try store.upsert(failed)
                retryCounts[message.clientMessageId] = nil
                nextRetryAt[message.clientMessageId] = nil
                changes.send([message.chatId])
            case .transient:
                let retryCount = (retryCounts[message.clientMessageId] ?? 0) + 1
                retryCounts[message.clientMessageId] = retryCount
                let delayMs = max(OutboxPolicy.baseBackoffMs(retryCount),
                                  (failure as? APIError)?.isRateLimited == true ? 60_000 : 0)
                #if os(iOS)
                PerformanceRecorder.shared.record(.queue, ["trace": PerformanceRecorder.shared.operation(message.clientMessageId),
                    "phase": 6, "count": Double(retryCount), "duration_ms": Double(delayMs),
                    "status": (failure as? APIError)?.isRateLimited == true ? 429 : 0])
                #endif
                nextRetryAt[message.clientMessageId] = Date().addingTimeInterval(Double(delayMs) / 1000.0)
                scheduleFlush(delayMs: delayMs)
            }
        }
    }

    private func scheduleFlush(delayMs: Int64) {
        Task { @MainActor [self] in
            try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
            guard !Task.isCancelled else { return }
            await flush()
        }
    }

    // MARK: - Deliver

    /// Durable intent, rendered as an overlay until the real server receipt.
    func enqueueLifecycle(chatId: String, targetServerId: String, kind: String, plaintext: Data) throws {
        try requireSession()
        guard let target = try store.message(byServerId: targetServerId),
              target.chatId == chatId, target.kind == "content", target.deleteSequence == 0,
              let owner = auth.currentUser?.id,
              kind == "reaction" || target.senderId == owner else { throw MessageAuthenticationError.invalidContext }
        let id = UUID().uuidString.lowercased()
        try MessageContentCodec.validateSendingText(MessageContentCodec.decode(plaintext))
        let authenticated = try MessageContentCodec.authenticate(plaintext, chatId: chatId,
            clientMessageId: id, operation: kind, targetMessageId: targetServerId,
            targetClientMessageId: target.clientMessageId)
        let event = makePending(clientMessageId: id, chatId: chatId,
            content: MessageContentCodec.decode(authenticated), targetMessageId: targetServerId, kind: kind)
        try store.upsert(event)
        changes.send([chatId]); triggerFlush()
    }

    /// plaintext -> ensure sessions -> per-device ciphertext envelopes -> POST.
    /// The network request never carries plaintext.
    private func deliver(
        clientMessageId: String, chatId: String, otherUserId: String?, plaintext: Data,
        kind: String, targetMessageId: String?, attachmentIds: [String], requiresPendingMessage: Bool = false
    ) async throws -> SendMessageResponse {
        #if os(iOS)
        return try await PerformanceRecorder.$trace.withValue(PerformanceRecorder.shared.operation(clientMessageId)) {
            try await deliverTraced(clientMessageId: clientMessageId, chatId: chatId, otherUserId: otherUserId, plaintext: plaintext,
                kind: kind, targetMessageId: targetMessageId, attachmentIds: attachmentIds, requiresPendingMessage: requiresPendingMessage)
        }
        #else
        return try await deliverTraced(clientMessageId: clientMessageId, chatId: chatId, otherUserId: otherUserId, plaintext: plaintext,
            kind: kind, targetMessageId: targetMessageId, attachmentIds: attachmentIds, requiresPendingMessage: requiresPendingMessage)
        #endif
    }

    private func deliverTraced(
        clientMessageId: String,
        chatId: String,
        otherUserId: String?,
        plaintext: Data,
        kind: String,
        targetMessageId: String?,
        attachmentIds: [String],
        requiresPendingMessage: Bool = false
    ) async throws -> SendMessageResponse {
        #if os(iOS)
        let performanceSpan = PerformanceRecorder.shared.begin(.messageSend, ["operation": PerformanceRecorder.shared.operation(clientMessageId)])
        var performanceSuccess = false
        defer { PerformanceRecorder.shared.end(performanceSpan, success: performanceSuccess) }
        #endif
        guard try !store.isChatDeleted(chatId) else { throw QueuedMessageCancelled() }
        try await ingestor.ensureBootstrap()
        guard let userId = auth.currentUser?.id else { throw APIError.missingSession }
        let currentDeviceId = sessionStore.deviceId()

        var recipients: [SignalRecipientDevice] = []
        if let otherUserId {
            recipients += try await sessionManager.ensureSessions(for: otherUserId)
        }
        recipients += try await sessionManager.ensureSessions(for: userId, excludingDeviceId: currentDeviceId)

        let deviceIds = recipients.map { $0.deviceId }
        guard Set(deviceIds).count == deviceIds.count else {
            throw OutboxError.duplicateRecipientDevice
        }

        try requireSession()
        let target = try targetMessageId.flatMap { try store.message(byServerId: $0) }
        if kind != "content" {
            guard let target, target.chatId == chatId, target.kind == "content",
                  kind == "reaction" || target.senderId == userId else {
                throw MessageAuthenticationError.invalidContext
            }
        }
        let authenticated = try MessageContentCodec.authenticate(plaintext, chatId: chatId,
            clientMessageId: clientMessageId, operation: kind, targetMessageId: targetMessageId,
            targetClientMessageId: kind == "content" ? nil : target?.clientMessageId)
        let envelopes = try sessionManager.encrypt(plaintext: authenticated, recipients: recipients)
        let request = SendMessageRequest(
            clientMessageId: clientMessageId,
            envelopes: envelopes,
            attachmentIds: attachmentIds,
            kind: kind,
            targetMessageId: targetMessageId
        )
        let token = try await auth.currentAccessToken()
        try requireSession()
        guard try !store.isChatDeleted(chatId) else { throw QueuedMessageCancelled() }
        // Preparation awaits key/session and token requests. Cancellation may
        // have removed the queued row during any of those suspension points.
        if requiresPendingMessage {
            guard let live = try store.message(byClientMessageId: clientMessageId),
                  live.status == .pending, !live.hiddenLocally else { throw QueuedMessageCancelled() }
        }
        let result = try await messagesAPI.sendMessage(
            accessToken: token,
            chatId: chatId,
            request: request
        )
        #if os(iOS)
        performanceSuccess = true
        #endif
        return result
    }

    // MARK: - Helpers

    private func resolveOtherUserId(chatId: String) async throws -> String? {
        if let cached = otherUserIdCache[chatId] {
            return cached
        }
        let summary = try await chatsRepository.getChat(chatId: chatId)
        try requireSession()
        let other = summary.otherUser?.id
        otherUserIdCache[chatId] = other
        return other
    }

    private func makePending(
        clientMessageId: String,
        chatId: String,
        content: MessageContent,
        targetMessageId: String? = nil,
        kind: String = "content"
    ) -> DecryptedChatMessage {
        DecryptedChatMessage(
            clientMessageId: clientMessageId,
            serverId: nil,
            chatId: chatId,
            senderId: auth.currentUser?.id ?? "",
            sequence: nil,
            createdAt: Date(),
            kind: kind,
            targetMessageId: targetMessageId,
            status: .pending,
            content: content
        )
    }

    private func reconcileSent(_ message: DecryptedChatMessage, response: SendMessageResponse) -> DecryptedChatMessage {
        var sent = DecryptedChatMessage(
            clientMessageId: message.clientMessageId,
            serverId: response.id,
            chatId: message.chatId,
            senderId: message.senderId,
            sequence: response.sequence,
            createdAt: response.createdAt,
            kind: message.kind,
            targetMessageId: message.targetMessageId,
            status: .sent,
            content: message.content
        )
        sent.payloadText = message.payloadText
        sent.pendingEditText = message.pendingEditText
        sent.pendingEditRevision = message.pendingEditRevision
        sent.sendAttempted = true
        return sent
    }

    /// Build the ordinary `edit` lifecycle row once the server id is known.
    /// It is authenticated exactly like a normal edit; the pending text never
    /// replaces the original POST payload under the same idempotency key.
    private func makePendingEditRow(for live: DecryptedChatMessage,
                                    response: SendMessageResponse) throws -> DecryptedChatMessage? {
        guard let raw = live.pendingEditText?.trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty, !live.hiddenLocally, live.deleteSequence == 0 else { return nil }
        let editId = UUID().uuidString.lowercased()
        let plaintext = MessageContentCodec.encodeEdit(raw)
        let authenticated = try MessageContentCodec.authenticate(
            plaintext,
            chatId: live.chatId,
            clientMessageId: editId,
            operation: "edit",
            targetMessageId: response.id,
            targetClientMessageId: live.clientMessageId
        )
        return DecryptedChatMessage(
            clientMessageId: editId,
            serverId: nil,
            chatId: live.chatId,
            senderId: live.senderId,
            sequence: nil,
            createdAt: Date(),
            kind: "edit",
            targetMessageId: response.id,
            status: .pending,
            content: MessageContentCodec.decode(authenticated)
        )
    }

    private func requireSession() throws {
        try Task.checkCancellation()
        guard generation == auth.sessionGeneration else { throw CancellationError() }
    }
}
