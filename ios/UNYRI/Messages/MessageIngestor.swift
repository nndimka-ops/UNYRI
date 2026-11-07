import Combine
import Foundation
import LibSignalClient

/// The SINGLE path every incoming encrypted message is decrypted and
/// persisted through — WebSocket push, `/sync` catch-up, and chat-history
/// pagination all call `ingest` (mirrors Android's `MessageIngestionPipeline`).
///
///     IncomingEncryptedMessage -> ingest -> dedupe -> decrypt -> store -> delivered watermark
///
/// Crash-safety ("cursor commit rule"): the durable global-sync cursor
/// (`SessionStore.lastSyncedSequence`) is advanced ONLY after the matching
/// message's store write has returned, one message at a time — never "advance
/// cursor first, persist later". A message that already made it into the
/// store is recognized by the dedupe check and never re-decrypted (Signal's
/// replay protection would reject a re-decrypt anyway).
///
/// Account-switch safety: captures the session generation once and re-checks
/// it before every message; a mismatch aborts the batch so a stale in-flight
/// ingest can never write into another account's store.
@MainActor
final class MessageIngestor {
    /// Chat ids whose stored messages changed in the most recent ingest call.
    /// An open chat screen observes this and reloads, mirroring Android's
    /// Room flow observation.
    let changes = PassthroughSubject<Set<String>, Never>()
    var onDirectFileOffer: ((String, DirectFileOffer) -> Void)?

    private let store: MessageStore
    private let attachmentStore: AttachmentStore
    private let keyManager: SignalKeyManager
    private let sessionManager: SignalSessionManager
    private let auth: AuthRepository
    private let messagesAPI: MessagesAPI
    private let sessionStore: SessionStore
    private let generation: UInt64
    private let ownerId: String
    private var didBootstrap = false
    private var bootstrapTask: Task<Void, Error>?
    private var ingestTail: Task<Bool, Never>?

    init(
        store: MessageStore,
        attachmentStore: AttachmentStore,
        keyManager: SignalKeyManager,
        sessionManager: SignalSessionManager,
        auth: AuthRepository,
        messagesAPI: MessagesAPI,
        sessionStore: SessionStore
    ) {
        self.store = store
        self.attachmentStore = attachmentStore
        self.keyManager = keyManager
        self.sessionManager = sessionManager
        self.auth = auth
        self.messagesAPI = messagesAPI
        self.sessionStore = sessionStore
        self.generation = auth.sessionGeneration
        self.ownerId = auth.currentUser?.id ?? ""
    }

    func ensureBootstrap() async throws {
        try requireSession()
        if didBootstrap { return }
        if let task = bootstrapTask {
            try await task.value
            try requireSession()
            return
        }
        let task = Task { try await keyManager.bootstrapKeysIfNeeded() }
        bootstrapTask = task
        defer { bootstrapTask = nil }
        try await task.value
        try requireSession()
        didBootstrap = true
    }

    /// Decrypts, dedupes and stores a batch. `advanceCursor` controls whether
    /// the durable `/sync` cursor is advanced (true for `/sync`, false for
    /// history pages). Returns false when the session changed mid-batch —
    /// callers must abort without using the result.
    @discardableResult
    func ingest(_ dtos: [MessageDto], advanceCursor: Bool) async -> Bool {
        let previous = ingestTail
        let task = Task {
            _ = await previous?.value
            return await ingestBatch(dtos, advanceCursor: advanceCursor)
        }
        ingestTail = task
        return await task.value
    }

    private var lastAutomaticRetry: [String: Date] = [:]
    private var retryTail: Task<Void, Never>?
    private var acknowledgedDelivery: [String: Int64] = [:]
    private var deliveryRequests = Set<String>()

