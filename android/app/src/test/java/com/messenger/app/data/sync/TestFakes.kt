package com.messenger.app.data.sync

import com.messenger.app.data.api.DeliveredReporter
import com.messenger.app.data.api.MessageDto
import com.messenger.app.data.api.ReadReceiptResponseDto
import com.messenger.app.data.api.SyncResponseDto
import com.messenger.app.data.api.SyncSource
import com.messenger.app.data.local.attachment.AttachmentDao
import com.messenger.app.data.local.attachment.AttachmentEntity
import com.messenger.app.data.local.chat.ChatDao
import com.messenger.app.data.local.chat.ChatEntity
import com.messenger.app.data.local.chat.ChatListItem
import com.messenger.app.data.local.chat.ChatMemberEntity
import com.messenger.app.data.local.chat.ChatParticipantEntity
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.local.message.ReactionDao
import com.messenger.app.data.local.message.ReactionEntity
import com.messenger.app.data.signal.MessageDecryptor
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.AuthUser
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** In-memory [AuthRepository] fake - real session-epoch/token/authState
 *  semantics without any Android/network dependency. */
class FakeAuthRepository(
    userId: String = "me",
    private var token: String? = "token",
) : AuthRepository {
    override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser(userId, "user", "User")))

    private var epoch = 1
    private var epochCallCount = 0

    /** Test hook: after the Nth call to [currentSessionEpoch], the epoch
     *  silently increments (simulating a login/logout racing with an
     *  in-flight, already-in-progress batch) - lets tests deterministically
     *  place the "session changed" moment between two specific items of a
     *  multi-message batch instead of a real concurrent race. */
    var switchEpochAfterCall: Int? = null

    override suspend fun register(username: String, password: String, displayName: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun login(username: String, password: String): Result<Unit> = Result.success(Unit)

    override suspend fun logout() {
        epoch++
        authState.value = AuthSessionState.LoggedOut
    }

    override suspend fun currentAccessToken(): String? = token

    override fun currentDeviceId(): String? = "device-1"

    override fun currentSessionEpoch(): Int {
        val result = epoch
        epochCallCount++
        if (switchEpochAfterCall == epochCallCount) epoch++
        return result
    }

    // Real SessionWriteGate semantics so tests exercise the deterministic
    // "stale write rejected before the DAO write" guarantee for real. The
    // epoch lambda goes through currentSessionEpoch() so the test-only
    // switchEpochAfterCall hook can fire mid-batch from inside the gate.
    private val writeGate = com.messenger.app.data.auth.SessionWriteGate { currentSessionEpoch() }

    /** Test hook: bump the epoch on the NEXT [withSessionWrite] call (before
     *  the gate checks), simulating a logout landing mid-operation. */
    var switchEpochOnWrite = false

    override suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T? {
        if (switchEpochOnWrite) { switchEpochOnWrite = false; epoch++ }
        return writeGate.write(epochAtStart, block)
    }

    override suspend fun clearRoom(block: suspend () -> Unit) = writeGate.clear(block)

    /** Test control: simulate a session switch happening mid-operation. */
    fun switchSession(newUserId: String = "someone-else") {
        epoch++
        authState.value = AuthSessionState.LoggedIn(AuthUser(newUserId, "user2", "User2"))
    }

    fun setToken(newToken: String?) {
        token = newToken
    }
}

/** In-memory [MessageDao] fake, keyed by clientMessageId exactly like the
 *  real Room table's primary key. */
