package com.messenger.app.data.directtransfer

import kotlin.test.*

class DirectExecutionPolicyTest {
    private val marker = "10000000-0000-4000-8000-000000000010"
    private val other = "10000000-0000-4000-8000-000000000011"
    private val epoch = 12
    private fun record(role: String = "receiver"): DirectTransferRecord {
        val raw = requireNotNull(javaClass.getResourceAsStream("/direct-file-json-nonce-v1.json")).bufferedReader().use { it.readText() }
        val json = DirectFileOffer.codec.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
        val offer = DirectFileOffer.codec.decodeFromJsonElement(DirectFileOffer.serializer(), json.getValue("offer"))
        return DirectTransferRecord(offer, "eeeeeeee-5555-4555-8555-eeeeeeeeeee5", role, state = "transferring", consent = true,
            durableOffset = 262144, requestId = other, attemptId = other, executionSession = marker,
            manualPause = DirectManualPauseState(localPaused = true, localRevision = 1), queue = DirectFileQueueState(4, 1024))
    }

    @Test fun submissionWithoutGrantAllowsOnlyForegroundAndGrantCoversOnlyExactMarkedMembers() {
        val pending = DirectExecutionSession(marker, epoch)
        assertTrue(DirectExecutionPolicy.mayRun(marker, false, true, pending, epoch))
        assertFalse(DirectExecutionPolicy.mayRun(marker, false, false, pending, epoch))
        val granted = pending.copy(granted = true)
        for (role in listOf("sender", "receiver")) {
            val member = record(role)
            assertTrue(DirectExecutionPolicy.mayRun(member.executionSession, false, false, granted, epoch))
        }
        assertFalse(DirectExecutionPolicy.mayRun(other, false, false, granted, epoch))
        assertFalse(DirectExecutionPolicy.mayRun(null, false, false, granted, epoch))
        assertFalse(DirectExecutionPolicy.mayRun(marker, false, false, granted, epoch + 1))
    }

    @Test fun revocationAndOldProcessMarkersNeverGainAuthorityFromForegroundOrNetworkRecovery() {
        val revoked = DirectExecutionSession(marker, epoch, granted = true, revoked = true)
        for (visible in listOf(false, true)) {
            assertFalse(DirectExecutionPolicy.mayRun(marker, false, visible, revoked, epoch))
            assertFalse(DirectExecutionPolicy.mayRun(marker, true, visible, revoked.copy(revoked = false), epoch))
            assertFalse(DirectExecutionPolicy.mayRun(marker, false, visible, null, epoch))
            assertFalse(DirectExecutionPolicy.mayRun(marker, false, visible, revoked.copy(id = other, revoked = false), epoch))
        }
        assertTrue(DirectExecutionPolicy.mayRun(null, false, true, null, epoch))
    }

    @Test fun callbackFreeDeathPreservesBothRoleBytesAndManualQueueIntentButRequiresExplicitContinue() {
        for (role in listOf("sender", "receiver")) for (state in listOf("accepted", "connecting", "transferring", "verifying", "paused")) {
            val before = record(role).copy(state = state)
            val after = before.afterProcessRestart(12345)
            assertEquals("paused", after.state); assertTrue(after.executionInterrupted); assertNull(after.executionSession)
            assertNull(after.requestId); assertNull(after.acceptedRequest)
            assertEquals(before.offer, after.offer); assertEquals(before.durableOffset, after.durableOffset)
            assertEquals(before.manualPause, after.manualPause); assertEquals(before.queue, after.queue)
            assertEquals(before.consent, after.consent)
            assertFalse(DirectManualRuntime.byteEligible(after)); assertFalse(DirectManualRuntime.requestEligible(after))
            assertEquals(after, after.afterProcessRestart(23456))
        }
    }

    @Test fun stoppedMarkerDoesNotLoseSafetyErrorsOrChangeCompletedAndPassiveOffers() {
        val error = record().copy(reason = "integrity_failed")
        assertEquals("integrity_failed", error.afterProcessRestart(12).reason)
        val complete = record().copy(completed = true, state = "completed", durableOffset = record().offer.size)
        assertEquals(complete, complete.afterProcessRestart(12))
        val passive = record().copy(executionSession = null, consent = false, state = "offered")
        assertEquals(passive, passive.afterProcessRestart(12))
    }

