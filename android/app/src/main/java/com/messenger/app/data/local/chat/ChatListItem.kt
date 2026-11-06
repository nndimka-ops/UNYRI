package com.messenger.app.data.local.chat

/** Denormalized read model joining chats + chat_members + chat_participants
 *  for display - not an @Entity, just a query projection. */
data class ChatListItem(
    val chatId: String,
    val type: String,
    val createdAt: String,
    val lastMessageAt: String?,
    val otherUserId: String?,
    val otherUsername: String?,
    val otherDisplayName: String?,
    val otherAvatarUrl: String?,
    val unreadCount: Int,
    /** Raw latest-message preview columns (see ChatDao.observeChats). Null
     *  when this chat has no cached displayable message. */
    val lastPreviewKind: String?,
    val lastPreviewText: String?,
    val lastPreviewFilename: String?,
    val otherAvatarVersion: Long? = null,
    val otherHasAvatar: Boolean? = null,
    val isMuted: Boolean = false,
    val mutedUntil: String? = null,
    val isBlocked: Boolean = false,
    val blockedUserId: String? = null,
)
