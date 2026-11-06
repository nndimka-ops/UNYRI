package com.messenger.app.data.message

import android.util.Log
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.MessagesApi
import com.messenger.app.domain.delivery.DeliveryAcceptance
import com.messenger.app.domain.delivery.DeliveryContext
import com.messenger.app.domain.delivery.DeliveryEngine
import com.messenger.app.domain.delivery.DeviceEnvelope
import com.messenger.app.domain.delivery.PreparedMessage
import com.messenger.app.domain.delivery.ServerReceipt
import com.messenger.app.data.attachment.AttachmentMetadataDto
import com.messenger.app.data.attachment.EncryptionInfoDto
import com.messenger.app.data.attachment.ImageInfoDto
import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.attachment.MessageContentDto
import com.messenger.app.data.attachment.ReplySnapshotDto
import com.messenger.app.data.attachment.VoiceInfoDto
import com.messenger.app.data.local.attachment.AttachmentDao
import com.messenger.app.data.local.attachment.AttachmentEntity
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.local.message.ReactionDao
import com.messenger.app.data.local.message.ReactionEntity
import com.messenger.app.data.signal.SignalSessionManager
import com.messenger.app.data.sync.GlobalSyncManager
import com.messenger.app.data.sync.MessageIngestionPipeline
import com.messenger.app.data.websocket.IncomingSocketEvent
import com.messenger.app.data.websocket.MessagingSocketClient
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.domain.model.ReactionSummary
import com.messenger.app.domain.model.ReplyPreview
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.ChatRepository
import com.messenger.app.domain.repository.MessageRepository
import com.messenger.app.domain.repository.SocketConnectionState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.messenger.app.data.directtransfer.directFilePresentations

private const val TAG = "MessageRepository"

/**
 * Resolves the single "other participant" of a chat for envelope building,
 * or returns null when there is none. Saved Messages (and future group
 * chats) have no single other user - in that case the sender's envelopes
 * are only their own other devices (multi-device sync), so callers must
 * NOT throw when this is null (previously `error("Direct chat has no other
 * participant.")` made every Saved Messages send fail with status "failed").
 */
internal suspend fun resolveOtherUserIdForChat(
    chatRepository: ChatRepository,
    chatId: String,
): String? {
    chatRepository.observeChat(chatId).first()?.otherUser?.id?.let { return it }
    return chatRepository.getChat(chatId).getOrThrow().otherUser?.id
}

