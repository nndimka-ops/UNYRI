package com.messenger.app.ui.chats

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Presentation grouping must never cross an author, date or non-message boundary. */
class CompactMessageGroupTest {
    @Test fun `adjacent messages by the same author on one day are compact`() {
        assertTrue(compactMessageGroup("sender", "sender", sameDay = true))
    }

    @Test fun `changing author starts a separate group`() {
        assertFalse(compactMessageGroup("sender", "other", sameDay = true))
        assertFalse(compactMessageGroup("sender", "Sender", sameDay = true))
    }

    @Test fun `new day separates messages even from the same author`() {
        assertFalse(compactMessageGroup("sender", "sender", sameDay = false))
    }

    @Test fun `first row and unknown authors cannot create an anonymous group`() {
        assertFalse(compactMessageGroup(null, "sender", sameDay = true))
        assertFalse(compactMessageGroup("sender", null, sameDay = true))
        assertFalse(compactMessageGroup(null, null, sameDay = true))
        assertFalse(compactMessageGroup("", "", sameDay = true))
        assertFalse(compactMessageGroup(" ", " ", sameDay = true))
    }

    @Test fun `calls and media boundaries retain their ordinary spacing`() {
        // The row layer marks calls, albums and photo/video rows ineligible.
        assertFalse(compactMessageGroup("sender", "sender", sameDay = true,
            previousIsMessage = false))
        assertFalse(compactMessageGroup("sender", "sender", sameDay = true,
            currentIsMessage = false))
        assertFalse(compactMessageGroup("sender", "sender", sameDay = true,
            previousIsMessage = false, currentIsMessage = false))
    }
}
