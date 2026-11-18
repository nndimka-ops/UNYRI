package com.messenger.app.ui.theme

import kotlin.test.*

class CocoFeedbackGateTest {
    @Test fun `only a recent pending message created in this chat visit can enter once`() {
        val gate = CocoMessageEntranceGate(openedAt = 10_000)
        assertFalse(gate.claim("restored", 9_999, true, 10_100))
        assertFalse(gate.claim("historical", 10_001, false, 10_100))
        assertFalse(gate.claim("future", 10_101, true, 10_100))
        assertFalse(gate.claim("stale", 10_001, true, 20_000))
        assertTrue(gate.claim("new", 10_050, true, 10_100))
        assertFalse(gate.claim("new", 10_050, true, 10_101))
        assertTrue(gate.claim("next", 10_060, true, 10_100))
    }
}