    private func ingestBatch(_ dtos: [MessageDto], advanceCursor: Bool) async -> Bool {
        guard generation == auth.sessionGeneration else { return false }
        guard !dtos.isEmpty else { return true }

        let token: String?
        do { token = try await auth.currentAccessToken() } catch { return false }
        guard generation == auth.sessionGeneration else { return false }
        guard let myUserId = auth.currentUser?.id else { return false }

        // One background read for the page's pre-state. Previously every DTO
        // paid two MainActor full-message reads (each decoding content JSON)
        // plus a status read just to discover it was already stored.
        let pageIDs = dtos.map(\.clientMessageId)
        let prefetched: [String: MessageStore.IngestMessageState]
        do {
            prefetched = try await store.readAsync { reader in
                try reader.ingestMessageStates(clientMessageIds: pageIDs)
            }
        } catch is CancellationError {
            return false
        } catch {
            // The previous per-message reads failed the batch too; never advance
            // the durable cursor without a reliable pre-state.
            return false
        }
        guard generation == auth.sessionGeneration else { return false }

        var touched = Set<String>()
        // Even if a later row fails, publish every earlier committed write.
        defer { if !touched.isEmpty { changes.send(touched) } }
        var deliveredWatermarks: [String: Int64] = [:]
        var establishedSenders = Set<String>()
        for dto in dtos.sorted(by: { $0.sequence < $1.sequence }) {
            guard generation == auth.sessionGeneration else { return false }
            do {
                // `ingestOne` resolves the live pre-state itself when the page
                // prefetch had no row (own-device echo / retry path), so a good
                // row still dedupes and the established-sender rule sees it.
                let outcome = try await ingestOne(dto, token: token, myUserId: myUserId,
                                                  known: prefetched[dto.clientMessageId])
                if outcome.changed { touched.insert(dto.chatId) }
                if (outcome.priorStatus == nil || outcome.priorStatus == .decryptFailed || outcome.priorStatus == .identityChanged),
                   dto.messageType == SignalMessageType.preKey,
                   let finalStatus = outcome.finalStatus,
                   finalStatus != .decryptFailed, finalStatus != .identityChanged {
                    establishedSenders.insert(dto.senderId)
                }
            } catch {
                // Session change, task cancellation, or a store write failure —
                // abort the batch WITHOUT advancing the durable cursor past the
                // unpersisted message (Android's cursor-commit rule).
                return false
            }
            guard generation == auth.sessionGeneration else { return false }
            deliveredWatermarks[dto.chatId] = max(deliveredWatermarks[dto.chatId] ?? 0, dto.sequence)
            if advanceCursor {
                let last = sessionStore.lastSyncedSequence() ?? 0
                if dto.sequence > last {
                    sessionStore.saveLastSyncedSequence(dto.sequence)
                }
            }
        }
        if !touched.isEmpty { changes.send(touched) }
        touched.removeAll()

        if let token {
            // A slow receipt endpoint must not hold the serial decrypt queue.
            let chatIds = Array(deliveredWatermarks.keys)
            Task {
                for chatId in chatIds {
                    await confirmDelivery(chatId: chatId, token: token)
                }
            }
        }
        for sender in establishedSenders { await retryPendingDecrypts(senderId: sender, automatic: true) }
        return true
    }

    /// Replay pages and overlapping sync batches share one request per chat.
    /// Re-read after each ACK so messages arriving during the request are not lost.
    func confirmDelivery(chatId: String) async {
        do {
            try requireSession()
            let token = try await auth.currentAccessToken()
            try requireSession()
            await confirmDelivery(chatId: chatId, token: token)
        } catch { /* The durable store remains available to the retry worker. */ }
    }

    private func confirmDelivery(chatId: String, token: String) async {
        guard generation == auth.sessionGeneration, deliveryRequests.insert(chatId).inserted else { return }
        defer { deliveryRequests.remove(chatId) }
        do {
            while true {
                try requireSession()
                let owner = ownerId
                let sequence = try await store.readAsync { try $0.receiptSequence(chatId: chatId, ownerId: owner, deliveredOnly: true) }
                try requireSession()
                guard sequence > (acknowledgedDelivery[chatId] ?? 0) else { return }
                let receipt = try await messagesAPI.markDelivered(accessToken: token, chatId: chatId, sequence: sequence)
                try requireSession()
                // Only a successful response advances the local suppression cursor.
                guard receipt.sequence >= sequence else { return }
                acknowledgedDelivery[chatId] = max(acknowledgedDelivery[chatId] ?? 0, receipt.sequence)
            }
        } catch { /* Next ingest retries without advancing the ACK cursor. */ }
    }

    // MARK: - Single message

    /// What one DTO did to the store, so the batch can decide `changes` and
    /// pre-key establishment without re-reading the row on the MainActor.
    private struct IngestOutcome {
        /// An existing row would differ now — exactly the previous
        /// `before != after` comparison for the dedupe and retry branches.
        let changed: Bool
        /// Status the row had before this DTO (nil when it did not exist).
        let priorStatus: MessageStatus?
        /// Status of the row after processing, `nil` when the row was not
        /// stored (deleted chat / cleared-through sequence).
        let finalStatus: MessageStatus?
        static let notStored = IngestOutcome(changed: false, priorStatus: nil, finalStatus: nil)
    }