class InMemoryMessageDao : MessageDao {
    override suspend fun historyArchivePage(after: Long, client: String) = rows.values.filter { it.kind == "content" && it.sequence != null && (it.sequence > after || (it.sequence == after && it.clientMessageId > client)) }.sortedWith(compareBy({ it.sequence }, { it.clientMessageId })).take(32)
    override suspend fun historyImportBlocked() = rows.values.any { !it.hiddenLocally && !it.isDeletedForEveryone && (it.status in setOf("pending","failed") || (it.kind != "content" && it.status !in setOf("sent","delivered","read"))) }
    override suspend fun historyRestoreBlocked() = rows.values.any { !it.hiddenLocally && !it.isDeletedForEveryone && it.kind != "content" && it.status !in setOf("sent","delivered","read") }
    override suspend fun historyCutoff(): Long? = mediaJobs["__server_history__" to "cleared_through_sequence"]?.toLong()
    override suspend fun clearHistoryReactions(before: Long) = Unit
    override suspend fun clearHistoryAttachments(before: Long) = Unit
    override suspend fun clearHistoryPreviews(before: Long) = Unit
    override suspend fun clearHistoryMessages(before: Long) {
        rows.entries.removeAll { it.value.sequence != null && it.value.sequence!! <= before }
    }

    override suspend fun nextScheduledAt(owner: String, now: Long): Long? = mediaJobs.filter { it.key.second.startsWith("schedule:") }.values.mapNotNull { it.toLongOrNull() }.filter { it > now }.minOrNull()
    val mediaJobs = mutableMapOf<Pair<String,String>,String>()
    override suspend fun composerValue(chat: String, name: String): String? = mediaJobs[chat to name]
    override suspend fun removeComposerValue(chat: String, name: String) { mediaJobs.remove(chat to name) }
    override suspend fun saveMediaJob(chatId: String, name: String, value: String) { mediaJobs[chatId to name] = value }
    override suspend fun incomingChatIds(ownerId: String): List<String> = rows.values.filter { it.senderId != ownerId && it.kind == "content" }.map { it.chatId }.distinct()
    override suspend fun receiptSequence(chatId: String, ownerId: String, upperBound: Long): Long {
        val content = rows.values.filter { it.chatId == chatId && it.kind == "content" && !it.hiddenLocally }
        return content.filter { it.senderId != ownerId && it.status in listOf("sent", "delivered", "read") }
            .mapNotNull { it.sequence }.filter { it <= upperBound }.maxOrNull() ?: 0
    }
    private val rows = LinkedHashMap<String, MessageEntity>()

    override suspend fun upsertSorted(message: MessageEntity) = upsert(message)
    override suspend fun upsert(message: MessageEntity) {
        rows[message.clientMessageId] = message
    }

    override fun observeFailedActions(chatId: String): Flow<List<MessageEntity>> = observeMessages(chatId).map { rows -> rows.filter { it.kind != "content" && it.status == "failed" && !it.hiddenLocally } }

    override fun observeMessages(chatId: String): Flow<List<MessageEntity>> =
        flowOf(rows.values.filter { it.chatId == chatId })

    override suspend fun findByClientMessageId(clientMessageId: String): MessageEntity? = rows[clientMessageId]

    override fun observeByClientMessageId(clientMessageId: String): Flow<MessageEntity?> = flowOf(rows[clientMessageId])

    override fun observeByServerId(serverId: String): Flow<MessageEntity?> = flowOf(rows.values.firstOrNull { it.serverId == serverId })

    override suspend fun findByServerId(serverId: String): MessageEntity? = rows.values.firstOrNull { it.serverId == serverId }

    override suspend fun findByChatSequence(chatId: String, sequence: Long): MessageEntity? = rows.values.firstOrNull { it.chatId == chatId && it.sequence == sequence }

    override suspend fun lifecycleEvents(serverId: String): List<MessageEntity> = rows.values.filter {
        it.targetMessageId == serverId && it.kind in listOf("edit", "delete", "reaction") && it.status in listOf("sent", "delivered", "read")
    }.sortedBy { it.sequence }

    override suspend fun updateStatus(clientMessageId: String, status: String) {
        rows[clientMessageId]?.let { rows[clientMessageId] = it.copy(status = status) }
    }

    override suspend fun markFailed(clientMessageId: String, lastErrorClass: String?) {
        rows[clientMessageId]?.takeIf { it.status == "pending" && !it.hiddenLocally && !it.isDeletedForEveryone }?.let {
            rows[clientMessageId] = it.copy(status = "failed", lastErrorClass = lastErrorClass)
        }
    }