    @Test fun failedLocalAcceptAdmissionRetainsUnclaimedOfferAndAcceptActionAfterStopOrDeath() {
        for (reason in listOf("too_many", "disk_full")) {
            val passive = record().copy(consent = false, state = "offered", reason = reason, manualPause = null)
            val expected = passive.copy(executionSession = null)
            assertEquals(expected, DirectExecutionPolicy.interrupted(passive))
            val recovered = passive.afterProcessRestart(123)
            assertEquals(expected, recovered)
            assertFalse(recovered.executionInterrupted); assertFalse(recovered.consent)
            val permissions = com.messenger.app.ui.transfers.DirectTransferUiPolicy.cardPermissions(recovered.presentation())
            assertTrue(permissions.allowOfferActions)
            assertFalse(DirectManualRuntime.requestEligible(recovered))
        }
    }

    @Test fun canonicalOldJournalReadCannotSilentlyClearExecutionStopOrManualHolds() {
        val durableOld = record()
        val heldMemory = DirectExecutionPolicy.interrupted(durableOld)
        val recovered = DirectManualJournalPolicy.reconciled(heldMemory, durableOld)
        assertTrue(recovered.executionInterrupted); assertNull(recovered.executionSession)
        assertEquals(heldMemory.manualPause, recovered.manualPause)
        assertFalse(DirectManualRuntime.byteEligible(recovered))
    }

    @Test fun naturalPeerHoldDisarmsOnlyMarkerAndPeerContinueRetainsForegroundCoreEligibility() {
        val held = record().copy(manualPause = DirectManualPauseState(peerPaused = true, peerRevision = 1))
        assertTrue(DirectExecutionPolicy.settledHold(held))
        val disarmed = DirectExecutionPolicy.disarmed(held)
        assertEquals(held.copy(executionSession = null), disarmed)
        assertFalse(disarmed.executionInterrupted)
        assertEquals(held.queue, disarmed.queue); assertEquals(held.durableOffset, disarmed.durableOffset)
        val peerContinued = disarmed.copy(manualPause = disarmed.manualPause!!.copy(peerPaused = false, peerRevision = 2))
        assertTrue(DirectManualRuntime.byteEligible(peerContinued))
        assertFalse(DirectManualRuntime.state(peerContinued).localPaused)
        assertTrue(DirectExecutionPolicy.mayRun(peerContinued.executionSession, false, true, null, epoch))
        assertFalse(DirectExecutionPolicy.mayRun(peerContinued.executionSession, false, false, null, epoch))
        val stopped = DirectExecutionPolicy.interrupted(held).copy(manualPause = peerContinued.manualPause)
        assertFalse(DirectManualRuntime.byteEligible(stopped))
        assertFalse(DirectExecutionPolicy.mayRun(null, stopped.executionInterrupted, true, null, epoch))
    }

    @Test fun knownCallOrIdleDisarmDoesNotAddManualOrExecutionHold() {
        for (before in listOf(record().copy(pausedForLocalCall = true, reason = "call_in_progress"),
            record().copy(reason = "network_waiting"))) {
            val after = DirectExecutionPolicy.disarmed(before)
            assertEquals(before.copy(executionSession = null), after)
            assertFalse(after.executionInterrupted)
            assertEquals(before.pausedForLocalCall, after.pausedForLocalCall)
            assertEquals(before.manualPause, after.manualPause)
        }
        assertTrue(DirectExecutionPolicy.settledHold(record().copy(pausedForLocalCall = true)))
    }

    @Test fun actualPrivateCodecRoundTripsMarkersAndOldRecordsDefaultToUnmarked() {
        val before = record()
        val raw = DirectFileOffer.codec.encodeToString(DirectTransferRecord.serializer(), before)
        assertEquals(before, DirectTransferCoordinator.decodeRecord(raw))
        val modern = DirectFileOffer.codec.parseToJsonElement(DirectFileOffer.codec.encodeToString(DirectTransferRecord.serializer(), before)) as kotlinx.serialization.json.JsonObject
        val old = kotlinx.serialization.json.JsonObject(modern - setOf("executionSession", "executionInterrupted")).toString()
        val decoded = assertNotNull(DirectTransferCoordinator.decodeRecord(old))
        assertNull(decoded.executionSession); assertFalse(decoded.executionInterrupted)
        val invalid = raw.replace(marker, "not-a-generated-session")
        assertNull(DirectTransferCoordinator.decodeRecord(invalid))
        assertFailsWith<IllegalArgumentException> { DirectFileAdmissionTicket(other, true, 1, 1024, executionSession = "content://provider/secret") }
    }
}
