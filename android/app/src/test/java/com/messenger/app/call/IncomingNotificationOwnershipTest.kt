package com.messenger.app.call

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P0.1 race hardening: the ownership rule that prevents a delayed terminal
 *  push for an OLD call from canceling a NEWER call's notification slot. */
class IncomingNotificationOwnershipTest {

    @Test
    fun `terminal push matching the stored owner cancels`() {
        assertTrue(IncomingCallNotificationManager.shouldCancelFor(storedOwner = "A", terminalCallId = "A"))
    }

    @Test
    fun `terminal push for an old call does not cancel a newer owner`() {
        // Slot is owned by B; a delayed call_ended(A) must be a no-op.
        assertFalse(IncomingCallNotificationManager.shouldCancelFor(storedOwner = "B", terminalCallId = "A"))
    }

    @Test
    fun `null terminal call id means authoritative unconditional cleanup`() {
        assertTrue(IncomingCallNotificationManager.shouldCancelFor(storedOwner = "B", terminalCallId = null))
    }

    @Test
    fun `no stored owner with a terminal call id is a no-op`() {
        assertFalse(IncomingCallNotificationManager.shouldCancelFor(storedOwner = null, terminalCallId = "A"))
    }

    @Test
    fun `no stored owner and no terminal call id is an unconditional cleanup`() {
        assertTrue(IncomingCallNotificationManager.shouldCancelFor(storedOwner = null, terminalCallId = null))
    }
}
