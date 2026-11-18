package com.messenger.app.conference

import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.*

private class ConferenceAuth : AuthRepository {
    var epoch = 7
    var refresh: suspend () -> String? = { "session-token" }
    override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedOut)
    override suspend fun register(username: String, password: String, displayName: String) = Result.success(Unit)
    override suspend fun login(username: String, password: String) = Result.success(Unit)
    override suspend fun logout() { epoch++ }
    override suspend fun currentAccessToken() = refresh()
    override fun currentDeviceId() = "device"
    override fun currentSessionEpoch() = epoch
    override suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T? = if (epochAtStart == epoch) block() else null
    override suspend fun clearRoom(block: suspend () -> Unit) = block()
}

class ConferenceApiTest {
    private val id = "d37b5fc1-1f85-4428-97db-91c8389ed933"
    private val dto get() = """{"id":"$id","title":"Team","owner_user_id":"me","max_participants":30,"state":"active","created_at":"now","participants":[]}"""
    @Test fun `participant moderation is capability gated and exact admission identity is sent`() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val auth = ConferenceAuth()
            val api = ConferenceApi(OkHttpClient(), Json { ignoreUnknownKeys = true }, auth) { origin }
            server.enqueue(MockResponse().setBody("""{"host_controls":true}"""))
            assertFalse(api.capabilities(origin, 7).participantModeration)
            server.takeRequest()
            server.enqueue(MockResponse().setBody("""{"participant_moderation":true}"""))
            assertTrue(api.capabilities(origin, 7).participantModeration)
            server.takeRequest()
            val user = "179345ba-84b9-4b5b-85c6-0b2abb1e85e8"
            val identity = "$user:fa69fb50-62a8-40bc-8690-850856898b24:2d412a97-114f-4695-906f-e671a74b4847"
            for (blocked in listOf(true, false)) {
                server.enqueue(MockResponse().setBody(dto))
                api.audioRestriction(id, user, identity, blocked, origin, 7)
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/conferences/$id/participants/$user/audio", request.path)
                assertEquals("Bearer session-token", request.getHeader("Authorization"))
                assertEquals("""{"participant_identity":"$identity","blocked":$blocked}""", request.body.readUtf8())
            }
            server.enqueue(MockResponse().setBody(dto))
            api.removeParticipant(id, user, identity, origin, 7)
            val remove = server.takeRequest()
            assertEquals("/conferences/$id/participants/$user/remove", remove.path)
            assertEquals("""{"participant_identity":"$identity"}""", remove.body.readUtf8())
        }
    }
    @Test fun `participant actions cannot target mismatched identity or cross sessions during refresh`() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val auth = ConferenceAuth()
            val api = ConferenceApi(OkHttpClient(), Json { ignoreUnknownKeys = true }, auth) { origin }
            val user = "179345ba-84b9-4b5b-85c6-0b2abb1e85e8"
            val identity = "$user:fa69fb50-62a8-40bc-8690-850856898b24:2d412a97-114f-4695-906f-e671a74b4847"
            assertFailsWith<IllegalArgumentException> { api.removeParticipant(id, "different", identity, origin, 7) }
            assertFailsWith<IllegalArgumentException> { api.audioRestriction(id, user, "missing-admission", true, origin, 7) }
            auth.refresh = { auth.epoch++; "new-account-token" }
            assertFailsWith<kotlinx.coroutines.CancellationException> { api.removeParticipant(id, user, identity, origin, 7) }
            assertFailsWith<IllegalStateException> { api.audioRestriction(id, user, identity, true, "https://other.example", 8) }
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            Unit
        }
    }
    @Test fun `older participant metadata keeps microphone allowed and new restrictions decode`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"user_id":"me","device_id":"device","display_name":"Name"}"""
        assertFalse(json.decodeFromString<ConferenceMember>(old).audioPublishBlocked)
        assertEquals("", json.decodeFromString<ConferenceMember>(old).participantIdentity)
        val current = old.dropLast(1) + """, "participant_identity":"exact-admission", "audio_publish_blocked":true}"""
        assertTrue(json.decodeFromString<ConferenceMember>(current).audioPublishBlocked)
        assertEquals("exact-admission", json.decodeFromString<ConferenceMember>(current).participantIdentity)
    }
    @Test fun `owner room discovery is additive and returns metadata without needing room keys`() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val auth = ConferenceAuth()
            val api = ConferenceApi(OkHttpClient(), Json { ignoreUnknownKeys = true }, auth) { origin }
            server.enqueue(MockResponse().setBody("""{"host_controls":true}"""))
            assertFalse(api.capabilities(origin, 7).ownerRooms)
            server.takeRequest()
            server.enqueue(MockResponse().setBody("""{"owner_rooms":true}"""))
            assertTrue(api.capabilities(origin, 7).ownerRooms)
            server.takeRequest()
            server.enqueue(MockResponse().setBody("[$dto]"))
            val rooms = api.owned(origin, 7)
            assertEquals(id, rooms.single().id)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/conferences/owned", request.path)
            assertEquals("Bearer session-token", request.getHeader("Authorization"))
            assertEquals(0L, request.bodySize)
        }
    }
    @Test fun `host capabilities are additive and settings use authenticated PATCH without media secrets`() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val auth = ConferenceAuth()
            val api = ConferenceApi(OkHttpClient(), Json { ignoreUnknownKeys = true }, auth) { origin }
            server.enqueue(MockResponse().setBody("""{"enabled":true}"""))
            assertFalse(api.capabilities(origin, 7).hostControls)
            assertEquals("GET", server.takeRequest().method)
            server.enqueue(MockResponse().setBody("""{"enabled":true,"host_controls":true}"""))
            assertTrue(api.capabilities(origin, 7).hostControls)
            assertEquals("/conferences/capabilities", server.takeRequest().path)
            server.enqueue(MockResponse().setBody(dto))
            assertEquals("Team", api.get(id, origin, 7).title)
            val metadata = server.takeRequest()
            assertEquals("GET", metadata.method); assertEquals("/conferences/$id", metadata.path)
            assertEquals("Bearer session-token", metadata.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody(dto.dropLast(1) + """, "admission_locked":true}"""))
            assertTrue(api.settings(id, "Team", true, origin, 7).admissionLocked)
            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method); assertEquals("/conferences/$id/settings", patch.path)
            assertEquals("Bearer session-token", patch.getHeader("Authorization"))
            assertEquals("""{"title":"Team","admission_locked":true}""", patch.body.readUtf8())
            assertFalse(Json { ignoreUnknownKeys = true }.decodeFromString<ConferenceDto>(dto).admissionLocked)
        }
    }
    @Test fun `host settings cannot cross account or server selection during refresh`() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val auth = ConferenceAuth()
            val api = ConferenceApi(OkHttpClient(), Json, auth) { origin }
            auth.refresh = { auth.epoch++; "new-account-token" }
            assertFailsWith<kotlinx.coroutines.CancellationException> { api.settings(id, "Room", true, origin, 7) }
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            assertFailsWith<IllegalStateException> { api.settings(id, "Room", true, "https://other.example", 8) }
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            Unit
        }
    }
    @Test fun `create sends only hashed admission capability and join never sends media key`() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val auth = ConferenceAuth()
            val api = ConferenceApi(OkHttpClient(), Json { ignoreUnknownKeys = true }, auth) { origin }
            val invitation = ConferenceInvite.fresh("https://coco.example", id)
            server.enqueue(MockResponse().setBody(dto))
            api.create("Team", 30, ConferenceInvite.secretHash(invitation.secret), origin, 7)
            val create = server.takeRequest()
            assertEquals("/conferences", create.path)
            assertEquals("Bearer session-token", create.getHeader("Authorization"))
            val body = create.body.readUtf8()
            assertTrue(body.contains(ConferenceInvite.secretHash(invitation.secret)))
            assertFalse(body.contains(invitation.secret)); assertFalse(body.contains(invitation.mediaKey))
            server.enqueue(MockResponse().setBody("""{"conference":$dto,"livekit_url":"wss://sfu.example","participant_token":"media-token","participant_identity":"me:device","e2ee_required":true}"""))
            val grant = api.join(ConferenceInvite(origin, id, invitation.secret, invitation.mediaKey), 7)
            val join = server.takeRequest()
            assertEquals("/conferences/$id/join", join.path)
            val joinBody = join.body.readUtf8()
            assertTrue(joinBody.contains(invitation.secret)); assertFalse(joinBody.contains(invitation.mediaKey))
            assertFalse(grant.toString().contains("media-token"))
        }
    }
    @Test fun `server and session change during token refresh aborts before sending credentials`() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val auth = ConferenceAuth()
            val api = ConferenceApi(OkHttpClient(), Json, auth) { origin }
            auth.refresh = { auth.epoch++; "new-account-token" }
            assertFailsWith<kotlinx.coroutines.CancellationException> { api.create("Team", 30, "a".repeat(64), origin, 7) }
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            assertFailsWith<IllegalStateException> { api.create("Team", 30, "a".repeat(64), "https://other.example", 8) }
            Unit
        }
    }
    @Test fun `transient refresh failure retries while real logout remains cancellation`() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val auth = ConferenceAuth()
            val api = ConferenceApi(OkHttpClient(), Json, auth) { origin }
            auth.authState.value = AuthSessionState.LoggedIn(com.messenger.app.domain.model.AuthUser("me", "me", "Me"))
            auth.refresh = { null }
            val transient = assertFailsWith<com.messenger.app.data.api.AuthApiException.Network> {
                api.create("Team", 30, "a".repeat(64), origin, 7)
            }
            assertTrue(ConferenceRecoveryPolicy.retryable(transient))
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            auth.refresh = { auth.epoch++; auth.authState.value = AuthSessionState.LoggedOut; null }
            assertFailsWith<kotlinx.coroutines.CancellationException> { api.create("Team", 30, "a".repeat(64), origin, 7) }
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            Unit
        }
    }
}
