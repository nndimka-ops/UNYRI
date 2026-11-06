package com.messenger.app.domain.model

/** Full profile as seen by the account owner. */
@kotlinx.serialization.Serializable
data class UserProfile(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String? = null,
    /** E.1: optimistic version counters + authoritative avatar presence. */
    val profileVersion: Long = 1,
    val avatarVersion: Long = 0,
    val hasAvatar: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    val adminRole: String? = null,
)

/** What another user is allowed to see about you - deliberately narrower
 *  than [UserProfile] (no timestamps, no private fields, no avatar_key). */
@kotlinx.serialization.Serializable
data class PublicUser(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String? = null,
    val profileVersion: Long = 1,
    val avatarVersion: Long = 0,
    val hasAvatar: Boolean = false,
)

data class SearchResult(val items: List<PublicUser>, val nextOffset: Long?, val cached: Boolean = false)

/** Tri-state field update for PATCH /users/me: a field can be left untouched,
 *  explicitly cleared, or set to a new value. A plain nullable string can't
 *  distinguish "untouched" from "clear". */
sealed interface AvatarUpdate {
    data object Keep : AvatarUpdate
    data object Clear : AvatarUpdate
    data class Set(val url: String) : AvatarUpdate
}

/** Tri-state update for the profile bio (PROFILE.1), mirroring [AvatarUpdate]. */
sealed interface BioUpdate {
    data object Keep : BioUpdate
    data object Clear : BioUpdate
    data class Set(val text: String) : BioUpdate
}