    override suspend fun pendingEligible(myUserId: String, nowEpochMs: Long): List<MessageEntity> =
        rows.values.filter {
            it.status == "pending" && it.kind in setOf("content", "edit", "delete", "reaction") &&
                it.senderId == myUserId && !it.hiddenLocally
        }.sortedBy { it.createdAt }.let { pending ->
            val blocked = mutableSetOf<Pair<String, String?>>()
            val blockedMedia = mutableSetOf<String>()
            pending.filter { row ->
                val lane = row.chatId to if (row.kind == "reaction") row.targetMessageId.orEmpty() else null
                val media = (row.chatId to "upload:${row.clientMessageId}") in mediaJobs
                if (row.nextRetryAtEpochMs > nowEpochMs) {
                    if (media) blockedMedia.add(row.chatId) else blocked.add(lane)
                }
                row.nextRetryAtEpochMs <= nowEpochMs && lane !in blocked && (!media || row.chatId !in blockedMedia)
            }
        }

    override suspend fun markSendStarted(clientMessageId: String, fallbackText: String) {
        rows[clientMessageId]?.let {
            rows[clientMessageId] = it.copy(
                payloadText = it.payloadText ?: fallbackText,
                sendAttempted = true,
            )
        }
    }

    override suspend fun updateTextBeforeSend(clientMessageId: String, text: String): Int {
        val row = rows[clientMessageId] ?: return 0
        if (row.serverId != null || row.sendAttempted || row.status !in setOf("pending", "failed") ||
            row.kind != "content" || row.hiddenLocally) return 0
        rows[clientMessageId] = row.copy(text = text, payloadText = text, pendingEditText = null, pendingEditRevision = 0)
        return 1
    }

    override suspend fun setPendingEdit(clientMessageId: String, text: String, revision: Int): Int {
        val row = rows[clientMessageId] ?: return 0
        if (row.serverId != null || !row.sendAttempted || row.status !in setOf("pending", "failed") ||
            row.kind != "content" || row.hiddenLocally) return 0
        rows[clientMessageId] = row.copy(text = text, pendingEditText = text, pendingEditRevision = revision)
        return 1
    }

    override suspend fun clearPendingEdit(clientMessageId: String) {
        rows[clientMessageId]?.let { rows[clientMessageId] = it.copy(pendingEditText = null, pendingEditRevision = 0) }
    }

    override suspend fun commitSentAndPendingEdit(sent: MessageEntity, edit: MessageEntity?) {
        rows[sent.clientMessageId] = sent
        if (edit != null) {
            rows[edit.clientMessageId] = edit
            rows[sent.clientMessageId] = rows[sent.clientMessageId]!!.copy(pendingEditText = null, pendingEditRevision = 0)
        }
    }

    override suspend fun resetPendingRetryWindows(myUserId: String) {
        rows.values.filter { it.status == "pending" && it.kind == "content" && it.senderId == myUserId }.forEach {
            rows[it.clientMessageId] = it.copy(nextRetryAtEpochMs = 0)
        }
    }

    override suspend fun scheduleRetry(clientMessageId: String, retryCount: Int, nextRetryAtEpochMs: Long, lastErrorClass: String?) {
        rows[clientMessageId]?.let {
            rows[clientMessageId] = it.copy(retryCount = retryCount, nextRetryAtEpochMs = nextRetryAtEpochMs, lastErrorClass = lastErrorClass)
        }
    }

    override suspend fun resetToPending(clientMessageId: String) {
        rows[clientMessageId]?.let {
            rows[clientMessageId] = it.copy(status = "pending", retryCount = 0, nextRetryAtEpochMs = 0, lastErrorClass = null)
        }
    }

    override suspend fun applyEdit(targetServerId: String, newText: String, sequence: Long): Int {
        val row = rows.values.firstOrNull { it.serverId == targetServerId } ?: return 0
        if (sequence <= row.lastEditSequence || row.isDeletedForEveryone) return 0
        rows[row.clientMessageId] = row.copy(text = newText, isEdited = true, lastEditSequence = sequence)
        return 1
    }

