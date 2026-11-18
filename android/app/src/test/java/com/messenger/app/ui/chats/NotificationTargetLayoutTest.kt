package com.messenger.app.ui.chats

import org.junit.Assert.*
import org.junit.Test

class NotificationTargetLayoutTest {
    @Test fun `hydrated target must not scroll against the previous calls-only layout`() {
        val keys = listOf("call", "message", "target")
        assertFalse(NotificationTargetLayout.current(keys, 1, listOf(0 to "call"), 800))
        assertFalse(NotificationTargetLayout.current(keys, 3, listOf(1 to "old-call"), 800))
        assertFalse(NotificationTargetLayout.current(keys, 3, emptyList(), 800))
        assertFalse(NotificationTargetLayout.current(keys, 3, listOf(0 to "call"), 0))
        assertTrue(NotificationTargetLayout.current(keys, 3, listOf(0 to "call", 1 to "message"), 800))
    }
}
