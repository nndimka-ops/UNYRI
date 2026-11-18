package com.messenger.app.ui.security

import android.view.MotionEvent
import kotlin.test.*

class ObscuredTouchesTest {
    @Test fun cleanGestureAndUnrelatedFlagsRemainUsable() {
        val policy = ObscuredTouchPolicy()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) assertFalse(policy.reject(action, 4))
    }
    @Test fun eitherObscuredFlagPoisonsEntireGestureEvenAfterOverlayDisappears() {
        for (flag in listOf(MotionEvent.FLAG_WINDOW_IS_OBSCURED, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED, 3)) {
            val policy = ObscuredTouchPolicy()
            assertTrue(policy.reject(MotionEvent.ACTION_DOWN, flag))
            assertTrue(policy.reject(MotionEvent.ACTION_MOVE, 0))
            assertTrue(policy.reject(MotionEvent.ACTION_UP, 0))
            assertFalse(policy.reject(MotionEvent.ACTION_DOWN, 0))
            assertFalse(policy.reject(MotionEvent.ACTION_UP, 0))
        }
    }
    @Test fun overlayAppearingAfterCleanDownCancelsClickIncludingPointerChanges() {
        val policy = ObscuredTouchPolicy()
        assertFalse(policy.reject(MotionEvent.ACTION_DOWN, 0))
        assertTrue(policy.reject(MotionEvent.ACTION_POINTER_DOWN, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED))
        assertTrue(policy.reject(MotionEvent.ACTION_POINTER_UP, 0))
        assertTrue(policy.reject(MotionEvent.ACTION_UP, 0))
    }
    @Test fun flaggedUpIsRejectedAndCancellationResetsOnlyFollowingGesture() {
        val policy = ObscuredTouchPolicy()
        assertFalse(policy.reject(MotionEvent.ACTION_DOWN, 0))
        assertTrue(policy.reject(MotionEvent.ACTION_UP, MotionEvent.FLAG_WINDOW_IS_OBSCURED))
        assertTrue(policy.rejectCurrentEvent)
        assertTrue(policy.reject(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_OBSCURED))
        assertTrue(policy.reject(MotionEvent.ACTION_CANCEL, 0))
        assertFalse(policy.reject(MotionEvent.ACTION_DOWN, 0))
    }
}