    override suspend fun applyDelete(targetServerId: String, sequence: Long): Int {
        val row = rows.values.firstOrNull { it.serverId == targetServerId } ?: return 0
        if (sequence <= row.deleteSequence) return 0
        rows[row.clientMessageId] = row.copy(isDeletedForEveryone = true, deleteSequence = sequence)
        return 1
    }

    override suspend fun hideUnsent(clientMessageId: String, chatId: String, ownerId: String): Int {
        val row = rows[clientMessageId] ?: return 0
        if (row.chatId != chatId || row.senderId != ownerId || row.serverId != null ||
            row.status !in setOf("pending", "failed") || row.hiddenLocally || row.isDeletedForEveryone || row.kind != "content") return 0
        rows[clientMessageId] = row.copy(hiddenLocally = true, status = "failed", pendingEditText = null, pendingEditRevision = 0)
        return 1
    }

    override suspend fun hideLocally(clientMessageId: String) {
        rows[clientMessageId]?.let { rows[clientMessageId] = it.copy(hiddenLocally = true, status = if (it.serverId == null && it.status == "pending") "failed" else it.status) }
    }

    override suspend fun setOptimisticText(clientMessageId: String, newText: String) {
        rows[clientMessageId]?.let { rows[clientMessageId] = it.copy(text = newText, isEdited = true) }
    }

    override suspend fun setOptimisticDeleted(clientMessageId: String, deleted: Boolean) {
        rows[clientMessageId]?.let { rows[clientMessageId] = it.copy(isDeletedForEveryone = deleted) }
    }

    override suspend fun advanceStatusUpTo(chatId: String, myUserId: String, sequence: Long, status: String, fromStatuses: List<String>) {
        rows.values.filter {
            it.chatId == chatId && it.senderId == myUserId && (it.sequence ?: Long.MAX_VALUE) <= sequence && it.status in fromStatuses
        }.forEach { rows[it.clientMessageId] = it.copy(status = status) }
    }

    override suspend fun minSequence(chatId: String): Long? =
        rows.values.filter { it.chatId == chatId && it.sequence != null }.minOfOrNull { it.sequence!! }

    private val pendingDecryptRows = LinkedHashMap<String, com.messenger.app.data.local.message.PendingDecryptEntity>()

    /** Test hook: simulate a Room transaction failure when retaining a
     *  decrypt failure, so the cursor-advance invariant can be asserted. */
    var throwOnPendingInsert = false

    override suspend fun replacePendingDecrypt(pending: com.messenger.app.data.local.message.PendingDecryptEntity) {
        if (throwOnPendingInsert) error("simulated pending-decrypt insert failure")
        pendingDecryptRows[pending.clientMessageId] = pending
    }

    override suspend fun pendingDecryptRetryCount(clientMessageId: String): Int? = pendingDecryptRows[clientMessageId]?.retryCount

    private fun retryRows(sender: String?, sequence: Long?, client: String, through: Boolean, limit: Int): List<com.messenger.app.data.local.message.PendingDecryptEntity> =
        pendingDecryptRows.values.filter {
            (sender == null || it.senderId == sender) && it.lastErrorClass !in setOf("permanent_duplicate", "permanent_malformed_ciphertext", "permanent_unsupported_type", "DuplicateMessageException") &&
                (sequence == null || ((it.sequence > sequence || (it.sequence == sequence && it.clientMessageId > client)) != through))
        }.sortedWith(compareBy({ it.sequence }, { it.clientMessageId })).take(limit)
    override suspend fun automaticDecryptsFirst(limit: Int) = retryRows(null, null, "", false, limit)
    override suspend fun automaticDecryptsAfter(sequence: Long, client: String, limit: Int) = retryRows(null, sequence, client, false, limit)
    override suspend fun automaticDecryptsThrough(sequence: Long, client: String, limit: Int) = retryRows(null, sequence, client, true, limit)
    override suspend fun automaticSenderDecryptsFirst(senderId: String, limit: Int) = retryRows(senderId, null, "", false, limit)
    override suspend fun automaticSenderDecryptsAfter(senderId: String, sequence: Long, client: String, limit: Int) = retryRows(senderId, sequence, client, false, limit)
    override suspend fun automaticSenderDecryptsThrough(senderId: String, sequence: Long, client: String, limit: Int) = retryRows(senderId, sequence, client, true, limit)

