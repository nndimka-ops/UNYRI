package com.messenger.app.data.local.chat

import androidx.room.Entity

/** Mirrors the server's `chat_members` shape. Only the *other* participant's
 *  row is cached locally - the app always knows its own identity via
 *  AuthRepository, so a self-membership row would be redundant. This keeps
 *  the schema ready for real multi-member (group) chats later without
 *  changing the shape, just adding more rows per chat_id. */
@Entity(tableName = "chat_members", primaryKeys = ["chatId", "userId"])
data class ChatMemberEntity(
    val chatId: String,
    val userId: String,
    val role: String,
)
