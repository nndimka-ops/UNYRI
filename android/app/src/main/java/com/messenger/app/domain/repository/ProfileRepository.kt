package com.messenger.app.domain.repository

import com.messenger.app.data.api.AvatarDownloadDto
import com.messenger.app.data.api.CreateAvatarDto
import com.messenger.app.domain.model.AvatarUpdate
import com.messenger.app.domain.model.BioUpdate
import com.messenger.app.domain.model.SearchResult
import com.messenger.app.domain.model.UserProfile

/**
 * Abstraction over the profile/search endpoints. Identity and access-token
 * refresh are entirely delegated to [AuthRepository] - this interface never
 * duplicates session state.
 */
interface ProfileRepository {
    /** Canonical self snapshots, scoped to the current account/session/server. */
    val profileUpdates: kotlinx.coroutines.flow.Flow<UserProfile> get() = kotlinx.coroutines.flow.emptyFlow()
    val usesAppRefreshCoordinator: Boolean get() = false
    fun clearSessionProfile() {}
    fun cachedMe(): UserProfile? = null
    suspend fun getMe(): Result<UserProfile>

    suspend fun updateProfile(
        username: String? = null,
        displayName: String? = null,
        bioUpdate: BioUpdate = BioUpdate.Keep,
        avatarUpdate: AvatarUpdate = AvatarUpdate.Keep,
    ): Result<UserProfile>

    suspend fun searchUsers(query: String, limit: Int = 20, offset: Long = 0): Result<SearchResult>
    suspend fun directory(query: String, limit: Int = 30, offset: Long = 0): Result<SearchResult> = searchUsers(query, limit, offset)

    /** PROFILE.1 avatar transport: presigned PUT intent for a fresh object. */
    suspend fun createAvatarUpload(sizeBytes: Long): Result<CreateAvatarDto>

    /** PROFILE.1: PUT avatar bytes directly to the presigned URL. */
    suspend fun uploadAvatarBytes(uploadUrl: String, bytes: ByteArray): Result<Unit>

    /** E.1: commit an uploaded object as the authoritative avatar (server
     *  HEAD-verifies + bumps versions exactly once). */
    suspend fun completeAvatarUpload(objectKey: String): Result<UserProfile>

    /** E.1: authoritative avatar removal via the server API (bumps versions
     *  only when an avatar actually existed). */
    suspend fun removeAvatar(): Result<UserProfile>

    /** E.1: presigned GET URL for a visible user's avatar (self or other). */
    suspend fun getAvatarDownloadUrl(userId: String): Result<AvatarDownloadDto>

    /** PROFILE.1: download avatar bytes from a presigned GET URL. */
    suspend fun downloadAvatarBytes(downloadUrl: String): Result<ByteArray>
}
