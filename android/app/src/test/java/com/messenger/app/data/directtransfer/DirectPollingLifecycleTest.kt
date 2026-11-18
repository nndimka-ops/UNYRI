package com.messenger.app.data.directtransfer

import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DirectPollingLifecycleTest {
    private val json = DirectFileOffer.codec
    private fun record(): DirectTransferRecord {
        val root = json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/direct-file-json-nonce-v1.json"))
            .bufferedReader().use { it.readText() }).jsonObject
        val offer = json.decodeFromJsonElement(DirectFileOffer.serializer(), root.getValue("offer")).copy(expiresAt = 100_000)
        return DirectTransferRecord(offer, "eeeeeeee-5555-4555-8555-eeeeeeeeeee5", "sender", registered = true,
            state = "waiting", reason = "queued", queue = DirectFileQueueState(1, 0))
    }
    private fun pending(record: DirectTransferRecord): FrozenDirectSignal {
        val id = "fedcfedc-9999-4999-8999-fedcfedcfed9"
        val peer = "dddddddd-4444-4444-8444-ddddddddddd4"
        val control = DirectFileControl(transferId = record.offer.transferId, chatId = record.chatId,
            offerClientMessageId = record.offer.offerClientMessageId, clientSignalId = id,
            senderUserId = record.offer.senderUserId, senderDeviceId = record.offer.senderDeviceId,
            recipientUserId = record.offer.recipientUserId, recipientDeviceId = peer,
            offerNonce = record.offer.offerNonce, attemptId = id, type = "complete_ack")
        return FrozenDirectSignal(DirectSignalEnvelope(clientSignalId = id, recipientDeviceId = peer, messageType = 1, ciphertext = "fixture"), control)
    }
    private class Journal(var current: DirectTransferRecord) {
        var durable: String? = null
        var stops = 0
        var failWrite = false
        suspend fun close(id: String, expired: Boolean) = closeDirectMailbox(id, expired, { stops++ }) { _, change ->
            val next = DirectManualJournalPolicy.transition(current, change(current))
            if (failWrite) throw DirectStorageError("storage_error")
            durable = DirectFileOffer.codec.encodeToString(DirectTransferRecord.serializer(), next)
            current = next
        }
    }
    private fun api(server: MockWebServer): DirectTransferApi {
        val auth = object : AuthRepository by FakeAuthRepository() {
            override fun currentSessionEpoch() = 1
            override suspend fun currentAccessToken() = "fixture-token"
        }
        return DirectTransferApi(OkHttpClient(), auth, server.url("/").toString().trimEnd('/'))
    }
    private suspend fun operation(journal: Journal, nowSeconds: Long = 1000, block: suspend () -> Unit) = runDirectMailboxOperation(
        journal.current.offer.transferId, { journal.current }, { nowSeconds }, { false }, journal::close, block)

    @Test fun `404 and 410 from signals metadata control and close persist final closure across restart`() = runBlocking {
        for (status in listOf(404, 410)) for (endpoint in listOf("signals", "metadata", "control", "close", "claim")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("{}"))
                val before = record().copy(completed = true, state = "completed", closePending = true)
                val frame = pending(before)
                val journal = Journal(before.copy(pending = listOf(frame), processingSignal = frame.envelope))
                val api = api(server)
                val id = before.offer.transferId
                operation(journal) {
                    when (endpoint) {
                        "signals" -> api.signals(id, 0, 1)
                        "metadata" -> api.get(id, 1)
                        "control" -> api.post(id, frame.envelope, 1)
                        "claim" -> api.claim(id, 1)
                        else -> api.close(id, 1)
                    }
                }
                val restored = assertNotNull(DirectTransferCoordinator.decodeRecord(assertNotNull(journal.durable))).afterProcessRestart(2000)
                assertTrue(restored.mailboxClosed, "$status from $endpoint")
                assertTrue(restored.completed); assertEquals("completed", restored.state)
                assertTrue(restored.pending.isEmpty()); assertFalse(restored.closePending); assertNull(restored.processingSignal)
                assertNull(DirectPollRuntime().deadline(restored))
                operation(journal) { fail("Closed journal must not execute HTTP") }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun `local expiry closes completed cancelled and unfinished journals without HTTP`() = runBlocking {
        for (before in listOf(record(), record().copy(state = "cancelled"), record().copy(completed = true, state = "completed"))) {
            val journal = Journal(before.copy(pending = listOf(pending(before)), closePending = true))
            operation(journal, nowSeconds = before.offer.expiresAt) { fail("Expired mailbox must never use HTTP") }
            val restored = assertNotNull(DirectTransferCoordinator.decodeRecord(assertNotNull(journal.durable)))
            assertTrue(restored.mailboxClosed); assertTrue(restored.pending.isEmpty())
            assertEquals(if (before.terminal) before.state else "expired", restored.state)
            assertEquals(before.completed, restored.completed)
            assertNull(DirectPollRuntime().deadline(restored))
        }
    }

    @Test fun `failed closure journal never publishes a false closed state`(): Unit = runBlocking {
        val journal = Journal(record().copy(state = "cancelled", closePending = true)).apply { failWrite = true }
        assertFailsWith<DirectStorageError> { operation(journal) { throw DirectTransferApiError(410) } }
        assertFalse(journal.current.mailboxClosed); assertNull(journal.durable)
        journal.failWrite = false
        operation(journal) { throw DirectTransferApiError(410) }
        assertTrue(journal.current.mailboxClosed); assertNotNull(journal.durable)
    }

    @Test fun `terminal reads back off even when successful then stop on confirmation or expiry`() {
        val runtime = DirectPollRuntime()
        var terminal = record().copy(state = "cancelled", closePending = true)
        var now = 0L
        for (delay in listOf(2000L, 4000L, 8000L, 16000L, 32000L, 64000L, 128000L, 256000L, 300000L, 300000L)) {
            runtime.settled(terminal, DirectPollPolicy.plan(terminal, false, false), now, now, 1000)
            assertEquals(now + delay, runtime.deadline(terminal))
            now += delay
        }
        terminal = terminal.withClosedMailbox()
        runtime.settled(terminal, DirectPollPolicy.plan(terminal, false, false), now, now, 1000)
        runtime.wake(terminal.offer.transferId)
        assertNull(runtime.deadline(terminal))
        val almostExpired = record().copy(state = "cancelled", offer = record().offer.copy(expiresAt = 1001))
        runtime.retry(almostExpired, 0, 1000)
        assertEquals(1000L, runtime.deadline(almostExpired))
        assertNull(runtime.deadline(almostExpired.withClosedMailbox(expired = true)))
    }

    @Test fun `idle queued mailbox makes no requests for ten simulated minutes and nudge reads immediately`() = runBlocking {
        MockWebServer().use { server ->
            val journal = Journal(record())
            val api = api(server)
            val runtime = DirectPollRuntime()
            var now = 0L
            suspend fun dispatch() {
                val at = runtime.deadline(journal.current) ?: return
                if (now < at) return
                server.enqueue(MockResponse().setBody(metadata(journal.current)))
                operation(journal) { api.get(journal.current.offer.transferId, 1) }
                runtime.settled(journal.current, DirectPollPolicy.plan(journal.current, false, false), now, now, 1000 + now / 1000)
            }
            dispatch() // Startup/foreground reconciliation, then complete quiescence.
            assertEquals(1, server.requestCount)
            repeat(600) { now += 1000; dispatch() }
            assertEquals(1, server.requestCount)
            assertNull(runtime.deadline(journal.current))
            assertNull(DirectPollSchedule.delayMillis(now, emptyList(), 0, 0))
            runtime.wake(journal.current.offer.transferId)
            dispatch()
            assertEquals(2, server.requestCount)
            assertNull(runtime.deadline(journal.current))
        }
    }

    @Test fun `live roles preparation requests sends manual probes and capacity hints keep two second cadence`() {
        val base = record()
        val proven = base.copy(consent = true, peerDeviceId = "dddddddd-4444-4444-8444-ddddddddddd4", signalBudget = DirectSignalBudget.fresh())
        val plans = listOf(
            DirectPollPolicy.plan(base, true, false), DirectPollPolicy.plan(base, false, true),
            DirectPollPolicy.plan(base.copy(requestId = "request"), false, false),
            DirectPollPolicy.plan(base.copy(pending = listOf(pending(base))), false, false),
            DirectPollPolicy.plan(proven.copy(manualPause = DirectManualPauseState(syncNeeded = true)), false, false),
            DirectPollPolicy.plan(proven.copy(queue = DirectFileQueueState(1, 0, capacityHintCount = 1)), false, false, true),
        )
        for (plan in plans) {
            assertEquals(2000L, plan.cadenceMillis); assertFalse(plan.retryOnly)
            val runtime = DirectPollRuntime()
            repeat(10) { runtime.settled(base, plan, 100, 150, 1000); assertEquals(2100L, runtime.deadline(base)) }
        }
        assertNull(DirectPollPolicy.plan(base.copy(role = "receiver", consent = false), false, false).cadenceMillis)
        assertNull(DirectPollPolicy.plan(proven.copy(manualPause = DirectManualPauseState(localRevision = 1, localPaused = true,
            publishedLocalRevision = 1, acknowledgedLocalRevision = 1)), false, false).cadenceMillis)
        assertNull(DirectPollPolicy.plan(proven.copy(signalBudget = DirectSignalBudget.unknown(),
            manualPause = DirectManualPauseState(syncNeeded = true)), false, false).cadenceMillis)
        assertNull(DirectPollPolicy.plan(proven.copy(signalBudget = DirectSignalBudget.unknown(),
            queue = DirectFileQueueState(1, 0, capacityHintCount = 1)), false, false, true).cadenceMillis)
    }

    @Test fun `registration race and transport errors back off and preserve Retry After`() = runBlocking {
        val waiting = Journal(record().copy(role = "receiver", registered = false, consent = false, state = "offered"))
        assertFailsWith<DirectTransferApiError> { operation(waiting) { throw DirectTransferApiError(404) } }
        assertFalse(waiting.current.mailboxClosed)
        operation(waiting) { throw DirectTransferApiError(410) }
        assertTrue(waiting.current.mailboxClosed)
        val observed = Journal(record().copy(role = "receiver", registered = false, mailboxObserved = true))
        operation(observed) { throw DirectTransferApiError(404) }
        assertTrue(observed.current.mailboxClosed)
        val runtime = DirectPollRuntime()
        val value = record()
        runtime.retry(value, 0, 1000); assertEquals(2000L, runtime.deadline(value))
        runtime.wake(value.offer.transferId) // An event can expedite a read, but cannot reset its retry count.
        runtime.retry(value, 0, 1000); assertEquals(4000L, runtime.deadline(value))
        runtime.retry(value, 0, 1000, 120_000); assertEquals(120_000L, runtime.deadline(value))
    }

    private fun metadata(record: DirectTransferRecord): String = json.encodeToString(DirectTransferMetadata.serializer(),
        DirectTransferMetadata(record.offer.transferId, record.chatId, record.offer.offerClientMessageId,
            record.offer.senderUserId, record.offer.senderDeviceId, record.offer.recipientUserId, expiresAt = record.offer.expiresAt, status = "active"))
}
