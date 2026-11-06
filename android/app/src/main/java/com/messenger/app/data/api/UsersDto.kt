package com.messenger.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserProfileDto(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("bio") val bio: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("profile_version") val profileVersion: Long = 1,
    @SerialName("avatar_version") val avatarVersion: Long = 0,
    @SerialName("has_avatar") val hasAvatar: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("admin_role") val adminRole: String? = null,
)

@Serializable
data class PublicUserDto(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("bio") val bio: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("profile_version") val profileVersion: Long = 1,
    @SerialName("avatar_version") val avatarVersion: Long = 0,
    @SerialName("has_avatar") val hasAvatar: Boolean = false,
    val online: Boolean? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
data class SearchResponseDto(
    val items: List<PublicUserDto>,
    @SerialName("next_offset") val nextOffset: Long? = null,
)

@Serializable
data class CreateAvatarDto(
    @SerialName("object_key") val objectKey: String,
    @SerialName("upload_url") val uploadUrl: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class AvatarDownloadDto(
    @SerialName("download_url") val downloadUrl: String,
    @SerialName("expires_at") val expiresAt: String,
)
