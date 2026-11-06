package com.messenger.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncResponseDto(
    val messages: List<MessageDto>,
    @SerialName("next_sequence") val nextSequence: Long,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("deleted_chat_ids") val deletedChatIds: List<String> = emptyList(),
    @SerialName("history_cleared_through_sequence") val historyClearedThroughSequence: Long? = null,
    @SerialName("history_cleared_before_ms") val historyClearedBeforeMs: Long? = null,
)
