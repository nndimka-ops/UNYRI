package com.messenger.app.ui.theme

import org.junit.Assert.*
import org.junit.Test

class CocoMessageEntranceGateTest {
    @Test fun historyRetriesAndRemoteMessagesStayStill() {
        val gate = CocoMessageEntranceGate(openedAt = 1000)
        assertFalse(gate.claim("history", 999, true, 1200))
        assertFalse(gate.claim("remote", 1100, false, 1200))
        assertFalse(gate.claim("old-retry", 1001, true, 7000))
        assertFalse(gate.claim("future", 1300, true, 1200))
    }
    @Test fun freshLocalMessageEntersOnceEvenAfterRowRecycling() {
        val gate = CocoMessageEntranceGate(openedAt = 1000)
        assertTrue(gate.claim("local", 1100, true, 1200))
        assertFalse(gate.claim("local", 1100, true, 1200))
        assertTrue(gate.claim("next", 1100, true, 1200))
    }
    @Test fun trackingIsBoundedWithoutEvictingAndReplayingOldRows() {
        val gate = CocoMessageEntranceGate(openedAt = 1000)
        repeat(512) { assertTrue(gate.claim("$it", 1100, true, 1200)) }
        assertFalse(gate.claim("overflow", 1100, true, 1200))
        assertFalse(gate.claim("0", 1100, true, 1200))
    }
}