    private func ingestOne(_ dto: MessageDto, token: String?, myUserId: String,
                           known: MessageStore.IngestMessageState? = nil) async throws -> IngestOutcome {
        guard try !store.isChatDeleted(dto.chatId), dto.sequence > (try store.personalCutoff(dto.chatId)) else { return .notStored }
        // The batch prefetches the page, but a row can appear between that read
        // and this DTO (own-device echo / outbox write), and the retained-decrypt
        // retry calls without a prefetch at all. Resolve the live pre-state
        // exactly as before: a good row must still dedupe, because a second
        // libsignal decrypt is a replay rejection that would overwrite it.
        var before = known
        // A prefetched retryable row can be repaired by `retryPendingDecrypts`
        // (which can run outside the ingest tail) after the page snapshot, before
        // this DTO is reached. Only that stale-bad state is dangerous, so it is
        // re-read live; already-stored good rows and absent-row fast paths keep
        // their prefetch behavior (absent rows already resolve live below).
        let knownIsRetryable = before.map {
            $0.status == .decryptFailed || $0.status == .identityChanged
        } ?? false
        if before == nil || knownIsRetryable {
            before = try liveIngestPreState(dto)
        }
        // Dedup by clientMessageId: anything already processed is skipped,
        // except a previous decrypt failure or an unaccepted identity change,
        // which are retried (a failed MAC never advanced the ratchet, so
        // retry is safe).
        if let existing = before, existing.status != .decryptFailed, existing.status != .identityChanged {
            // Only a stored text kind can carry a legacy text-encoded
            // attachment; the pre-state projection decides this without a
            // full-row read on the MainActor for every known message.
            if existing.contentKind == MessageContent.kindText, let raw = existing.text {
                let recovered = MessageContentCodec.decode(Data(raw.utf8))
                if recovered.kind == MessageContent.kindAttachment,
                   var message = try store.message(byClientMessageId: dto.clientMessageId) {
                    message.content = recovered
                    if let attachment = recovered.attachment { try persistAttachment(dto, attachment: attachment) }
                    try store.upsert(message)
                    return IngestOutcome(changed: true, priorStatus: existing.status, finalStatus: existing.status)
                }
            }
            return IngestOutcome(changed: false, priorStatus: existing.status, finalStatus: existing.status)
        }
        guard token != nil else { throw APIError.missingSession }

        let baseStatus: MessageStatus = dto.senderId == myUserId ? .sent : .delivered
        var decrypted: MessageContent
        let status: MessageStatus
        var retained = false
        var failureErrorClass: String?

        do {
            let signalDeviceId = try await sessionManager.resolveSignalDeviceId(
                senderId: dto.senderId,
                senderDeviceId: dto.senderDeviceId
            )
            try requireSession()
            let plaintext = try sessionManager.decrypt(
                ciphertextBase64: dto.ciphertext,
                messageType: dto.messageType,
                senderUserId: dto.senderId,
                senderSignalDeviceId: signalDeviceId
            )
            try MessageTextPolicy.requireFreshPlaintext(plaintext)
            decrypted = MessageContentCodec.decode(plaintext)
            if let offer = decrypted.directFile {
                // The Signal packet was consumed already. Invalid extension
                // bindings are terminal unsupported content, not a request to
                // repeatedly decrypt the same ratchet counter.
                guard decrypted.authentication != nil, !decrypted.forwarded,
                      MessageContentCodec.authenticates(decrypted, chatId: dto.chatId,
                        clientMessageId: dto.clientMessageId, operation: dto.kind, targetMessageId: dto.targetMessageId),
                      offer.senderUserId == dto.senderId, offer.senderDeviceId == dto.senderDeviceId,
                      offer.recipientUserId == myUserId || dto.senderId == myUserId,
                      offer.offerClientMessageId == dto.clientMessageId,
                      offer.expiresAt <= Int64(dto.createdAt.timeIntervalSince1970) + DirectFileOffer.lifetimeSeconds + 300 else {
                    throw MessageTextPolicyError.invalid
                }
            }
            guard MessageContentCodec.authenticates(decrypted, chatId: dto.chatId,
                clientMessageId: dto.clientMessageId, operation: dto.kind, targetMessageId: dto.targetMessageId) else {
                throw MessageAuthenticationError.invalidContext
            }
            try MessageContentCodec.validateReceivedText(decrypted)
            status = baseStatus
        } catch is CancellationError {
            throw CancellationError()
        } catch is MessageTextPolicyError {
            // The ratchet already consumed this packet. An unbound local
            // placeholder is terminal and cannot authorize a lifecycle effect.
            decrypted = MessageContent(kind: MessageContent.kindText, text: MessageTextPolicy.invalidPresentation)
            status = baseStatus
        } catch {
            let identityChange: Bool
            if case SignalError.untrustedIdentity = error {
                identityChange = true
            } else {
                identityChange = false
            }
            let placeholder = identityChange ? "[Security key changed]" : "[Unable to decrypt message]"
            decrypted = MessageContent(kind: MessageContent.kindText, text: placeholder)
            status = identityChange ? .identityChanged : .decryptFailed
            retained = true
            failureErrorClass = identityChange ? "identity_changed" : String(describing: type(of: error))
        }

        guard try !store.isChatDeleted(dto.chatId), dto.sequence > (try store.personalCutoff(dto.chatId)) else { return .notStored }
        // Этап I: lifecycle events (edit/delete/reaction) are never rendered as
        // their own bubble — they apply a side effect to the target content row
        // and are persisted only so the clientMessageId dedup boundary survives
        // restart. The persisted content is a placeholder; the UI filters on
        // `kind == "content"` (mirrors Android's MessageIngestionPipeline).
        if dto.kind == "content", let attachment = decrypted.attachment {
            try persistAttachment(dto, attachment: attachment)
        }
        let storedContent = decrypted
        let message = DecryptedChatMessage(
            clientMessageId: dto.clientMessageId,
            serverId: dto.id,
            chatId: dto.chatId,
            senderId: dto.senderId,
            sequence: dto.sequence,
            createdAt: dto.createdAt,
            kind: dto.kind,
            targetMessageId: dto.targetMessageId,
            status: status,
            content: storedContent
        )
        if retained {
            // P1-4: the encrypted envelope is retained durably BEFORE the
            // caller may advance the /sync cursor past it.
            try store.upsertDecryptFailed(message, pending: PendingDecrypt(
                clientMessageId: dto.clientMessageId,
                serverId: dto.id,
                chatId: dto.chatId,
                senderId: dto.senderId,
                senderDeviceId: dto.senderDeviceId,
                sequence: dto.sequence,
                createdAt: dto.createdAt,
                messageType: dto.messageType,
                ciphertext: dto.ciphertext,
                kind: dto.kind,
                targetMessageId: dto.targetMessageId,
                notificationBatchAlert: dto.notificationBatchAlert,
                retryCount: 0,
                lastErrorClass: failureErrorClass
            ))
            // A repeated failed retry rewrites the same placeholder bytes, so
            // only a status transition is a visible change (old before != after:
            // the pending-decrypt lastErrorClass lives in another table).
            return IngestOutcome(changed: before?.status != status, priorStatus: before?.status, finalStatus: status)
        } else {
            try store.upsert(message)
            try store.deletePendingDecrypt(clientMessageId: dto.clientMessageId)
            if let offer = storedContent.directFile { onDirectFileOffer?(dto.chatId, offer) }
            // Either the row is new or it was retried out of decryptFailed /
            // identityChanged, so the committed status always differs.
            return IngestOutcome(changed: true, priorStatus: before?.status, finalStatus: status)
        }
    }

