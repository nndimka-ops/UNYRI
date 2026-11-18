package com.messenger.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BlockedUserDto(val user: PublicUserDto, @SerialName("blocked_at") val blockedAt: String)

@Serializable
data class BlockedUsersPageDto(
    val items: List<BlockedUserDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** Account preferences can be queried without discovering a single chat. */
interface BlockedUsersRemote {
    suspend fun blockedUsers(accessToken: String, query: String, cursor: String?): BlockedUsersPageDto
    suspend fun directory(accessToken: String, query: String, limit: Int, offset: Long): SearchResponseDto
    suspend fun setUserBlocked(accessToken: String, userId: String, blocked: Boolean)
}
