package com.messenger.app.domain.model

/** One of the user's active devices, as shown on the Devices screen. */
@kotlinx.serialization.Serializable
data class DeviceInfo(
    val id: String,
    val displayName: String,
    val platform: String,
    val osVersion: String?,
    val appVersion: String?,
    val lastActiveAt: String?,
    val isCurrent: Boolean,
)

/** One server-side security event (newest-first on screen). */
data class SecurityEventInfo(
    val id: Long,
    val eventType: String,
    val actorDeviceId: String?,
    val subjectDeviceId: String?,
    val description: String,
    val createdAt: String,
)
