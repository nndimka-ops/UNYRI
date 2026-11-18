package com.messenger.app.ui.call

import kotlin.test.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*

class CallPortraitReturnPolicyTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun anIgnoredOrientationRequestCannotPreventCompletionOrRestartTheDeadline() = runTest {
        var completed = false
        val job = launch { CallPortraitReturnPolicy.await { awaitCancellation() }; completed = true }
        runCurrent()
        advanceTimeBy(699)
        assertFalse(completed)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(completed)
        assertTrue(job.isCompleted)
        advanceTimeBy(10_000)
        assertTrue(completed)
    }
    @Test fun onlyAnEffectiveFullScreenLeaseReleaseCanWaitForPortrait() {
        assertTrue(CallPortraitReturnPolicy.shouldWait(true, true, true, false, false))
        assertFalse(CallPortraitReturnPolicy.shouldWait(false, true, true, false, false))
        assertFalse(CallPortraitReturnPolicy.shouldWait(true, false, true, false, false))
        assertFalse(CallPortraitReturnPolicy.shouldWait(true, true, false, false, false))
        assertFalse(CallPortraitReturnPolicy.shouldWait(true, true, true, true, false))
        assertFalse(CallPortraitReturnPolicy.shouldWait(true, true, true, false, true))
        assertEquals(700L, CallPortraitReturnPolicy.TIMEOUT_MS)
    }
    @Test fun minimizedEndedOrFailedDoesNotAcquireLease() {
        assertFalse(CallPortraitReturnPolicy.fullScreen(true, true, true, false))
        assertTrue(CallPortraitReturnPolicy.fullScreen(true, true, false, true))
        assertTrue(CallPortraitReturnPolicy.fullScreen(true, false, true, false))
        assertFalse(CallPortraitReturnPolicy.fullScreen(false, true, true, true))
    }
}
