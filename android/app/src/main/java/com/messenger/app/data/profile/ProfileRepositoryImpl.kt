package com.messenger.app.data.profile

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.AvatarDownloadDto
import com.messenger.app.data.api.CreateAvatarDto
import com.messenger.app.data.api.PublicUserDto
import com.messenger.app.data.api.UserProfileDto
import com.messenger.app.data.api.UsersApi
import com.messenger.app.domain.model.AvatarUpdate
import com.messenger.app.domain.model.BioUpdate
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.model.SearchResult
import com.messenger.app.domain.model.UserProfile
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.data.api.ServerEndpoint
import kotlinx.coroutines.CancellationException

class ProfileRepositoryImpl(
    private val api: UsersApi,
    private val authRepository: AuthRepository,
    private val tokenStore: com.messenger.app.data.secure.SecureTokenStore? = null,
    private val invalidateAvatar: (String) -> Unit = {},
    private val serverOrigin: () -> String = { ServerEndpoint.current() },
) : ProfileRepository {

    private val directoryCacheMutex = kotlinx.coroutines.sync.Mutex()
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()
    private var completedRefreshes = 0L
    private fun owner(): SelfProfileOwner? = (authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
        ?.let { SelfProfileOwner(it, authRepository.currentSessionEpoch(), serverOrigin()) }
    private val canonical = SelfProfileState(::owner)
    override val usesAppRefreshCoordinator = true
    override val profileUpdates: Flow<UserProfile> = canonical.updates.transform { snapshot ->
        if (snapshot != null && snapshot.owner == owner()) emit(snapshot.profile)
    }
    override fun clearSessionProfile() = canonical.clear()

    override fun cachedMe(): UserProfile? = runCatching {
        val owner = owner() ?: return null
        canonical.current(owner)?.let { return it }
        kotlinx.serialization.json.Json.decodeFromString<UserProfile>(tokenStore?.offlineScreen("profile") ?: return null)
            .takeIf { it.id == owner.userId && owner == owner() }
            ?.copy(adminRole = null)
    }.getOrNull()

    override suspend fun getMe(): Result<UserProfile> {
        val captured = owner() ?: return Result.failure(AuthApiException.Api("unauthorized", "Not authenticated."))
        val generation = synchronized(refreshMutex) { completedRefreshes }
        return refreshMutex.withLock {
            if (captured != owner()) return@withLock Result.failure(CancellationException("Profile owner changed"))
            val existing = canonical.current(captured)
            if (generation != synchronized(refreshMutex) { completedRefreshes } && existing != null) return@withLock Result.success(existing)
            val result = withProfileToken(returnCanonical = true) { token, origin -> api.getMe(token, origin).toDomain() }
            if (result.isSuccess) synchronized(refreshMutex) { completedRefreshes++ }
            result
        }
    }

    private suspend fun withProfileToken(returnCanonical: Boolean = false, block: suspend (String, String) -> UserProfile): Result<UserProfile> = runCatching {
        val captured = owner() ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
        val cacheOwner = tokenStore?.offlineScreenOwner()
        val token = authRepository.currentAccessToken() ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
        if (captured != owner()) throw CancellationException("Profile owner changed")
        val profile = block(token, captured.origin)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (captured != owner()) throw CancellationException("Profile owner changed")
        val accepted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            authRepository.withSessionWrite(captured.epoch) {
                val before = canonical.current(captured)
                canonical.publish(captured, profile) { accepted ->
                    if (!accepted.hasAvatar || (before != null &&
                        (before.avatarVersion != accepted.avatarVersion || before.hasAvatar != accepted.hasAvatar))) {
                        invalidateAvatar(accepted.id)
                    }
                    runCatching { tokenStore?.saveOfflineScreen("profile", cacheOwner,
                        kotlinx.serialization.json.Json.encodeToString(UserProfile.serializer(), accepted.copy(adminRole = null))) }
                }
            }
        } ?: throw CancellationException("Profile owner changed")
        // An avatar commit response identifies the bytes just uploaded. Returning a
        // newer canonical version here would incorrectly label those bytes as its picture.
        if (returnCanonical) accepted else profile
    }

    override suspend fun updateProfile(
        username: String?,
        displayName: String?,
        bioUpdate: BioUpdate,
        avatarUpdate: AvatarUpdate,
    ): Result<UserProfile> = withProfileToken { token, origin ->
        api.updateProfile(token, username, displayName, bioUpdate, avatarUpdate, origin).toDomain()
    }

    override suspend fun searchUsers(query: String, limit: Int, offset: Long): Result<SearchResult> =
        withAccessToken { token ->
            val response = api.search(token, query, limit, offset)
            SearchResult(response.items.filter { it.id != (authRepository.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id }.map { it.toDomain() }, response.nextOffset)
        }

    override suspend fun directory(query: String, limit: Int, offset: Long): Result<SearchResult> = runCatching {
        val epoch = authRepository.currentSessionEpoch()
        val origin = com.messenger.app.data.api.ServerEndpoint.current()
        fun checkOwner() {
            if (epoch != authRepository.currentSessionEpoch() || origin != com.messenger.app.data.api.ServerEndpoint.current()) throw kotlinx.coroutines.CancellationException("Account changed")
        }
        val token = authRepository.currentAccessToken() ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
        checkOwner()
        val cacheOwner = tokenStore?.offlineScreenOwner("directory")
        val response = try { api.directory(token, query, limit.coerceIn(1, 30), offset) }
        catch (error: AuthApiException.Network) {
            if (!canUseDirectoryCache(error)) throw error
            checkOwner()
            return@runCatching kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                checkOwner(); kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val cache = tokenStore?.offlineScreen("directory")?.let {
                    runCatching { kotlinx.serialization.json.Json.decodeFromString<DirectoryCache>(it) }.getOrNull()
                } ?: throw error
                checkOwner()
                cache.search(query, limit, offset)
            }
        }
        checkOwner()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val result = SearchResult(response.items.filter { it.id != (authRepository.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id }.map { it.toDomain() }, response.nextOffset)
        if (query.trim().removePrefix("@").isEmpty() && offset < 100) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                directoryCacheMutex.withLock {
                    checkOwner(); kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val old = tokenStore?.offlineScreen("directory")?.let {
                        runCatching { kotlinx.serialization.json.Json.decodeFromString<DirectoryCache>(it) }.getOrNull()
                    } ?: DirectoryCache()
                    val updated = old.append(offset, result.items)
                    runCatching { tokenStore?.saveOfflineScreen("directory", cacheOwner,
                        kotlinx.serialization.json.Json.encodeToString(DirectoryCache.serializer(), updated)) }
                }
            }
        }
        checkOwner()
        result
    }

    override suspend fun createAvatarUpload(sizeBytes: Long): Result<CreateAvatarDto> =
        withAccessToken { token -> api.createAvatar(token, sizeBytes) }

    override suspend fun uploadAvatarBytes(uploadUrl: String, bytes: ByteArray): Result<Unit> =
        runCatching { api.uploadAvatarBytes(uploadUrl, bytes) }

    override suspend fun completeAvatarUpload(objectKey: String): Result<UserProfile> =
        withProfileToken { token, origin -> api.completeAvatar(token, objectKey, origin).toDomain() }

    override suspend fun removeAvatar(): Result<UserProfile> =
        withProfileToken { token, origin -> api.deleteAvatar(token, origin).toDomain() }

    override suspend fun getAvatarDownloadUrl(userId: String): Result<AvatarDownloadDto> =
        withAccessToken { token -> api.getAvatarDownloadUrl(token, userId) }

    override suspend fun downloadAvatarBytes(downloadUrl: String): Result<ByteArray> =
        runCatching { api.downloadAvatarBytes(downloadUrl) }

    private suspend fun <T> withAccessToken(block: suspend (String) -> T): Result<T> = runCatching {
        val token = authRepository.currentAccessToken()
            ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
        block(token)
    }

    private fun UserProfileDto.toDomain() = UserProfile(
        id = id,
        username = username,
        displayName = displayName,
        avatarUrl = avatarUrl,
        bio = bio,
        profileVersion = profileVersion,
        avatarVersion = avatarVersion,
        hasAvatar = hasAvatar,
        createdAt = createdAt,
        updatedAt = updatedAt,
        adminRole = adminRole,
    )

    private fun PublicUserDto.toDomain() = PublicUser(
        id = id,
        username = username,
        displayName = displayName,
        avatarUrl = avatarUrl,
        bio = bio,
        profileVersion = profileVersion,
        avatarVersion = avatarVersion,
        hasAvatar = hasAvatar,
    )
}
