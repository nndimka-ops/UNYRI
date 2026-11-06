package com.messenger.app.domain.repository

import com.messenger.app.domain.model.ChatSummary
import kotlinx.coroutines.flow.Flow

/**
 * Local-first: [chats] and [observeChat] read from the local cache (Room),
 * which [refreshChats]/[createOrOpenDirectChat]/[getChat] populate from the
 * server. Never loads message history - chat metadata only.
 */
interface ChatRepository {
    val chats: Flow<List<ChatSummary>>

    fun observeChat(chatId: String): Flow<ChatSummary?>

    suspend fun refreshChats(): Result<Unit>

    suspend fun createOrOpenDirectChat(otherUserId: String): Result<ChatSummary>

    suspend fun peerOnline(chatId: String): Result<Boolean> = Result.success(false)

    suspend fun getChat(chatId: String): Result<ChatSummary>

    /** Idempotent get-or-create of the owner's private Saved Messages chat. */
    suspend fun getOrCreateSavedChat(): Result<ChatSummary>

    suspend fun setMute(chatId: String, mutedUntil: String? = null): Result<Unit>
    suspend fun clearMute(chatId: String): Result<Unit>
    suspend fun setPins(ids: Set<String>, pinned: Boolean): Set<String> = error("Unsupported")
    suspend fun refreshPins(): Set<String> = emptySet()
    suspend fun localPins(): Set<String> = emptySet()
    /** Personal presentation state only; IDs must never hydrate missing history. */
    suspend fun setArchived(ids: Set<String>, archived: Boolean): Set<String> = error("Unsupported")
    suspend fun refreshArchives(): Set<String> = emptySet()
    suspend fun localArchives(): Set<String> = emptySet()
    suspend fun blockUser(chatId: String, blockedUserId: String): Result<Unit>
    suspend fun unblockUser(chatId: String): Result<Unit>
    suspend fun reportChat(chatId: String, reason: String, reportedUserId: String? = null, description: String? = null): Result<Unit>

    /** Advances the local-only unread-badge watermark (forward-only) - see
     *  ChatDao.updateLastReadSequence. Distinct from the durable,
     *  multi-device-safe receipt sent via `MessageRepository.markRead`. */
    suspend fun markLocallyRead(chatId: String, sequence: Long)

    suspend fun localReadWatermarks(): Map<String, Long> = emptyMap()
    suspend fun recordPeerReceipt(chatId: String, delivered: Long, read: Long) = Unit
    suspend fun peerReceipt(chatId: String): Pair<Long, Long> = 0L to 0L
}
