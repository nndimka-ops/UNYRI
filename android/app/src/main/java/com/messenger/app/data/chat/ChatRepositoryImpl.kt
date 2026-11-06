package com.messenger.app.data.chat

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.ChatSummaryDto
import com.messenger.app.data.api.ChatsApi
import com.messenger.app.data.local.chat.ChatDao
import com.messenger.app.data.local.chat.ChatEntity
import com.messenger.app.data.local.chat.ChatListItem
import com.messenger.app.data.local.chat.ChatParticipantEntity
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.ChatPreviewFormatter
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.ChatRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import com.messenger.app.data.local.chat.ChatWorkspaceValue
import com.messenger.app.data.api.ServerEndpoint

class ChatRepositoryImpl(
    private val api: ChatsApi,
    private val dao: ChatDao,
    private val authRepository: AuthRepository,
    private val deletedChatDao: com.messenger.app.data.local.chat.DeletedChatDao? = null,
    private val workspaceDao: com.messenger.app.data.local.chat.ChatWorkspaceDao? = null,
    private val pinPreferences: android.content.SharedPreferences? = null,
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
) : ChatRepository {
    @kotlinx.serialization.Serializable
    private data class Intent(val id: String = java.util.UUID.randomUUID().toString(), val enabled: Boolean, val until: String? = null, val origin: String? = null, val owner: String? = null)
    private val preferenceJson = kotlinx.serialization.json.Json
    private val replayMutex = kotlinx.coroutines.sync.Mutex()
    private val replayRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private var preferenceRevision = 0L
    private fun pinKey() = ServerEndpoint.current().trimEnd('/') + "|" + currentUserId().orEmpty()
    private fun cachedPins(): Set<String> = pinPreferences?.getStringSet(pinKey(), emptySet()).orEmpty().toSet()
    private suspend fun pending(chat: String, kind: String): Intent? = workspaceDao?.get(chat, "pending_preference:$kind")?.let { preferenceJson.decodeFromString(Intent.serializer(), it) }
    private suspend fun enqueue(chat: String, kind: String, intent: Intent) {
        check(currentUserId() != null && deletedChatDao?.contains(chat) != true)
        checkNotNull(workspaceDao).put(ChatWorkspaceValue(chat, "pending_preference:$kind", preferenceJson.encodeToString(Intent.serializer(), intent)))
        preferenceRevision++
        replayRequested.set(true)
    }
    private suspend fun projectPins(base: Set<String>): Set<String> {
        val result = base.toMutableSet()
        workspaceDao?.pendingPreferences().orEmpty().filter { it.name == "pending_preference:pin" }.forEach {
            if (preferenceJson.decodeFromString(Intent.serializer(), it.value).enabled) result.add(it.chatId) else result.remove(it.chatId)
        }
        return result
    }
    override suspend fun localPins(): Set<String> = authRepository.withSessionWrite(authRepository.currentSessionEpoch()) {
        projectPins(cachedPins())
    } ?: emptySet()
    override suspend fun setPins(ids: Set<String>, pinned: Boolean): Set<String> {
        val epoch = authRepository.currentSessionEpoch()
        val result = authRepository.withSessionWrite(epoch) {
            ids.forEach { enqueue(it, "pin", Intent(enabled = pinned)) }
            projectPins(cachedPins()).also { checkNotNull(pinPreferences).edit().putStringSet(pinKey(), it).commit() }
        } ?: error("Session changed")
        scope?.launch { replayPreferences() }
        return result
    }
    override suspend fun refreshPins(): Set<String> {
        val epoch = authRepository.currentSessionEpoch()
        val revision = preferenceRevision
        val remote = withAccessToken { api.pins(it) }.getOrThrow()
        return authRepository.withSessionWrite(epoch) {
            projectPins(if (revision == preferenceRevision) remote else cachedPins()).also {
                checkNotNull(pinPreferences).edit().putStringSet(pinKey(), it).commit()
            }
        } ?: error("Session changed")
    }

    private val archivesWorkspace = "__chat_archives__"
    private fun archivesKey() = "snapshot|" + pinKey()
    private suspend fun cachedArchives(key: String = archivesKey()): Set<String> = workspaceDao?.get(archivesWorkspace, key)
        ?.let { preferenceJson.decodeFromString<List<String>>(it).toSet() }.orEmpty()
    private fun archiveSnapshot(ids: Set<String>, key: String = archivesKey()) = ChatWorkspaceValue(archivesWorkspace, key, preferenceJson.encodeToString(ids.sorted()))
    private suspend fun projectArchives(base: Set<String>, origin: String = ServerEndpoint.current()): Set<String> {
        val result = base.toMutableSet()
        workspaceDao?.pendingPreferences().orEmpty().filter { it.name == "pending_preference:archive" }.forEach {
            val intent = preferenceJson.decodeFromString(Intent.serializer(), it.value)
            if (intent.origin == origin && intent.owner == currentUserId() && deletedChatDao?.contains(it.chatId) != true) {
                if (intent.enabled) result.add(it.chatId) else result.remove(it.chatId)
            }
        }
        return result
    }
    override suspend fun localArchives(): Set<String> {
        val epoch = authRepository.currentSessionEpoch()
        val origin = ServerEndpoint.current()
        val key = archivesKey()
        return authRepository.withSessionWrite(epoch) {
            check(origin == ServerEndpoint.current())
            projectArchives(cachedArchives(key), origin).also { check(origin == ServerEndpoint.current()) }
        } ?: emptySet()
    }
    override suspend fun setArchived(ids: Set<String>, archived: Boolean): Set<String> {
        require(ids.isNotEmpty() && ids.size <= 100)
        val epoch = authRepository.currentSessionEpoch()
        val origin = ServerEndpoint.current()
        val key = archivesKey()
        val result = authRepository.withSessionWrite(epoch) {
            check(origin == ServerEndpoint.current() && currentUserId() != null)
            // Validate the entire local batch first. Archive is never a reason
            // to retrieve a server conversation missing on this device.
            ids.forEach { check(dao.cachedChat(it)?.type == "direct" && deletedChatDao?.contains(it) != true) }
            val values = ids.sorted().map { id ->
                ChatWorkspaceValue(id, "pending_preference:archive", preferenceJson.encodeToString(Intent.serializer(), Intent(enabled = archived, origin = origin, owner = currentUserId())))
            }
            val projected = projectArchives(cachedArchives(key), origin).toMutableSet().apply {
                if (archived) addAll(ids) else removeAll(ids)
            }.toSet()
            check(origin == ServerEndpoint.current())
            checkNotNull(workspaceDao).queueArchives(values, archiveSnapshot(projected, key))
            preferenceRevision++
            replayRequested.set(true)
            projected
        } ?: error("Session changed")
        scope?.launch { replayPreferences() }
        return result
    }
    override suspend fun refreshArchives(): Set<String> {
        val epoch = authRepository.currentSessionEpoch()
        val origin = ServerEndpoint.current()
        val key = archivesKey()
        val revision = preferenceRevision
        val remote = withAccessToken { token ->
            check(origin == ServerEndpoint.current())
            api.archives(token)
        }.getOrThrow()
        return authRepository.withSessionWrite(epoch) {
            check(origin == ServerEndpoint.current())
            projectArchives(if (revision == preferenceRevision) remote else cachedArchives(key), origin).also {
                check(origin == ServerEndpoint.current())
                checkNotNull(workspaceDao).put(archiveSnapshot(it, key))
            }
        } ?: error("Session changed")
    }
    suspend fun replayPreferences() {
        replayRequested.set(true)
        if (!replayMutex.tryLock()) return
        try {
            val epoch = authRepository.currentSessionEpoch()
            val origin = ServerEndpoint.current()
            val archiveKey = archivesKey()
            if (currentUserId() == null) return
            do {
            replayRequested.set(false)
            val rows = authRepository.withSessionWrite(epoch) { workspaceDao?.pendingPreferences().orEmpty() } ?: return
            for (row in rows) {
                if (epoch != authRepository.currentSessionEpoch() || origin != ServerEndpoint.current()) return
                val intent = preferenceJson.decodeFromString(Intent.serializer(), row.value)
                if (row.name == "pending_preference:archive" && (intent.origin != origin || intent.owner != currentUserId())) continue
                val deleted = authRepository.withSessionWrite(epoch) { deletedChatDao?.contains(row.chatId) == true } ?: return
                var archiveGone = deleted
                if (!deleted) {
                    val sent = withAccessToken { token ->
                        check(epoch == authRepository.currentSessionEpoch() && origin == ServerEndpoint.current())
                        when (row.name) {
                            "pending_preference:pin" -> { api.setPins(token, setOf(row.chatId), intent.enabled); Unit }
                            "pending_preference:mute" -> if (intent.enabled) api.setMute(token, row.chatId, intent.until) else api.clearMute(token, row.chatId)
                            "pending_preference:archive" -> { api.setArchived(token, setOf(row.chatId), intent.enabled); Unit }
                        }
                    }
                    if (sent.isFailure) {
                        val error = sent.exceptionOrNull()
                        if (row.name == "pending_preference:archive" && error is AuthApiException.Api && error.code == "chat_not_found") archiveGone = true
                        else continue // Unsupported older routes and network errors retain the intent.
                    }
                }
                authRepository.withSessionWrite(epoch) {
                    if (origin == ServerEndpoint.current() && workspaceDao?.get(row.chatId, row.name) == row.value) {
                        if (row.name == "pending_preference:pin") checkNotNull(pinPreferences).edit().putStringSet(pinKey(), projectPins(cachedPins())).commit()
                        if (row.name == "pending_preference:archive") {
                            val archived = projectArchives(cachedArchives(archiveKey), origin).toMutableSet()
                            if (archiveGone) archived.remove(row.chatId)
                            check(origin == ServerEndpoint.current())
                            checkNotNull(workspaceDao).put(archiveSnapshot(archived, archiveKey))
                        }
                        workspaceDao?.remove(row.chatId, row.name)
                        preferenceRevision++
                    }
                }
            }
            } while (replayRequested.get())
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (_: Exception) { /* Durable intent survives network/storage failure. */ }
        finally {
            replayMutex.unlock()
            // A new intent may arrive between the final check and unlock.
            if (replayRequested.get() && currentUserId() != null) scope?.launch { replayPreferences() }
        }
    }
    private suspend fun queueMute(chatId: String, enabled: Boolean, until: String?): Result<Unit> = runCatching {
        val epoch = authRepository.currentSessionEpoch()
        authRepository.withSessionWrite(epoch) {
            checkNotNull(dao.cachedChat(chatId))
            check(currentUserId() != null && deletedChatDao?.contains(chatId) != true)
            val intent = Intent(enabled = enabled, until = until)
            checkNotNull(workspaceDao).queueMute(ChatWorkspaceValue(chatId, "pending_preference:mute", preferenceJson.encodeToString(Intent.serializer(), intent)), enabled, until)
            preferenceRevision++
            replayRequested.set(true)
            true
        } ?: error("Session changed")
        scope?.launch { replayPreferences() }
        Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val chats: Flow<List<ChatSummary>> =
        authRepository.authState.flatMapLatest { state ->
            val myUserId = (state as? AuthSessionState.LoggedIn)?.user?.id
            if (myUserId == null) flowOf(emptyList()) else authRepository.historyContext.flatMapLatest { context ->
                dao.observeChats(myUserId, (context?.beforeSequence ?: authRepository.historyBeforeSequence()).coerceAtLeast(0))
                    .map { items -> items.map { it.toDomain() } }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeChat(chatId: String): Flow<ChatSummary?> {
        val myUserId = currentUserId() ?: return flowOf(null)
        return authRepository.historyContext.flatMapLatest { context ->
            dao.observeChat(chatId, myUserId, context?.beforeSequence ?: authRepository.historyBeforeSequence()).map { it?.toDomain() }
        }
    }

    suspend fun projectCallActivity(calls: List<com.messenger.app.data.api.CallDto>, expectedEpoch: Int) {
        val owner = currentUserId() ?: return
        val epoch = authRepository.currentSessionEpoch()
        if (epoch != expectedEpoch) return
        val cutoffs = authRepository.withSessionWrite(epoch) {
            workspaceDao?.allValues().orEmpty().filter { it.name == RoomChatClearStore.CALLS_CUTOFF }
                .associate { it.chatId to java.time.Instant.parse(it.value).toEpochMilli() }
        } ?: return
        val eligible = com.messenger.app.data.call.CallActivityPolicy.applyClears(calls, cutoffs)
            .filter { com.messenger.app.data.call.CallActivityPolicy.eligible(it, owner) }
        val ids = eligible.mapNotNull { it.chatId }.distinct()
        val missing = authRepository.withSessionWrite(epoch) { ids.filter { dao.cachedChat(it) == null && deletedChatDao?.contains(it) != true } } ?: return
        if (missing.isNotEmpty()) refreshKnownChats(missing).getOrThrow()
        authRepository.withSessionWrite(epoch) {
            val rows = eligible.filter { deletedChatDao?.contains(it.chatId!!) != true && dao.cachedChat(it.chatId!!) != null }
                .groupBy { it.chatId!! }
            workspaceDao?.replaceCallActivity(rows.flatMap { (id, entries) -> listOf(
                com.messenger.app.data.local.chat.ChatWorkspaceValue(id, "call_activity_at", entries.maxBy { com.messenger.app.data.call.callTimestamp(it) }.createdAt),
                com.messenger.app.data.local.chat.ChatWorkspaceValue(id, "call_unread", entries.count { it.unread }.toString())) })
        }
    }

    override suspend fun peerOnline(chatId: String): Result<Boolean> = withAccessToken { token -> api.presence(token, chatId) }

    override suspend fun refreshChats(): Result<Unit> = withAccessToken { token ->
        val epoch = authRepository.currentSessionEpoch()
        val known = authRepository.withSessionWrite(epoch) {
            dao.locallyKnownChatIds(authRepository.historyBeforeSequence()).distinct().sorted()
        } ?: error("Session changed.")
        refreshKnown(token, epoch, known)
    }

    suspend fun refreshKnownChats(chatIds: List<String>): Result<Unit> = withAccessToken { token ->
        refreshKnown(token, authRepository.currentSessionEpoch(), chatIds.distinct().sorted())
    }

    private suspend fun refreshKnown(token: String, epoch: Int, known: List<String>) {
        for (batch in known.chunked(50)) {
            check(epoch == authRepository.currentSessionEpoch()) { "Session changed." }
            val revision = preferenceRevision
            val response = api.listKnownChats(token, batch)
            check(response.items.all { it.chatId in batch }) { "Unexpected chat in scoped response." }
            authRepository.withSessionWrite(epoch) { response.items.forEach { persist(it, revision != preferenceRevision) }; true }
                ?: error("Session changed.")
        }
    }

    override suspend fun createOrOpenDirectChat(otherUserId: String): Result<ChatSummary> = withAccessToken { token ->
        val epoch = authRepository.currentSessionEpoch()
        val dto = api.createDirect(token, otherUserId)
        authRepository.withSessionWrite(epoch) {
            persist(dto)
            workspaceDao?.put(com.messenger.app.data.local.chat.ChatWorkspaceValue(dto.chatId, "opened_on_device", "1"))
            true
        } ?: error("Session changed.")
        dto.toDomain()
    }

    override suspend fun getChat(chatId: String): Result<ChatSummary> = withAccessToken { token ->
        val epoch = authRepository.currentSessionEpoch()
        val revision = preferenceRevision
        val dto = api.getChat(token, chatId)
        authRepository.withSessionWrite(epoch) {
            persist(dto, revision != preferenceRevision)
            val cached = dao.cachedChat(chatId)
            dto.toDomain().copy(isMuted = cached?.isMuted ?: dto.isMuted, mutedUntil = cached?.mutedUntil,
                isBlocked = cached?.isBlocked ?: dto.isBlocked,
                blockedUserId = if (cached != null) cached.blockedUserId else dto.blockedUserId)
        } ?: error("Session changed")
    }

    override suspend fun getOrCreateSavedChat(): Result<ChatSummary> = runCatching {
        val epoch = authRepository.currentSessionEpoch()
        check(currentUserId() != null) { "Not authenticated." }
        // The server owns creation, but a known private chat needs no network
        // round-trip merely to open it or enqueue an offline message.
        val cached = authRepository.withSessionWrite(epoch) { dao.cachedSavedChat() }
        check(epoch == authRepository.currentSessionEpoch()) { "Session changed." }
        if (cached != null) {
            ChatSummary(cached.chatId, cached.type, null, cached.createdAt, cached.lastMessageAt,
                otherMemberDeliveredSequence = cached.peerDeliveredSequence,
                otherMemberReadSequence = cached.peerReadSequence)
        } else {
            withAccessToken { token ->
                check(epoch == authRepository.currentSessionEpoch()) { "Session changed." }
                val dto = api.getOrCreateSaved(token)
                authRepository.withSessionWrite(epoch) { persist(dto); dto.toDomain() }
                    ?: error("Session changed.")
            }.getOrThrow()
        }
    }

    override suspend fun setMute(chatId: String, mutedUntil: String?): Result<Unit> =
        queueMute(chatId, true, mutedUntil)

    override suspend fun clearMute(chatId: String): Result<Unit> =
        queueMute(chatId, false, null)

    override suspend fun blockUser(chatId: String, blockedUserId: String): Result<Unit> =
        changePreference(chatId, { api.blockUser(it, chatId, blockedUserId) }) {
            it.copy(isBlocked = true, blockedUserId = blockedUserId)
        }

    override suspend fun unblockUser(chatId: String): Result<Unit> =
        changePreference(chatId, { api.unblockUser(it, chatId) }) {
            it.copy(isBlocked = false, blockedUserId = null)
        }

    /** Projects an acknowledged account preference without creating/loading chats. */
    suspend fun updateKnownPeerBlock(userId: String, blocked: Boolean, epoch: Int): Boolean =
        authRepository.withSessionWrite(epoch) {
            preferenceRevision++
            dao.updateKnownPeerBlock(userId, blocked)
            true
        } ?: false

    private suspend fun changePreference(
        chatId: String,
        request: suspend (String) -> Unit,
        update: (ChatEntity) -> ChatEntity,
    ): Result<Unit> = withAccessToken { token ->
        val epoch = authRepository.currentSessionEpoch()
        request(token)
        // Apply only acknowledged changes, in the original account. Do not
        // resurrect deleted chats or replace read/delivery watermarks.
        authRepository.withSessionWrite(epoch) {
            preferenceRevision++
            if (deletedChatDao?.contains(chatId) != true) {
                dao.cachedChat(chatId)?.let { cached ->
                    val changed = update(cached)
                    dao.updatePreferences(chatId, changed.isMuted, changed.mutedUntil,
                        changed.isBlocked, changed.blockedUserId)
                }
            }
            true
        } ?: error("Session changed.")
        Unit
    }

    override suspend fun reportChat(
        chatId: String,
        reason: String,
        reportedUserId: String?,
        description: String?,
    ): Result<Unit> = withAccessToken { token ->
        api.reportChat(token, chatId, reason, reportedUserId, description)
        Unit
    }

    /** Marks this chat read up to [sequence] locally (unread-badge
     *  watermark only - the durable, multi-device-safe receipt is the
     *  server-side `POST /chats/{id}/read` call). Forward-only. */
    override suspend fun markLocallyRead(chatId: String, sequence: Long) {
        dao.updateLastReadSequence(chatId, sequence)
    }

    override suspend fun localReadWatermarks(): Map<String, Long> =
        dao.locallyReadChats().associate { it.chatId to it.lastReadSequence }

    override suspend fun recordPeerReceipt(chatId: String, delivered: Long, read: Long) = dao.recordPeerReceipt(chatId, delivered, read)
    override suspend fun peerReceipt(chatId: String): Pair<Long, Long> =
        dao.cachedChat(chatId)?.let { it.peerDeliveredSequence to it.peerReadSequence } ?: (0L to 0L)

    private fun currentUserId(): String? = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id

    private suspend fun persist(dto: ChatSummaryDto, preserveMute: Boolean = false) {
        if (deletedChatDao?.contains(dto.chatId) == true) return
        val newerPreferences = if (preserveMute) dao.cachedChat(dto.chatId) else null
        val mute = pending(dto.chatId, "mute") ?: newerPreferences?.let { Intent(enabled = it.isMuted, until = it.mutedUntil) }
        val chat = ChatEntity(chatId = dto.chatId, type = dto.type, createdAt = dto.createdAt, lastMessageAt = dto.lastMessageAt, isMuted = mute?.enabled ?: dto.isMuted, mutedUntil = if (mute != null) mute.until else dto.mutedUntil, isBlocked = newerPreferences?.isBlocked ?: dto.isBlocked, blockedUserId = if (newerPreferences != null) newerPreferences.blockedUserId else dto.blockedUserId)
        val otherUser = dto.otherUser?.let {
            ChatParticipantEntity(userId = it.id, username = it.username, displayName = it.displayName, avatarUrl = it.avatarUrl, avatarVersion = it.avatarVersion, hasAvatar = it.hasAvatar)
        }
        dao.upsertChatWithOtherUser(chat, otherUser)
        dao.recordPeerReceipt(dto.chatId, dto.otherMemberDeliveredSequence, dto.otherMemberReadSequence)
        if (dto.ownReadSequence > 0) dao.updateLastReadSequence(dto.chatId, dto.ownReadSequence)
    }

    private suspend fun <T> withAccessToken(block: suspend (String) -> T): Result<T> = runCatching {
        val epoch = authRepository.currentSessionEpoch()
        val token = authRepository.currentAccessToken() ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
        check(epoch == authRepository.currentSessionEpoch()) { "Session changed." }
        val result = block(token)
        check(epoch == authRepository.currentSessionEpoch()) { "Session changed." }
        result
    }

    private fun ChatSummaryDto.toDomain() = ChatSummary(
        chatId = chatId,
        type = type,
        otherUser = otherUser?.let {
            PublicUser(it.id, it.username, it.displayName, it.avatarUrl, bio = it.bio, profileVersion = it.profileVersion, avatarVersion = it.avatarVersion, hasAvatar = it.hasAvatar)
        },
        createdAt = createdAt,
        lastMessageAt = lastMessageAt,
        isMuted = isMuted,
        mutedUntil = mutedUntil,
        isBlocked = isBlocked,
        blockedUserId = blockedUserId,
        otherMemberDeliveredSequence = otherMemberDeliveredSequence,
        otherMemberReadSequence = otherMemberReadSequence,
    )

    private fun ChatListItem.toDomain() = ChatSummary(
        chatId = chatId,
        type = type,
        otherUser = otherUserId?.let { id ->
            PublicUser(id = id, username = otherUsername.orEmpty(), displayName = otherDisplayName.orEmpty(), avatarUrl = otherAvatarUrl, avatarVersion = otherAvatarVersion ?: 0, hasAvatar = otherHasAvatar ?: false)
        },
        createdAt = createdAt,
        lastMessageAt = lastMessageAt,
        isMuted = isMuted, mutedUntil = mutedUntil, isBlocked = isBlocked, blockedUserId = blockedUserId,
        unreadCount = unreadCount,
        lastPreview = ChatPreviewFormatter.from(lastPreviewKind, lastPreviewText, lastPreviewFilename),
    )
}

