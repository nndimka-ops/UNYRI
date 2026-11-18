package com.messenger.app.ui.transfers

import com.messenger.app.data.directtransfer.*
import kotlin.test.*

class DirectTransferUiPolicyTest {
    @Test fun executionStopShowsContinueWithoutChangingManualPeerHold() {
        val stopped = base.copy(reason = "background_interrupted")
        assertEquals(DirectTransferStatus.BACKGROUND_INTERRUPTED, DirectTransferUiPolicy.status(stopped))
        assertEquals(DirectTransferManualAction.CONTINUE, DirectTransferUiPolicy.manualAction(stopped))
        val peerHeld = stopped.copy(peerManualPaused = true)
        assertEquals(DirectTransferStatus.HELD_PEER, DirectTransferUiPolicy.status(peerHeld))
        assertEquals(DirectTransferManualAction.CONTINUE, DirectTransferUiPolicy.manualAction(peerHeld))
        assertFalse(DirectTransferUiPolicy.canRetry(peerHeld, allPermissions))
    }
    private val offer = DirectFileOffer(transferId = "10000000-0000-4000-8000-000000000001",
        offerClientMessageId = "10000000-0000-4000-8000-000000000002",
        senderUserId = "10000000-0000-4000-8000-000000000003",
        senderDeviceId = "10000000-0000-4000-8000-000000000004",
        recipientUserId = "10000000-0000-4000-8000-000000000005",
        offerNonce = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        filename = "original.bin", mimeType = "application/octet-stream", size = 1024,
        sha256 = "a".repeat(64), expiresAt = 999999)
    private val base = DirectFilePresentation(offer, "paused", "receiver", manualControlsAvailable = true)
    private val allPermissions = DirectTransferActionPermissions(true, true, true, true, true)

    @Test fun heldLabelsOutrankAcknowledgmentAndProbeWarnings() {
        for (pending in listOf(false, true)) for (limit in listOf(false, true)) {
            assertEquals(DirectTransferStatus.HELD_LOCAL, DirectTransferUiPolicy.status(base.copy(localManualPaused = true, manualSyncPending = pending, manualSyncLimitReached = limit)))
            assertEquals(DirectTransferStatus.HELD_PEER, DirectTransferUiPolicy.status(base.copy(peerManualPaused = true, manualSyncPending = pending, manualSyncLimitReached = limit)))
            assertEquals(DirectTransferStatus.HELD_BOTH, DirectTransferUiPolicy.status(base.copy(localManualPaused = true, peerManualPaused = true, manualSyncPending = pending, manualSyncLimitReached = limit)))
        }
        assertEquals(DirectTransferStatus.CONFIRMING, DirectTransferUiPolicy.status(base.copy(manualSyncPending = true)))
        assertEquals(DirectTransferStatus.SYNC_LIMIT, DirectTransferUiPolicy.status(base.copy(manualSyncPending = true, manualSyncLimitReached = true)))
        assertEquals(DirectTransferStatus.PAUSED, DirectTransferUiPolicy.status(base.copy(manualSyncLimitReached = true)))
    }

    @Test fun continueIsOnlyForOurHoldAndNeverClearsPeerStatus() {
        val both = base.copy(localManualPaused = true, peerManualPaused = true)
        assertEquals(DirectTransferManualAction.CONTINUE, DirectTransferUiPolicy.manualAction(both))
        assertEquals(DirectTransferManualAction.PAUSE, DirectTransferUiPolicy.manualAction(base.copy(peerManualPaused = true)))
        assertEquals(DirectTransferStatus.HELD_PEER, DirectTransferUiPolicy.status(both.copy(localManualPaused = false)))
        for (held in listOf(base.copy(localManualPaused = true), base.copy(peerManualPaused = true), both, base.copy(manualSyncPending = true))) {
            assertFalse(DirectTransferUiPolicy.canRetry(held, allPermissions))
            assertFalse(DirectTransferUiPolicy.canRestart(held, allPermissions))
        }
    }

