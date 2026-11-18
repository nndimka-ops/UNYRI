package com.messenger.app.data.directtransfer

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class DirectCompletionRecoveryTest {
    private fun record(): DirectTransferRecord {
        val root = DirectFileOffer.codec.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/direct-file-json-nonce-v1.json")).bufferedReader().use { it.readText() }).jsonObject
        val offer = DirectFileOffer.codec.decodeFromJsonElement(DirectFileOffer.serializer(), root.getValue("offer"))
        return DirectTransferRecord(offer, "eeeeeeee-5555-4555-8555-eeeeeeeeeee5", "sender", state = "completed", consent = true,
            peerDeviceId = "dddddddd-4444-4444-8444-ddddddddddd4", durableOffset = offer.size, completed = true,
            attemptId = "fedcfedc-9999-4999-8999-fedcfedcfed9")
    }
    @Test fun completedSenderRestartRecoversOnlyMissingReceiptWithSameAttemptAndStableProbe() {
        val before = record()
        val restored = before.afterProcessRestart(1000)
        val probe = requireNotNull(restored.completionProbe(1000))
        assertEquals(before.attemptId, probe.attempt)
        assertEquals(probe, restored.completionProbe(1000))
        assertTrue(restored.completed); assertEquals(before.offer.size, restored.durableOffset)
        val waiting = restored.copy(completionRecoveryCount = probe.nextCount, completionRecoveryAt = probe.nextAt)
        assertNull(waiting.completionProbe(probe.nextAt - 1))
        assertNotEquals(probe.trigger, requireNotNull(waiting.completionProbe(probe.nextAt)).trigger)
    }
    @Test fun bootstrapCandidateReceiverConfirmedExpiredAndRetryLimitCannotProbe() {
        val value = record()
        assertNull(value.copy(peerDeviceId = null).completionProbe(1000))
        assertNull(value.copy(consent = false).completionProbe(1000))
        assertNull(value.copy(role = "receiver").completionProbe(1000))
        assertNull(value.copy(completionReceived = true).completionProbe(1000))
        assertNull(value.copy(completionRecoveryCount = 6).completionProbe(1000))
        assertNull(value.completionProbe(value.offer.expiresAt * 1000))
        assertNull(value.copy(attemptId = null).completionProbe(1000))
    }
}
