package com.messenger.app.data.message

import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.repository.ChatRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * Regression for the Saved Messages send bug (M.1 live verification):
 * `resolveOtherUserIdForChat` used to `error("Direct chat has no other
 * participant.")` when a chat had no `otherUser` (Saved Messages), which
 * made every send into Saved Messages fail with status "failed" after
 * optimistic insert. It must return null instead so envelope building
 * skips the (nonexistent) other user and only produces the sender's own
 * multi-device envelopes.
 */
class MessageRepositoryRecipientResolutionTest {

    private class FakeChatRepository(
        private val chat: ChatSummary,
    ) : ChatRepository {
        override val chats: Flow<List<ChatSummary>> = flowOf(listOf(chat))
        override fun observeChat(chatId: String): Flow<ChatSummary?> =
            flowOf(if (chatId == chat.chatId) chat else null)
        override suspend fun refreshChats(): Result<Unit> = Result.success(Unit)
        override suspend fun createOrOpenDirectChat(otherUserId: String): Result<ChatSummary> =
            Result.success(chat)
        override suspend fun getChat(chatId: String): Result<ChatSummary> =
            if (chatId == chat.chatId) Result.success(chat) else Result.failure(IllegalArgumentException("no chat"))
        override suspend fun getOrCreateSavedChat(): Result<ChatSummary> = Result.success(chat)
        override suspend fun setMute(chatId: String, mutedUntil: String?): Result<Unit> = error("Moderation is outside this test")
        override suspend fun clearMute(chatId: String): Result<Unit> = error("Moderation is outside this test")
        override suspend fun blockUser(chatId: String, blockedUserId: String): Result<Unit> = error("Moderation is outside this test")
        override suspend fun unblockUser(chatId: String): Result<Unit> = error("Moderation is outside this test")
        override suspend fun reportChat(chatId: String, reason: String, reportedUserId: String?, description: String?): Result<Unit> = error("Moderation is outside this test")
        override suspend fun markLocallyRead(chatId: String, sequence: Long) = Unit
    }

    private fun savedChat() = ChatSummary(
        chatId = "saved-chat-1",
        type = "saved",
        otherUser = null,
        createdAt = "2026-09-06T00:00:00Z",
        lastMessageAt = null,
    )

    private fun directChat() = ChatSummary(
        chatId = "direct-chat-1",
        type = "direct",
        otherUser = PublicUser(id = "other-user-1", username = "bob", displayName = "Bob", avatarUrl = null),
        createdAt = "2026-09-06T00:00:00Z",
        lastMessageAt = null,
    )

    @Test
    fun `saved chat resolves to null other user instead of throwing`() = runBlocking {
        val repo = FakeChatRepository(savedChat())
        assertNull(resolveOtherUserIdForChat(repo, "saved-chat-1"))
    }

    @Test
    fun `direct chat resolves the other participant id`() = runBlocking {
        val repo = FakeChatRepository(directChat())
        assertEquals("other-user-1", resolveOtherUserIdForChat(repo, "direct-chat-1"))
    }

    @Test
    fun `saved chat resolved via getChat fallback also returns null`() = runBlocking {
        // observeChat returns null (cache miss), forcing the getChat fallback path.
        val repo = object : ChatRepository {
            override val chats: Flow<List<ChatSummary>> = flowOf(emptyList())
            override fun observeChat(chatId: String): Flow<ChatSummary?> = flowOf(null)
            override suspend fun refreshChats(): Result<Unit> = Result.success(Unit)
            override suspend fun createOrOpenDirectChat(otherUserId: String): Result<ChatSummary> =
                Result.success(savedChat())
            override suspend fun getChat(chatId: String): Result<ChatSummary> = Result.success(savedChat())
            override suspend fun getOrCreateSavedChat(): Result<ChatSummary> = Result.success(savedChat())
            override suspend fun setMute(chatId: String, mutedUntil: String?): Result<Unit> = error("Moderation is outside this test")
            override suspend fun clearMute(chatId: String): Result<Unit> = error("Moderation is outside this test")
            override suspend fun blockUser(chatId: String, blockedUserId: String): Result<Unit> = error("Moderation is outside this test")
            override suspend fun unblockUser(chatId: String): Result<Unit> = error("Moderation is outside this test")
            override suspend fun reportChat(chatId: String, reason: String, reportedUserId: String?, description: String?): Result<Unit> = error("Moderation is outside this test")
            override suspend fun markLocallyRead(chatId: String, sequence: Long) = Unit
        }
        assertNull(resolveOtherUserIdForChat(repo, "saved-chat-1"))
    }
}
