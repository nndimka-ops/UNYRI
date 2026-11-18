package com.messenger.app.data.profile

import com.messenger.app.data.api.AvatarDownloadDto
import com.messenger.app.data.api.CreateAvatarDto
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.domain.model.*
import com.messenger.app.domain.repository.ProfileRepository
import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SelfProfileRefreshCoordinatorTest {
    private class Repo : ProfileRepository {
        var calls = 0
        var clears = 0
        var loader: suspend () -> UserProfile = { profile(3) }
        override fun clearSessionProfile() { clears++ }
        override suspend fun getMe(): Result<UserProfile> { calls++; return runCatching { loader() } }
        override suspend fun updateProfile(username: String?, displayName: String?, bioUpdate: BioUpdate, avatarUpdate: AvatarUpdate) = getMe()
        override suspend fun searchUsers(query: String, limit: Int, offset: Long) = Result.success(SearchResult(emptyList(), null))
        override suspend fun createAvatarUpload(sizeBytes: Long): Result<CreateAvatarDto> = error("unused")
        override suspend fun uploadAvatarBytes(uploadUrl: String, bytes: ByteArray): Result<Unit> = error("unused")
        override suspend fun completeAvatarUpload(objectKey: String) = getMe()
        override suspend fun removeAvatar() = getMe()
        override suspend fun getAvatarDownloadUrl(userId: String): Result<AvatarDownloadDto> = error("unused")
        override suspend fun downloadAvatarBytes(downloadUrl: String): Result<ByteArray> = error("unused")
    }
    companion object {
        private fun profile(version: Long) = UserProfile("me", "user", "Name", null,
            profileVersion = version, createdAt = "", updatedAt = "")
    }

    @Test fun reconnectForegroundAndBurstOfHintsUseOneRefresh() = runTest {
        val repo = Repo(); val auth = FakeAuthRepository()
        val connection = MutableStateFlow(SocketConnectionState.DISCONNECTED)
        val foreground = MutableStateFlow(false)
        val coordinator = SelfProfileRefreshCoordinator(repo, auth, connection, foreground, backgroundScope) { "https://one.example" }
        runCurrent()
        connection.value = SocketConnectionState.CONNECTED
        foreground.value = true
        repeat(10) { coordinator.changed("me", 3) }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(1, repo.calls)
        connection.value = SocketConnectionState.DISCONNECTED; runCurrent()
        connection.value = SocketConnectionState.CONNECTED; runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(2, repo.calls) // Already-loaded profiles refresh on reconnect too.
    }

    @Test fun foreignOrInvalidHintCannotTriggerSelfRequest() = runTest {
        val repo = Repo(); val auth = FakeAuthRepository()
        val coordinator = SelfProfileRefreshCoordinator(repo, auth, MutableStateFlow(SocketConnectionState.DISCONNECTED), MutableStateFlow(false), backgroundScope) { "https://one.example" }
        runCurrent(); coordinator.changed("foreign", 4); coordinator.changed("me", -1)
        advanceTimeBy(1000); runCurrent()
        assertEquals(0, repo.calls)
    }

    @Test fun queuedRefreshCannotCrossLogoutOrServerChange() = runTest {
        for (switchAccount in listOf(true, false)) {
            var origin = "https://one.example"
            val repo = Repo(); val auth = FakeAuthRepository()
            val coordinator = SelfProfileRefreshCoordinator(repo, auth, MutableStateFlow(SocketConnectionState.DISCONNECTED), MutableStateFlow(false), backgroundScope) { origin }
            runCurrent(); coordinator.changed("me", 3); runCurrent()
            if (switchAccount) auth.logout() else origin = "https://two.example"
            advanceTimeBy(101); runCurrent()
            assertEquals(0, repo.calls)
            if (switchAccount) assertEquals(2, repo.clears)
        }
    }

    @Test fun hintOvertakingOlderGetGetsOneBoundedFollowUp() = runTest {
        val repo = Repo().apply { loader = { profile(if (calls == 1) 2 else 3) } }
        val coordinator = SelfProfileRefreshCoordinator(repo, FakeAuthRepository(), MutableStateFlow(SocketConnectionState.DISCONNECTED), MutableStateFlow(false), backgroundScope) { "https://one.example" }
        runCurrent(); coordinator.changed("me", 3); runCurrent(); advanceTimeBy(301); runCurrent()
        assertEquals(2, repo.calls)
    }

    @Test fun requestsStaySerialWhileFetchIsInFlight() = runTest {
        val waiting = CompletableDeferred<UserProfile>()
        val repo = Repo().apply { loader = { if (calls == 1) waiting.await() else profile(4) } }
        val coordinator = SelfProfileRefreshCoordinator(repo, FakeAuthRepository(), MutableStateFlow(SocketConnectionState.DISCONNECTED), MutableStateFlow(false), backgroundScope) { "https://one.example" }
        runCurrent(); coordinator.changed("me", 3); runCurrent(); advanceTimeBy(101); runCurrent()
        repeat(20) { coordinator.changed("me", 4) }
        runCurrent(); assertEquals(1, repo.calls)
        waiting.complete(profile(3)); runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(2, repo.calls)
    }

    @Test fun foregroundCannotEraseHigherVersionHintWhileOldGetFinishes() = runTest {
        val repo = Repo().apply { loader = { profile(if (calls == 1) 2 else 3) } }
        val foreground = MutableStateFlow(false)
        val coordinator = SelfProfileRefreshCoordinator(repo, FakeAuthRepository(), MutableStateFlow(SocketConnectionState.DISCONNECTED), foreground, backgroundScope) { "https://one.example" }
        runCurrent()
        coordinator.changed("me", 3)
        foreground.value = true
        runCurrent(); advanceTimeBy(301); runCurrent()
        assertEquals(2, repo.calls)
    }

    @Test fun lowerDelayedHintCannotEraseAnAlreadyObservedHigherVersion() = runTest {
        val firstGet = CompletableDeferred<UserProfile>()
        val repo = Repo().apply { loader = { if (calls == 1) firstGet.await() else profile(if (calls == 2) 4 else 5) } }
        val coordinator = SelfProfileRefreshCoordinator(repo, FakeAuthRepository(), MutableStateFlow(SocketConnectionState.DISCONNECTED), MutableStateFlow(false), backgroundScope) { "https://one.example" }
        runCurrent()
        coordinator.changed("me", 0)
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(1, repo.calls)
        coordinator.changed("me", 5)
        coordinator.changed("me", 3)
        firstGet.complete(profile(4))
        runCurrent(); advanceTimeBy(301); runCurrent()
        assertEquals(3, repo.calls)
    }
}
