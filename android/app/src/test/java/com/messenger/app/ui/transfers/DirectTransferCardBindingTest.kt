package com.messenger.app.ui.transfers

import com.messenger.app.data.directtransfer.DirectFileOffer
import com.messenger.app.data.directtransfer.DirectFilePresentation
import kotlin.test.*

class DirectTransferCardBindingTest {
    private val offer = DirectFileOffer(
        transferId = "10000000-0000-4000-8000-000000000001",
        offerClientMessageId = "10000000-0000-4000-8000-000000000002",
        senderUserId = "10000000-0000-4000-8000-000000000003",
        senderDeviceId = "10000000-0000-4000-8000-000000000004",
        recipientUserId = "10000000-0000-4000-8000-000000000005",
        offerNonce = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        filename = "original.bin", mimeType = "application/octet-stream", size = 1024,
        sha256 = "a".repeat(64), expiresAt = 999999,
    )
    private val value = DirectFilePresentation(offer, "paused", "receiver", manualControlsAvailable = true)

    private fun click(shown: DirectFilePresentation, selecting: Boolean = false): List<String> {
        val calls = mutableListOf<String>()
        directTransferManualClick(shown, selecting, { calls += "select" },
            { calls += "pause:$it" }, { calls += "continue:$it" })
        return calls
    }

    @Test fun peerOnlyHoldDoesNotClearPeerOrInventLocalContinue() {
        val peerHeld = value.copy(peerManualPaused = true)
        assertEquals(listOf("pause:${offer.transferId}"), click(peerHeld))
        assertEquals(listOf("continue:${offer.transferId}"), click(peerHeld.copy(localManualPaused = true)))
        assertEquals(DirectTransferStatus.HELD_PEER, DirectTransferUiPolicy.status(peerHeld))
    }

    @Test fun interruptedAndAmbiguousContinueUseContinueRatherThanGenericRetry() {
        for (shown in listOf(value.copy(reason = "background_interrupted"),
            value.copy(reason = "manual_continue_save_failed", localManualPaused = false))) {
            assertEquals(listOf("continue:${offer.transferId}"), click(shown))
            assertFalse(DirectTransferUiPolicy.canRetry(shown, DirectTransferUiPolicy.cardPermissions(shown)))
        }
        val failedPause = value.copy(reason = "manual_pause_save_failed", localManualPaused = true)
        assertEquals(listOf("pause:${offer.transferId}"), click(failedPause))
    }

    @Test fun selectionUsesOnlySelectionAndNeverDispatchesFileActions() {
        for (shown in listOf(value, value.copy(localManualPaused = true), value.copy(reason = "background_interrupted"))) {
            assertEquals(listOf("select"), click(shown, selecting = true))
        }
    }

    @Test fun pendingClosedTerminalHistoryAndUnconsentedCannotDispatchManualActions() {
        for (shown in listOf(value.copy(reason = "manual_pause_saving"), value.copy(reason = "manual_continue_saving"),
            value.copy(state = "completed"), value.copy(state = "verifying"), value.copy(state = "other_device", reason = "history_only"),
            value.copy(role = "other"), value.copy(offset = offer.size), value.copy(manualControlsAvailable = false))) {
            assertTrue(click(shown).isEmpty())
        }
        val closed = value.copy(mailboxClosed = true, manualControlsAvailable = false)
        assertTrue(click(closed).isEmpty())
        assertFalse(DirectTransferUiPolicy.canRetry(closed, DirectTransferUiPolicy.cardPermissions(closed)))
    }
}
