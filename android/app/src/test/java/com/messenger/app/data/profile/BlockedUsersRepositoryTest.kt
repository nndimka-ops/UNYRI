package com.messenger.app.data.profile

import com.messenger.app.data.api.*
import com.messenger.app.data.sync.FakeAuthRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class BlockedUsersRepositoryTest {
    @Test fun hostileRefreshCannotUnblockAndOnlyUserActionUnblocks() = runBlocking {
        val security = com.messenger.app.data.secure.LocalSecurityDecisions(com.messenger.app.data.signal.InMemorySecureKeyValueStore()) { "owner" }
        val remote = Remote()
        val repo = BlockedUsersRepositoryImpl(remote, FakeAuthRepository(), { id, blocked, _ -> security.setBlocked(id, blocked); true },
            { "https://test.invalid" }, security)
        repo.setBlocked(peer, true).getOrThrow()
        repeat(3) { assertEquals(peer, repo.list("", null).getOrThrow().items.single().user.id) }
        assertEquals(true, security.blockDecision(peer))
        repo.setBlocked(peer, false).getOrThrow()
        remote.list = { BlockedUsersPageDto(listOf(BlockedUserDto(publicUser, "now"))) }
        assertTrue(repo.list("", null).getOrThrow().items.isEmpty())
        assertEquals(false, security.blockDecision(peer))
    }

    @Test fun identityFailureAndRemoteFailureStillShowLocalBlocksWithoutPrivateLoginQuery() = runBlocking {
        val auth = FakeAuthRepository()
        val security = com.messenger.app.data.secure.LocalSecurityDecisions(com.messenger.app.data.signal.InMemorySecureKeyValueStore()) { "owner" }
        security.setBlocked(peer, true)
        val client = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline") }.build()
        val identity = com.messenger.app.data.identity.IdentityRepository(com.messenger.app.data.identity.IdentityApi(client), auth) { "https://test.invalid" }
        val remote = Remote().apply { list = { throw java.io.IOException("offline") } }
        val repo = BlockedUsersRepositoryImpl(remote, auth, { _, _, _ -> true }, { "https://test.invalid" }, security, identity)
        assertEquals(peer, repo.list("", null).getOrThrow().items.single().user.id)
        assertEquals("", remote.lastQuery)
        assertTrue(repo.list("private_login", null).getOrThrow().items.isEmpty())
        assertEquals("", remote.lastQuery)
        assertEquals(true, security.blockDecision(peer))
    }

    private val peer = "11111111-1111-4111-8111-111111111111"
    private val self = "22222222-2222-4222-8222-222222222222"
    private val publicUser get() = PublicUserDto(peer, "alice", "Alice")
    private class Remote : BlockedUsersRemote {
        var list: suspend () -> BlockedUsersPageDto = { BlockedUsersPageDto(emptyList()) }
        var people: suspend () -> SearchResponseDto = { SearchResponseDto(emptyList()) }
        var mutate: suspend () -> Unit = {}
        var mutations = 0
        var lastQuery: String? = null
        override suspend fun blockedUsers(accessToken: String, query: String, cursor: String?): BlockedUsersPageDto { lastQuery = query; return list() }
        override suspend fun directory(accessToken: String, query: String, limit: Int, offset: Long) = people()
        override suspend fun setUserBlocked(accessToken: String, userId: String, blocked: Boolean) { mutations++; mutate() }
    }

    @Test fun `transport uses paged account API with encoded query and no chat discovery`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val body = if (chain.request().method == "GET")
                """{"items":[{"user":{"id":"$peer","username":"alice","display_name":"Alice","has_avatar":true,"avatar_version":3},"blocked_at":"2026-10-28T12:00:00Z"}],"next_cursor":"$peer"}""" else "null"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = UsersApi(client, Json { ignoreUnknownKeys = true }, "https://isolated.invalid")
        val page = api.blockedUsers("token", "Alice & Bob", peer)
        assertEquals(peer, page.nextCursor); assertEquals(3, page.items.single().user.avatarVersion)
        assertEquals("/users/blocked", requests[0].url.encodedPath)
        assertEquals("Alice & Bob", requests[0].url.queryParameter("q")); assertEquals("50", requests[0].url.queryParameter("limit"))
        assertEquals(peer, requests[0].url.queryParameter("cursor")); assertEquals("Bearer token", requests[0].header("Authorization"))
        api.setUserBlocked("token", peer, true); api.setUserBlocked("token", peer, false)
        assertEquals(listOf("GET", "POST", "DELETE"), requests.map { it.method })
        assertEquals(listOf("/users/blocked", "/users/$peer/block", "/users/$peer/block"), requests.map { it.url.encodedPath })
        Unit
    }

    @Test fun `self blocking is rejected before network or cache writes and self is filtered from directory`() = runBlocking {
        val auth = FakeAuthRepository().apply { switchSession(self) }
        val remote = Remote().apply { people = { SearchResponseDto(listOf(PublicUserDto(self, "self", "Self"), publicUser)) } }
        var writes = 0
        val repo = BlockedUsersRepositoryImpl(remote, auth, { _, _, _ -> writes++; true }, { "https://test.invalid" })
        assertTrue(repo.setBlocked(self, true).isFailure); assertEquals(0, remote.mutations); assertEquals(0, writes)
        assertEquals(listOf(peer), repo.people("", 0).getOrThrow().items.map { it.id })
    }

    @Test fun `network failure preserves the explicit local decision`() = runBlocking {
        val remote = Remote().apply { mutate = { throw java.io.IOException("offline") } }
        var writes = 0
        val repo = BlockedUsersRepositoryImpl(remote, FakeAuthRepository(), { _, _, _ -> writes++; true }, { "https://test.invalid" })
        assertTrue(repo.setBlocked(peer, false).isFailure); assertEquals(1, writes)
    }

    @Test fun `late old-session block acknowledgement cannot mutate the next account`() = runBlocking {
        val auth = FakeAuthRepository()
        val remote = Remote().apply { mutate = { auth.switchSession(self) } }
        var writes = 0
        val repo = BlockedUsersRepositoryImpl(remote, auth, { _, _, _ -> writes++; true }, { "https://test.invalid" })
        assertTrue(repo.setBlocked(peer, true).isFailure); assertEquals(1, writes)
    }

    @Test fun `late response from another server cannot leak rows or project preferences`() = runBlocking {
        var origin = "https://first.invalid"
        val remote = Remote().apply {
            list = { origin = "https://second.invalid"; BlockedUsersPageDto(listOf(BlockedUserDto(publicUser, "now"))) }
            mutate = { origin = "https://second.invalid" }
        }
        var writes = 0
        val repo = BlockedUsersRepositoryImpl(remote, FakeAuthRepository(), { _, _, _ -> writes++; true }, { origin })
        assertTrue(repo.list("", null).isFailure)
        origin = "https://first.invalid"
        assertTrue(repo.setBlocked(peer, true).isFailure); assertEquals(1, writes)
    }

    @Test fun `acknowledgement projects existing peers with original epoch and rejected fence is not success`() = runBlocking {
        val auth = FakeAuthRepository()
        var projected: Triple<String, Boolean, Int>? = null
        val repo = BlockedUsersRepositoryImpl(Remote(), auth, { id, block, epoch -> projected = Triple(id, block, epoch); true }, { "https://test.invalid" })
        val epoch = auth.currentSessionEpoch()
        repo.setBlocked(peer, true).getOrThrow()
        assertEquals(Triple(peer, true, epoch), projected)
        val rejected = BlockedUsersRepositoryImpl(Remote(), auth, { _, _, _ -> false }, { "https://test.invalid" })
        assertTrue(rejected.setBlocked(peer, false).isFailure)
    }
}
