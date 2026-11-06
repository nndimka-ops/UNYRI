package com.messenger.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateDirectChatRequestDto(
    @SerialName("other_user_id") val otherUserId: String,
)

@Serializable
data class ChatSummaryDto(
    @SerialName("chat_id") val chatId: String,
    val type: String,
    @SerialName("other_user") val otherUser: PublicUserDto? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("other_member_delivered_sequence") val otherMemberDeliveredSequence: Long = 0,
    @SerialName("own_read_sequence") val ownReadSequence: Long = 0,
    @SerialName("other_member_read_sequence") val otherMemberReadSequence: Long = 0,
    @SerialName("is_muted") val isMuted: Boolean = false,
    @SerialName("muted_until") val mutedUntil: String? = null,
    @SerialName("is_blocked") val isBlocked: Boolean = false,
    @SerialName("blocked_user_id") val blockedUserId: String? = null,
)

@Serializable
data class ChatListResponseDto(
    val items: List<ChatSummaryDto>,
    @SerialName("next_offset") val nextOffset: Long? = null,
)

@Serializable
data class MuteChatRequestDto(@SerialName("muted_until") val mutedUntil: String?)

@Serializable
data class BlockChatRequestDto(@SerialName("blocked_user_id") val blockedUserId: String)

@Serializable
data class ReportChatRequestDto(
    val reason: String,
    @SerialName("reported_user_id") val reportedUserId: String? = null,
    val description: String? = null,
)