    override suspend fun deletePendingDecrypt(clientMessageId: String) {
        pendingDecryptRows.remove(clientMessageId)
    }

    override suspend fun pendingDecryptsOrdered(): List<com.messenger.app.data.local.message.PendingDecryptEntity> =
        pendingDecryptRows.values.sortedWith(compareBy({ it.sequence }, { it.clientMessageId }))

    override suspend fun pendingDecryptsForSender(senderId: String): List<com.messenger.app.data.local.message.PendingDecryptEntity> =
        pendingDecryptRows.values.filter { it.senderId == senderId }.sortedWith(compareBy({ it.sequence }, { it.clientMessageId }))

    override suspend fun bumpPendingDecrypt(clientMessageId: String, retryCount: Int, lastErrorClass: String?) {
        pendingDecryptRows[clientMessageId]?.let {
            pendingDecryptRows[clientMessageId] = it.copy(retryCount = maxOf(it.retryCount, retryCount), lastErrorClass = lastErrorClass ?: it.lastErrorClass)
        }
    }

    fun pendingDecrypts(): List<com.messenger.app.data.local.message.PendingDecryptEntity> = pendingDecryptRows.values.toList()

    fun all(): List<MessageEntity> = rows.values.toList()

    /** Simulates `RoomDatabase.clearAllTables()` for race tests: every table
     *  (messages AND pending_decrypts) is wiped, exactly like a logout. */
    fun clear() {
        rows.clear()
        pendingDecryptRows.clear()
    }
}

/** In-memory [ChatDao] fake - only [touchLastMessageAt]/[updateLastReadSequence]
 *  are exercised by the sync layer's tests; everything else is a minimal
 *  correct-enough implementation. */
class InMemoryChatDao : ChatDao {
    override suspend fun boundPeer(chatId: String): String? = members.firstOrNull { it.chatId == chatId }?.userId
    override suspend fun chatsForPeer(peer: String): List<String> = members.filter { it.userId == peer }.map { it.chatId }
    var knownChatIds = emptyList<String>()
    override suspend fun locallyKnownChatIds(historyCutoff: Long): List<String> = knownChatIds
    override fun observeMissingChatIds(historyCutoff: Long): Flow<List<String>> = flowOf(emptyList())
    private val chats = LinkedHashMap<String, ChatEntity>()
    private val participants = LinkedHashMap<String, ChatParticipantEntity>()
    val deletedPeers = LinkedHashMap<String, String>()
    override suspend fun locallyReadChats(): List<ChatEntity> = chats.values.filter { it.lastReadSequence > 0 }
    override suspend fun cachedChat(chatId: String): ChatEntity? = chats[chatId]
    override suspend fun cachedParticipant(userId: String): ChatParticipantEntity? = participants[userId]
    override suspend fun eraseDeletedPeerProfile(userId: String, label: String) {
        participants[userId]?.let {
            participants[userId] = it.copy(username = "", displayName = label, avatarUrl = null,
                avatarVersion = 0, hasAvatar = false, publicUsername = null)
        }
    }
    override suspend fun rememberDeletedPeer(userId: String, label: String) { deletedPeers[userId] = label }
    override suspend fun cachedSavedChat(): ChatEntity? = chats.values.firstOrNull { it.type == "saved" }
    override suspend fun recordPeerReceipt(chatId: String, delivered: Long, read: Long) {
        chats[chatId]?.let { chats[chatId] = it.copy(peerDeliveredSequence = maxOf(it.peerDeliveredSequence, delivered), peerReadSequence = maxOf(it.peerReadSequence, read)) }
    }
    val touchedLastMessageAt = mutableListOf<Pair<String, String>>()

