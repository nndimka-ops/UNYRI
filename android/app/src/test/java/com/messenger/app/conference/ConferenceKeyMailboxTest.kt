package com.messenger.app.conference

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.Base64
import kotlin.test.*

class ConferenceKeyMailboxTest {
    private val origin = "https://conference-test.invalid"
    private val room = "11111111-1111-4111-8111-111111111111"
    private val provider = "55555555-5555-4555-8555-555555555555"
    private val providerDevice = "66666666-6666-4666-8666-666666666666"
    private val identity = "$provider:$providerDevice:77777777-7777-4777-8777-777777777777"
    private val now = 1800000000L
    private val key = "ISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0-P0A"
    private val nonce = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"
    private val json = Json { ignoreUnknownKeys = true }
    private fun request(id: String, nonce: String, state: String = "pending", envelope: ConferenceKeyEnvelope? = null) = ConferenceKeyRequest(id, room,
        "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444", provider, providerDevice, identity, 1, nonce, 3, "2027-01-15T08:01:00Z", state, envelope)
    private fun serialized(r: ConferenceKeyRequest) = json.encodeToString(ConferenceKeyRequest.serializer(), r)
    private class Crypto : ConferenceMailboxCrypto {
        var trusted = true
        var identity = "approved-fixture-key"
        override suspend fun review(epoch: Int, user: String, device: String, token: String) = com.messenger.app.data.signal.DeviceTrustSnapshot(user, device, 1, identity, trusted, "fixture-safety-number")
        override suspend fun approve(epoch: Int, snapshot: com.messenger.app.data.signal.DeviceTrustSnapshot) { check(snapshot.identity == identity); trusted = true }
        var encrypts = 0; var decrypts = 0
        var plaintext = byteArrayOf()
        var afterDecrypt: suspend () -> Unit = {}
        override suspend fun encrypt(epoch: Int, user: String, device: String, token: String, plaintext: ByteArray): ConferenceKeyEnvelope { encrypts++; this.plaintext = plaintext.copyOf(); return ConferenceKeyEnvelope(device, 1, Base64.getEncoder().encodeToString("opaque".toByteArray())) }
        override suspend fun decrypt(epoch: Int, user: String, device: String, token: String, envelope: ConferenceKeyEnvelope): ByteArray { decrypts++; afterDecrypt(); return plaintext.copyOf() }
    }
    private fun api(auth: DiscoveryAuth, handle: (okhttp3.Request) -> Pair<Int, String>): ConferenceApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = handle(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
        }.build()
        return ConferenceApi(client, json, auth) { origin }
    }
    private fun body(request: okhttp3.Request): JsonObject { val b = okio.Buffer(); request.body!!.writeTo(b); return json.parseToJsonElement(b.readUtf8()).jsonObject }
    @Test fun `first contact provider waits for exact key approval and resumes the same requester envelope`() = runBlocking {
        val auth = DiscoveryAuth(); val crypto = Crypto().apply { trusted = false }; var posts = 0
        val api = api(auth) { req ->
            val b = body(req); posts++
            val r = request(b.getValue("request_id").jsonPrimitive.content, b.getValue("nonce").jsonPrimitive.content)
            crypto.plaintext = ConferenceKeyPayload.encode(ConferenceKeyBinding(origin,r,now),key)
            200 to serialized(request(r.id,r.nonce,"ready",ConferenceKeyEnvelope(auth.device,1,Base64.getEncoder().encodeToString(byteArrayOf(1)))))
        }
        val mailbox = ConferenceKeyMailbox(api,auth,crypto,{now},{origin})
        val receiving = async { mailbox.receive(room,origin,1,null) }
        val review = withTimeout(5_000) { mailbox.trustReview.first { it != null }!! }
        assertEquals(provider, review.snapshot.userId); assertEquals(providerDevice, review.snapshot.deviceId)
        assertEquals(0,crypto.decrypts); assertEquals(1, posts)
        mailbox.decideTrust(review,true)
        assertEquals(key,receiving.await().mediaKey); assertEquals(1,posts); assertEquals(1,crypto.decrypts)
    }
    @Test fun `cached provider envelope is withheld after identity loses trust until exact approval`() = runBlocking {
        val auth = DiscoveryAuth(provider,providerDevice); val crypto = Crypto()
        val r = request("22222222-2222-4222-8222-222222222222",nonce); var fulfilled = 0
        val api = api(auth) { req -> if(req.method == "GET") 200 to json.encodeToString(ConferenceKeyRequests.serializer(),ConferenceKeyRequests(listOf(r)))
            else { fulfilled++; throw IOException("fixture lost acknowledgement") } }
        val mailbox = ConferenceKeyMailbox(api,auth,crypto,{now},{origin})
        val admission = ConferenceAdmission.Catalog(origin,room,"88888888-8888-4888-8888-888888888888",key)
        val firstRelease = launch { mailbox.provide(admission,identity,1){true} }
        val initialReview = withTimeout(5_000) { mailbox.trustReview.first { it != null }!! }
        assertTrue(initialReview.snapshot.trusted); assertEquals(0,fulfilled); assertEquals(0,crypto.encrypts)
        mailbox.decideTrust(initialReview,true); firstRelease.join(); assertEquals(1,fulfilled)
        crypto.trusted = false; crypto.identity = "changed-fixture-key"
        val retry = launch { mailbox.provide(admission,identity,1){true} }
        val review = withTimeout(5_000) { mailbox.trustReview.first { it != null }!! }
        assertEquals(1,fulfilled); assertEquals(1,crypto.encrypts)
        mailbox.decideTrust(review,false); retry.join()
        assertEquals(1,fulfilled)
        mailbox.provide(admission,identity,1){true}
        assertNull(mailbox.trustReview.value); assertEquals(1,fulfilled)
    }
    @Test fun `lost request acknowledgement recovers exact same ID and decrypts once`() = runBlocking {
        val auth = DiscoveryAuth(); val crypto = Crypto(); var posted: ConferenceKeyRequest? = null; var posts = 0; var deletes = 0
        val api = api(auth) { req -> when (req.method) {
            "POST" -> { posts++; val b = body(req); val pending = request(b.getValue("request_id").jsonPrimitive.content, b.getValue("nonce").jsonPrimitive.content)
                crypto.plaintext = ConferenceKeyPayload.encode(ConferenceKeyBinding(origin, pending, now), key)
                posted = request(pending.id, pending.nonce, "ready", ConferenceKeyEnvelope(auth.device, 1, Base64.getEncoder().encodeToString("opaque".toByteArray())))
                throw IOException("Acknowledgement lost after commit") }
            "GET" -> 200 to serialized(posted!!)
            else -> { deletes++; 200 to "{}" }
        } }
        val mailbox = ConferenceKeyMailbox(api, auth, crypto, { now }, { origin })
        val admission = mailbox.receive(room, origin, 1, null)
        assertEquals(1, posts); assertEquals(posted!!.id, admission.requestId); assertEquals(key, admission.mediaKey); assertEquals(1, crypto.decrypts); assertEquals(0, deletes)
        assertFalse(admission.toString().contains(key))
    }
    @Test fun `cancel after noncancellable decrypt never returns an admission and cancels the server request`() = runBlocking {
        val auth = DiscoveryAuth(); val crypto = Crypto(); val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); var deletes = 0; var admitted = false
        crypto.afterDecrypt = { started.complete(Unit); withContext(NonCancellable) { finish.await() } }
        val api = api(auth) { req -> if (req.method == "DELETE") { deletes++; 200 to "{}" } else {
            val b = body(req); val r = request(b.getValue("request_id").jsonPrimitive.content, b.getValue("nonce").jsonPrimitive.content)
            crypto.plaintext = ConferenceKeyPayload.encode(ConferenceKeyBinding(origin, r, now), key)
            200 to serialized(request(r.id, r.nonce, "ready", ConferenceKeyEnvelope(auth.device, 1, Base64.getEncoder().encodeToString("opaque".toByteArray()))))
        } }
        val mailbox = ConferenceKeyMailbox(api, auth, crypto, { now }, { origin })
        val job = launch { mailbox.receive(room, origin, 1, null); admitted = true }
        started.await(); job.cancel(); finish.complete(Unit); job.join()
        assertFalse(admitted); assertEquals(1, crypto.decrypts); assertEquals(1, deletes)
    }
    @Test fun `cancellation after decrypt completed still cancels ready request before handing off a key`() = runBlocking {
        val auth = DiscoveryAuth(); val crypto = Crypto(); var deletes = 0; var clockReads = 0; var consumer: Job? = null
        val api = api(auth) { req -> if (req.method == "DELETE") { deletes++; 200 to "{}" } else {
            val b = body(req); val r = request(b.getValue("request_id").jsonPrimitive.content, b.getValue("nonce").jsonPrimitive.content)
            crypto.plaintext = ConferenceKeyPayload.encode(ConferenceKeyBinding(origin, r, now), key)
            200 to serialized(request(r.id, r.nonce, "ready", ConferenceKeyEnvelope(auth.device, 1, Base64.getEncoder().encodeToString("opaque".toByteArray()))))
        } }
        val mailbox = ConferenceKeyMailbox(api, auth, crypto, { clockReads++; if (clockReads == 2) consumer!!.cancel(); now }, { origin })
        var returned = false
        consumer = launch { mailbox.receive(room, origin, 1, null); returned = true }
        consumer!!.join()
        assertFalse(returned); assertEquals(1, crypto.decrypts); assertEquals(1, deletes)
    }
    @Test fun `account replacement during decrypt cannot admit or write through replacement token`() = runBlocking {
        val auth = DiscoveryAuth(); val crypto = Crypto(); var deletes = 0
        val api = api(auth) { req -> if (req.method == "DELETE") { deletes++; 200 to "{}" } else {
            val b = body(req); val r = request(b.getValue("request_id").jsonPrimitive.content, b.getValue("nonce").jsonPrimitive.content)
            crypto.plaintext = ConferenceKeyPayload.encode(ConferenceKeyBinding(origin, r, now), key)
            200 to serialized(request(r.id, r.nonce, "ready", ConferenceKeyEnvelope(auth.device, 1, Base64.getEncoder().encodeToString("opaque".toByteArray()))))
        } }
        crypto.afterDecrypt = { auth.epoch++ }
        assertFailsWith<CancellationException> { ConferenceKeyMailbox(api, auth, crypto, { now }, { origin }).receive(room, origin, 1, null) }
        assertEquals(1, crypto.decrypts); assertEquals(0, deletes)
    }
    @Test fun `provider reuses exact ciphertext after transient fulfill error without another ratchet step`() = runBlocking {
        val auth = DiscoveryAuth(provider, providerDevice); val crypto = Crypto(); val r = request("22222222-2222-4222-8222-222222222222", nonce)
        val fulfillBodies = mutableListOf<String>(); var attempt = 0
        val api = api(auth) { req -> if (req.method == "GET") 200 to json.encodeToString(ConferenceKeyRequests.serializer(), ConferenceKeyRequests(listOf(r)))
            else { fulfillBodies += body(req).toString(); attempt++; if (attempt == 1) throw IOException("Lost response"); 200 to serialized(r) } }
        val mailbox = ConferenceKeyMailbox(api, auth, crypto, { now }, { origin })
        val admission = ConferenceAdmission.Catalog(origin, room, "88888888-8888-4888-8888-888888888888", key)
        val firstRelease = launch { mailbox.provide(admission, identity, 1) { true } }
        val review = withTimeout(5_000) { mailbox.trustReview.first { it != null }!! }
        assertTrue(review.snapshot.trusted); assertEquals(0,crypto.encrypts); assertTrue(fulfillBodies.isEmpty())
        mailbox.decideTrust(review,true); firstRelease.join()
        mailbox.provide(admission, identity, 1) { true }
        assertEquals(1, crypto.encrypts); assertEquals(2, fulfillBodies.size); assertEquals(fulfillBodies[0], fulfillBodies[1])
        assertEquals(key, ConferenceKeyPayload.decode(crypto.plaintext.toString(Charsets.UTF_8), ConferenceKeyBinding(origin, r, now)))
        assertFalse(crypto.plaintext.toString(Charsets.UTF_8).contains("invite_secret"))
    }
    @Test fun `fresh request waits for canceled receive or ready credential deletion acknowledgement`() = runBlocking {
        for (cancelledReceive in listOf(true, false)) {
            val events = mutableListOf<String>(); val deleting = CompletableDeferred<Unit>(); val allowDelete = CompletableDeferred<Unit>()
            suspend fun delete() { events += "DELETE started"; deleting.complete(Unit); allowDelete.await(); events += "DELETE acknowledged" }
            val old = if (cancelledReceive) launch { try { awaitCancellation() } finally { withContext(NonCancellable) { delete() } } } else null
            yield(); old?.cancel()
            val cleanup = ConferenceRequestCleanup(this)
            cleanup.enqueue(old) { if (!cancelledReceive) delete() }
            val retry = launch { cleanup.awaitPrevious(); events += "new POST" }
            deleting.await(); yield()
            assertFalse("new POST" in events, "The new request must not race the prior exact DELETE")
            allowDelete.complete(Unit); retry.join()
            assertEquals(listOf("DELETE started", "DELETE acknowledged", "new POST"), events)
        }
    }
    @Test fun `stale provider admission is filtered before encryption and never fulfilled`() = runBlocking {
        val auth = DiscoveryAuth(provider, providerDevice); val crypto = Crypto(); var posts = 0
        val r = request("22222222-2222-4222-8222-222222222222", nonce)
        val api = api(auth) { req -> if (req.method == "POST") { posts++; 200 to serialized(r) } else 200 to json.encodeToString(ConferenceKeyRequests.serializer(), ConferenceKeyRequests(listOf(r))) }
        val mailbox = ConferenceKeyMailbox(api, auth, crypto, { now }, { origin })
        mailbox.provide(ConferenceAdmission.Catalog(origin, room, r.id, key), "$provider:$providerDevice:99999999-9999-4999-8999-999999999999", 1) { true }
        assertEquals(0, crypto.encrypts); assertEquals(0, posts)
    }
}
