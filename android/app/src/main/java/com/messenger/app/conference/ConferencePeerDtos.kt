package com.messenger.app.conference

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Public room identities also decode responses from servers predating login step 2. */
@Serializable data class ConferenceMember(@SerialName("user_id") val userId: String, @SerialName("device_id") val deviceId: String, @SerialName("display_name") val displayName: String,
    @SerialName("participant_identity") val participantIdentity: String = "", @SerialName("audio_publish_blocked") val audioPublishBlocked: Boolean = false,
    val username: String = "", @SerialName("public_username") val publicUsername: String? = null,
    @SerialName("unyri_number") val publicNumber: String? = null)
@Serializable data class ConferenceDto(val id: String, val title: String, @SerialName("owner_user_id") val ownerUserId: String, @SerialName("max_participants") val maxParticipants: Int, val state: String, val participants: List<ConferenceMember> = emptyList(), @SerialName("expires_at") val expiresAt: String = "", @SerialName("admission_locked") val admissionLocked: Boolean = false, val description: String = "", val listed: Boolean = false, @SerialName("access_mode") val accessMode: String = "invite", @SerialName("approval_required") val approvalRequired: Boolean? = null, @SerialName("settings_revision") val settingsRevision: Long = 1, @SerialName("access_revision") val accessRevision: Long = 1,
    val username: String = "", @SerialName("public_username") val publicUsername: String? = null,
    @SerialName("unyri_number") val publicNumber: String? = null) { init { com.messenger.app.data.api.HostileWire.range(maxParticipants.toLong(),2,30); com.messenger.app.data.api.HostileWire.range(settingsRevision,1,Long.MAX_VALUE); com.messenger.app.data.api.HostileWire.range(accessRevision,1,Long.MAX_VALUE) } }

@Serializable data class DirectoryRoom(val id: String, val title: String, val description: String = "",
    @SerialName("owner_user_id") val ownerUserId: String, @SerialName("owner_display_name") val ownerDisplayName: String,
    @SerialName("participant_count") val participantCount: Int, @SerialName("max_participants") val maxParticipants: Int,
    @SerialName("access_mode") val accessMode: String, @SerialName("admission_locked") val admissionLocked: Boolean = false,
    @SerialName("key_available") val keyAvailable: Boolean = false, @SerialName("created_at") val createdAt: String, @SerialName("expires_at") val expiresAt: String,
    val username: String = "", @SerialName("public_username") val publicUsername: String? = null,
    @SerialName("unyri_number") val publicNumber: String? = null) {
    init { com.messenger.app.data.api.HostileWire.range(maxParticipants.toLong(), 2, 30); com.messenger.app.data.api.HostileWire.range(participantCount.toLong(), 0, maxParticipants.toLong()) }
}

@Serializable class ConferenceJoinRequest(
    @SerialName("request_id") val requestId: String,
    @SerialName("conference_id") val conferenceId: String,
    @SerialName("requester_user_id") val requesterUserId: String,
    @SerialName("requester_device_id") val requesterDeviceId: String,
    @SerialName("display_name") val displayName: String,
    val username: String, val status: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("retry_at") val retryAt: String? = null,
    @SerialName("public_username") val publicUsername: String? = null,
    @SerialName("unyri_number") val publicNumber: String? = null,
) { override fun toString() = "ConferenceJoinRequest([redacted])" }
