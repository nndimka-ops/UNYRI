package com.messenger.app.data.profile

import com.messenger.app.data.api.UsersApi
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProfileRepositoryLiveSyncTest {
    private fun response(version: Long, name: String = "Name", avatar: Long = 0, hasAvatar: Boolean = false) = MockResponse().setBody(
        """{"id":"me","username":"user","display_name":"$name","bio":"bio","profile_version":$version,"avatar_version":$avatar,"has_avatar":$hasAvatar,"created_at":"","updated_at":""}""")
    private fun repository(server: MockWebServer, auth: AuthRepository = FakeAuthRepository(), invalidated: (String) -> Unit = {}, origin: () -> String = { server.url("/").toString() }) =
        ProfileRepositoryImpl(UsersApi(OkHttpClient(), Json { ignoreUnknownKeys = true }, server.url("/").toString()), auth,
            invalidateAvatar = invalidated, serverOrigin = origin)

    @Test fun concurrentGetsShareOneSuccessfulRefresh() = runBlocking {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return response(2)
                }
            }
            val repo = repository(server)
            val first = async { repo.getMe() }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { repo.getMe() }
            release.countDown()
            assertEquals(2L, first.await().getOrThrow().profileVersion)
            assertEquals(2L, second.await().getOrThrow().profileVersion)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun lateGetCannotOverwriteNewerPatchOrObservableAvatar() = runBlocking {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.method == "GET") {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); response(4, "old", 1, true)
                } else response(5, "new", 2, true)
            }
            val repo = repository(server)
            val oldGet = async { repo.getMe() }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            assertEquals("new", repo.updateProfile(displayName = "new").getOrThrow().displayName)
            release.countDown()
            assertEquals("new", oldGet.await().getOrThrow().displayName)
            assertEquals(5L, repo.profileUpdates.first().profileVersion)
            assertEquals(2L, repo.cachedMe()!!.avatarVersion)
        }
    }

    @Test fun endedOwnerCannotPublishLateHttpResponse() = runBlocking {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return response(2)
                }
            }
            val auth = FakeAuthRepository(); val repo = repository(server, auth)
            val oldGet = async { repo.getMe() }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            auth.switchSession("other"); release.countDown()
            assertTrue(oldGet.await().isFailure)
            assertNull(repo.cachedMe())
            assertNull(withTimeoutOrNull(50) { repo.profileUpdates.first() })
        }
    }

    @Test fun originChangeDuringTokenLookupMakesNoHttpRequest() = runBlocking {
        MockWebServer().use { server ->
            var origin = server.url("/").toString()
            val base = FakeAuthRepository()
            val auth = object : AuthRepository by base {
                override suspend fun currentAccessToken(): String { origin = "https://changed.example"; return "test-token" }
            }
            val repo = repository(server, auth, origin = { origin })
            assertTrue(repo.getMe().isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun remoteAvatarRemovalInvalidatesCachedImageAndPublishesFallback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response(2, avatar = 1, hasAvatar = true))
            server.enqueue(response(3, avatar = 2, hasAvatar = false))
            val invalidated = mutableListOf<String>()
            val repo = repository(server, invalidated = { synchronized(invalidated) { invalidated += it } })
            assertTrue(repo.getMe().getOrThrow().hasAvatar)
            assertFalse(repo.getMe().getOrThrow().hasAvatar)
            assertEquals(listOf("me"), invalidated)
            assertFalse(repo.profileUpdates.first().hasAvatar)
        }
    }

    @Test fun lateAvatarCommitReturnsActualBytesVersionWhileCanonicalKeepsRemoteAvatar() = runBlocking {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.endsWith("/avatar/complete")) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); response(2, "local", 2, true)
                } else response(3, "remote", 3, true)
            }
            val repo = repository(server)
            val completion = async { repo.completeAvatarUpload("own-synthetic-avatar") }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            assertEquals(3L, repo.getMe().getOrThrow().avatarVersion)
            release.countDown()
            val actual = completion.await().getOrThrow()
            assertEquals(2L, actual.avatarVersion)
            assertEquals("local", actual.displayName)
            assertEquals(3L, repo.cachedMe()!!.avatarVersion)
            assertEquals("remote", repo.profileUpdates.first().displayName)
        }
    }
}
