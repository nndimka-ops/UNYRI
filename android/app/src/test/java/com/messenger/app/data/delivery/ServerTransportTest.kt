package com.messenger.app.data.delivery

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.MessagesApi
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.message.MessageOutboxProcessor
import com.messenger.app.data.sync.InMemoryMessageDao
import com.messenger.app.domain.delivery.DeliveryAcceptance
import com.messenger.app.domain.delivery.DeliveryContext
import com.messenger.app.domain.delivery.DeliveryEngine
import com.messenger.app.domain.delivery.DeviceEnvelope
import com.messenger.app.domain.delivery.PreparedMessage
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ServerTransportTest {
    @Test fun preparedAuthDoesNotRefreshInsideAccountWrite() = runTest {
        val gate = com.messenger.app.data.auth.SessionWriteGate { 7 }
        val adapter = transport(token = { error("must resolve auth before the gate") }) { request ->
            assertEquals("Bearer prepared-token", request.header("Authorization"))
            response(request)
        }
        gate.write(7) {
            withPreparedServerAuth("prepared-token", 7) {
                adapter.send(message, context)
            }
        }
        Unit
    }

    @Test fun preparedAuthFromAnotherSessionCannotSend() = runTest {
        val adapter = transport(token = { error("must not refresh") }) { error("must not send") }
        try {
            withPreparedServerAuth("old-token", 6) { adapter.send(message, context) }
            fail("expected cancellation")
        } catch (_: CancellationException) { }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val context = DeliveryContext(7)
    private val message = PreparedMessage(
        conversationId = "chat-1", clientMessageId = "client-1",
        envelopes = listOf(DeviceEnvelope("recipient-device", 3, "YWJj"), DeviceEnvelope("own-other-device", 2, "ZGVm")),
    )
    private val responseJson = """{"id":"server-1","chat_id":"chat-1","client_message_id":"client-1","sender_id":"sender-1","sequence":42,"created_at":"2026-09-07T12:00:00Z","status":"sent"}"""

    private fun transport(
        token: suspend () -> String? = { "test-access-token" },
        epoch: () -> Int = { 7 },
        handle: (Request) -> Response,
    ): ServerTransport {
        val client = OkHttpClient.Builder().addInterceptor { chain -> handle(chain.request()) }.build()
        return ServerTransport(MessagesApi(client, json, "https://unit.invalid"), token, epoch)
    }

    private fun response(request: Request, code: Int = 200, body: String = responseJson) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody()).build()

    private fun body(request: Request) = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject

    @Test fun textPreservesHttpContractAndPerDeviceCiphertext() = runTest {
        var calls = 0
        val adapter = transport { request ->
            calls++
            assertEquals("POST", request.method)
            assertEquals("/chats/chat-1/messages", request.url.encodedPath)
            assertEquals("Bearer test-access-token", request.header("Authorization"))
            val data = body(request)
            assertEquals("client-1", data["client_message_id"]!!.jsonPrimitive.content)
            val envelopes = data["envelopes"]!!.jsonArray
            assertEquals(2, envelopes.size)
            assertEquals("recipient-device", envelopes[0].jsonObject["recipient_device_id"]!!.jsonPrimitive.content)
            assertEquals("3", envelopes[0].jsonObject["message_type"]!!.jsonPrimitive.content)
            assertEquals("YWJj", envelopes[0].jsonObject["ciphertext"]!!.jsonPrimitive.content)
            assertEquals("own-other-device", envelopes[1].jsonObject["recipient_device_id"]!!.jsonPrimitive.content)
            assertEquals("ZGVm", envelopes[1].jsonObject["ciphertext"]!!.jsonPrimitive.content)
            assertFalse(data.containsKey("protocol_version")) // no wire migration in this stage
            assertFalse(data.containsKey("plaintext"))
            response(request)
        }
        val accepted = DeliveryEngine(adapter).attempt(message, context) as DeliveryAcceptance.ServerAccepted
        assertEquals(1, calls)
        assertEquals("server-1", accepted.receipt.id)
        assertEquals("chat-1", accepted.receipt.chatId)
        assertEquals("client-1", accepted.receipt.clientMessageId)
        assertEquals("sender-1", accepted.receipt.senderId)
        assertEquals(42L, accepted.receipt.sequence)
        assertEquals("sent", accepted.receipt.status)
        assertEquals("2026-09-07T12:00:00Z", accepted.receipt.createdAt)
    }

    @Test fun attachmentAndReplyBindingsSurvive() = runTest {
        val adapter = transport { request ->
            val data = body(request)
            assertEquals(listOf("attachment-1", "attachment-2"), data["attachment_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("reply-server-id", data["target_message_id"]!!.jsonPrimitive.content)
            response(request)
        }
        adapter.send(message.copy(attachmentIds = listOf("attachment-1", "attachment-2"), targetMessageId = "reply-server-id"), context)
    }

    @Test fun lifecycleDiscriminatorsAndTargetsAreNotDropped() = runTest {
        for (kind in listOf("edit", "delete", "reaction")) {
            val adapter = transport { request ->
                assertEquals(kind, body(request)["kind"]!!.jsonPrimitive.content)
                assertEquals("target-1", body(request)["target_message_id"]!!.jsonPrimitive.content)
                response(request)
            }
            adapter.send(message.copy(kind = kind, targetMessageId = "target-1"), context)
        }
    }

    @Test fun savedMessagesMayHaveNoOtherDeviceEnvelopes() = runTest {
        val adapter = transport { request ->
            assertEquals(0, body(request)["envelopes"]!!.jsonArray.size)
            response(request)
        }
        adapter.send(message.copy(envelopes = emptyList()), context)
    }

    @Test fun missingTokenIsTransientAndDoesNotSend() = runTest {
        val adapter = transport(token = { null }) { error("HTTP must not run") }
        try { adapter.send(message, context); fail("expected token failure") }
        catch (e: AuthApiException.Network) { assertTrue(e.message!!.contains("no access token")) }
    }

    @Test fun apiErrorCodeReachesExistingOutboxPolicy() = runTest {
        val adapter = transport { request -> response(request, 401, """{"error":{"code":"session_revoked","message":"revoked"}}""") }
        try { DeliveryEngine(adapter).attempt(message, context); fail("expected API failure") }
        catch (e: AuthApiException.Api) { assertEquals("session_revoked", e.code) }
    }

    @Test fun responseLostAfterAcceptanceUsesSameIdOnExplicitRetry() = runTest {
        var calls = 0
        val storedIds = mutableSetOf<String>()
        val sentBodies = mutableListOf<String>()
        val adapter = transport { request ->
            calls++
            val data = body(request)
            storedIds += data["client_message_id"]!!.jsonPrimitive.content
            sentBodies += data.toString()
            if (calls == 1) throw IOException("response lost after simulated server acceptance")
            response(request)
        }
        val engine = DeliveryEngine(adapter)
        try { engine.attempt(message, context); fail("expected lost response") }
        catch (_: AuthApiException.Network) { }
        assertEquals(1, calls) // Engine did not create another retry loop
        engine.attempt(message, context)
        assertEquals(2, calls)
        assertEquals(setOf("client-1"), storedIds)
        assertEquals(sentBodies[0], sentBodies[1])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun existingOutboxRetriesThroughEngineAndReconcilesOnlyOnce() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(MessageEntity("client-1", null, "chat-1", "sender-1", "local text", null,
            "2026-09-07T12:00:00Z", "pending"))
        var calls = 0
        val storedIds = mutableSetOf<String>()
        val adapter = transport { request ->
            calls++
            storedIds += body(request)["client_message_id"]!!.jsonPrimitive.content
            if (calls == 1) throw IOException("response lost after commit")
            response(request)
        }
        val engine = DeliveryEngine(adapter)
        var reconciliations = 0
        val outbox = MessageOutboxProcessor(dao, backgroundScope, { testScheduler.currentTime }, { "sender-1" }) { row ->
            val accepted = engine.attempt(message.copy(clientMessageId = row.clientMessageId), context)
            assertTrue(accepted is DeliveryAcceptance.ServerAccepted)
            dao.updateStatus(row.clientMessageId, "sent")
            reconciliations++
        }
        outbox.flush()
        assertEquals("pending", dao.findByClientMessageId("client-1")!!.status)
        assertEquals(1, calls)
        assertEquals(1, dao.findByClientMessageId("client-1")!!.retryCount)
        advanceTimeBy(2_000)
        runCurrent()
        // Real IO dispatcher can resume after virtual time; flush waits on the same mutex.
        outbox.flush()
        assertEquals("sent", dao.findByClientMessageId("client-1")!!.status)
        assertEquals(2, calls)
        assertEquals(1, reconciliations)
        assertEquals(setOf("client-1"), storedIds)
        outbox.flush()
        assertEquals(2, calls)
    }

    @Test fun sessionChangeBeforeTokenPreventsAnyWork() = runTest {
        val adapter = transport(token = { error("token must not be read") }, epoch = { 8 }) { error("HTTP must not run") }
        try { adapter.send(message, context); fail("expected cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun sessionChangeDuringTokenRefreshPreventsSend() = runTest {
        var epoch = 7
        val adapter = transport(token = { epoch = 8; "other-account-token" }, epoch = { epoch }) { error("HTTP must not run") }
        try { adapter.send(message, context); fail("expected cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun sessionChangeDuringHttpDiscardsAcceptance() = runTest {
        var epoch = 7
        val adapter = transport(epoch = { epoch }) { request -> epoch = 8; response(request) }
        try { adapter.send(message, context); fail("expected cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun cancellationIsNotConvertedToNetworkFailure() = runTest {
        val cancelled = CancellationException("cancel test")
        val adapter = transport(token = { throw cancelled }) { error("HTTP must not run") }
        try { DeliveryEngine(adapter).attempt(message, context); fail("expected cancellation") }
        catch (e: CancellationException) { assertSame(cancelled, e) }
    }
}
