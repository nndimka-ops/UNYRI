package com.messenger.app.data.local.chat

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local mirror of the server's `chats` row - metadata only, never message
 *  content. `lastReadSequence` is a purely local (never server-synced as
 *  such) watermark this device has marked read up to, in this chat, used
 *  only to compute the local unread badge - the durable, multi-device-safe
 *  read receipt is the server-side `message_receipts.read_at` watermark. */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val chatId: String,
    val type: String,
    val createdAt: String,
    val lastMessageAt: String?,
    val lastReadSequence: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val peerDeliveredSequence: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val peerReadSequence: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val isMuted: Boolean = false,
    val mutedUntil: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val isBlocked: Boolean = false,
    val blockedUserId: String? = null,
)