    override suspend fun upsertChatMetadata(chatId: String, type: String, createdAt: String, lastMessageAt: String?) {
        val existing = chats[chatId]
        chats[chatId] = ChatEntity(chatId, type, createdAt, listOfNotNull(existing?.lastMessageAt, lastMessageAt).maxOrNull(), existing?.lastReadSequence ?: 0, existing?.peerDeliveredSequence ?: 0, existing?.peerReadSequence ?: 0)
    }

    override suspend fun updatePreferences(id: String, muted: Boolean, until: String?, blocked: Boolean, peer: String?) {
        chats[id]?.let { chats[id] = it.copy(isMuted = muted, mutedUntil = until, isBlocked = blocked, blockedUserId = peer) }
    }
    override suspend fun updateKnownPeerBlock(peer: String, blocked: Boolean) {
        members.filter { it.userId == peer }.forEach { member ->
            chats[member.chatId]?.takeIf { it.type == "direct" }?.let {
                chats[member.chatId] = it.copy(isBlocked = blocked, blockedUserId = if (blocked) peer else null)
            }
        }
    }
    private val members = mutableSetOf<ChatMemberEntity>()
    override suspend fun upsertMember(member: ChatMemberEntity) { members.removeAll { it.chatId == member.chatId && it.userId == member.userId }; members += member }

    override suspend fun upsertParticipant(participant: ChatParticipantEntity) { participants[participant.userId] = participant }

    override suspend fun updateLastReadSequence(chatId: String, sequence: Long) {
        chats[chatId]?.let { chats[chatId] = it.copy(lastReadSequence = maxOf(it.lastReadSequence, sequence)) }
    }

    override suspend fun touchLastMessageAt(chatId: String, createdAt: String) {
        touchedLastMessageAt += chatId to createdAt
        val existing = chats[chatId] ?: return
        if (existing.lastMessageAt == null || existing.lastMessageAt < createdAt) {
            chats[chatId] = existing.copy(lastMessageAt = createdAt)
        }
    }

    override fun observeChats(myUserId: String, historyCutoff: Long): Flow<List<ChatListItem>> = flowOf(emptyList())

    override fun observeChat(chatId: String, myUserId: String, historyCutoff: Long): Flow<ChatListItem?> = flowOf(null)
}

/** In-memory [AttachmentDao] fake. */
class InMemoryAttachmentDao : AttachmentDao {
    private val rows = LinkedHashMap<String, AttachmentEntity>()
    private val cacheState = MutableStateFlow<List<com.messenger.app.data.local.attachment.CacheAttachmentState>>(emptyList())
    private fun cacheChanged() {
        cacheState.value = rows.values.filter { it.localPath != null }.sortedBy { it.attachmentId }.map {
            com.messenger.app.data.local.attachment.CacheAttachmentState(it.attachmentId, it.chatId, requireNotNull(it.localPath), it.uploadState, it.downloadState)
        }
    }
    override fun observeCacheState() = cacheState

    override suspend fun upsert(attachment: AttachmentEntity) {
        rows[attachment.attachmentId] = attachment
        cacheChanged()
    }

    override suspend fun findByAttachmentId(attachmentId: String): AttachmentEntity? = rows[attachmentId]

    override suspend fun findByClientMessageId(clientMessageId: String): AttachmentEntity? =
        rows.values.firstOrNull { it.clientMessageId == clientMessageId }

    override fun observeForChat(chatId: String): Flow<List<AttachmentEntity>> =
        flowOf(rows.values.filter { it.chatId == chatId })

    override suspend fun allWithLocalPath(): List<AttachmentEntity> =
        rows.values.filter { it.localPath != null }

    override suspend fun updateUploadState(attachmentId: String, state: String, progress: Int) {
        rows[attachmentId]?.let { rows[attachmentId] = it.copy(uploadState = state, progress = progress) }
        cacheChanged()
    }

    override suspend fun markUploadSent(attachmentId: String, clientMessageId: String, chatId: String): Int {
        val row = rows[attachmentId] ?: return 0
        if (row.clientMessageId != clientMessageId || row.chatId != chatId ||
            row.uploadState.lowercase() in setOf("sent", "cancelled")) return 0
        rows[attachmentId] = row.copy(uploadState = "sent")
        cacheChanged()
        return 1
    }

