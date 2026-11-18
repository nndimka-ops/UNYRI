package com.messenger.app.data.secure
import kotlin.test.*
class ClipboardOwnershipTest {
    @Test fun ownershipExpiryBackgroundAndReplacement() {
        val owner = ClipboardOwnership(); owner.copied("invite-1", 1000)
        assertFalse(owner.shouldClear("invite-1", 120999, false))
        assertTrue(owner.shouldClear("invite-1", 121000, false))
        assertTrue(owner.shouldClear("invite-1", 1001, true))
        assertFalse(owner.shouldClear("other-app", 999999, true))
        assertFalse(owner.shouldClear(null, 999999, true))
        owner.copied("media-2", 2000)
        assertFalse(owner.shouldClear("invite-1", 999999, true))
        assertFalse(owner.shouldClear("media-2", 121000, false))
        assertTrue(owner.shouldClear("media-2", 122000, false))
        owner.reset(); assertFalse(owner.shouldClear("media-2", 999999, true))
    }
}
