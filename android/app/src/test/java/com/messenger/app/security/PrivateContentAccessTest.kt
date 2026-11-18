package com.messenger.app.security

import kotlin.test.*

class PrivateContentAccessTest {
    @Test fun `cancelled unlock never reveals chats`() {
        var opened = false
        var requested = false
        PrivateContentAccess({ true }, { requested = true }).open { opened = true }
        assertTrue(requested)
        assertFalse(opened)
    }

    @Test fun `success callback while still locked or relocked cannot reveal chats`() {
        var opened = false
        PrivateContentAccess({ true }, { it() }).open { opened = true }
        assertFalse(opened)
    }

    @Test fun `only verified system unlock permits leaving call`() {
        var locked = true
        var opened = 0
        var success: (() -> Unit)? = null
        val gate = PrivateContentAccess({ locked }, { success = it })
        gate.open { opened++ }
        assertEquals(0, opened)
        locked = false
        success!!()
        assertEquals(1, opened)
        // A new lock must be checked again even after a successful unlock.
        locked = true
        gate.open { opened++ }
        assertEquals(1, opened)
    }

    @Test fun `unlocked phone can navigate without authentication prompt`() {
        var opened = false
        PrivateContentAccess({ false }, { fail("unnecessary unlock") }).open { opened = true }
        assertTrue(opened)
    }
}