    @Test fun terminalVerifyingHistoryAndUnconsentedViewsHaveNoManualActions() {
        for (state in listOf("completed", "cancelled", "rejected", "expired", "other_device", "verifying")) {
            val value = base.copy(state = state, manualSyncPending = true, manualSyncLimitReached = true, localManualPaused = true)
            assertNull(DirectTransferUiPolicy.manualAction(value))
            assertFalse(DirectTransferUiPolicy.canRetrySynchronization(value))
        }
        assertEquals(DirectTransferStatus.COMPLETED, DirectTransferUiPolicy.status(base.copy(completed = true, localManualPaused = true)))
        assertEquals(DirectTransferStatus.VERIFYING, DirectTransferUiPolicy.status(base.copy(state = "verifying", localManualPaused = true)))
        val history = base.copy(state = "other_device", reason = "history_only")
        assertNull(DirectTransferUiPolicy.manualAction(history))
        assertFalse(DirectTransferUiPolicy.canCancel(history, allPermissions))
        assertFalse(DirectTransferUiPolicy.canOpen(history.copy(completed = true), allPermissions))
        assertNull(DirectTransferUiPolicy.manualAction(base.copy(state = "offered", manualControlsAvailable = false)))
        assertNull(DirectTransferUiPolicy.manualAction(base.copy(offset = offer.size)))
    }

    @Test fun managerDefaultsCannotGrantActionsAndSafetyErrorDoesNotBecomeRetry() {
        val none = DirectTransferActionPermissions()
        assertFalse(DirectTransferUiPolicy.canCancel(base, none))
        assertFalse(DirectTransferUiPolicy.canRetry(base, none))
        assertFalse(DirectTransferUiPolicy.canOpen(base.copy(completed = true), none))
        assertFalse(DirectTransferUiPolicy.canOfferActions(base.copy(state = "offered"), none))
        for (reason in listOf("source_missing", "source_changed", "transfer_unavailable", "access_unavailable", "prefix_mismatch", "integrity_failed")) {
            assertFalse(DirectTransferUiPolicy.canRetry(base.copy(reason = reason), allPermissions))
        }
        assertTrue(DirectTransferUiPolicy.canRestart(base.copy(reason = "prefix_mismatch"), allPermissions))
        assertFalse(DirectTransferUiPolicy.canRestart(base.copy(role = "sender", reason = "prefix_mismatch"), allPermissions))
        assertFalse(DirectTransferUiPolicy.canRestart(base.copy(reason = "network_waiting"), allPermissions))
        assertEquals(DirectTransferStatus.SERVER_CAPACITY, DirectTransferUiPolicy.status(base.copy(reason = "server_capacity")))
        assertEquals(DirectTransferStatus.QUEUED, DirectTransferUiPolicy.status(base.copy(reason = "queued")))
    }

    @Test fun managerOrderingUsesGroupOrdinalAndUuidNotProgress() {
        fun row(id: String, ordinal: Long, group: DirectTransferManagerGroup) = DirectTransferManagerItem(id, "chat", false, base, ordinal, group)
        val rows = listOf(row("b", 2, DirectTransferManagerGroup.QUEUED), row("z", 9, DirectTransferManagerGroup.ACTIVE),
            row("c", 1, DirectTransferManagerGroup.QUEUED), row("a", 2, DirectTransferManagerGroup.QUEUED), row("d", 1, DirectTransferManagerGroup.COMPLETED))
        val order = DirectTransferUiPolicy.managerOrder(rows)
        assertEquals(listOf("z", "c", "a", "b", "d"), order.map { it.id })
        val advanced = rows.map { it.copy(presentation = base.copy(offset = 512, ackSampleId = 800, speedBytesPerSecond = 1024)) }
        assertEquals(order, DirectTransferUiPolicy.managerOrder(advanced))
        assertEquals(order, DirectTransferUiPolicy.managerOrder(advanced.reversed()))
    }
}
