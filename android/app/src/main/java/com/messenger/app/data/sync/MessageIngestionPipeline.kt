package com.messenger.app.data.sync

import android.util.Log
import com.messenger.app.BuildConfig
import com.messenger.app.data.api.DeliveredReporter
import com.messenger.app.data.api.MessageDto
import com.messenger.app.data.attachment.AttachmentMetadataDto
import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.attachment.MessageContentDto
import com.messenger.app.data.attachment.ReplySnapshotDto
import com.messenger.app.data.local.attachment.AttachmentDao
import com.messenger.app.data.local.attachment.AttachmentEntity
import com.messenger.app.data.local.chat.ChatDao
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.local.message.PendingDecryptEntity
import com.messenger.app.data.local.message.ReactionDao
import com.messenger.app.data.local.message.ReactionEntity
import com.messenger.app.data.signal.IdentityChangedException
import com.messenger.app.data.signal.MessageDecryptor
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private const val TAG = "MessageIngestion"

/**
 * The SINGLE path every incoming encrypted message is decrypted and
 * persisted through - WebSocket push, `/sync` catch-up, and chat-history
 * pagination all call [ingest] with whatever [MessageDto]s they received.
 * Never three independent decrypt/store implementations.
 *
 *     IncomingEncryptedMessage -> ingest() -> dedupe -> decrypt if needed -> Room -> delivered watermark
 *
 * Crash-safety ("cursor commit rule"): the durable global-sync cursor
 * ([SecureTokenStore.saveLastSyncedSequence]) is advanced ONLY after the
 * corresponding message's Room write has returned successfully, one message
 * at a time - never "advance cursor first, persist later". If the process
 * dies mid-batch, the next run re-fetches from the last successfully
 * advanced cursor; any message that already made it into Room is
 * recognized by the dedupe check below and never re-decrypted (Signal
 * Protocol's replay protection means re-decrypting an already-processed
 * ciphertext would fail anyway - see Этап F's `DuplicateMessageException`
 * lesson, now enforced systemically instead of per-call-site).
 *
 * Account-switch safety: captures [AuthRepository.currentSessionEpoch] once
 * at the start of a batch and re-checks it before every single message;
 * a mismatch (a logout/new-login happened mid-batch) aborts immediately
 * without processing the remainder, so a stale in-flight batch can never
 * write into a different account's/an already-logged-out state's Room rows.
 */