    /// Minimal live pre-state for one DTO, the same status→message reads the
    /// pre-prefetch ingest always performed. Used only when the page snapshot
    /// has no row or still marks it retryable; a good row resolves to its full
    /// projection so the text→attachment recovery branch stays intact.
    private func liveIngestPreState(_ dto: MessageDto) throws -> MessageStore.IngestMessageState? {
        guard let liveStatus = try? store.status(forClientMessageId: dto.clientMessageId) else { return nil }
        if liveStatus == .decryptFailed || liveStatus == .identityChanged {
            return MessageStore.IngestMessageState(status: liveStatus, contentKind: "", text: nil)
        }
        guard let existing = try store.message(byClientMessageId: dto.clientMessageId) else {
            // The row disappeared between the two reads. The dedupe branch
            // returns without a recovery write, exactly like before.
            return MessageStore.IngestMessageState(status: liveStatus, contentKind: "", text: nil)
        }
        return MessageStore.IngestMessageState(
            status: liveStatus,
            contentKind: existing.content.kind,
            text: existing.content.kind == MessageContent.kindText ? existing.content.text : nil)
    }

    /// The sender's own copy of an attachment message (multi-device echo or
    /// history/sync replay) reaches this same path — the row already exists
    /// from `AttachmentManager` in that case (same `attachmentId` primary
    /// key), so this is a harmless REPLACE. For a genuinely new incoming
    /// attachment this is the first time the device learns of it —
    /// `downloadState` starts `not_downloaded` (tap-to-download, no automatic
    /// full-attachment fetch on ingest — Android's Этап H п.34).
    private func persistAttachment(_ dto: MessageDto, attachment: AttachmentMetadata) throws {
        if try attachmentStore.attachment(byId: attachment.attachmentId) != nil { return }
        let record = AttachmentRecord(
            attachmentId: attachment.attachmentId,
            clientMessageId: dto.clientMessageId,
            chatId: dto.chatId,
            mediaType: attachment.mediaType,
            mimeType: attachment.mimeType,
            filename: attachment.filename,
            size: attachment.size,
            width: attachment.image?.width,
            height: attachment.image?.height,
            durationMs: attachment.mimeType.hasPrefix("video/") ? attachment.video?.durationMs : attachment.voice?.durationMs,
            thumbnailBase64: attachment.thumbnail,
            localPath: nil,
            downloadState: AttachmentRecord.downloadStateNotDownloaded,
            uploadState: AttachmentRecord.uploadStateUploaded,
            progress: 0,
            objectKey: attachment.objectKey,
            encryptedSize: attachment.encryptedSize,
            encryptionVersion: attachment.encryption.version,
            keyBase64: attachment.encryption.key,
            nonceBase64: attachment.encryption.nonce,
            keysetBase64: attachment.encryption.keyset,
            sha256Base64: attachment.encryption.sha256
        )
        try attachmentStore.upsert(record)
    }

