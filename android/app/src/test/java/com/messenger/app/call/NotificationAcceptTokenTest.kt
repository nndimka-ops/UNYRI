package com.messenger.app.call

import kotlin.test.*

class NotificationAcceptTokenTest {
    @Test fun `external launcher extras cannot answer without private token`() {
        assertFalse(NotificationAcceptToken.matches("call", "private", "call", null))
        assertFalse(NotificationAcceptToken.matches("call", "private", "call", "forged"))
        assertFalse(NotificationAcceptToken.matches(null, null, "call", null))
    }
    @Test fun `token cannot authorize a different call`() {
        assertFalse(NotificationAcceptToken.matches("old", "private", "new", "private"))
        assertTrue(NotificationAcceptToken.matches("call", "private", "call", "private"))
    }
}
