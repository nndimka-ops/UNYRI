package com.messenger.app.ui

import androidx.lifecycle.SavedStateHandle
import com.messenger.app.data.api.UsersApi
import com.messenger.app.data.profile.ProfileRepositoryImpl
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.UserProfile
import com.messenger.app.security.PublicProfileLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.*

/** Exercise the real repository: /users/search deliberately cannot resolve the signed-in user. */
class OwnProfileLinkTest {
    private fun ownResponse(username: String = "user") = MockResponse().setBody(
        """{"id":"me","username":"$username","display_name":"Authenticated Me","bio":"Own biography","profile_version":3,"avatar_version":2,"has_avatar":true,"created_at":"","updated_at":""}""")

    private fun repository(server: MockWebServer, auth: FakeAuthRepository): ProfileRepositoryImpl {
        val origin = server.url("/").toString().trimEnd('/')
        return ProfileRepositoryImpl(UsersApi(OkHttpClient(), Json { ignoreUnknownKeys = true }, origin), auth,
            serverOrigin = { origin })
    }

    private fun owner(auth: FakeAuthRepository): String? = (auth.authState.value as? AuthSessionState.LoggedIn)
        ?.let { "origin|${it.user.id}|${auth.currentSessionEpoch()}" }

    @Test fun ownLinksOpenTheProfileCardOnColdAndWarmDeliveryWhenSearchExcludesSelf() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/users/me" -> ownResponse()
                    "/users/search" -> MockResponse().setBody("""{"items":[],"next_offset":null}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val auth = FakeAuthRepository()
            val repo = repository(server, auth)
            val captured = assertNotNull(owner(auth))
            // This is the production search contract which the old injected self-result masked.
            assertTrue(repo.searchUsers("user").getOrThrow().items.isEmpty())
            for (cold in listOf(true, false)) {
                val saved = SavedStateHandle()
                val initial = ProfileLinkViewModel(saved).inbox
                val link = assertNotNull(PublicProfileLink.parse("android.intent.action.VIEW", "unyri://user/UsEr"))
                initial.offer(link, "workspace", if (cold) null else captured)
                val inbox = if (cold) ProfileLinkViewModel(saved).inbox else initial
                val request = assertNotNull(inbox.claim("workspace", captured))
                val profile = resolveProfileLink(request.username, captured, { owner(auth) }, "me", repo::getMe,
                    { name, offset -> repo.searchUsers(name, limit = 50, offset = offset) }).getOrThrow()
                assertEquals("me", profile.id)
                assertEquals("Authenticated Me", profile.displayName)
                assertEquals("Own biography", profile.bio)
                assertEquals(3L, profile.profileVersion)
                assertEquals(2L, profile.avatarVersion)
                assertTrue(profile.hasAvatar)
                assertTrue(inbox.present(request, profile, "workspace", owner(auth)))
                assertEquals(profile, inbox.visibleCard("workspace", captured))
                assertFalse(inbox.present(request, profile, "workspace", captured))
                inbox.dismissCard()
                assertNull(ProfileLinkViewModel(saved).inbox.pending)
                assertNull(ProfileLinkViewModel(saved).inbox.visibleCard("workspace", captured))
                assertEquals(captured, owner(auth))
            }
            assertEquals(3, server.requestCount)
            val requests = List(3) { server.takeRequest() }
            assertEquals(listOf("/users/search", "/users/me", "/users/me"), requests.map { it.requestUrl?.encodedPath })
            assertTrue(requests.all { it.method == "GET" && it.getHeader("Authorization") == "Bearer token" })
        }
    }

    @Test fun renamedOwnUsernameUsesAuthenticatedProfileRatherThanTheLoginSnapshot() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ownResponse("new_name"))
            val auth = FakeAuthRepository() // AuthUser still has the old login username "user".
            val repo = repository(server, auth)
            val captured = assertNotNull(owner(auth))
            val profile = resolveProfileLink("NEW_NAME", captured, { owner(auth) }, "me", repo::getMe,
                { _, _ -> error("Own link must never use search") }).getOrThrow()
            assertEquals("me", profile.id)
            assertEquals("new_name", profile.username)
            assertEquals("/users/me", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun accountChangeDuringOwnProfileLookupCannotPresentTheOldCard() = runBlocking {
        MockWebServer().use { server ->
            val auth = FakeAuthRepository()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    auth.switchSession("other-account")
                    return ownResponse()
                }
            }
            val repo = repository(server, auth)
            val captured = assertNotNull(owner(auth))
            val inbox = ProfileLinkInbox()
            inbox.offer(assertNotNull(PublicProfileLink.parse("android.intent.action.VIEW", "unyri://user/user")),
                "workspace", captured)
            assertNotNull(inbox.claim("workspace", captured))
            assertFailsWith<CancellationException> {
                resolveProfileLink("user", captured, { owner(auth) }, "me", repo::getMe,
                    { _, _ -> error("Do not fall back to another account's search") })
            }
            assertNull(inbox.claim("workspace", owner(auth)))
            assertNull(inbox.visibleCard("workspace", owner(auth)))
            assertNull(inbox.pending)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun ownResponseForAnotherIdentityIsRejectedBeforeSearchOrPresentation() = runBlocking {
        val result = resolveProfileLink("user", "owner", { "owner" }, "me", {
            Result.success(UserProfile("foreign", "user", "Foreign", null, createdAt = "", updatedAt = ""))
        }, { _, _ -> error("Mismatched own identity must not fall through to search") })
        assertTrue(result.isFailure)
    }

    @Test fun otherProfilesStillUseExactSearchAfterCheckingOwnIdentity() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ownResponse())
            server.enqueue(MockResponse().setBody(
                """{"items":[{"id":"peer","username":"boris","display_name":"Boris"}],"next_offset":null}"""))
            val auth = FakeAuthRepository()
            val repo = repository(server, auth)
            val captured = assertNotNull(owner(auth))
            val profile = resolveProfileLink("boris", captured, { owner(auth) }, "me", repo::getMe,
                { name, offset -> repo.searchUsers(name, limit = 50, offset = offset) }).getOrThrow()
            assertEquals("peer", profile.id)
            assertEquals("/users/me", server.takeRequest().requestUrl?.encodedPath)
            val search = server.takeRequest()
            assertEquals("/users/search", search.requestUrl?.encodedPath)
            assertEquals("boris", search.requestUrl?.queryParameter("q"))
            assertEquals(2, server.requestCount)
        }
    }
}
