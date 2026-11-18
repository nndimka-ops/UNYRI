package com.messenger.app.data.local.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("""
        SELECT DISTINCT chatId FROM messages WHERE kind='content' AND hiddenLocally=0
            AND (sequence IS NULL OR sequence>:historyCutoff OR status NOT IN ('decrypt_failed','identity_changed'))
        UNION SELECT chatId FROM chat_workspace WHERE name='opened_on_device' AND value='1'
        UNION SELECT chatId FROM chat_workspace WHERE name='call_activity_at'
        UNION SELECT chatId FROM chats WHERE type='saved'
    """)
    suspend fun locallyKnownChatIds(historyCutoff: Long): List<String>

    @Query("""
        SELECT DISTINCT m.chatId FROM messages m LEFT JOIN chats c ON c.chatId=m.chatId
        WHERE c.chatId IS NULL AND m.kind='content' AND m.hiddenLocally=0
          AND (m.sequence IS NULL OR m.sequence>:historyCutoff OR m.status NOT IN ('decrypt_failed','identity_changed'))
          AND NOT EXISTS(SELECT 1 FROM deleted_chats d WHERE d.chatId=m.chatId)
    """)
    fun observeMissingChatIds(historyCutoff: Long): Flow<List<String>>

    @Query("SELECT * FROM chats WHERE lastReadSequence > 0")
    suspend fun locallyReadChats(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE chatId = :chatId")
    suspend fun cachedChat(chatId: String): ChatEntity?
    @Query("SELECT * FROM chat_participants WHERE userId = :userId")
    suspend fun cachedParticipant(userId: String): ChatParticipantEntity?
    @Query("SELECT userId FROM chat_members WHERE chatId = :chatId LIMIT 1")
    suspend fun boundPeer(chatId: String): String?
    @Query("SELECT chatId FROM chat_members WHERE userId=:peer")
    suspend fun chatsForPeer(peer: String): List<String>

    @Query("SELECT * FROM chats WHERE type = 'saved' LIMIT 1")
    suspend fun cachedSavedChat(): ChatEntity?

    @Query("UPDATE chats SET peerDeliveredSequence = MAX(peerDeliveredSequence, :delivered), peerReadSequence = MAX(peerReadSequence, :read) WHERE chatId = :chatId")
    suspend fun recordPeerReceipt(chatId: String, delivered: Long, read: Long)

    /** Upserts chat metadata WITHOUT touching `lastReadSequence` - that's a
     *  purely local watermark this device advances itself (see
     *  [updateLastReadSequence]); re-syncing chat metadata from the server
     *  must never reset it back to 0. */
    @Query(
        """
        INSERT INTO chats (chatId, type, createdAt, lastMessageAt, lastReadSequence)
        VALUES (:chatId, :type, :createdAt, :lastMessageAt, 0)
        ON CONFLICT(chatId) DO UPDATE SET type = :type, createdAt = :createdAt,
            lastMessageAt = CASE WHEN chats.lastMessageAt IS NULL THEN :lastMessageAt
                WHEN :lastMessageAt IS NULL THEN chats.lastMessageAt
                WHEN julianday(:lastMessageAt) > julianday(chats.lastMessageAt) THEN :lastMessageAt ELSE chats.lastMessageAt END
        """
    )
    suspend fun upsertChatMetadata(chatId: String, type: String, createdAt: String, lastMessageAt: String?)

    @Query("UPDATE chats SET isMuted=:muted, mutedUntil=:until, isBlocked=:blocked, blockedUserId=:peer WHERE chatId=:id")
    suspend fun updatePreferences(id: String, muted: Boolean, until: String?, blocked: Boolean, peer: String?)

    /** Update known direct chats only: account block preferences never discover history. */
    @Query("UPDATE chats SET isBlocked=:blocked, blockedUserId=CASE WHEN :blocked THEN :peer ELSE NULL END WHERE type='direct' AND chatId IN (SELECT chatId FROM chat_members WHERE userId=:peer)")
    suspend fun updateKnownPeerBlock(peer: String, blocked: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: ChatMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParticipant(participant: ChatParticipantEntity)

    /** Forward-only, mirrors the server's watermark semantics: never lets
     *  the local read cursor move backwards. */
    @Query("UPDATE chats SET lastReadSequence = MAX(lastReadSequence, :sequence) WHERE chatId = :chatId")
    suspend fun updateLastReadSequence(chatId: String, sequence: Long)

    /** Bumps `lastMessageAt` for a chat when a message is ingested via
     *  WS/sync (rather than a full HTTP chat refresh) - keeps the chat
     *  list's ordering/preview fresh without a network round-trip. Only
     *  moves forward (a message ingested out of order must never regress
     *  the displayed "last activity" time). */
    @Query("UPDATE chats SET lastMessageAt = :createdAt WHERE chatId = :chatId AND (lastMessageAt IS NULL OR julianday(lastMessageAt) < julianday(:createdAt))")
    suspend fun touchLastMessageAt(chatId: String, createdAt: String)

    @Transaction
    suspend fun upsertChatWithOtherUser(chat: ChatEntity, otherUser: ChatParticipantEntity?) {
        upsertChatMetadata(chat.chatId, chat.type, chat.createdAt, chat.lastMessageAt)
        updatePreferences(chat.chatId, chat.isMuted, chat.mutedUntil, chat.isBlocked, chat.blockedUserId)
        if (otherUser != null) {
            upsertParticipant(otherUser)
            upsertMember(ChatMemberEntity(chatId = chat.chatId, userId = otherUser.userId, role = "member"))
        }
    }

    /** Local previews compare creation instants before server sequences: an
     *  old unacknowledged send must not mask a newer delivered message.
     *  Ordering mirrors the server's `GET /chats`: last_message_at DESC
     *  (NULLs last), chat_id DESC as a stable tie-breaker. `unreadCount` is
     *  a plain indexed count scoped to this one chat (never a full-table
     *  scan across all chats) of incoming (not-own) messages with an
     *  assigned sequence beyond the local read watermark. */
    @Query(ChatListSql.LIST)
    fun observeChats(myUserId: String, historyCutoff: Long = -1): Flow<List<ChatListItem>>

    @Query(ChatListSql.CHAT)
    fun observeChat(chatId: String, myUserId: String, historyCutoff: Long = 0): Flow<ChatListItem?>
}
