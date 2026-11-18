package com.messenger.app.data.local.chat

/**
 * Shared by both Room observers and real-SQLite JVM regressions. Unread counts
 * explicitly seek the watermark range so the new preview index cannot attract
 * the planner into walking an entire chat's content for each count.
 */
internal object ChatListSql {
    const val LATEST = """
        SELECT newest.clientMessageId FROM messages newest
        WHERE newest.chatId = c.chatId AND newest.kind = 'content'
          AND newest.hiddenLocally = 0 AND newest.isDeletedForEveryone = 0
          AND newest.status NOT IN ('decrypt_failed','identity_changed')
        ORDER BY newest.sortKey DESC, newest.sortSequence DESC, newest.clientMessageId DESC
        LIMIT 1
    """
    private const val PROJECTION = """
        SELECT c.chatId, c.type, c.createdAt,
               CASE WHEN COALESCE(julianday(lm.createdAt), -1) > COALESCE(julianday(c.lastMessageAt), -1)
                    THEN lm.createdAt ELSE c.lastMessageAt END AS lastMessageAt,
               c.isMuted, c.mutedUntil, c.isBlocked, c.blockedUserId,
               p.userId AS otherUserId, p.username AS otherUsername,
               p.displayName AS otherDisplayName, p.avatarUrl AS otherAvatarUrl,
               p.avatarVersion AS otherAvatarVersion, p.hasAvatar AS otherHasAvatar,
               (SELECT COUNT(*) FROM messages m INDEXED BY index_messages_chatId_sequence
                    WHERE m.chatId = c.chatId AND m.senderId != :myUserId
                    AND m.kind = 'content' AND m.hiddenLocally = 0 AND m.isDeletedForEveryone = 0
                    AND m.sequence IS NOT NULL AND m.sequence > c.lastReadSequence
                    AND (m.sequence > :historyCutoff OR m.status NOT IN ('decrypt_failed','identity_changed')))
                 + COALESCE((SELECT CAST(value AS INTEGER) FROM chat_workspace WHERE chatId=c.chatId AND name='call_unread'),0) AS unreadCount,
               CASE WHEN lm.clientMessageId IS NULL THEN NULL
                    ELSE COALESCE(CASE WHEN LOWER(a.mimeType) LIKE 'video/%' THEN 'video' ELSE a.mediaType END, 'text') END AS lastPreviewKind,
               lm.text AS lastPreviewText, a.filename AS lastPreviewFilename,
               lm.clientMessageId AS lastPreviewClientMessageId
        FROM chats c
        LEFT JOIN messages lm ON lm.clientMessageId = (""" + LATEST + """)
        LEFT JOIN attachments a ON a.attachmentId =
            (SELECT attachmentId FROM attachments WHERE clientMessageId=lm.clientMessageId LIMIT 1)
        LEFT JOIN chat_members cm ON cm.chatId = c.chatId
        LEFT JOIN chat_participants p ON p.userId = cm.userId
    """
    const val LIST = "SELECT * FROM (" + PROJECTION + """
        WHERE (EXISTS (SELECT 1 FROM chat_workspace w WHERE w.chatId=c.chatId AND w.name='call_activity_at') OR NOT EXISTS (SELECT 1 FROM chat_workspace w WHERE w.chatId=c.chatId AND w.name='personal_clear')
           OR EXISTS (SELECT 1 FROM messages m WHERE m.chatId=c.chatId AND m.kind='content' AND m.hiddenLocally=0 AND (m.sequence IS NULL OR m.sequence>CAST((SELECT value FROM chat_workspace WHERE chatId=c.chatId AND name='personal_clear') AS INTEGER))))
          AND (:historyCutoff < 0 OR c.type='saved' OR EXISTS (SELECT 1 FROM chat_workspace w WHERE w.chatId=c.chatId AND w.name='call_activity_at')
               OR EXISTS (SELECT 1 FROM chat_workspace w WHERE w.chatId=c.chatId AND w.name='opened_on_device' AND w.value='1')
               OR EXISTS (SELECT 1 FROM messages m WHERE m.chatId=c.chatId AND m.kind='content' AND m.hiddenLocally=0
                   AND (m.sequence IS NULL OR m.sequence>:historyCutoff OR m.status NOT IN ('decrypt_failed','identity_changed'))))
        ) ORDER BY (lastMessageAt IS NULL), julianday(lastMessageAt) DESC, chatId DESC
    """
    const val CHAT = PROJECTION + " WHERE c.chatId = :chatId"
}