    override suspend fun updateDownloadState(attachmentId: String, state: String, progress: Int) {
        rows[attachmentId]?.let { rows[attachmentId] = it.copy(downloadState = state, progress = progress) }
        cacheChanged()
    }

    override suspend fun markDownloaded(attachmentId: String, state: String, localPath: String) {
        rows[attachmentId]?.let { rows[attachmentId] = it.copy(downloadState = state, localPath = localPath, progress = 100) }
        cacheChanged()
    }

    override suspend fun recoverInterruptedDownloads(): Int {
        val interrupted = rows.values.filter { it.downloadState == "downloading" }
        interrupted.forEach { rows[it.attachmentId] = it.copy(downloadState = "not_downloaded", progress = 0) }
        cacheChanged()
        return interrupted.size
    }
    override suspend fun finishInterruptedDownload(attachmentId: String, clientMessageId: String, chatId: String, state: String): Int {
        val row = rows[attachmentId] ?: return 0
        if (row.clientMessageId != clientMessageId || row.chatId != chatId || row.downloadState != "downloading") return 0
        rows[attachmentId] = row.copy(downloadState = state, progress = 0)
        cacheChanged()
        return 1
    }
    override suspend fun clearLocalCache(attachmentId: String) {
        rows[attachmentId]?.let { rows[attachmentId] = it.copy(downloadState = "not_downloaded", localPath = null, progress = 0) }
        cacheChanged()
    }

    fun all(): List<AttachmentEntity> = rows.values.toList()
}

/** In-memory [ReactionDao] fake. */
class InMemoryReactionDao : ReactionDao {
    override suspend fun historyArchiveRows() = rows.values.sortedWith(compareBy({ it.targetMessageId }, { it.userId })).take(50001)
    private val rows = LinkedHashMap<Pair<String, String>, ReactionEntity>()

    override suspend fun upsert(reaction: ReactionEntity) {
        rows[reaction.targetMessageId to reaction.userId] = reaction
    }

    override suspend fun currentSequence(targetMessageId: String, userId: String): Long? = rows[targetMessageId to userId]?.lastReactionSequence

    override suspend fun find(targetMessageId: String, userId: String): ReactionEntity? = rows[targetMessageId to userId]

    override fun observeActiveForMessage(targetMessageId: String): Flow<List<ReactionEntity>> =
        flowOf(rows.values.filter { it.targetMessageId == targetMessageId && it.emoji != null })

    override fun observeActiveForMessages(targetMessageIds: List<String>): Flow<List<ReactionEntity>> =
        flowOf(rows.values.filter { it.targetMessageId in targetMessageIds && it.emoji != null })

    override fun observeActiveForChat(chatId: String): Flow<List<ReactionEntity>> = flowOf(rows.values.filter { it.emoji != null })

    override suspend fun deleteAllForMessage(targetMessageId: String) {
        rows.keys.filter { it.first == targetMessageId }.forEach { rows.remove(it) }
    }

    fun all(): List<ReactionEntity> = rows.values.toList()
}

/** Records every delivered-watermark report, per (chatId, sequence) - lets
 *  tests assert it's called once per chat with the MAX sequence in a batch,
 *  never once per message. */
class FakeDeliveredReporter : DeliveredReporter {
    data class Call(val accessToken: String, val chatId: String, val sequence: Long)

    val calls = mutableListOf<Call>()
    var failFor: String? = null

    override suspend fun markDelivered(accessToken: String, chatId: String, sequence: Long): ReadReceiptResponseDto {
        if (chatId == failFor) error("simulated network failure")
        calls += Call(accessToken, chatId, sequence)
        return ReadReceiptResponseDto(sequence)
    }
}

/** Fake decryptor - by default "decrypts" to a deterministic plaintext
 *  derived from the message id, tracks how many times [decrypt] was
 *  actually invoked per message (to assert idempotency/no-double-decrypt),
 *  and can be told to fail for specific message ids. */