class MessageRepositoryImpl(
    private val canRecordViewedCursor: () -> Boolean = { true },
    private val deletedChatDao: com.messenger.app.data.local.chat.DeletedChatDao? = null,
    private val pendingMediaLabel: (java.io.File) -> String = { it.name + "\n" + com.messenger.app.data.attachment.PendingFilePresentation.size(it.length()) },
    private val shareActivity: () -> Boolean = { false },
    private val api: MessagesApi,
    private val deliveryEngine: DeliveryEngine,
    private val dao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val reactionDao: ReactionDao,
    private val authRepository: AuthRepository,
    private val chatRepository: ChatRepository,
    private val socketClient: MessagingSocketClient,
    private val signalSessionManager: SignalSessionManager,
    private val ingestionPipeline: MessageIngestionPipeline,
    private val globalSyncManager: GlobalSyncManager,
    private val mediaUploader: com.messenger.app.data.attachment.AttachmentManager? = null,
    private val workspaceDao: com.messenger.app.data.local.chat.ChatWorkspaceDao? = null,
    private val directLocalStops: Flow<Map<String, com.messenger.app.data.directtransfer.DirectLocalTransferStop>> = kotlinx.coroutines.flow.flowOf(emptyMap()),
) : MessageRepository {

    override val peerActivity get() = socketClient.activity
    private var lastActivityAt = 0L
    override fun sendActivity(chatId: String, activity: String) {
        if (activity != "idle" && !shareActivity()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (activity != "idle" && now - lastActivityAt < 2500) return
        lastActivityAt = now
        socketClient.sendRaw(org.json.JSONObject().put("type", "chat.activity").put("chat_id", chatId).put("activity", activity).toString())
    }
    override suspend fun locateMessage(chatId: String, id: String): String? {
        val epoch = authRepository.currentSessionEpoch()
        repeat(100) {
            val row = dao.findByClientMessageId(id) ?: dao.findByServerId(id)
            if (row != null && row.chatId == chatId && !row.hiddenLocally) return row.clientMessageId
            if (authRepository.currentSessionEpoch() != epoch) return null
            if (loadOlderMessages(chatId).getOrNull() != true) {
                val last = dao.findByClientMessageId(id) ?: dao.findByServerId(id)
                return last?.takeIf { it.chatId == chatId && !it.hiddenLocally }?.clientMessageId
            }
        }
        return null
    }
    override suspend fun chatValue(chatId: String, name: String): String? = workspaceDao?.get(chatId, name)
    override suspend fun saveChatValue(chatId: String, name: String, value: String?) {
        val epoch = authRepository.currentSessionEpoch()
        authRepository.withWorkspaceWrite(epoch) {
            val previous = workspaceDao?.get(chatId, name)
            if (value == null) workspaceDao?.remove(chatId, name)
            else workspaceDao?.put(com.messenger.app.data.local.chat.ChatWorkspaceValue(chatId, name, value))
            releaseDraftPaths(com.messenger.app.data.attachment.mediaDraftPaths(name, previous))
        }
    }

    /** Call while holding the workspace lease, so ownership cannot change during deletion. */
    override suspend fun discardMediaDrafts(paths: Set<String>) {
        authRepository.withWorkspaceWrite(authRepository.currentSessionEpoch()) { releaseDraftPaths(paths) }
    }

    private suspend fun releaseDraftPaths(candidates: Set<String>) {
        if (candidates.isEmpty()) return
        val references = workspaceDao?.allValues().orEmpty().flatMap {
            com.messenger.app.data.attachment.mediaDraftPathsOrNull(it.name, it.value) ?: return
        }.toSet() + attachmentDao.allWithLocalPath().mapNotNull { it.localPath }
        for (path in candidates) mediaUploader?.discardDraft(path, references)
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private val readReceipts = ReadReceiptQueue()
    private val deliveredReceipts = ReadReceiptQueue()
    private var loginWork: kotlinx.coroutines.Job? = null

    /** N.2R durable outbox: a single processor owns all send retries (see
     *  [MessageOutboxProcessor]). `attemptSendEntity` throws on failure and the
     *  processor classifies transient vs permanent vs auth-terminal. */
    // Only accessed by the serialized outbox; network work never owns the Room gate.
    private var outboxEpoch = -1
    private var outboxAccessToken: String? = null
    private var outboxOtherUserId: String? = null
    private val outbox = MessageOutboxProcessor(
        dao = dao,
        scope = scope,
        nowEpochMs = { System.currentTimeMillis() },
        currentUserId = { currentUserId() },
        currentSessionEpoch = { authRepository.currentSessionEpoch() },
        withSessionWrite = { epoch, block -> authRepository.withSessionWrite(epoch, block) },
        prepareAttempt = { entity ->
            outboxEpoch = authRepository.currentSessionEpoch()
            check(deletedChatDao?.contains(entity.chatId) != true) { "Chat deleted" }
            outboxAccessToken = authRepository.currentAccessToken()
                ?: throw AuthApiException.Network("no access token (transient refresh failure)")
            ensureOutboxSession(outboxEpoch)
            outboxOtherUserId = resolveOtherUserId(entity.chatId)
            ensureOutboxSession(outboxEpoch)
        },
        mediaReadiness = { entity ->
            if (workspaceDao?.get(entity.chatId, "upload:" + entity.clientMessageId) == null) OutboxMediaReadiness.NONE
            else if (attachmentDao.findByClientMessageId(entity.clientMessageId)?.uploadState in setOf("uploaded", "sent")) OutboxMediaReadiness.READY
            else OutboxMediaReadiness.WAITING
        },
        prepareMedia = { entity ->
            check(deletedChatDao?.contains(entity.chatId) != true) { "Chat deleted" }
            prepareMediaUpload(entity)
        },
        attemptSend = { entity ->
            val epoch = outboxEpoch
            ensureOutboxSession(epoch)
            signalSessionManager.withSendingAccount(epoch) { attemptSendEntity(entity, epoch) }
        },
    )

    private val _managementRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override val managementRevision: StateFlow<Long> = _managementRevision
    override val socketState: StateFlow<SocketConnectionState> = socketClient.connectionState
    override val syncPhase get() = globalSyncManager.phase
    override suspend fun measurePing(): Long? = socketClient.measurePing()
    override fun retryConnectionSync() { socketClient.reconnectIfNeeded(); scope.launch { globalSyncManager.runCatchUpSync() } }

    init {
        ingestionPipeline.onDeleteChats = { ids -> deleteChats(ids) }
        scope.launch {
            while (true) {
                runCatching { flushReadReceipts() }
                kotlinx.coroutines.delay(15_000)
            }
        }
        // The messaging socket only makes sense while logged in - connect on
        // login, disconnect on logout, so it never outlives the session it
        // was authenticated for. Key bootstrap (identity/prekey generation +
        // upload) similarly only makes sense once authenticated, and is
        // idempotent so re-running it on every login is harmless.
        var observedOutboxEpoch = authRepository.currentSessionEpoch()
        authRepository.authState
            .onEach { state ->
                val epoch = authRepository.currentSessionEpoch()
                if (epoch != observedOutboxEpoch) {
                    observedOutboxEpoch = epoch
                    outbox.cancelActiveAttempt()
                }
                loginWork?.cancel()
                loginWork = null
                when (state) {
                    is AuthSessionState.LoggedIn -> {
                        val loginEpoch = authRepository.currentSessionEpoch()
                        socketClient.start()
                        loginWork = scope.launch {
                            val token = authRepository.currentAccessToken() ?: return@launch
                            if (authRepository.currentSessionEpoch() != loginEpoch) return@launch
                            kotlinx.coroutines.supervisorScope {
                                launch {
                                    try { signalSessionManager.bootstrapKeysIfNeeded(token) }
                                    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                    catch (error: Exception) { Log.w(TAG, "key bootstrap failed: ${error::class.simpleName}") }
                                }
                                // Recover missed events and pending sends within this login.
                                launch { globalSyncManager.runCatchUpSync() }
                                outbox.forceFlush()
                                launch { flushReadReceipts() }
                            }
                        }
                    }
                    else -> socketClient.stop()
                }
            }
            .launchIn(scope)

        // WS reconnect -> global catch-up sync. Covers ALL chats on this
        // device in one pass (not just whichever chat screen happens to be
        // open) - the primary defense against missed WS events while
        // disconnected.
        socketClient.connectionState
            .onEach { state ->
                if (state == SocketConnectionState.CONNECTED) {
                    scope.launch { globalSyncManager.runCatchUpSync() }
                    // N.2R outbox: a re-established backend connection is the
                    // primary signal to retry any PENDING sends immediately.
                    outbox.forceFlush()
                    scope.launch { flushReadReceipts() }
                }
            }
            .launchIn(scope)

        socketClient.events
            .onEach { event ->
                when (event) {
                    IncomingSocketEvent.ChatManagementChanged -> {
                        globalSyncManager.runCatchUpSync()
                        _managementRevision.value += 1
                    }
                    is IncomingSocketEvent.ChatDeleted -> ingestionPipeline.deleteChats(listOf(event.chatId))
                    is IncomingSocketEvent.NewMessage -> ingestionPipeline.ingest(listOf(event.message), advanceCursor = false)
                    is IncomingSocketEvent.ReceiptUpdate -> handleReceiptUpdate(event)
                    // Call signaling frames are handled entirely by
                    // CallSignalingClient/CallController (Этап J) - this is
                    // messaging's own event stream, unrelated concern.
                    is IncomingSocketEvent.CallHistoryChanged,
                    is IncomingSocketEvent.CallRinging,
                    is IncomingSocketEvent.CallOffer,
                    is IncomingSocketEvent.CallAnswer,
                    is IncomingSocketEvent.CallIce,
                    is IncomingSocketEvent.CallAccepted,
                    is IncomingSocketEvent.CallAnsweredElsewhere,
                    is IncomingSocketEvent.CallEnded,
                    is IncomingSocketEvent.CallVideoUpgradeRequest,
                    is IncomingSocketEvent.CallVideoUpgradeAccept,
                    is IncomingSocketEvent.CallVideoUpgradeReject,
                    is IncomingSocketEvent.CallVideoUpgradeCancel,
                    is IncomingSocketEvent.CallVideoState -> Unit
                }
            }
            .launchIn(scope)
    }

    override fun observeMessages(chatId: String): Flow<List<Message>> =
        projectChatSnapshots(dao.observeMessages(chatId), reactionDao.observeActiveForChat(chatId)) { entities, reactions ->
            val projected = projectPendingLifecycle(entities, reactions)
            val reactionsByTarget = projected.second.groupBy { it.targetMessageId }
            val myUserId = currentUserId()
            val messages = projected.first.map { it.toDomain(reactionsByTarget[it.serverId].orEmpty(), myUserId) }
            withContext(Dispatchers.IO) {
                val reserved = com.messenger.app.data.attachment.PendingMediaPreview.reserve(messages, workspaceDao?.mediaPresentations(chatId).orEmpty())
                if (messages.any { it.status == MessageStatus.PENDING || it.status == MessageStatus.FAILED })
                    com.messenger.app.data.attachment.PendingMediaPreview.seed(reserved, workspaceDao?.mediaJobs(chatId).orEmpty())
                else reserved
            }
        }.combine(workspaceDao?.conferenceDecisions(chatId)?.distinctUntilChanged() ?: kotlinx.coroutines.flow.flowOf(emptyList())) { messages, values ->
            val declined = values.map { it.name.removePrefix("conference_declined:") }.toSet()
            messages.map { if (it.clientMessageId in declined) it.copy(conferenceDeclined = true) else it }
        }.combine((workspaceDao?.directTransfers(chatId)?.distinctUntilChanged() ?: kotlinx.coroutines.flow.flowOf(emptyList())).directFilePresentations(chatId, directLocalStops)) { messages, direct ->
            messages.map { message ->
                val transfer = direct[message.clientMessageId]
                if (transfer != null && transfer.offer.senderUserId == message.senderId && !message.forwarded && !message.isDeletedForEveryone &&
                    message.status !in setOf(MessageStatus.DECRYPT_FAILED, MessageStatus.IDENTITY_CHANGED)) message.copy(directFile = transfer) else message
            }
        }.flowOn(Dispatchers.Default)

    override fun observeAttachments(chatId: String): Flow<List<com.messenger.app.domain.model.Attachment>> =
        attachmentDao.observeForChat(chatId).distinctUntilChanged().map { entities -> entities.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    override suspend fun sendMessage(chatId: String, text: String): Result<Unit> =
        sendMessageForSession(chatId, text, authRepository.currentSessionEpoch())

    internal suspend fun sendDirectFileOffer(chatId: String, offer: com.messenger.app.data.directtransfer.DirectFileOffer, expectedEpoch: Int) {
        offer.validated()
        require(offer.senderDeviceId == authRepository.currentDeviceId() && offer.senderUserId == currentUserId())
        authRepository.withSessionWrite(expectedEpoch) {
            val stored = requireNotNull(workspaceDao)
            val key = "direct_offer:${offer.offerClientMessageId}"
            val previousOffer = stored.get(chatId, key)
            require(previousOffer == null || previousOffer == offer.stored()) { "Offer metadata changed" }
            stored.put(com.messenger.app.data.local.chat.ChatWorkspaceValue(chatId, key, offer.stored()))
            val previous = dao.findByClientMessageId(offer.offerClientMessageId)
            if (previous == null) dao.upsert(MessageEntity(offer.offerClientMessageId, null, chatId, offer.senderUserId,
                com.messenger.app.data.directtransfer.DirectFileOffer.FALLBACK, null, java.time.Instant.now().toString(), "pending"))
            else require(previous.chatId == chatId && previous.senderId == offer.senderUserId && previous.kind == "content")
            outbox.triggerFlush()
            true
        } ?: throw kotlinx.coroutines.CancellationException("Transfer session changed")
    }

    override suspend fun sendMessageForSession(chatId: String, text: String, expectedEpoch: Int): Result<Unit> = runCatching {
      val prepared = MessageTextPolicy.outgoing(text)
      authRepository.withSessionWrite(expectedEpoch) {
        val clientMessageId = UUID.randomUUID().toString()
        val senderId = currentUserId() ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
        dao.upsert(
            MessageEntity(
                clientMessageId = clientMessageId,
                serverId = null,
                chatId = chatId,
                senderId = senderId,
                text = prepared,
                sequence = null,
                createdAt = java.time.Instant.now().toString(),
                status = "pending",
            ),
        )
        // N.2R outbox: the tap only durably enqueues the message; actual
        // delivery is eventual (this returns control to the UI immediately).
        outbox.triggerFlush()
      } ?: throw kotlinx.coroutines.CancellationException("Message session changed")
      Unit
    }

    override suspend fun sendConferenceInvitation(chatId: String, invitation: com.messenger.app.conference.ConferenceInvitation, expectedEpoch: Int): Result<Unit> = runCatching {
        val metadata = invitation.validated().stored()
        authRepository.withSessionWrite(expectedEpoch) {
            val sender = currentUserId() ?: error("Not authenticated")
            val previous = dao.findByClientMessageId(invitation.id)
            if (previous != null) check(previous.chatId == chatId && previous.senderId == sender && previous.conferenceInviteJson == metadata) { "Invitation already queued differently" }
            else dao.upsert(MessageEntity(invitation.id, null, chatId, sender,
                com.messenger.app.conference.ConferenceInvitation.FALLBACK, null, java.time.Instant.now().toString(), "pending", conferenceInviteJson = metadata))
            outbox.triggerFlush()
            true
        } ?: throw kotlinx.coroutines.CancellationException("Message session changed")
        Unit
    }
    override suspend fun declineConferenceInvitation(clientMessageId: String, expectedEpoch: Int): Result<Unit> = runCatching {
        authRepository.withSessionWrite(expectedEpoch) {
            val message = dao.findByClientMessageId(clientMessageId) ?: error("Invitation unavailable")
            check(currentUserId() != null && message.senderId != currentUserId() && !message.hiddenLocally && !message.isDeletedForEveryone)
            check(com.messenger.app.conference.ConferenceInvitation.decodeStored(message.conferenceInviteJson) != null)
            requireNotNull(workspaceDao).put(com.messenger.app.data.local.chat.ChatWorkspaceValue(message.chatId, "conference_declined:$clientMessageId", "1"))
            true
        } ?: throw kotlinx.coroutines.CancellationException("Message session changed")
        Unit
    }

    /** Called once an [AttachmentMetadataDto] has already been fully
     *  prepared/encrypted/uploaded/completed by `AttachmentManager` - this
     *  method only wraps it in the versioned Signal-encrypted content and
     *  sends it, exactly like a text message otherwise (optimistic Room row
     *  first, then push, `failed`+retry on any error). */
    override fun scheduledDates(chatId: String): Flow<Map<String, Long>> = workspaceDao?.schedules(chatId)?.map { rows -> rows.mapNotNull { row -> row.value.toLongOrNull()?.let { row.name.removePrefix("schedule:") to it } }.toMap() } ?: kotlinx.coroutines.flow.flowOf(emptyMap())
    override suspend fun scheduleText(chatId: String, text: String, due: Long): Result<Unit> = runCatching {
        val prepared = MessageTextPolicy.outgoing(text)
        require(due > System.currentTimeMillis() && due - System.currentTimeMillis() <= 366L * 86400000)
        val epoch = authRepository.currentSessionEpoch()
        authRepository.withWorkspaceWrite(epoch) {
            val owner = currentUserId() ?: error("Not authenticated")
            dao.enqueueScheduled(MessageEntity(clientMessageId=UUID.randomUUID().toString(), serverId=null, chatId=chatId, senderId=owner, text=prepared, sequence=null, createdAt=java.time.Instant.now().toString(), status="pending"), due)
            true
        } ?: throw kotlinx.coroutines.CancellationException("Account changed")
        outbox.triggerFlush()
    }
    override suspend fun queueMedia(chatId: String, id: String, uri: String, kind: String, caption: String?, duration: Long, albumId: String?): Result<Unit> =
        queueMediaBatch(chatId, listOf(com.messenger.app.domain.repository.QueuedMediaDraft(id, uri, kind, caption, duration, albumId)))

    override suspend fun queueMediaBatch(chatId: String, media: List<com.messenger.app.domain.repository.QueuedMediaDraft>): Result<Unit> = runCatching {
        require(media.size in 1..10 && media.map { it.id }.distinct().size == media.size)
        require(media.all { it.kind in setOf("image", "image_high", "video", "video_high", "file", "voice") })
        val safeMedia = media.map { it.copy(caption = MessageTextPolicy.caption(it.caption)) }
        val epoch = authRepository.currentSessionEpoch()
        // Read headers off-main and outside the write gate. No upload/bitmap decoding here.
        val prepared = withContext(Dispatchers.IO) { safeMedia.map { draft ->
            val label = draft.caption?.takeIf { it.isNotBlank() } ?: pendingMediaLabel(java.io.File(java.net.URI(draft.uri)))
            val job = com.messenger.app.data.attachment.PendingMediaPreview.job(draft.uri, draft.kind, draft.duration)
            Triple(draft, label, job)
        } }
        authRepository.withWorkspaceWrite(epoch) {
            val owner = currentUserId() ?: error("Not authenticated")
            // Position when the batch actually enters the timeline. A text sent
            // during header preparation must not later be overtaken by this album.
            val createdAt = java.time.Instant.now().toString()
            dao.enqueueMediaBatch(prepared.mapIndexed { index, (draft, label, job) ->
                com.messenger.app.data.local.message.QueuedMediaRow(
                    MessageEntity(clientMessageId=draft.id, serverId=null, chatId=chatId, senderId=owner,
                        text=label, sequence=null, createdAt=createdAt, status="pending", caption=draft.caption, albumId=draft.albumId),
                    job.toString(), com.messenger.app.data.attachment.PendingMediaPreview.presentation(job, createdAt, index).toString())
            })
            true
        } ?: throw kotlinx.coroutines.CancellationException("Account changed")
        outbox.triggerFlush()
    }
    private suspend fun prepareMediaUpload(entity: MessageEntity) = kotlinx.coroutines.coroutineScope {
        val job = workspaceDao?.get(entity.chatId, "upload:" + entity.clientMessageId) ?: return@coroutineScope
        val existing = attachmentDao.findByClientMessageId(entity.clientMessageId)
        if (existing?.uploadState in setOf("uploaded", "sent")) return@coroutineScope
        val uploader = mediaUploader ?: error("Media uploader unavailable")
        val value = org.json.JSONObject(job)
        val uri = android.net.Uri.parse(value.getString("uri"))
        val activityJob = launch {
            while (true) { if (shareActivity()) sendActivity(entity.chatId, "uploading"); kotlinx.coroutines.delay(3000) }
        }
        try {
        if (uploader.resumeUpload(entity.clientMessageId)) return@coroutineScope
        when(value.getString("kind")) {
            "image" -> uploader.prepareAndUploadImage(uri, entity.chatId, entity.clientMessageId)
            "image_high" -> uploader.prepareAndUploadImage(uri, entity.chatId, entity.clientMessageId, highQuality = true)
            "voice" -> uploader.prepareAndUploadVoice(java.io.File(requireNotNull(uri.path)), value.optLong("duration"), entity.chatId, entity.clientMessageId)
            "video", "video_high" -> uploader.prepareAndUploadVideo(uri, entity.chatId, entity.clientMessageId, value.getString("kind") == "video_high")
            "file" -> uploader.prepareAndUploadFile(uri, entity.chatId, entity.clientMessageId)
            else -> error("Unsupported media draft")
        }
        } finally { activityJob.cancel(); if (shareActivity()) sendActivity(entity.chatId, "idle") }
    }

    override suspend fun sendAttachmentMessage(chatId: String, clientMessageId: String, attachment: AttachmentMetadataDto, forwarded: Boolean, caption: String?, albumId: String?): Result<Unit> = runCatching {
      val safeCaption = MessageTextPolicy.caption(caption)
      authRepository.withSessionWrite(authRepository.currentSessionEpoch()) {
        val senderId = currentUserId() ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
        if (dao.findByClientMessageId(clientMessageId) == null) {
            dao.upsert(
                MessageEntity(
                    clientMessageId = clientMessageId,
                    serverId = null,
                    chatId = chatId,
                    senderId = senderId,
                    text = safeCaption ?: attachmentPlaceholder(attachment),
                    caption = safeCaption,
                    albumId = albumId,
                    sequence = null,
                    createdAt = java.time.Instant.now().toString(),
                    status = "pending",
                    forwarded = forwarded,
                ),
            )
        }
        outbox.triggerFlush()
      } ?: throw kotlinx.coroutines.CancellationException("Message session changed")
      Unit
    }

    override fun observeFailedActions(chatId: String) = dao.observeFailedActions(chatId).map { rows ->
        rows.map { com.messenger.app.domain.repository.FailedMessageAction(it.clientMessageId, it.kind, it.lastErrorClass) }
    }.distinctUntilChanged()

    override suspend fun retry(clientMessageId: String): Result<Unit> = runCatching {
      authRepository.withSessionWrite(authRepository.currentSessionEpoch()) {
        val live = dao.findByClientMessageId(clientMessageId) ?: return@withSessionWrite
        if (live.status != "failed" || live.hiddenLocally || live.isDeletedForEveryone || live.senderId != currentUserId()) return@withSessionWrite
        // Same clientMessageId forever (idempotency). Reset to PENDING with a
        // fresh retry window and let the outbox do the actual send.
        dao.resetToPending(clientMessageId)
        outbox.triggerFlush()
      } ?: throw kotlinx.coroutines.CancellationException("Message session changed")
      Unit
    }

    /** plaintext -> SignalSessionManager.encrypt() -> per-device ciphertext
     *  envelopes -> POST - the network request never carries plaintext.
     *  Encrypts once per active device of the other chat member(s), plus
     *  once per *other* device of this account (multi-device echo, so a
     *  second device of the sender's own account also gets the message).
     *  [targetMessageId] is the reply-to reference (Этап I п.3) - null for a
     *  plain send. */
    override suspend fun applyPersonalClear(chatId: String, sequence: Long): Boolean {
        val epoch = authRepository.currentSessionEpoch()
        return authRepository.withSessionWrite(epoch) {
            val store = deletedChatDao ?: return@withSessionWrite false
            store.clearPersonal(chatId, sequence); true
        } ?: false
    }
    suspend fun <T> withBoundedChatClear(block: suspend () -> T): T = outbox.withDeliveryPaused(block)
    override suspend fun applyDeletedChat(chatId: String): Boolean = deleteChats(listOf(chatId))

    private suspend fun deleteChats(ids: List<String>): Boolean {
        if (ids.isEmpty()) return true
        val store = deletedChatDao ?: return false
        val epoch = authRepository.currentSessionEpoch()
        outbox.cancelActiveAttempt()
        mediaUploader?.cancelChats(epoch, ids)
        return authRepository.withSessionWrite(epoch) {
            authRepository.withWorkspaceWrite(epoch) {
                val workspace = workspaceDao?.allValues().orEmpty()
                val rows = attachmentDao.allWithLocalPath().filter { it.chatId in ids }
                val candidates = workspace.filter { it.chatId in ids }.flatMap {
                    com.messenger.app.data.attachment.mediaDraftPaths(it.name, it.value)
                }.toSet() + rows.mapNotNull { it.localPath }
                ids.distinct().forEach { store.tombstone(it) }
                releaseDraftPaths(candidates)
                mediaUploader?.discardDeletedChatCache(rows)
                true
            } ?: false
        } ?: false
    }

    private fun ensureOutboxSession(epoch: Int) {
        if (epoch != authRepository.currentSessionEpoch()) throw kotlinx.coroutines.CancellationException("Outbox session changed")
    }

    private suspend fun attemptSendEntity(entity: MessageEntity, epoch: Int) {
        ensureOutboxSession(epoch)
        // Validate only; an attempted payload and deferred edit are never trimmed.
        MessageTextPolicy.retry(entity.pendingEditText, optional = true)
        check(deletedChatDao?.contains(entity.chatId) != true) { "Chat deleted" }
        val deliveryContext = DeliveryContext(epoch)
        val token = outboxAccessToken
            ?: throw AuthApiException.Network("no access token (transient refresh failure)")
        if (entity.kind != "content") {
            val targetId = requireNotNull(entity.targetMessageId)
            val target = dao.findByServerId(targetId) ?: error("Message no longer available")
            check(target.chatId == entity.chatId && (entity.kind == "reaction" || target.senderId == entity.senderId))
            val plain = entity.text.toByteArray(Charsets.UTF_8)
            val authenticated = MessageContentCodec.authenticate(plain, entity.chatId, entity.clientMessageId,
                entity.kind, targetId, target.clientMessageId)
            val envelopes = buildEnvelopes(outboxOtherUserId, token, authenticated)
            val receipt = sendPrepared(PreparedMessage(entity.chatId, entity.clientMessageId, envelopes,
                kind = entity.kind, targetMessageId = targetId), deliveryContext, token)
            val content = MessageContentCodec.decode(plain)
            authRepository.withSessionWrite(epoch) {
                val live = dao.findByClientMessageId(entity.clientMessageId)
                if (live == null || live.hiddenLocally || deletedChatDao?.contains(entity.chatId) == true) return@withSessionWrite
                dao.commitLifecycle(entity.copy(serverId = receipt.id, sequence = receipt.sequence,
                    createdAt = receipt.createdAt, status = "sent", text = authenticated.toString(Charsets.UTF_8))) {
                    when (entity.kind) {
                        "edit" -> dao.applyEdit(targetId, content.text.orEmpty(), receipt.sequence)
                        "delete" -> { dao.applyDelete(targetId, receipt.sequence); reactionDao.deleteAllForMessage(targetId) }
                        "reaction" -> if (dao.findByServerId(targetId)?.isDeletedForEveryone == false) reactionDao.upsert(ReactionEntity(targetId, entity.senderId,
                            if (content.reactionAction == MessageContentDto.REACTION_ACTION_REMOVE) null else content.reaction, receipt.sequence))
                    }
                }
                if (entity.kind == "delete") {
                    attachmentDao.findByClientMessageId(target.clientMessageId)?.let { attachment ->
                        attachment.localPath?.let { runCatching { java.io.File(it).delete() } }
                        attachmentDao.clearLocalCache(attachment.attachmentId)
                    }
                }
            } ?: throw kotlinx.coroutines.CancellationException("Outbox session changed")
            return
        }
        val replySnapshot = entity.replySnapshotJson?.let { raw ->
            runCatching { json.decodeFromString(ReplySnapshotDto.serializer(), raw) }.getOrNull()
        }
        val plaintext: ByteArray
        val attachmentIds: List<String>
        // An attachment is already uploaded by the time a send can fail -
        // retry must never re-upload it (Этап H п.17), only resend the message.
        val attachmentEntity = attachmentDao.findByClientMessageId(entity.clientMessageId)
        // Freeze the text that belongs to this idempotency key before the first
        // POST. Edits after this point update text + a pending intent, never
        // this frozen payload.
        if (attachmentEntity == null) {
            authRepository.withSessionWrite(epoch) {
                dao.markSendStarted(entity.clientMessageId, entity.text)
            } ?: throw kotlinx.coroutines.CancellationException("Outbox session changed")
        }
        val liveEntity = dao.findByClientMessageId(entity.clientMessageId)
            ?: throw kotlinx.coroutines.CancellationException("Message disappeared")
        if (attachmentEntity != null) {
            val attachment = attachmentEntity.toMetadataDto()
            plaintext = MessageContentCodec.encodeAttachment(attachment, replySnapshot = replySnapshot, forwarded = entity.forwarded, caption = entity.caption, albumId = entity.albumId)
            attachmentIds = listOf(attachment.attachmentId)
        } else {
            val directOffer = com.messenger.app.data.directtransfer.DirectFileOffer.decodeStored(workspaceDao?.get(entity.chatId, "direct_offer:${entity.clientMessageId}"))
            plaintext = composeTextMessagePayload(liveEntity, replySnapshot, directOffer)
            attachmentIds = emptyList()
        }
        val authenticated = MessageContentCodec.authenticate(plaintext, entity.chatId, entity.clientMessageId,
            "content", entity.targetMessageId)
        val envelopes = buildEnvelopes(outboxOtherUserId, token, authenticated)
        val response = sendPrepared(
            PreparedMessage(entity.chatId, entity.clientMessageId, envelopes, attachmentIds,
                targetMessageId = entity.targetMessageId),
            deliveryContext, token,
        )
        authRepository.withSessionWrite(epoch) {
            val live = dao.findByClientMessageId(entity.clientMessageId)
            if (live != null && !live.hiddenLocally && deletedChatDao?.contains(entity.chatId) != true) {
                reconcileSent(response, entity.clientMessageId, plaintext, entity.targetMessageId)
            }
        } ?: throw kotlinx.coroutines.CancellationException("Outbox session changed")
    }

    /** Persist the intent before returning to UI. The shared outbox retains its
     * ID and order across reconnect/process death; no synthetic server sequence. */
    private suspend fun enqueueLifecycle(target: MessageEntity, kind: String, plaintext: ByteArray) {
        val owner = currentUserId() ?: error("Not authenticated")
        check(target.kind == "content" && !target.isDeletedForEveryone)
        check(kind == "reaction" || target.senderId == owner)
        dao.upsert(MessageEntity(clientMessageId = UUID.randomUUID().toString(), serverId = null,
            chatId = target.chatId, senderId = owner, text = plaintext.toString(Charsets.UTF_8),
            sequence = null, createdAt = java.time.Instant.now().toString(), status = "pending",
            kind = kind, targetMessageId = requireNotNull(target.serverId)))
        outbox.triggerFlush()
    }

    /** Reconciliation still uses the legacy server sequence in this server-only stage.
     * A second transport must add its own acceptance handling, never invent a sequence. */
    private suspend fun sendPrepared(message: PreparedMessage, context: DeliveryContext, token: String): ServerReceipt =
      com.messenger.app.data.delivery.withPreparedServerAuth(token, context.sessionEpoch) {
        when (val accepted = deliveryEngine.attempt(message, context)) {
            is DeliveryAcceptance.ServerAccepted -> accepted.receipt
        }
      }

    private suspend fun buildEnvelopes(otherUserId: String?, token: String, plaintext: ByteArray): List<DeviceEnvelope> {
        val myUserId = currentUserId() ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
        val myDeviceId = authRepository.currentDeviceId()

        val envelopes = mutableListOf<DeviceEnvelope>()
        // Saved Messages has no "other user" (the owner is the only member);
        // future group chats likewise have no single other participant. For
        // those, the only envelopes are the sender's own other devices
        // (multi-device sync), so skip the other-user loop when there is none.
        if (otherUserId != null) {
            val peerEnvelopes = signalSessionManager.buildEnvelopesForUser(otherUserId, token, plaintext)
            if (peerEnvelopes.isEmpty()) throw RecipientDevicesUnavailableException()
            peerEnvelopes.forEach {
                envelopes += DeviceEnvelope(it.recipientDeviceId, it.messageType, it.ciphertext)
            }
        }
        signalSessionManager.buildEnvelopesForUser(myUserId, token, plaintext, excludeDeviceId = myDeviceId).forEach {
            envelopes += DeviceEnvelope(it.recipientDeviceId, it.messageType, it.ciphertext)
        }
        return envelopes
    }

    private suspend fun resolveOtherUserId(chatId: String): String? =
        resolveOtherUserIdForChat(chatRepository, chatId)

    override suspend fun syncLatest(chatId: String): Result<Unit> = withAccessToken {
        val response = api.listMessages(it, chatId)
        ingestionPipeline.ingest(response.messages, advanceCursor = false)
        Unit
    }

    override suspend fun loadOlderMessages(chatId: String): Result<Boolean> = withAccessToken { token ->
        val oldest = dao.minSequence(chatId)
        val response = api.listMessages(token, chatId, beforeSequence = oldest)
        ingestionPipeline.ingest(response.messages, advanceCursor = false)
        response.hasMore
    }
    override suspend fun prepareHistoryBackup(chatId: String): Result<Unit> = withAccessToken { token ->
        val epoch = authRepository.currentSessionEpoch()
        var before: Long? = null
        var pages = 0
        do {
            check(authRepository.currentSessionEpoch() == epoch)
            val response = api.listMessages(token, chatId, beforeSequence = before)
            check(authRepository.currentSessionEpoch() == epoch)
            check(ingestionPipeline.ingest(response.messages, advanceCursor = false).advancedFully)
            if (!response.hasMore) break
            val next = response.messages.minOfOrNull { it.sequence } ?: error("History pagination stalled")
            check(before == null || next < before!!)
            before = next
            check(++pages <= 1000) { "History exceeds backup limit" }
        } while (true)
    }

    override suspend fun markRead(chatId: String, sequence: Long): Result<Unit> = runCatching {
        if (!canRecordViewedCursor()) return@runCatching Unit
        authRepository.withSessionWrite(authRepository.currentSessionEpoch()) {
            val owner = currentUserId()
            if (owner != null && sequence > 0 && canRecordViewedCursor()) {
                // The local viewed cursor includes unavailable placeholders.
                // flushReadReceipts independently chooses a readable target.
                chatRepository.markLocallyRead(chatId, sequence)
            }
        }
        scope.launch { flushReadReceipts() }
        Unit
    }

    private suspend fun flushReadReceipts() {
        val epoch = authRepository.currentSessionEpoch()
        if (currentUserId() == null) return
        val token = authRepository.currentAccessToken() ?: return
        readReceipts.flush(epoch,
            isCurrent = { authRepository.currentSessionEpoch() == epoch && currentUserId() != null },
            load = { authRepository.withSessionWrite(epoch) { chatRepository.localReadWatermarks().mapValues { (chat, sequence) ->
                dao.receiptSequence(chat, currentUserId() ?: return@withSessionWrite emptyMap(), sequence)
            }.filterValues { it > 0 } } ?: emptyMap() },
            send = { chat, sequence -> api.markRead(token, chat, sequence); Unit },
        )
        val owner = currentUserId() ?: return
        deliveredReceipts.flush(epoch,
            isCurrent = { authRepository.currentSessionEpoch() == epoch && currentUserId() == owner },
            load = { authRepository.withSessionWrite(epoch) {
                dao.incomingChatIds(owner).associateWith { dao.receiptSequence(it, owner, Long.MAX_VALUE) }
            } ?: emptyMap() },
            send = { chat, sequence -> api.markDelivered(token, chat, sequence); Unit },
        )
    }

    /** The response to our own send never carries ciphertext back (the
     *  sender already has the plaintext it just composed) - reconcile
     *  directly from the locally-known content, no decrypt involved. */
    private suspend fun reconcileSent(response: ServerReceipt, clientMessageId: String, plaintext: ByteArray, targetMessageId: String? = null) {
        val content = MessageContentCodec.decode(plaintext)
        val displayText = if (content.kind == MessageContentDto.KIND_ATTACHMENT && content.attachment != null) {
            attachmentDao.updateUploadState(content.attachment.attachmentId, "sent", 100)
            attachmentPlaceholder(content.attachment)
        } else {
            content.text.orEmpty()
        }
        val existing = dao.findByClientMessageId(clientMessageId)
        val pendingEdit = existing?.pendingEditText?.takeIf { it.isNotBlank() }
        val (delivered, read) = chatRepository.peerReceipt(response.chatId)
        val sent = MessageEntity(
            clientMessageId = clientMessageId,
            serverId = response.id,
            chatId = response.chatId,
            senderId = response.senderId,
            text = pendingEdit ?: if (existing?.isEdited == true) existing.text else displayText,
            sequence = response.sequence,
            createdAt = response.createdAt,
            status = when {
                existing?.status == "read" || response.sequence <= read -> "read"
                existing?.status == "delivered" || response.sequence <= delivered -> "delivered"
                else -> "sent"
            },
            hiddenLocally = existing?.hiddenLocally ?: false,
            isEdited = existing?.isEdited ?: false,
            lastEditSequence = existing?.lastEditSequence ?: 0,
            isDeletedForEveryone = existing?.isDeletedForEveryone ?: false,
            deleteSequence = existing?.deleteSequence ?: 0,
            targetMessageId = targetMessageId,
            replySnapshotJson = content.replySnapshot?.let { json.encodeToString(ReplySnapshotDto.serializer(), it) },
            forwarded = content.forwarded,
            caption = content.text.takeIf { content.kind == MessageContentDto.KIND_ATTACHMENT },
            albumId = content.albumId,
            payloadText = existing?.payloadText,
            pendingEditText = pendingEdit,
            pendingEditRevision = existing?.pendingEditRevision ?: 0,
            sendAttempted = true,
            conferenceInviteJson = com.messenger.app.conference.ConferenceInvitation.decode(content.conferenceInvite)?.stored(),
        )
        val edit = pendingEdit?.let { buildDeferredEditRow(existing, response, it) }
        dao.commitSentAndPendingEdit(sent, edit)
        saveChatValue(response.chatId, "upload:$clientMessageId", null)
        saveChatValue(response.chatId, "schedule:$clientMessageId", null)
    }

    /** Build the edit before committing the receipt. If construction fails,
     *  keep the original retryable row and its durable edit intent together. */
    private fun buildDeferredEditRow(
        target: MessageEntity?,
        response: ServerReceipt,
        newText: String,
    ): MessageEntity {
        val targetKey = target?.clientMessageId ?: error("missing edit target")
        val id = UUID.randomUUID().toString()
        val authenticated = MessageContentCodec.authenticate(
            MessageContentCodec.encodeEdit(newText),
            response.chatId,
            id,
            "edit",
            response.id,
            targetKey,
        )
        return MessageEntity(
            clientMessageId = id,
            serverId = null,
            chatId = response.chatId,
            senderId = response.senderId,
            text = authenticated.toString(Charsets.UTF_8),
            sequence = null,
            createdAt = java.time.Instant.now().toString(),
            status = "pending",
            kind = "edit",
            targetMessageId = response.id,
        )
    }

    private fun attachmentPlaceholder(attachment: AttachmentMetadataDto): String = when (attachment.mediaType) {
        AttachmentMetadataDto.MEDIA_TYPE_IMAGE -> "[Photo]"
        AttachmentMetadataDto.MEDIA_TYPE_VOICE -> "[Voice message]"
        AttachmentMetadataDto.MEDIA_TYPE_FILE -> "[File] ${attachment.filename.orEmpty()}"
        else -> "[Attachment]"
    }

    private fun AttachmentEntity.toMetadataDto(): AttachmentMetadataDto = AttachmentMetadataDto(
        attachmentId = attachmentId,
        mediaType = mediaType,
        objectKey = objectKey,
        size = size,
        encryptedSize = encryptedSize,
        mimeType = mimeType,
        encryption = EncryptionInfoDto(
            version = encryptionVersion,
            key = keyBase64,
            nonce = nonceBase64,
            keyset = keysetBase64,
            sha256 = sha256Base64,
        ),
        filename = filename,
        image = if (width != null && height != null) ImageInfoDto(width, height) else null,
        voice = if (mediaType == "voice") durationMs?.let { VoiceInfoDto(it) } else null,
        thumbnail = thumbnailBase64,
        video = if (mimeType.startsWith("video/")) durationMs?.let { com.messenger.app.data.attachment.VideoInfoDto(it) } else null,
    )

    private fun AttachmentEntity.toDomain() = com.messenger.app.domain.model.Attachment(
        attachmentId = attachmentId,
        clientMessageId = clientMessageId,
        mediaType = com.messenger.app.domain.model.AttachmentMediaType.fromStored(mediaType),
        mimeType = mimeType,
        filename = filename,
        size = size,
        width = width,
        height = height,
        durationMs = durationMs,
        thumbnailBase64 = thumbnailBase64,
        localPath = localPath,
        downloadState = com.messenger.app.domain.model.AttachmentDownloadState.fromStored(downloadState),
        uploadState = com.messenger.app.domain.model.AttachmentUploadState.fromStored(uploadState),
        progress = progress,
    )

    /** Fast-path propagation of the OTHER member's delivered/read watermark
     *  onto OUR OWN sent message bubbles in this chat (forward-only - see
     *  MessageDao.advanceStatusUpTo). Also opportunistically refreshes the
     *  chat's HTTP-cached reconciliation fields as a correctness-path
     *  fallback for any receipt event ever missed - fire-and-forget, never
     *  blocks realtime processing on a network round-trip. */
    private suspend fun handleReceiptUpdate(event: IncomingSocketEvent.ReceiptUpdate) {
        authRepository.withSessionWrite(authRepository.currentSessionEpoch()) {
            if (event.userId == currentUserId()) {
                chatRepository.markLocallyRead(event.chatId, event.readSequence)
            } else {
                advanceOwnMessageStatuses(event.chatId, event.deliveredSequence, event.readSequence)
            }
        }
    }

    override suspend fun reconcileReceipts(chatId: String, otherMemberDeliveredSequence: Long, otherMemberReadSequence: Long) {
        advanceOwnMessageStatuses(chatId, otherMemberDeliveredSequence, otherMemberReadSequence)
    }

    private suspend fun advanceOwnMessageStatuses(chatId: String, deliveredSequence: Long, readSequence: Long) {
        val myUserId = currentUserId() ?: return
        chatRepository.recordPeerReceipt(chatId, deliveredSequence, readSequence)
        if (deliveredSequence > 0) {
            dao.advanceStatusUpTo(
                chatId = chatId,
                myUserId = myUserId,
                sequence = deliveredSequence,
                status = MessageStatus.DELIVERED.name.lowercase(),
                fromStatuses = listOf(MessageStatus.SENT.name.lowercase()),
            )
        }
        if (readSequence > 0) {
            dao.advanceStatusUpTo(
                chatId = chatId,
                myUserId = myUserId,
                sequence = readSequence,
                status = MessageStatus.READ.name.lowercase(),
                fromStatuses = listOf(MessageStatus.SENT.name.lowercase(), MessageStatus.DELIVERED.name.lowercase()),
            )
        }
    }

    private fun currentUserId(): String? = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id

    private suspend fun <T> withAccessToken(block: suspend (String) -> T): Result<T> = runCatching {
        val token = authRepository.currentAccessToken() ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
        block(token)
    }

    /** Lifecycle mutations share the same account boundary as ingestion and the outbox. */
    private suspend fun sessionMutation(block: suspend () -> Unit): Result<Unit> = runCatching {
        val epoch = authRepository.currentSessionEpoch()
        authRepository.withSessionWrite(epoch) {
            if (currentUserId() == null) throw AuthApiException.Api("unauthorized", "Not authenticated.")
            block()
        } ?: throw kotlinx.coroutines.CancellationException("Message session changed")
    }

    // --- Этап I: message lifecycle actions -----------------------------

    override suspend fun replyMessage(chatId: String, text: String, replyToClientMessageId: String): Result<Unit> = sessionMutation {
        val prepared = MessageTextPolicy.outgoing(text)
        val target = dao.findByClientMessageId(replyToClientMessageId) ?: error("Reply target not found locally.")
        val targetServerId = target.serverId ?: error("Cannot reply to a message that hasn't been sent yet.")
        val senderId = currentUserId() ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
        val snapshot = buildReplySnapshot(target)
        MessageTextPolicy.received(snapshot.preview)
        val clientMessageId = UUID.randomUUID().toString()
        dao.upsert(
            MessageEntity(
                clientMessageId = clientMessageId,
                serverId = null,
                chatId = chatId,
                senderId = senderId,
                text = prepared,
                sequence = null,
                createdAt = java.time.Instant.now().toString(),
                status = "pending",
                targetMessageId = targetServerId,
                replySnapshotJson = json.encodeToString(ReplySnapshotDto.serializer(), snapshot),
            ),
        )
        outbox.triggerFlush()
    }

    private suspend fun buildReplySnapshot(target: MessageEntity): ReplySnapshotDto {
        val attachment = attachmentDao.findByClientMessageId(target.clientMessageId)
        val (contentType, preview) = when {
            attachment == null -> ReplySnapshotDto.CONTENT_TYPE_TEXT to MessageTextPolicy.replyPreview(com.messenger.app.conference.ConferenceInvitation.safePreview(target.text))
            attachment.mediaType == AttachmentMetadataDto.MEDIA_TYPE_IMAGE -> ReplySnapshotDto.CONTENT_TYPE_IMAGE to "Photo"
            attachment.mediaType == AttachmentMetadataDto.MEDIA_TYPE_VOICE -> ReplySnapshotDto.CONTENT_TYPE_VOICE to "Voice message"
            else -> ReplySnapshotDto.CONTENT_TYPE_FILE to "File"
        }
        return ReplySnapshotDto(senderId = target.senderId, contentType = contentType, preview = preview, sequenceHint = target.sequence)
    }

    override suspend fun editMessage(clientMessageId: String, newText: String): Result<Unit> = sessionMutation {
        val target = dao.findByClientMessageId(clientMessageId) ?: error("Message not found")
        check(target.conferenceInviteJson == null) { "Invitation cannot be edited" }
        val prepared = MessageTextPolicy.outgoing(newText)
        require(target.kind == "content" && !target.isDeletedForEveryone && !target.hiddenLocally) {
            "Message cannot be edited"
        }
        if (target.serverId != null) {
            enqueueLifecycle(target, "edit", MessageContentCodec.encodeEdit(prepared))
            return@sessionMutation
        }
        // Editing media replacement is out of scope. Only plain text has a
        // safe pre-send representation.
        require(attachmentDao.findByClientMessageId(clientMessageId) == null) {
            "Attachments cannot be edited before sending"
        }
        val direct = dao.updateTextBeforeSend(clientMessageId, prepared)
        if (direct == 0) {
            // Legacy/migrated rows may already be in-flight without a frozen
            // payload. Freeze the CURRENT text before replacing the visible
            // text, so the original idempotency payload is not lost.
            if (target.payloadText == null) dao.markSendStarted(clientMessageId, target.text)
            val intended = dao.setPendingEdit(clientMessageId, prepared, target.pendingEditRevision + 1)
            check(intended > 0) { "Message cannot be edited" }
        }
    }

    override suspend fun deleteForEveryone(clientMessageId: String): Result<Unit> = sessionMutation {
        val target = dao.findByClientMessageId(clientMessageId) ?: error("Message not found")
        dao.clearPendingEdit(clientMessageId)
        if (target.serverId == null) { dao.hideLocally(clientMessageId); return@sessionMutation }
        enqueueLifecycle(target, "delete", MessageContentCodec.encodeDelete())
    }

    override suspend fun deleteForMe(clientMessageId: String) {
        val epoch = authRepository.currentSessionEpoch()
        authRepository.withSessionWrite(epoch) { dao.clearPendingEdit(clientMessageId) }
            ?: throw kotlinx.coroutines.CancellationException("Message session changed")
        // Joining workers must remain outside the message/workspace/media leases.
        // Explicit delivered-message deletion retains its existing hide semantics.
        outbox.cancelAndHide(clientMessageId, epoch)
        requireMessageSession(epoch)
        var chatId: String? = null
        authRepository.withSessionWrite(epoch) {
            chatId = dao.findByClientMessageId(clientMessageId)?.chatId
        } ?: throw kotlinx.coroutines.CancellationException("Message session changed")
        cleanupMessageMediaForSession(epoch, chatId, listOf(clientMessageId))
    }

    override suspend fun cancelUnsentMessages(chatId: String, clientMessageIds: List<String>): Result<Unit> = runCatching {
        val epoch = authRepository.currentSessionEpoch()
        val cancelled = outbox.cancelUnsentAndHide(chatId, clientMessageIds, epoch)
        requireMessageSession(epoch)
        cleanupMessageMediaForSession(epoch, chatId, cancelled)
    }

    private fun requireMessageSession(epoch: Int) {
        if (authRepository.currentSessionEpoch() != epoch) throw kotlinx.coroutines.CancellationException("Message session changed")
    }

    /** Reuse captured workspace -> media leases in logout's existing lock order.
     * Nested legacy helpers may capture a fresh epoch, but their reentrant
     * gates cannot adopt it while these original-session leases are held. */
    private suspend fun cleanupMessageMediaForSession(epoch: Int, chatId: String?, clientMessageIds: List<String>) {
        requireMessageSession(epoch)
        if (clientMessageIds.isEmpty()) return
        authRepository.withWorkspaceWrite(epoch) {
            authRepository.withMediaWrite(epoch) {
                requireMessageSession(epoch)
                for (id in clientMessageIds) {
                    mediaUploader?.discardPendingUpload(id)
                    requireMessageSession(epoch)
                    if (chatId != null) {
                        saveChatValue(chatId, "upload:$id", null)
                        requireMessageSession(epoch)
                        saveChatValue(chatId, "schedule:$id", null)
                        requireMessageSession(epoch)
                    }
                }
            } ?: throw kotlinx.coroutines.CancellationException("Message session changed")
        } ?: throw kotlinx.coroutines.CancellationException("Message session changed")
    }

    override suspend fun setReaction(clientMessageId: String, emoji: String): Result<Unit> = sessionMutation {
        val target = dao.findByClientMessageId(clientMessageId) ?: error("Message not found")
        enqueueLifecycle(target, "reaction", MessageContentCodec.encodeReaction(emoji))
    }

    override suspend fun removeReaction(clientMessageId: String): Result<Unit> = sessionMutation {
        val target = dao.findByClientMessageId(clientMessageId) ?: error("Message not found")
        enqueueLifecycle(target, "reaction", MessageContentCodec.encodeReactionRemoval())
    }

    /** Text only - see the interface doc comment for why attachment
     *  forwarding is a separate `AttachmentManager.forwardAttachment` +
     *  [sendAttachmentMessage] call instead. */
    override suspend fun forwardMessage(sourceClientMessageId: String, destinationChatId: String): Result<Unit> = sessionMutation {
        val source = dao.findByClientMessageId(sourceClientMessageId) ?: error("Message not found locally.")
        val senderId = currentUserId() ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
        val newClientMessageId = UUID.randomUUID().toString()
        dao.upsert(
            MessageEntity(
                clientMessageId = newClientMessageId,
                serverId = null,
                chatId = destinationChatId,
                senderId = senderId,
                text = if (source.conferenceInviteJson == null) MessageTextPolicy.outgoing(source.text) else source.text,
                conferenceInviteJson = source.conferenceInviteJson,
                sequence = null,
                createdAt = java.time.Instant.now().toString(),
                status = "pending",
                forwarded = true,
            ),
        )
        outbox.triggerFlush()
    }

    /** Same as forward but for Saved Messages: independent encrypted copy,
     *  NOT marked "Forwarded" (M.44/M.45). */
    override suspend fun saveMessage(sourceClientMessageId: String, savedChatId: String): Result<Unit> = sessionMutation {
        val source = dao.findByClientMessageId(sourceClientMessageId) ?: error("Message not found locally.")
        val senderId = currentUserId() ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
        val newClientMessageId = UUID.randomUUID().toString()
        dao.upsert(
            MessageEntity(
                clientMessageId = newClientMessageId,
                serverId = null,
                chatId = savedChatId,
                senderId = senderId,
                text = if (source.conferenceInviteJson == null) MessageTextPolicy.outgoing(source.text) else source.text,
                conferenceInviteJson = source.conferenceInviteJson,
                sequence = null,
                createdAt = java.time.Instant.now().toString(),
                status = "pending",
                forwarded = false,
            ),
        )
        outbox.triggerFlush()
    }

    private fun MessageEntity.toDomain(reactions: List<ReactionEntity> = emptyList(), myUserId: String? = null): Message {
        val displayText = if (text.trimStart().startsWith("{")) {
            val recovered = MessageContentCodec.decode(text.toByteArray(Charsets.UTF_8))
            if (recovered.kind == MessageContentDto.KIND_ATTACHMENT) {
                recovered.attachment?.let(::attachmentPlaceholder) ?: recovered.text.orEmpty()
            } else text
        } else text
        val replyPreview = replySnapshotJson?.let { raw ->
            runCatching { json.decodeFromString(ReplySnapshotDto.serializer(), raw) }.getOrNull()
        }?.let { snap -> ReplyPreview(targetServerId = targetMessageId.orEmpty(), senderId = snap.senderId, contentType = snap.contentType, preview = snap.preview) }
        val reactionSummaries = reactions.filter { MessageTextPolicy.isDisplayableReaction(it.emoji) }
            .groupBy { it.emoji }.mapNotNull { (emoji, rows) ->
            emoji ?: return@mapNotNull null
            ReactionSummary(emoji, rows.size, rows.any { it.userId == myUserId })
        }
        return Message(
            clientMessageId = clientMessageId,
            serverId = serverId,
            chatId = chatId,
            senderId = senderId,
            text = displayText,
            caption = caption,
            albumId = albumId,
            sequence = sequence,
            createdAt = createdAt,
            status = MessageStatus.valueOf(status.uppercase()),
            isEdited = isEdited,
            isDeletedForEveryone = isDeletedForEveryone,
            replyPreview = replyPreview,
            forwarded = forwarded,
            reactions = reactionSummaries,
            pendingReason = lastErrorClass.takeIf { status == "pending" },
            conferenceInvitation = if (!isDeletedForEveryone && !hiddenLocally) com.messenger.app.conference.ConferenceInvitation.decodeStored(conferenceInviteJson) else null,
        )
    }
}
