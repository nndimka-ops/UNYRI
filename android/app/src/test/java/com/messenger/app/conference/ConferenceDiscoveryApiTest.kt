package com.messenger.app.conference

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.KeysApi
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.AuthUser
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

internal class DiscoveryAuth(var user: String = "33333333-3333-4333-8333-333333333333", var device: String = "44444444-4444-4444-8444-444444444444") : AuthRepository {
    var epoch = 1
    override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser(user, "fixture", "Fixture")))
    override suspend fun register(username: String, password: String, displayName: String) = Result.success(Unit)
    override suspend fun login(username: String, password: String) = Result.success(Unit)
    override suspend fun logout() { epoch++; authState.value = AuthSessionState.LoggedOut }
    override suspend fun currentAccessToken() = "fixture-token"
    override fun currentDeviceId() = device
    override fun currentSessionEpoch() = epoch
    override suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T? = if (epochAtStart == epoch) block() else null
    override suspend fun clearRoom(block: suspend () -> Unit) = block()
}
class ConferenceDiscoveryApiTest {
    private val room = "11111111-1111-4111-8111-111111111111"
    private val requestId = "22222222-2222-4222-8222-222222222222"
    private val json = Json { ignoreUnknownKeys = true }
    private val dto get() = """{"id":"$room","title":"Room","owner_user_id":"owner","max_participants":30,"state":"active"}"""
    @Test fun `old servers default hidden invitation and receive no access fields`() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/'); val api = ConferenceApi(OkHttpClient(), json, DiscoveryAuth()) { origin }
            server.enqueue(MockResponse().setBody("{}")); val caps = api.capabilities(origin, 1); server.takeRequest()
            assertFalse(caps.roomDirectory || caps.roomAccessV2 || caps.roomKeyHandoff)
            server.enqueue(MockResponse().setBody(dto)); val result = api.create(ConferenceCreateOptions("Room"), "0".repeat(64), origin, 1, false)
            assertEquals("invite", result.accessMode); assertFalse(result.listed); assertEquals(1L, result.settingsRevision)
            assertEquals(setOf("title", "max_participants", "invite_secret_hash"), json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.keys)
            assertFails { api.create(ConferenceCreateOptions("Room", listed = true, access = ConferenceAccess.OPEN), "0".repeat(64), origin, 1, false) }
            Unit
        }
    }
    @Test fun `catalog join sends per-device request without invitation secret password or media key`() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/'); val api = ConferenceApi(OkHttpClient(), json, DiscoveryAuth()) { origin }
            server.enqueue(MockResponse().setBody("""{"conference":$dto,"livekit_url":"wss://sfu.invalid","participant_token":"opaque","participant_identity":"identity","e2ee_required":true}"""))
            val mediaKey = "fixture-media-key-only-local"
            api.join(ConferenceAdmission.Catalog(origin, room, requestId, mediaKey), 1, true)
            val body = server.takeRequest().body.readUtf8(); val obj = json.parseToJsonElement(body).jsonObject
            assertEquals(setOf("key_request_id", "supports_key_handoff"), obj.keys); assertFalse(body.contains(mediaKey))
        }
    }
    @Test fun `host password keep and set preserve omission and exact bytes`() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/'); val api = ConferenceApi(OkHttpClient(), json, DiscoveryAuth()) { origin }
            for ((action, password) in listOf("keep" to null, "set" to "  secret  ")) {
                server.enqueue(MockResponse().setBody(dto))
                api.settings(room, ConferenceSettingsEdit("Room", "Description", true, ConferenceAccess.PASSWORD, false, 8, action, password), origin, 1)
                val request = server.takeRequest(); assertEquals("PATCH", request.method)
                val obj = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(8L, obj.getValue("expected_revision").jsonPrimitive.long)
                assertEquals(password, obj["password"]?.jsonPrimitive?.content)
                assertEquals(password != null, "password" in obj)
            }
        }
    }
    @Test fun `password errors remain403 and settings conflicts keep machine code`() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/'); val api = ConferenceApi(OkHttpClient(), json, DiscoveryAuth()) { origin }
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"forbidden","message":"Conference password is incorrect."}}"""))
            val error = assertFailsWith<AuthApiException.Api> { api.requestKey(room, requestId, "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA", "wrong password", origin, 1) }
            assertEquals(403, error.httpStatus); assertEquals("forbidden", error.code)
        }
    }
    @Test fun `directory query and opaque cursor are encoded separately`() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/'); val api = ConferenceApi(OkHttpClient(), json, DiscoveryAuth()) { origin }
            server.enqueue(MockResponse().setBody("""{"items":[],"next_cursor":null}"""))
            api.directory("A&B / room", "opaque+/?&", origin, 1)
            val request = server.takeRequest(); assertEquals("A&B / room", request.requestUrl!!.queryParameter("q")); assertEquals("opaque+/?&", request.requestUrl!!.queryParameter("cursor")); assertEquals("20", request.requestUrl!!.queryParameter("limit"))
        }
    }
    @Test fun `conference password and invitation bodies never follow307or308 redirects`() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { other ->
            val origin = first.url("/").toString().trimEnd('/')
            val api = ConferenceApi(OkHttpClient(), json, DiscoveryAuth()) { origin }
            repeat(4) { other.enqueue(MockResponse().setBody(dto)) }
            val value = ConferenceInvite.fresh("https://fixture.invalid", room)
            for (status in listOf(307, 308)) {
                first.enqueue(MockResponse().setResponseCode(status).setHeader("Location", other.url("/collect")).setBody("{}"))
                assertFailsWith<AuthApiException> { api.create(ConferenceCreateOptions("Room", access = ConferenceAccess.PASSWORD, password = "do-not-redirect"), "0".repeat(64), origin, 1, true) }
                val create = first.takeRequest(); assertTrue(create.body.readUtf8().contains("do-not-redirect"))
                assertNull(other.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                first.enqueue(MockResponse().setResponseCode(status).setHeader("Location", other.url("/collect")).setBody("{}"))
                assertFailsWith<AuthApiException> { api.join(ConferenceInvite(origin, room, value.secret, value.mediaKey), 1) }
                val join = first.takeRequest(); assertTrue(join.body.readUtf8().contains(value.secret))
                assertNull(other.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            }
            Unit
        } }
    }
    @Test fun `exact target key endpoint has no fallback on404`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain -> paths += chain.request().url.encodedPath
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(404).message("Not Found").body(okhttp3.ResponseBody.create(null, "{}" )).build() }.build()
        assertFailsWith<AuthApiException.Api> { KeysApi(client, json, "https://fixture.invalid").getTargetBundle("fixture-token", room, requestId) }
        assertEquals(listOf("/keys/bundles/$room/$requestId"), paths)
    }
}
