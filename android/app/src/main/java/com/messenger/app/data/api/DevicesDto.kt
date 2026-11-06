package com.messenger.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceDto(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val platform: String,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("last_active_at") val lastActiveAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("is_current") val isCurrent: Boolean,
)

@Serializable
data class DeviceListResponseDto(val devices: List<DeviceDto>)

@Serializable
data class RenameDeviceRequestDto(
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class RevokeOthersResponseDto(
    @SerialName("revoked_count") val revokedCount: Long,
)

@Serializable
data class SecurityEventDto(
    val id: Long,
    @SerialName("event_type") val eventType: String,
    @SerialName("actor_device_id") val actorDeviceId: String? = null,
    @SerialName("subject_device_id") val subjectDeviceId: String? = null,
    val metadata: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class SecurityEventsResponseDto(
    val events: List<SecurityEventDto>,
    @SerialName("next_cursor") val nextCursor: Long? = null,
)
