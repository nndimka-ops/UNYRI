package com.messenger.app.ui.chats

import kotlin.test.*

class HiddenRowMotionTest {
    @Test fun `hidden events are consumed and restored rows never replay them`() {
        val clock = ChatListMotionClock()
        val signal = ChatRowMotionSignal(1, "row", lift = true, sweep = true)
        assertFalse(consumeRowMotion(signal, clock, active = false))
        assertFalse(consumeRowMotion(signal, clock, active = true))
        assertFalse(consumeRowMotion(null, clock, active = true))
        val next = signal.copy(token = 2)
        assertTrue(consumeRowMotion(next, clock, active = true))
        assertFalse(consumeRowMotion(next, clock, active = true))
    }
}
