package com.messenger.app.conference

import com.messenger.app.data.api.AuthApiException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.time.Instant
import kotlin.test.*

class ConferenceJoinApprovalTest {
    private val origin = "https://conference-test.invalid"
    private val room = "11111111-1111-4111-8111-111111111111"
    private val json = Json { ignoreUnknownKeys = true }
    private fun id(req: Request): String {
        val buffer = okio.Buffer(); req.body!!.writeTo(buffer)
        return json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("request_id").jsonPrimitive.content
    }
    private fun dto(auth: DiscoveryAuth, id: String, status: String, extra: Map<String, String> = emptyMap()): String = buildJsonObject {
        put("request_id", id); put("conference_id", room); put("requester_user_id", auth.user); put("requester_device_id", auth.device)
        put("display_name", "Guest"); put("username", "guest"); put("status", status); put("expires_at", "2030-01-01T00:00:00Z")
        extra.forEach { (key, value) -> put(key, value) }
    }.toString()
    private fun api(auth: DiscoveryAuth, handle: (Request) -> Pair<Int, String>): ConferenceApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = handle(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
        }.build()
        return ConferenceApi(client, json, auth) { origin }
    }
    @Test fun `accepted or disabled gate never deletes a valid grant`() = runBlocking {
        for (status in listOf("approved", "consumed", "not_required")) {
            val auth = DiscoveryAuth(); var calls = 0
            val api = api(auth) { req -> calls++; assertEquals("POST", req.method); 200 to dto(auth, id(req), status) }
            api.awaitJoinApproval(room, "invitation", " password ", origin, 1) { fail("Unexpected wait") }
            assertEquals(1, calls)
        }
    }
    @Test fun `lost POST response recovers exact same request ID`() = runBlocking {
        val auth = DiscoveryAuth(); var saved = ""; var posts = 0; var gets = 0
        val api = api(auth) { req ->
            if (req.method == "POST") { posts++; saved = id(req); throw IOException("lost acknowledgement") }
            assertEquals("GET", req.method); assertTrue(req.url.encodedPath.endsWith(saved)); gets++
            200 to dto(auth, saved, "approved")
        }
        api.awaitJoinApproval(room, null, null, origin, 1) {}
        assertEquals(1, posts); assertEquals(1, gets)
    }
    @Test fun `pending shows waiting before approval without another POST`() = runBlocking {
        val auth = DiscoveryAuth(); var saved = ""; var posts = 0; var reads = 0; var waits = 0
        val api = api(auth) { req -> when (req.method) {
            "POST" -> { posts++; saved = id(req); 200 to dto(auth, saved, "pending") }
            else -> { assertEquals("GET", req.method); reads++; 200 to dto(auth, saved, "approved") }
        } }
        api.awaitJoinApproval(room, null, null, origin, 1) { waits++ }
        assertEquals(1, posts); assertEquals(1, reads); assertEquals(1, waits)
    }
    @Test fun `decline and cooldown retain retry deadline and delete exact request`() = runBlocking {
        for (status in listOf("denied", "cooldown")) {
            val auth = DiscoveryAuth(); var saved = ""; var deletes = 0
            val api = api(auth) { req -> if (req.method == "DELETE") {
                deletes++; assertTrue(req.url.encodedPath.endsWith(saved)); 200 to "{}"
            } else { saved = id(req); 200 to dto(auth, saved, status, mapOf("retry_at" to "2030-01-01T00:01:00Z")) } }
            val failure = assertFailsWith<ConferenceJoinApprovalFailure> { api.awaitJoinApproval(room, null, null, origin, 1) {} }
            assertEquals(ConferenceJoinApprovalFailure.Reason.DENIED, failure.reason)
            assertEquals(Instant.parse("2030-01-01T00:01:00Z").epochSecond, failure.retryAt); assertEquals(1, deletes)
        }
    }
    @Test fun `cancel promptly removes pending ID and never admits`() = runBlocking {
        val auth = DiscoveryAuth(); var saved = ""; var deletes = 0; var returned = false; val waiting = CompletableDeferred<Unit>()
        val api = api(auth) { req -> if (req.method == "DELETE") {
            deletes++; assertTrue(req.url.encodedPath.endsWith(saved)); 200 to "{}"
        } else { saved = id(req); 200 to dto(auth, saved, "pending") } }
        val job = launch { api.awaitJoinApproval(room, null, null, origin, 1) { waiting.complete(Unit) }; returned = true }
        withTimeout(3_000) { waiting.await() }; job.cancelAndJoin()
        assertFalse(returned); assertEquals(1, deletes)
    }
    @Test fun `changed account cannot cancel or read through replacement session`() = runBlocking {
        val auth = DiscoveryAuth(); val waiting = CompletableDeferred<Unit>(); var calls = 0
        val api = api(auth) { req -> calls++; 200 to dto(auth, id(req), "pending") }
        val job = launch { api.awaitJoinApproval(room, null, null, origin, 1) { waiting.complete(Unit) } }
        withTimeout(3_000) { waiting.await() }; auth.epoch++; job.cancelAndJoin()
        assertEquals(1, calls)
    }
    @Test fun `wrong target or status never returns an admission`() = runBlocking {
        for (field in listOf("request_id", "conference_id", "requester_user_id", "requester_device_id", "status")) {
            val auth = DiscoveryAuth(); var deletes = 0
            val api = api(auth) { req -> if (req.method == "DELETE") { deletes++; 200 to "{}" }
                else 200 to dto(auth, id(req), "approved", mapOf(field to if (field == "status") "unknown" else "99999999-9999-4999-8999-999999999999")) }
            assertFailsWith<IllegalArgumentException> { api.awaitJoinApproval(room, null, null, origin, 1) {} }
            assertEquals(1, deletes)
        }
    }
    @Test fun `429 backs off and remains cancellable`() = runBlocking {
        val auth = DiscoveryAuth(); val observed = CompletableDeferred<Unit>(); var posts = 0; var deletes = 0
        val api = api(auth) { req -> when (req.method) {
            "POST" -> { posts++; observed.complete(Unit); 429 to "{}" }
            "GET" -> 404 to "{}"
            else -> { deletes++; 200 to "{}" }
        } }
        val job = launch { api.awaitJoinApproval(room, null, null, origin, 1) {} }
        withTimeout(3_000) { observed.await() }; delay(120); assertEquals(1, posts)
        job.cancelAndJoin(); assertEquals(1, deletes)
    }
    @Test fun `429 on recovery GET also blocks further POST retries`() = runBlocking {
        val auth = DiscoveryAuth(); val limited = CompletableDeferred<Unit>(); var posts = 0
        val api = api(auth) { req -> when (req.method) {
            "POST" -> { posts++; throw IOException("lost acknowledgement") }
            "GET" -> { limited.complete(Unit); 429 to "{}" }
            else -> 200 to "{}"
        } }
        val job = launch { api.awaitJoinApproval(room, null, null, origin, 1) {} }
        withTimeout(3_000) { limited.await() }; delay(3_200)
        assertEquals(1, posts, "The GET rate limit must override the shorter network retry delay")
        job.cancelAndJoin()
    }
    @Test fun `only exact approval conflict activates the gate`() {
        assertTrue(needsConferenceApproval(AuthApiException.Api("conflict", "Conference creator approval is required.", 409)))
        assertFalse(needsConferenceApproval(AuthApiException.Api("conflict", "Conference admission is locked.", 409)))
    }
}
