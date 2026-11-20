package com.messenger.app.data.directtransfer

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class DirectTransferRecoveryTest {
    @Test fun blockedPeerNotificationCannotEscapeRecoveryButCancellationStillStopsWork(): Unit = runBlocking {
        val denied = IllegalStateException("Blocked peer key unavailable")
        var observed: Exception? = null
        var safePausedState = "access_unavailable"
        notifyDirectPauseBestEffort({ throw denied }) { observed = it }
        assertSame(denied, observed)
        assertEquals("access_unavailable", safePausedState)
        val cancelled = CancellationException("Owner changed")
        try {
            notifyDirectPauseBestEffort({ throw cancelled }) { safePausedState = "incorrect" }
            fail("Cancellation was swallowed")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        assertEquals("access_unavailable", safePausedState)
    }
    private fun base(): DirectTransferRecord {
        val raw = requireNotNull(javaClass.getResourceAsStream("/direct-file-json-nonce-v1.json")).bufferedReader().use { it.readText() }
        val json = DirectFileOffer.codec.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
        val offer = DirectFileOffer.codec.decodeFromJsonElement(DirectFileOffer.serializer(), json.getValue("offer"))
        return DirectTransferRecord(offer, "eeeeeeee-5555-4555-8555-eeeeeeeeeee5", "receiver", consent = true,
            durableOffset = offer.size, attemptId = "fedcfedc-9999-4999-8999-fedcfedcfed9", requestId = "fedcfedc-9999-4999-8999-fedcfedcfed9")
    }
    @Test fun receiverVerificationCrashReentersReconciliationWithDurableBytesIntact() {
        val value = base().copy(state = "verifying").afterProcessRestart(1234)
        assertEquals("paused", value.state); assertNull(value.requestId)
        assertEquals(value.offer.size, value.durableOffset); assertFalse(value.completed)
        assertEquals("connection_lost", value.reason)
    }
    @Test fun acceptedLongHashKeepsAcknowledgedRequestAndFreshBoundedLease() {
        val before = base().copy(state = "accepted", requestCreatedAt = 1, requestAcknowledgedAt = 100, requestRetryCount = 6)
        val value = before.afterProcessRestart(1234)
        assertEquals(before.requestId, value.requestId); assertEquals(1234L, value.requestAcknowledgedAt)
        assertEquals(1, value.requestRetryCount)
    }
    @Test fun permanentFailuresAndCompletionNeverTurnIntoAutomaticSourceRetry() {
        val paused = base().copy(role = "sender", state = "paused", reason = "source_changed").afterProcessRestart(1234)
        assertEquals("source_changed", paused.reason); assertNull(paused.requestId)
        val complete = base().copy(state = "completed", completed = true)
        assertEquals(complete, complete.afterProcessRestart(1234))
        val rejected = base().copy(state = "rejected")
        assertEquals(rejected, rejected.afterProcessRestart(1234))
    }
    @Test fun missingRelayRemainsPausedWithoutAutomaticRetryAfterRestart() {
        for (role in listOf("sender", "receiver")) {
            val paused = base().copy(role=role,state="paused",reason="relay_unavailable",durableOffset=0).afterProcessRestart(1234)
            assertEquals("relay_unavailable",paused.reason)
            assertTrue(DirectManualRuntime.safetyBlocked(paused))
            assertFalse(DirectManualRuntime.byteEligible(paused))
            assertFalse(DirectManualRuntime.requestEligible(paused))
        }
    }
    @Test fun initialMailbox404PreservesExplicitConsentAndCannotBypassRealAccessRevocation() {
        val offered = base().copy(consent = false, state = "offered", registered = false)
        assertTrue(offered.isAwaitingRegistration(404, false))
        assertEquals("offered", offered.registrationWaiting().state)
        assertFalse(offered.registrationWaiting().consent)
        assertEquals("accepted", offered.copy(consent = true).registrationWaiting().state)
        assertFalse(offered.copy(registered = true).isAwaitingRegistration(404, false))
        assertFalse(offered.copy(mailboxObserved = true).isAwaitingRegistration(404, false))
        assertFalse(offered.isAwaitingRegistration(404, true))
        assertFalse(offered.isAwaitingRegistration(403, false))
        assertFalse(offered.isAwaitingRegistration(401, false))
    }
    @Test fun OnlyVerifiedNewBytesResetConsecutiveFailuresAndCancelledStateIsSticky() {
        val waiting = base().copy(durableOffset = 0, state = "connecting", connectionFailures = 5, requestRetryCount = 6)
        val heartbeat = waiting.acknowledgeProgress(0, DirectAckStats(1, 0))
        assertEquals(5, heartbeat.connectionFailures); assertEquals(6, heartbeat.requestRetryCount)
        val progressed = waiting.acknowledgeProgress(262144, DirectAckStats(2, 1048576))
        assertEquals(0, progressed.connectionFailures); assertEquals(0, progressed.requestRetryCount)
        val cancelled = waiting.copy(state = "cancelled")
        assertEquals(cancelled, cancelled.acknowledgeProgress(262144, DirectAckStats(2, 1048576)))
    }
}
