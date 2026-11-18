package com.messenger.app.call

import org.junit.Assert.*
import org.junit.Test

class IncomingCallStyleTest {
    @Test fun `native call style requires an attached full screen intent on Android 12 through 16`() {
        for (sdk in 31..36) {
            assertFalse("API $sdk without FSI must use the plain action fallback", IncomingCallPresentationPolicy.supportsCallStyle(sdk, false))
        }
    }
    @Test fun `native call style remains available with an actually attached full screen intent`() {
        for (sdk in 31..36) {
            assertTrue("API $sdk with attached FSI", IncomingCallPresentationPolicy.supportsCallStyle(sdk, true))
        }
    }
    @Test fun `pre Android 12 keeps its compat presentation with or without a full screen intent`() {
        for (sdk in listOf(26, 30)) {
            assertTrue(IncomingCallPresentationPolicy.supportsCallStyle(sdk, false))
            assertTrue(IncomingCallPresentationPolicy.supportsCallStyle(sdk, true))
        }
    }
    @Test fun `unlocked background phone is not forced full screen to permit call style`() {
        val attached = IncomingCallPresentationPolicy.shouldUseFullScreenIntent(false, false, true)
        assertFalse(attached)
        for (sdk in 31..36) assertFalse(IncomingCallPresentationPolicy.supportsCallStyle(sdk, attached))
    }
    @Test fun `locked permitted first presentation keeps full screen call style`() {
        val requested = IncomingCallPresentationPolicy.shouldUseFullScreenIntent(false, true, true)
        val attached = requested && IncomingCallPresentationPolicy.mayLaunchFullScreen("new-call", "old-call")
        assertTrue(attached)
        for (sdk in 31..36) assertTrue(IncomingCallPresentationPolicy.supportsCallStyle(sdk, attached))
    }
    @Test fun `a requested full screen presentation without permission is not an attached intent`() {
        assertTrue(IncomingCallPresentationPolicy.shouldUseFullScreenIntent(false, true, true))
        val attached = false // permission unavailable: manager does not attach FSI
        for (sdk in 31..36) assertFalse(IncomingCallPresentationPolicy.supportsCallStyle(sdk, attached))
    }
    @Test fun `a deduplicated full screen launch uses the existing plain action fallback`() {
        val requested = IncomingCallPresentationPolicy.shouldUseFullScreenIntent(false, true, true)
        val attached = requested && IncomingCallPresentationPolicy.mayLaunchFullScreen("same-call", "same-call")
        assertFalse(attached)
        for (sdk in 31..36) assertFalse(IncomingCallPresentationPolicy.supportsCallStyle(sdk, attached))
    }
}
