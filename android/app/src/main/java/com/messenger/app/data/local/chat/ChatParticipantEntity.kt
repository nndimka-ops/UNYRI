package com.messenger.app.data.local.chat

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cached public profile of a chat participant (never private fields) - only
 *  what `GET /chats`/`GET /chats/{id}` already returned, so the chat list can
 *  render offline without a separate profile fetch. */
@Entity(tableName = "chat_participants")
data class ChatParticipantEntity(
    @PrimaryKey val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    @androidx.room.ColumnInfo(defaultValue = "0") val avatarVersion: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val hasAvatar: Boolean = false,
)
