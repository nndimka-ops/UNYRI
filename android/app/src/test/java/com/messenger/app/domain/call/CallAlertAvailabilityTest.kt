package com.messenger.app.domain.call

import org.junit.Assert.*
import org.junit.Test

class CallAlertAvailabilityTest {
    private fun visual(visible: Boolean = false, interactive: Boolean = false, importance: Int = 4,
        requested: Boolean = false, permitted: Boolean = false, sdk: Int = 34, headsUp: Boolean = true,
        display: Boolean = interactive) =
        CallRingConfirmationPolicy.visualSurfaceAvailable(visible, interactive, importance, requested, permitted, sdk, headsUp, display)

    @Test fun successfulSilentPostWithoutAnySurfaceDoesNotConfirm() {
        assertFalse(visual())
        assertFalse(CallRingConfirmationPolicy.presented(true, true, 4, true, visual()))
        assertFalse(visual(visible = true)) // Started/activity flag with the display off.
        assertFalse(visual(interactive = true, importance = 3)) // Shade-only default importance.
    }

    @Test fun deniedFsiHasAVisualPathOnlyWhenTheFsiRequestWasKept() {
        assertFalse(visual(requested = false))
        assertFalse(visual(requested = true)) // A successful post with the display still off is insufficient.
        assertTrue(visual(requested = true, display = true)) // Platform woke a visible AOD/HUN surface.
        assertFalse(visual(requested = true, display = true, headsUp = false))
        assertFalse(visual(requested = true, display = true, importance = 3))
        assertFalse(visual(requested = true, display = true, sdk = 33))
        assertFalse(visual(requested = true, permitted = true, headsUp = false)) // Wait for the actual Activity.
    }

    @Test fun genuinelyVisibleSilentCallOrInteractiveHeadsUpCanConfirm() {
        assertTrue(visual(visible = true, interactive = true, importance = 3, headsUp = false))
        assertTrue(visual(interactive = true))
        assertFalse(visual(interactive = true, headsUp = false))
        assertTrue(CallRingConfirmationPolicy.presented(true, true, 4, true,
            visual(visible = true, interactive = true)))
        assertFalse(CallRingConfirmationPolicy.presented(true, true, 4, false,
            visual(visible = true, interactive = true)))
    }

    @Test fun priorityCallExceptionMatchesThePostedAppPerson() {
        val unknown = CallRingConfirmationPolicy.CallerAffinity.UNKNOWN
        fun allowed(calls: Boolean, senders: Int) = CallRingConfirmationPolicy.dndAllowsCalls(2, false, calls, senders, unknown)
        assertTrue(allowed(true, 0)) // CATEGORY_CALL, calls from anyone, no channel bypass.
        assertFalse(allowed(false, 0))
        assertFalse(allowed(true, 1)) // App-key/name does not imply a system contact.
        assertFalse(allowed(true, 2)) // Person.isImportant does not imply starred contact.
        assertFalse(allowed(true, -1))
        assertTrue(CallRingConfirmationPolicy.dndAllowsCalls(2, true, false, 2, unknown))
    }

    @Test fun contactAndStarredFiltersNeedMatchingAffinityAndNeverOverrideTotalDnd() {
        for (caller in CallRingConfirmationPolicy.CallerAffinity.entries) {
            assertEquals(caller != CallRingConfirmationPolicy.CallerAffinity.UNKNOWN,
                CallRingConfirmationPolicy.dndAllowsCalls(2, false, true, 1, caller))
            assertEquals(caller == CallRingConfirmationPolicy.CallerAffinity.STARRED,
                CallRingConfirmationPolicy.dndAllowsCalls(2, false, true, 2, caller))
            for (filter in listOf(0, 3, 4)) {
                assertFalse(CallRingConfirmationPolicy.dndAllowsCalls(filter, true, true, 0, caller))
            }
        }
    }

    @Test fun allowedPriorityCallUsesUserRingerModeInsteadOfZensExternalSilence() {
        assertEquals(2, CallRingConfirmationPolicy.effectiveRingerMode(0, 2, 2, true))
        assertEquals(1, CallRingConfirmationPolicy.effectiveRingerMode(0, 1, 2, true))
        assertEquals(0, CallRingConfirmationPolicy.effectiveRingerMode(0, 0, 2, true))
        assertEquals(0, CallRingConfirmationPolicy.effectiveRingerMode(0, null, 2, true))
        assertEquals(0, CallRingConfirmationPolicy.effectiveRingerMode(0, -1, 2, true))
        assertEquals(0, CallRingConfirmationPolicy.effectiveRingerMode(0, 2, 2, false))
        for (filter in listOf(0, 1, 3, 4)) {
            assertEquals(0, CallRingConfirmationPolicy.effectiveRingerMode(0, 2, filter, true))
        }
    }
}