class MessageIngestionPipeline(
    private val dao: MessageDao,
    private val chatDao: ChatDao,
    private val attachmentDao: AttachmentDao,
    private val reactionDao: ReactionDao,
    private val decryptor: MessageDecryptor,
    private val authRepository: AuthRepository,
    private val messagesApi: DeliveredReporter,
    private val tokenStore: SyncCursorStore,
    private val deletedChatDao: com.messenger.app.data.local.chat.DeletedChatDao? = null,
    private val scheduleDelivery: ((suspend () -> Unit) -> Unit)? = null,
    private val workspaceDao: com.messenger.app.data.local.chat.ChatWorkspaceDao? = null,
) : MessageIngestor {
    var onDeleteChats: suspend (List<String>) -> Boolean = { it.isEmpty() }
    override suspend fun deleteChats(chatIds: List<String>): Boolean = onDeleteChats(chatIds)
    private val mutex = Mutex()
    private val retryMutex = Mutex()
    private var retryEpoch = -1
    private val lastAutomaticRetry = mutableMapOf<String, Long>()
    private val json = Json { ignoreUnknownKeys = true }

    private sealed interface DecryptOutcome {
        /** Decrypted+persisted, already processed, or intentionally skipped. */
        data object Processed : DecryptOutcome
        /** Decrypt failed; the encrypted envelope was durably retained. */
        data class Retained(val errorClass: String?) : DecryptOutcome
    }

    private val reprocessableStatuses = setOf(
        MessageStatus.DECRYPT_FAILED.name.lowercase(),
        MessageStatus.IDENTITY_CHANGED.name.lowercase(),
    )

    override suspend fun ingest(dtos: List<MessageDto>, advanceCursor: Boolean): MessageIngestor.IngestResult = mutex.withLock {
        var highestSequence = tokenStore.lastSyncedSequence()
        if (dtos.isEmpty()) return@withLock MessageIngestor.IngestResult(true, highestSequence)

        val epoch = authRepository.currentSessionEpoch()
        val token = authRepository.currentAccessToken()
            ?: return@withLock MessageIngestor.IngestResult(false, highestSequence)
        val myUserId = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
        val deliveredWatermarks = mutableMapOf<String, Long>()
        val establishedSenders = mutableSetOf<String>()

        for (dto in dtos.sortedBy { it.sequence }) {
            // P0 hardening: the write AND the cursor advance happen inside the
            // shared session gate (the same mutex clearRoom uses), so a logout
            // that bumps the epoch either (a) sees this write finish first and
            // then clears it, or (b) runs first and this write is rejected -
            // a stale old-account row can never survive the switch.
            val applied = authRepository.withSessionWrite(epoch) {
                val before = dao.findByClientMessageId(dto.clientMessageId)
                val result = ingestOne(dto, token, myUserId)
                // Only a newly processed Signal pre-key envelope can establish a
                // session. Receipt/history replays and unrelated chats cannot.
                if ((before == null || before.status in reprocessableStatuses) && dto.messageType == com.messenger.app.data.signal.MESSAGE_TYPE_PREKEY && result is DecryptOutcome.Processed &&
                    dao.findByClientMessageId(dto.clientMessageId)?.let { it.status !in reprocessableStatuses } == true) {
                    establishedSenders.add(dto.senderId)
                }
                if (advanceCursor && dto.sequence > highestSequence) {
                    highestSequence = dto.sequence
                    // Advance the durable cursor only now - AFTER this specific
                    // message's Room write above already returned successfully.
                    tokenStore.saveLastSyncedSequence(highestSequence)
                }
                true
            }
            if (applied != true) {
                Log.w(TAG, "aborting mid-batch ingest: session changed")
                return@withLock MessageIngestor.IngestResult(false, highestSequence)
            }
            deliveredWatermarks[dto.chatId] = maxOf(deliveredWatermarks[dto.chatId] ?: 0L, dto.sequence)
        }

        if (token != null) {
            deliveredWatermarks.forEach { (chatId, upperBound) ->
                val sequence = authRepository.withSessionWrite(epoch) {
                    dao.receiptSequence(chatId, myUserId ?: return@withSessionWrite 0L, upperBound)
                } ?: 0L
                if (sequence <= 0 || authRepository.currentSessionEpoch() != epoch) return@forEach
                val report: suspend () -> Unit = {
                    if (authRepository.currentSessionEpoch() == epoch) {
                        runCatching { messagesApi.markDelivered(token, chatId, sequence) }
                            .onFailure { Log.w(TAG, "failed to report delivered watermark: ${it::class.simpleName}") }
                    }
                }
                // Realtime processing must not wait for receipt HTTP timeouts.
                // Production schedules transport independently; persisted cursors retry failures.
                if (scheduleDelivery != null) scheduleDelivery.invoke(report) else report()
            }
        }
        for (sender in establishedSenders) retryPendingDecrypts(sender, automatic = true)
        MessageIngestor.IngestResult(true, highestSequence)
    }

    private suspend fun ingestOne(dto: MessageDto, token: String?, myUserId: String?): DecryptOutcome {
        val cutoff = dao.historyCutoff()
        if (cutoff != null && dto.sequence <= cutoff) return DecryptOutcome.Processed
        if (dto.sequence <= (deletedChatDao?.personalCutoff(dto.chatId) ?: -1)) return DecryptOutcome.Processed
        if (deletedChatDao?.contains(dto.chatId) == true) return DecryptOutcome.Processed
        val existing = dao.findByClientMessageId(dto.clientMessageId)
        val alreadyProcessed = existing != null && existing.status !in reprocessableStatuses
        if (alreadyProcessed) {
            if (existing != null && existing.kind == "content" && dto.kind == "content" &&
                existing.serverId == dto.id && !existing.serverId.isNullOrBlank() &&
                existing.clientMessageId == dto.clientMessageId && existing.chatId == dto.chatId &&
                existing.senderId == dto.senderId && existing.sequence == dto.sequence && dto.sequence > 0 &&
                existing.status in setOf("sent", "delivered", "read") && !existing.hiddenLocally && !existing.isDeletedForEveryone) {
                // Repair legacy/imported upload state without replay-decrypting
                // already consumed Signal ciphertext or changing download data.
                attachmentDao.findByClientMessageId(dto.clientMessageId)?.let { attachment ->
                    if (attachment.clientMessageId == dto.clientMessageId && attachment.chatId == dto.chatId) {
                        attachmentDao.markUploadSent(attachment.attachmentId, dto.clientMessageId, dto.chatId)
                    }
                }
            }
            // Repair old voice envelopes saved as plain text, without replaying
            // a Signal ciphertext whose ratchet has already advanced.
            if (existing != null && existing.kind == "content" && existing.text.trimStart().startsWith("{")) {
                val recovered = MessageContentCodec.decode(existing.text.toByteArray(Charsets.UTF_8))
                if (recovered.kind == MessageContentDto.KIND_ATTACHMENT) {
                    recovered.attachment?.let { persistAttachment(dto, it) }
                    dao.upsert(existing.copy(text = recovered.attachment?.let(::attachmentPlaceholder) ?: recovered.text.orEmpty()))
                }
            }
            return DecryptOutcome.Processed
        }

        if (token == null) {
            Log.w(TAG, "dropping message ${dto.id}: not authenticated")
            return DecryptOutcome.Processed
        }

        var failureErrorClass: String? = null
        val result = try {
            val signalDeviceId = decryptor.resolveSignalDeviceId(dto.senderId, dto.senderDeviceId, token)
            val plaintext = decryptor.decrypt(dto.senderId, signalDeviceId, dto.messageType, dto.ciphertext)
            com.messenger.app.data.message.MessageTextPolicy.requireFreshPlaintext(plaintext)
            val content = MessageContentCodec.decode(plaintext)
            require(MessageContentCodec.authenticates(content, dto.chatId, dto.clientMessageId, dto.kind, dto.targetMessageId)) {
                "Message authentication context mismatch"
            }
            MessageContentCodec.validateReceivedText(content)
            if (content.directFile != null) {
                val offer = com.messenger.app.data.directtransfer.DirectFileOffer.decode(content.directFile)
                    ?: throw com.messenger.app.data.message.MessageTextPolicyException()
                val binding = content.authentication
                if (binding == null || binding.version != 1 || binding.chatId != dto.chatId || binding.clientMessageId != dto.clientMessageId ||
                    binding.operation != "content" || dto.kind != "content" || content.forwarded || offer.offerClientMessageId != dto.clientMessageId ||
                    offer.senderUserId != dto.senderId || offer.senderDeviceId != dto.senderDeviceId ||
                    myUserId !in setOf(offer.senderUserId, offer.recipientUserId)) throw com.messenger.app.data.message.MessageTextPolicyException()
                workspaceDao?.let { store ->
                    val key = com.messenger.app.data.directtransfer.DirectTransferCoordinator.key(offer.transferId)
                    val existing = store.get(dto.chatId, key)
                    if (existing == null) {
                        val role = if (myUserId == offer.recipientUserId) "receiver" else "other"
                        val record = com.messenger.app.data.directtransfer.DirectTransferRecord(offer, dto.chatId, role,
                            if (role == "other") "other_device" else "offered", peerDeviceId = if (role == "receiver") offer.senderDeviceId else null)
                        store.put(com.messenger.app.data.local.chat.ChatWorkspaceValue(dto.chatId, key,
                            com.messenger.app.data.directtransfer.DirectFileOffer.codec.encodeToString(com.messenger.app.data.directtransfer.DirectTransferRecord.serializer(), record)))
                    } else {
                        val record = com.messenger.app.data.directtransfer.DirectTransferCoordinator.decodeRecord(existing)
                        if (record?.offer != offer || record.chatId != dto.chatId) throw com.messenger.app.data.message.MessageTextPolicyException()
                    }
                }
            }
            val status = if (dto.senderId == myUserId) MessageStatus.SENT else MessageStatus.DELIVERED
            when (dto.kind) {
                "edit" -> {
                    applyEditEffect(dto, content)
                    IngestedContent(text = plaintext.toString(Charsets.UTF_8), status = status)
                }
                "delete" -> {
                    applyDeleteEffect(dto, content)
                    IngestedContent(text = plaintext.toString(Charsets.UTF_8), status = status)
                }
                "reaction" -> {
                    applyReactionEffect(dto, content)
                    IngestedContent(text = plaintext.toString(Charsets.UTF_8), status = status)
                }
                else -> {
                    val replySnapshotJson = content.replySnapshot?.let { json.encodeToString(ReplySnapshotDto.serializer(), it) }
                    if (content.kind == MessageContentDto.KIND_ATTACHMENT && content.attachment != null) {
                        persistAttachment(dto, content.attachment)
                        IngestedContent(content.text?.takeIf { it.isNotBlank() } ?: attachmentPlaceholder(content.attachment), status, replySnapshotJson, content.forwarded, content.text, content.albumId)
                    } else {
                        IngestedContent(content.text.orEmpty(), status, replySnapshotJson, content.forwarded,
                            conferenceInviteJson = com.messenger.app.conference.ConferenceInvitation.decode(content.conferenceInvite)?.stored())
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: com.messenger.app.data.message.MessageTextPolicyException) {
            // Signal already consumed this packet. Store a terminal bounded
            // placeholder, never retain/re-decrypt it or apply lifecycle effects.
            IngestedContent(com.messenger.app.data.message.MessageTextPolicy.invalidPresentation,
                if (dto.senderId == myUserId) MessageStatus.SENT else MessageStatus.DELIVERED)
        } catch (e: IdentityChangedException) {
            // Identity change is a distinct security state, never a generic
            // decrypt failure: surface "Security key changed" so the user is
            // prompted to verify/accept, not shown a silent placeholder. The
            // envelope is still retained durably so accepting the new key
            // later recovers it.
            failureErrorClass = e::class.simpleName
            Log.w(TAG, "identity changed for message ${dto.id} from ${dto.senderId} devices=${e.signalDeviceIds}")
            IngestedContent("[Security key changed]", MessageStatus.IDENTITY_CHANGED)
        } catch (e: Exception) {
            // Never log ciphertext/plaintext/session state - id and
            // exception class + stack trace only (no payload bytes).
            failureErrorClass = e::class.simpleName
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "failed to decrypt message ${dto.id}: ${e::class.simpleName}", e)
            } else {
                Log.w(TAG, "failed to decrypt message ${dto.id}: ${e::class.simpleName}")
            }
            IngestedContent("[Unable to decrypt message]", MessageStatus.DECRYPT_FAILED)
        }

        val messageEntity = MessageEntity(
            clientMessageId = dto.clientMessageId,
            serverId = dto.id,
            chatId = dto.chatId,
            senderId = dto.senderId,
            text = result.text,
            caption = result.caption,
            albumId = result.albumId,
            sequence = dto.sequence,
            createdAt = dto.createdAt,
            status = result.status.name.lowercase(),
            kind = dto.kind,
            targetMessageId = dto.targetMessageId,
            replySnapshotJson = result.replySnapshotJson,
            forwarded = result.forwarded,
            conferenceInviteJson = result.conferenceInviteJson,
        )

        // P1-4: a failed decrypt must retain the encrypted envelope durably
        // BEFORE the caller advances the cursor past this sequence. The
        // placeholder + ciphertext are written in one Room transaction.
        if (result.status == MessageStatus.DECRYPT_FAILED || result.status == MessageStatus.IDENTITY_CHANGED) {
            dao.upsertDecryptFailed(
                messageEntity,
                PendingDecryptEntity(
                    clientMessageId = dto.clientMessageId,
                    serverId = dto.id,
                    chatId = dto.chatId,
                    senderId = dto.senderId,
                    senderDeviceId = dto.senderDeviceId,
                    sequence = dto.sequence,
                    createdAt = dto.createdAt,
                    messageType = dto.messageType,
                    ciphertext = dto.ciphertext,
                    kind = dto.kind,
                    targetMessageId = dto.targetMessageId,
                    lastErrorClass = failureErrorClass,
                ),
            )
            return DecryptOutcome.Retained(failureErrorClass)
        }

        dao.upsert(messageEntity)
        dao.deletePendingDecrypt(dto.clientMessageId)
        // A history page may contain the target long after a WS lifecycle event.
        // Replay persisted plaintext; never attempt to decrypt a Signal message twice.
        if (dto.kind == "content") {
            for (event in dao.lifecycleEvents(dto.id)) {
                if (event.chatId != dto.chatId) continue
                val replay = dto.copy(id = event.serverId ?: continue,
                    clientMessageId = event.clientMessageId, senderId = event.senderId,
                    sequence = event.sequence ?: continue, kind = event.kind,
                    targetMessageId = dto.id)
                val content = MessageContentCodec.decode(event.text.toByteArray(Charsets.UTF_8))
                when (event.kind) {
                    "edit" -> if (event.senderId == dto.senderId && event.text != "[edit]") applyEditEffect(replay, content)
                    "delete" -> if (event.senderId == dto.senderId) applyDeleteEffect(replay, content)
                    "reaction" -> if (event.text != "[reaction]") applyReactionEffect(replay, content)
                }
            }
        }
        chatDao.touchLastMessageAt(dto.chatId, dto.createdAt)
        return DecryptOutcome.Processed
    }

    /**
     * P1-4: retry durably-retained decrypt failures, in server-sequence
     * order, through the SAME [ingestOne] path (so edit/delete/reaction/
     * attachment semantics are identical to a first-attempt decrypt). Bounded
     * WORK per trigger: each invocation attempts each pending record at most
     * once — a record is never permanently disqualified by its retryCount
     * (which is diagnostics only) and stays durable until it decrypts, is
     * proven unrecoverable, or the account is wiped. [senderId] scopes the
     * pass to one peer (used by the acceptNewKey trigger); null = all.
     * Account-scoped via the session gate - a logout/switch aborts
     * immediately and never writes into the new account.
     */
    override suspend fun recoverRetainedAfterSync() = retryPendingDecrypts(automatic = true)

    suspend fun retryPendingDecrypts(senderId: String? = null, automatic: Boolean = false) = retryMutex.withLock {
        val epoch = authRepository.currentSessionEpoch()
        if (retryEpoch != epoch) { lastAutomaticRetry.clear(); retryEpoch = epoch }
        if (automatic) {
            // A successful catch-up must also repair retained envelopes when
            // it fetched no new pre-key message (including after an app update).
            val sender = senderId ?: "after-sync"
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastAutomaticRetry[sender]?.let { now - it < 60_000 } == true) return@withLock
            lastAutomaticRetry[sender] = now
        }
        val token = authRepository.currentAccessToken() ?: return@withLock
        val myUserId = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
        val pending = if (senderId == null) dao.pendingDecryptsOrdered() else dao.pendingDecryptsForSender(senderId)
        for (entry in pending.filterNot { automatic && it.lastErrorClass?.contains("DuplicateMessage") == true }.take(if (automatic) 128 else Int.MAX_VALUE)) {
            val applied = authRepository.withSessionWrite(epoch) {
                when (val outcome = ingestOne(entry.toDto(), token, myUserId)) {
                    is DecryptOutcome.Processed -> dao.deletePendingDecrypt(entry.clientMessageId)
                    is DecryptOutcome.Retained -> dao.bumpPendingDecrypt(entry.clientMessageId, entry.retryCount + 1, outcome.errorClass)
                }
                true
            }
            if (applied != true) return@withLock
        }
    }

    private data class IngestedContent(
        val text: String,
        val status: MessageStatus,
        val replySnapshotJson: String? = null,
        val forwarded: Boolean = false,
        val caption: String? = null,
        val albumId: String? = null,
        val conferenceInviteJson: String? = null,
    )

    /** Applies only if genuinely newer than whatever was last applied AND
     *  the target isn't already tombstoned (Этап I п.41/43 - deterministic
     *  by global sequence, resilient to stale/reordered/duplicate WS+sync
     *  delivery). A target not yet present locally is a silent no-op (a
     *  device that never saw the original simply has nothing to update -
     *  matches real multi-device semantics, same as a brand-new device
     *  never seeing pre-existing history it has no envelope for). */
    private suspend fun applyEditEffect(dto: MessageDto, content: MessageContentDto) {
        val targetServerId = dto.targetMessageId ?: return
        if (!authorizedTarget(dto, content, requireAuthor = true)) return
        dao.applyEdit(targetServerId, content.text.orEmpty(), dto.sequence)
    }

    /** Tombstones the target (Этап I п.13/14) and, if the target genuinely
     *  existed locally and this delete actually took effect, drops any
     *  cached reactions and cleans up a locally-cached decrypted
     *  attachment (Этап I п.40/47) - deletes the plaintext file itself
     *  before clearing Room's reference to it. */
    private suspend fun applyDeleteEffect(dto: MessageDto, content: MessageContentDto) {
        val targetServerId = dto.targetMessageId ?: return
        if (!authorizedTarget(dto, content, requireAuthor = true)) return
        val affected = dao.applyDelete(targetServerId, dto.sequence)
        if (affected == 0) return
        reactionDao.deleteAllForMessage(targetServerId)
        val target = dao.findByServerId(targetServerId) ?: return
        val attachment = attachmentDao.findByClientMessageId(target.clientMessageId) ?: return
        attachment.localPath?.let { runCatching { java.io.File(it).delete() } }
        attachmentDao.clearLocalCache(attachment.attachmentId)
    }

    /** Applies only if genuinely newer than this (target, sender)'s last
     *  reaction event (Этап I п.20/42) - a "removed" reaction keeps its row
     *  (emoji = null) purely so this sequence guard survives the removal,
     *  see [ReactionEntity]'s doc comment. */
    private suspend fun applyReactionEffect(dto: MessageDto, content: MessageContentDto) {
        val targetServerId = dto.targetMessageId ?: return
        if (!authorizedTarget(dto, content, requireAuthor = false)) return
        val currentSequence = reactionDao.currentSequence(targetServerId, dto.senderId) ?: 0L
        if (dto.sequence <= currentSequence) return
        val emoji = if (content.reactionAction == MessageContentDto.REACTION_ACTION_REMOVE) null else content.reaction
        reactionDao.upsert(ReactionEntity(targetServerId, dto.senderId, emoji, dto.sequence))
    }

    private suspend fun authorizedTarget(dto: MessageDto, content: MessageContentDto, requireAuthor: Boolean): Boolean {
        if (!MessageContentCodec.authenticates(content, dto.chatId, dto.clientMessageId, dto.kind, dto.targetMessageId)) return false
        val target = dto.targetMessageId?.let { dao.findByServerId(it) } ?: return false
        return target.kind == "content" && target.chatId == dto.chatId &&
            target.clientMessageId == content.authentication?.targetClientMessageId &&
            (!requireAuthor || target.senderId == dto.senderId)
    }

    /** The sender's own copy of an attachment message (multi-device echo or
     *  history/sync replay of our own send) reaches this same path - the
     *  attachment row already exists from `AttachmentManager` in that case
     *  (same `attachmentId` primary key), so this is a harmless REPLACE,
     *  never a duplicate. For a genuinely new incoming attachment, this is
     *  the first time the local device learns about it - `downloadState`
     *  starts `not_downloaded` (Этап H п.34: tap-to-download policy, no
     *  automatic full-attachment fetch on ingest). */
    private suspend fun persistAttachment(dto: MessageDto, attachment: AttachmentMetadataDto) {
        attachmentDao.findByAttachmentId(attachment.attachmentId)?.let { existing ->
            if (existing.clientMessageId == dto.clientMessageId && existing.chatId == dto.chatId &&
                dto.id.isNotBlank() && dto.sequence > 0) {
                attachmentDao.markUploadSent(existing.attachmentId, dto.clientMessageId, dto.chatId)
            }
            return
        }
        attachmentDao.upsert(
            AttachmentEntity(
                attachmentId = attachment.attachmentId,
                clientMessageId = dto.clientMessageId,
                chatId = dto.chatId,
                mediaType = attachment.mediaType,
                mimeType = attachment.mimeType,
                filename = attachment.filename,
                size = attachment.size,
                width = attachment.image?.width,
                height = attachment.image?.height,
                durationMs = if (attachment.mimeType.startsWith("video/")) attachment.video?.durationMs else attachment.voice?.durationMs,
                thumbnailBase64 = attachment.thumbnail,
                localPath = null,
                downloadState = "not_downloaded",
                uploadState = "sent",
                objectKey = attachment.objectKey,
                encryptedSize = attachment.encryptedSize,
                encryptionVersion = attachment.encryption.version,
                keyBase64 = attachment.encryption.key,
                nonceBase64 = attachment.encryption.nonce,
                keysetBase64 = attachment.encryption.keyset,
                sha256Base64 = attachment.encryption.sha256,
            ),
        )
    }

    private fun attachmentPlaceholder(attachment: AttachmentMetadataDto): String = when (attachment.mediaType) {
        AttachmentMetadataDto.MEDIA_TYPE_IMAGE -> "[Photo]"
        AttachmentMetadataDto.MEDIA_TYPE_VOICE -> "[Voice message]"
        AttachmentMetadataDto.MEDIA_TYPE_FILE -> "[File] ${attachment.filename.orEmpty()}"
        else -> "[Attachment]"
    }
}
