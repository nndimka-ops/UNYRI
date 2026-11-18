package com.messenger.app.data.profile

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.UsersApi
import com.messenger.app.data.sync.FakeAuthRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class AccountAutoDeleteRepositoryTest {
    private fun snapshot(revision: Int, days: Int? = null) = """{"inactive_days":$days,"last_active_at":"2026-11-01T10:00:00Z","next_deletion_at":null,"revision":$revision}"""

    @Test fun loadingNeverWritesAndDisableSendsExplicitNullWithoutPassword() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val origin = server.url("/").toString().trimEnd('/')
            val repo = AccountAutoDeleteRepository(UsersApi(OkHttpClient(), Json { ignoreUnknownKeys = true }, origin), FakeAuthRepository()) { origin }
            server.enqueue(MockResponse().setBody(snapshot(7, 1095)))
            assertTrue(repo.refresh().isSuccess)
            assertEquals("GET", server.takeRequest().method)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody(snapshot(8)))
            assertTrue(repo.update(repo.ownerKey()!!, null, 7, "must-not-be-sent").isSuccess)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/users/me/auto-delete", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(JsonNull, body["inactive_days"])
            assertEquals("7", body["expected_revision"]?.jsonPrimitive?.content)
            assertFalse(body.containsKey("password"))
            assertNull(repo.state.value?.inactiveDays)
        } finally { server.shutdown() }
    }

    @Test fun conflictFetchesLatestWithoutRetryingDestructiveWrite() = runBlocking {
        val remote = FakeAccountAutoDeleteRemote()
        val repo = AccountAutoDeleteRepository(remote, FakeAuthRepository()) { "https://first.example" }
        assertTrue(repo.refresh().isSuccess)
        remote.updateFailure = AuthApiException.Api("conflict", "changed", 409)
        remote.snapshot = remote.snapshot.copy(inactiveDays = 7, revision = 9)
        assertTrue(repo.update(repo.ownerKey()!!, 1095, 1, "qa-password").isFailure)
        assertEquals(1, remote.updates.size)
        assertEquals(AccountAutoDeleteUpdate(1095, 1, "qa-password"), remote.updates.single())
        assertEquals(2, remote.getCount)
        assertEquals(9L, repo.state.value?.revision)
        assertEquals(7, repo.state.value?.inactiveDays)
        assertTrue(repo.update(repo.ownerKey()!!, 1, 1, "qa-password").isFailure)
        assertEquals(1, remote.updates.size)
    }

    @Test fun failedConflictRefetchMakesOldPolicyUnusable() = runBlocking {
        val remote = FakeAccountAutoDeleteRemote()
        val repo = AccountAutoDeleteRepository(remote, FakeAuthRepository()) { "https://first.example" }
        assertTrue(repo.refresh().isSuccess)
        remote.updateFailure = AuthApiException.Api("conflict", "changed", 409)
        remote.getFailure = AuthApiException.Network("offline")
        assertTrue(repo.update(repo.ownerKey()!!, 1, 1, "qa-password").isFailure)
        assertNull(repo.state.value)
        assertTrue(repo.update(repo.ownerKey()!!, null, 1, null).isFailure)
        assertEquals(1, remote.updates.size)
    }

    @Test fun exactIntegerRangeAndPasswordAreCheckedBeforeSending() = runBlocking {
        val remote = FakeAccountAutoDeleteRemote()
        val repo = AccountAutoDeleteRepository(remote, FakeAuthRepository()) { "https://first.example" }
        assertTrue(repo.refresh().isSuccess)
        val owner = repo.ownerKey()!!
        for (days in listOf(0, -1, 1096, Int.MAX_VALUE)) assertTrue(repo.update(owner, days, 1, "qa-password").isFailure)
        assertTrue(repo.update(owner, 1, 1, "").isFailure)
        assertTrue(remote.updates.isEmpty())
        assertTrue(repo.update(owner, 2, 1, "qa-password").isSuccess)
        assertTrue(repo.update(owner, 1095, 2, "qa-password").isSuccess)
        assertEquals(listOf(2, 1095), remote.updates.map { it.inactiveDays })
    }

    @Test fun accountChangeDuringResponseCannotPublishOldPolicy() = runBlocking {
        val auth = FakeAuthRepository()
        val remote = FakeAccountAutoDeleteRemote()
        val repo = AccountAutoDeleteRepository(remote, auth) { "https://first.example" }
        assertTrue(repo.refresh().isSuccess)
        val oldOwner = repo.ownerKey()!!
        remote.beforeGet = { auth.switchSession("new-account") }
        assertTrue(repo.refresh().isFailure)
        assertNull(repo.state.value)
        assertTrue(repo.update(oldOwner, 7, 1, "qa-password").isFailure)
        assertTrue(remote.updates.isEmpty())
    }

    @Test fun originChangeDuringTokenRefreshCannotSendOldTokenAnywhere() = runBlocking {
        var origin = "https://first.example"
        val fake = FakeAuthRepository()
        val auth = object : com.messenger.app.domain.repository.AuthRepository by fake {
            override suspend fun currentAccessToken(): String { origin = "https://second.example"; return "qa-token" }
        }
        val remote = FakeAccountAutoDeleteRemote()
        val repo = AccountAutoDeleteRepository(remote, auth) { origin }
        assertTrue(repo.refresh().isFailure)
        assertEquals(0, remote.getCount)
        assertTrue(remote.updates.isEmpty())
        assertNull(repo.state.value)
    }
}

internal class FakeAccountAutoDeleteRemote : AccountAutoDeleteRemote {
    var snapshot = AccountAutoDeleteSnapshot(null, "2026-11-01T10:00:00Z", null, 1)
    var getCount = 0
    var activityCount = 0
    val activityTokens = mutableListOf<String>()
    val updates = mutableListOf<AccountAutoDeleteUpdate>()
    var getFailure: Exception? = null
    var updateFailure: Exception? = null
    var beforeGet: () -> Unit = {}
    var beforeActivity: suspend () -> Unit = {}
    override suspend fun getAutoDelete(token: String, origin: String): AccountAutoDeleteSnapshot {
        getCount++; getFailure?.let { throw it }; beforeGet(); return snapshot
    }
    override suspend fun updateAutoDelete(token: String, origin: String, update: AccountAutoDeleteUpdate): AccountAutoDeleteSnapshot {
        updates.add(update); updateFailure?.let { throw it }
        snapshot = snapshot.copy(inactiveDays = update.inactiveDays, revision = snapshot.revision + 1)
        return snapshot
    }
    override suspend fun recordAccountActivity(token: String, origin: String) {
        beforeActivity(); activityCount++; activityTokens.add(token)
    }
}