    /// Retry only after a relevant session change or explicit identity acceptance.
    /// Serialize requests so accepting a key during another retry is not lost.
    func retryPendingDecrypts(senderId: String? = nil, automatic: Bool = false) async {
        let previous = retryTail
        let task = Task {
            await previous?.value
            await retryRetainedDecrypts(senderId: senderId, automatic: automatic)
        }
        retryTail = task
        await task.value
    }

    private func retryRetainedDecrypts(senderId: String?, automatic: Bool) async {
        if automatic {
            let retryScope = senderId ?? "after-sync"
            let now = Date()
            if let last = lastAutomaticRetry[retryScope], now.timeIntervalSince(last) < 60 { return }
            lastAutomaticRetry[retryScope] = now
        }
        guard generation == auth.sessionGeneration else { return }
        guard let token = try? await auth.currentAccessToken(), token != nil else { return }
        guard generation == auth.sessionGeneration else { return }
        guard let myUserId = auth.currentUser?.id else { return }
        guard let pending = try? store.pendingDecrypts(senderId: senderId), !pending.isEmpty else { return }
        var recovered = Set<String>()
        for entry in pending.prefix(automatic ? 128 : pending.count) {
            guard generation == auth.sessionGeneration else { return }
            do {
                _ = try await ingestOne(entry.toDto(), token: token, myUserId: myUserId)
                let status = try store.status(forClientMessageId: entry.clientMessageId)
                if status == .decryptFailed || status == .identityChanged {
                    try store.bumpPendingDecrypt(clientMessageId: entry.clientMessageId,
                        retryCount: entry.retryCount + 1, lastErrorClass: entry.lastErrorClass)
                    continue
                }
                try store.deletePendingDecrypt(clientMessageId: entry.clientMessageId)
                recovered.insert(entry.chatId)
            } catch is CancellationError {
                return
            } catch {
                // Still undecryptable: keep the ciphertext, bump diagnostics only.
                try? store.bumpPendingDecrypt(clientMessageId: entry.clientMessageId,
                    retryCount: entry.retryCount + 1, lastErrorClass: errorClass(error))
            }
        }
        if !recovered.isEmpty { changes.send(recovered) }
    }

    private func errorClass(_ error: Error) -> String {
        if case SignalError.untrustedIdentity = error { return "identity_changed" }
        return String(describing: type(of: error))
    }

    private func requireSession() throws {
        try Task.checkCancellation()
        guard generation == auth.sessionGeneration else { throw CancellationError() }
    }

    /// Fast-path propagation of the OTHER member's delivered/read watermark
    /// onto OUR OWN sent message bubbles (forward-only). Mirrors Android's
    /// `advanceOwnMessageStatuses` from `MessageRepositoryImpl`; the HTTP
    /// `GET /chats` reconciliation is the correctness fallback for any missed
    /// `receipt.updated` event.
    func advanceOwnMessageStatuses(chatId: String, deliveredSequence: Int64, readSequence: Int64) {
        guard generation == auth.sessionGeneration else { return }
        if (try? store.recordPeerReceipt(chatId: chatId, ownerId: ownerId,
                                        delivered: deliveredSequence, read: readSequence)) == true {
            changes.send([chatId])
        }
    }
}