class FakeMessageDecryptor : MessageDecryptor {
    val decryptCallCounts = mutableMapOf<String, Int>()
    val failingMessageIds = mutableSetOf<String>()
    val identityChangedMessageIds = mutableSetOf<String>()
    val decryptOrder = mutableListOf<String>()

    /** Per-message-id override - lets a test control exactly what
     *  "decrypted" bytes a given message resolves to (e.g. real
     *  [com.messenger.app.data.attachment.MessageContentCodec]-encoded
     *  edit/reaction/delete content), instead of the default
     *  `"decoded:<id>"` placeholder. */
    val customResponses = mutableMapOf<String, ByteArray>()

    override suspend fun resolveSignalDeviceId(userId: String, deviceId: String, accessToken: String): Int = 1

    override suspend fun decrypt(userId: String, signalDeviceId: Int, messageType: Int, ciphertextBase64: String): ByteArray {
        // ciphertextBase64 here is just "plaintext-for:<messageId>" in tests
        // (no real base64/libsignal involved - see SignalCryptoTest.kt for
        // real crypto coverage), so we can recover the message id from it.
        val messageId = ciphertextBase64.removePrefix("plaintext-for:")
        decryptOrder += messageId
        decryptCallCounts[messageId] = (decryptCallCounts[messageId] ?: 0) + 1
        if (messageId in identityChangedMessageIds) throw com.messenger.app.data.signal.IdentityChangedException(messageId, listOf(signalDeviceId))
        if (messageId in failingMessageIds) throw IllegalStateException("simulated decrypt failure for $messageId")
        return customResponses[messageId] ?: "decoded:$messageId".toByteArray(Charsets.UTF_8)
    }
}

fun testMessageDto(
    id: String,
    chatId: String = "chat-1",
    senderId: String = "other",
    senderDeviceId: String = "other-device",
    clientMessageId: String = id,
    sequence: Long,
    createdAt: String = "2026-01-01T00:00:00Z",
    messageType: Int = 2,
    kind: String = "content",
    targetMessageId: String? = null,
) = MessageDto(
    id = id,
    chatId = chatId,
    clientMessageId = clientMessageId,
    senderId = senderId,
    senderDeviceId = senderDeviceId,
    sequence = sequence,
    createdAt = createdAt,
    messageType = messageType,
    ciphertext = "plaintext-for:$id",
    kind = kind,
    targetMessageId = targetMessageId,
)

/** Queue-driven [SyncSource] fake - each call to [sync] pops the next
 *  configured response (by afterSequence), letting tests script multi-page
 *  pagination precisely. */
class FakeSyncSource : SyncSource {
    val calls = mutableListOf<Pair<Long, Int>>()
    var responder: suspend (afterSequence: Long, limit: Int) -> SyncResponseDto = { after, _ -> SyncResponseDto(emptyList(), after, false) }
    var thrower: (() -> Nothing)? = null

    override suspend fun sync(accessToken: String, afterSequence: Long, limit: Int): SyncResponseDto {
        calls += afterSequence to limit
        thrower?.invoke()
        return responder(afterSequence, limit)
    }
}

/** Records every batch handed to it and returns a scripted result per call -
 *  lets [GlobalSyncManager] tests assert pagination/abort behavior without
 *  needing a real ingestion pipeline. */
class FakeMessageIngestor : MessageIngestor {
    val ingestedBatches = mutableListOf<List<MessageDto>>()
    var results = ArrayDeque<MessageIngestor.IngestResult>()
    var defaultResult: (List<MessageDto>) -> MessageIngestor.IngestResult = { dtos ->
        MessageIngestor.IngestResult(true, dtos.maxOfOrNull { it.sequence } ?: 0)
    }

    override suspend fun ingest(dtos: List<MessageDto>, advanceCursor: Boolean): MessageIngestor.IngestResult {
        ingestedBatches += dtos
        return if (results.isNotEmpty()) results.removeFirst() else defaultResult(dtos)
    }
}

class InMemorySyncCursorStore(initial: Long = 0L) : SyncCursorStore {
    private var value = initial
    override fun lastSyncedSequence(): Long = value
    override fun saveLastSyncedSequence(sequence: Long) {
        value = sequence
    }
}
