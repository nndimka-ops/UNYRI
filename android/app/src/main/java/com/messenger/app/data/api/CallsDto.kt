package com.messenger.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors the backend's `calls` row shape exactly - see
 *  server/src/calls/models.rs::CallDto. */
@Serializable
data class CallDto(
    val id: String,
    @SerialName("caller_user_id") val callerUserId: String,
    @SerialName("callee_user_id") val calleeUserId: String,
    @SerialName("media_kind") val mediaKind: String,
    val state: String,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("accepted_device_id") val acceptedDeviceId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("accepted_at") val acceptedAt: String? = null,
    @SerialName("connected_at") val connectedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("chat_visible") val chatVisible: Boolean? = null,
    val unread: Boolean = false,
    val peer: PublicUserDto? = null,
)

@Serializable
data class CallHistoryPage(val items: List<CallDto>, @SerialName("next_cursor") val nextCursor: String? = null, @SerialName("history_restored") val historyRestored: Boolean = false, @SerialName("visible_since") val visibleSince: String? = null)

@Serializable
data class CreateCallRequestDto(
    @SerialName("callee_user_id") val calleeUserId: String,
    @SerialName("media_kind") val mediaKind: String,
)

@Serializable
data class IceServerDto(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class IceServersResponseDto(
    @SerialName("ice_servers") val iceServers: List<IceServerDto>,
)
