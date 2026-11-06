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

    /** Ordering mirrors the server's `GET /chats`: last_message_at DESC
     *  (NULLs last), chat_id DESC as a stable tie-breaker. `unreadCount` is
     *  a plain indexed count scoped to this one chat (never a full-table
     *  scan across all chats) of incoming (not-own) messages with an
     *  assigned sequence beyond the local read watermark. */
    @Query(
        """
        SELECT c.chatId AS chatId, c.type AS type, c.createdAt AS createdAt, c.activityAt AS lastMessageAt,
               c.isMuted, c.mutedUntil, c.isBlocked, c.blockedUserId, p.userId AS otherUserId, p.username AS otherUsername,
               p.displayName AS otherDisplayName, p.avatarUrl AS otherAvatarUrl,
               p.avatarVersion AS otherAvatarVersion, p.hasAvatar AS otherHasAvatar,
               (SELECT COUNT(*) FROM messages m WHERE m.chatId = c.chatId AND m.senderId != :myUserId
                    AND m.kind = 'content' AND m.hiddenLocally = 0 AND m.isDeletedForEveryone = 0
                    AND m.sequence IS NOT NULL AND m.sequence > c.lastReadSequence
                    AND (m.sequence > :historyCutoff OR m.status NOT IN ('decrypt_failed','identity_changed'))) + COALESCE((SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chatId=c.chatId AND name='call_unread'),0) AS unreadCount,
               (SELECT CASE WHEN lm.isDeletedForEveryone = 1 THEN 'deleted'
                            ELSE COALESCE((SELECT CASE WHEN LOWER(a.mimeType) LIKE 'video/%' THEN 'video' ELSE a.mediaType END FROM attachments a WHERE a.clientMessageId = lm.clientMessageId), 'text')
                        END
                FROM messages lm
                WHERE lm.chatId = c.chatId AND lm.kind = 'content' AND lm.hiddenLocally = 0 AND lm.isDeletedForEveryone = 0
                  AND lm.status <> 'decrypt_failed' AND lm.status <> 'identity_changed'
                ORDER BY COALESCE(lm.sequence, 9223372036854775807) DESC, lm.createdAt DESC
                LIMIT 1) AS lastPreviewKind,
               (SELECT lm.text
                FROM messages lm
                WHERE lm.chatId = c.chatId AND lm.kind = 'content' AND lm.hiddenLocally = 0 AND lm.isDeletedForEveryone = 0
                  AND lm.status <> 'decrypt_failed' AND lm.status <> 'identity_changed'
                ORDER BY COALESCE(lm.sequence, 9223372036854775807) DESC, lm.createdAt DESC
                LIMIT 1) AS lastPreviewText,
               (SELECT a.filename
                FROM messages lm
                LEFT JOIN attachments a ON a.clientMessageId = lm.clientMessageId
                WHERE lm.chatId = c.chatId AND lm.kind = 'content' AND lm.hiddenLocally = 0 AND lm.isDeletedForEveryone = 0
                  AND lm.status <> 'decrypt_failed' AND lm.status <> 'identity_changed'
                ORDER BY COALESCE(lm.sequence, 9223372036854775807) DESC, lm.createdAt DESC
                LIMIT 1) AS lastPreviewFilename
        FROM (
            SELECT chats.*, CASE
                WHEN COALESCE((SELECT MAX(julianday(m.createdAt)) FROM messages m
                    WHERE m.chatId = chats.chatId AND m.kind = 'content' AND m.hiddenLocally = 0), -1)
                    > COALESCE(julianday(chats.lastMessageAt), -1)
                THEN (SELECT m.createdAt FROM messages m WHERE m.chatId = chats.chatId
                    AND m.kind = 'content' AND m.hiddenLocally = 0
                    ORDER BY julianday(m.createdAt) DESC, m.clientMessageId DESC LIMIT 1)
                ELSE chats.lastMessageAt END AS activityAt
            FROM chats
        ) c
        LEFT JOIN chat_members cm ON cm.chatId = c.chatId
        LEFT JOIN chat_participants p ON p.userId = cm.userId
        WHERE (EXISTS (SELECT 1 FROM chat_workspace w WHERE w.chatId=c.chatId AND w.name='call_activity_at') OR NOT EXISTS (SELECT 1 FROM chat_workspace w WHERE w.chatId=c.chatId AND w.name='personal_clear')
           OR EXISTS (SELECT 1 FROM messages m WHERE m.chatId=c.chatId AND m.kind='content' AND m.hiddenLocally=0 AND (m.sequence IS NULL OR m.sequence>CAST((SELECT value FROM chat_workspace WHERE chatId=c.chatId AND name='personal_clear') AS INTEGER))))
          AND (:historyCutoff < 0 OR c.type='saved' OR EXISTS (SELECT 1 FROM chat_workspace w WHERE w.chatId=c.chatId AND w.name='call_activity_at')
               OR EXISTS (SELECT 1 FROM chat_workspace w WHERE w.chatId=c.chatId AND w.name='opened_on_device' AND w.value='1')
               OR EXISTS (SELECT 1 FROM messages m WHERE m.chatId=c.chatId AND m.kind='content' AND m.hiddenLocally=0
                   AND (m.sequence IS NULL OR m.sequence>:historyCutoff OR m.status NOT IN ('decrypt_failed','identity_changed'))))
        ORDER BY (c.activityAt IS NULL), julianday(c.activityAt) DESC, c.chatId DESC
        """
    )
    fun observeChats(myUserId: String, historyCutoff: Long = -1): Flow<List<ChatListItem>>

    @Query(
        """
        SELECT c.chatId AS chatId, c.type AS type, c.createdAt AS createdAt, c.activityAt AS lastMessageAt,
               c.isMuted, c.mutedUntil, c.isBlocked, c.blockedUserId, p.userId AS otherUserId, p.username AS otherUsername,
               p.displayName AS otherDisplayName, p.avatarUrl AS otherAvatarUrl,
               p.avatarVersion AS otherAvatarVersion, p.hasAvatar AS otherHasAvatar,
               (SELECT COUNT(*) FROM messages m WHERE m.chatId = c.chatId AND m.senderId != :myUserId
                    AND m.kind = 'content' AND m.hiddenLocally = 0 AND m.isDeletedForEveryone = 0
                    AND m.sequence IS NOT NULL AND m.sequence > c.lastReadSequence
                    AND (m.sequence > :historyCutoff OR m.status NOT IN ('decrypt_failed','identity_changed'))) + COALESCE((SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chatId=c.chatId AND name='call_unread'),0) AS unreadCount,
               (SELECT CASE WHEN lm.isDeletedForEveryone = 1 THEN 'deleted'
                            ELSE COALESCE((SELECT CASE WHEN LOWER(a.mimeType) LIKE 'video/%' THEN 'video' ELSE a.mediaType END FROM attachments a WHERE a.clientMessageId = lm.clientMessageId), 'text')
                        END
                FROM messages lm
                WHERE lm.chatId = c.chatId AND lm.kind = 'content' AND lm.hiddenLocally = 0 AND lm.isDeletedForEveryone = 0
                  AND lm.status <> 'decrypt_failed' AND lm.status <> 'identity_changed'
                ORDER BY COALESCE(lm.sequence, 9223372036854775807) DESC, lm.createdAt DESC
                LIMIT 1) AS lastPreviewKind,
               (SELECT lm.text
                FROM messages lm
                WHERE lm.chatId = c.chatId AND lm.kind = 'content' AND lm.hiddenLocally = 0 AND lm.isDeletedForEveryone = 0
                  AND lm.status <> 'decrypt_failed' AND lm.status <> 'identity_changed'
                ORDER BY COALESCE(lm.sequence, 9223372036854775807) DESC, lm.createdAt DESC
                LIMIT 1) AS lastPreviewText,
               (SELECT a.filename
                FROM messages lm
                LEFT JOIN attachments a ON a.clientMessageId = lm.clientMessageId
                WHERE lm.chatId = c.chatId AND lm.kind = 'content' AND lm.hiddenLocally = 0 AND lm.isDeletedForEveryone = 0
                  AND lm.status <> 'decrypt_failed' AND lm.status <> 'identity_changed'
                ORDER BY COALESCE(lm.sequence, 9223372036854775807) DESC, lm.createdAt DESC
                LIMIT 1) AS lastPreviewFilename
        FROM (
            SELECT chats.*, CASE
                WHEN COALESCE((SELECT MAX(julianday(m.createdAt)) FROM messages m
                    WHERE m.chatId = chats.chatId AND m.kind = 'content' AND m.hiddenLocally = 0), -1)
                    > COALESCE(julianday(chats.lastMessageAt), -1)
                THEN (SELECT m.createdAt FROM messages m WHERE m.chatId = chats.chatId
                    AND m.kind = 'content' AND m.hiddenLocally = 0
                    ORDER BY julianday(m.createdAt) DESC, m.clientMessageId DESC LIMIT 1)
                ELSE chats.lastMessageAt END AS activityAt
            FROM chats
        ) c
        LEFT JOIN chat_members cm ON cm.chatId = c.chatId
        LEFT JOIN chat_participants p ON p.userId = cm.userId
        WHERE c.chatId = :chatId
        """
    )
    fun observeChat(chatId: String, myUserId: String, historyCutoff: Long = 0): Flow<ChatListItem?>
}

