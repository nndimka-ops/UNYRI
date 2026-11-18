package com.messenger.app.ui.chats

import org.junit.Assert.*
import org.junit.Test

class EmojiSurfaceTransitionTest {
    @Test fun gridWaitsForBothCurrentAndTargetImeToLeave() {
        assertFalse(emojiPanelReady(true, true, 700, 700))
        assertFalse(emojiPanelReady(true, true, 400, 0))
        assertFalse(emojiPanelReady(true, true, 0, 700))
        assertTrue(emojiPanelReady(true, true, 0, 0))
        assertFalse(emojiPanelReady(false, true, 0, 0))
        assertFalse(emojiPanelReady(true, false, 0, 0))
    }
    @Test fun reservedSpaceDoesNotCollapseDuringKeyboardHandoff() {
        for (ime in listOf(700, 400, 0)) assertEquals(700, emojiReservedBottom(ime, 700, 2000, true))
    }
}
