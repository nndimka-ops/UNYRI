package com.messenger.app.ui.chats

import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class ChatBottomPinTest {
    @Test fun `a gesture cancelling one correction does not stop subsequent layout corrections`() = runTest {
        var corrected = 0
        chatBottomCorrection { throw CancellationException("User scroll took ownership") }
        chatBottomCorrection { corrected++ }
        assertEquals(1, corrected)
    }

    private fun ChatBottomPin.anchor(covered: Boolean = false, scrolling: Boolean = false) =
        shouldAnchor(ready = true, covered = covered, scrolling = scrolling)

    @Test fun `arrival uses prior bottom intent even when new row is offscreen`() {
        val pin = ChatBottomPin(true)
        assertFalse(ChatViewportPolicy.isNearBottom(11, 9, 800, 800, 120))
        assertTrue(pin.arrival(own = false, addressedOffer = false))
        assertTrue(pin.anchor())
    }

    @Test fun `reading history keeps position on arrival and later growth`() {
        val pin = ChatBottomPin(true)
        pin.userScroll()
        pin.settled(nearBottom = false)
        assertFalse(pin.arrival(own = false, addressedOffer = false))
        assertFalse(pin.anchor())
        assertEquals(240, chatBottomOverflow(1040, 900, 100))
        assertFalse(pin.anchor())
    }

    @Test fun `own message and addressed offer independently resume following from history`() {
        for ((own, offer) in listOf(true to false, false to true)) {
            val pin = ChatBottomPin(false)
            assertTrue(pin.arrival(own, offer))
            assertTrue(pin.anchor())
        }
    }

    @Test fun `offer and completed card stay pinned through successive measures without new IDs`() {
        val pin = ChatBottomPin(true)
        // One stable row: initial offer, its buttons, and the completed preview.
        for ((bottom, expected) in listOf(800 to 0, 970 to 170, 1160 to 360)) {
            assertEquals(expected, chatBottomOverflow(bottom, 920, 120))
            assertTrue(pin.anchor())
        }
        assertFalse(ChatViewportPolicy.isNearBottom(10, 9, 1160, 800, 120))
        assertTrue(pin.pinned) // Growing past the threshold does not become user intent.
    }

    @Test fun `viewer close reanchors unchanged tail only if previously pinned`() {
        for (initial in listOf(true, false)) {
            val pin = ChatBottomPin(initial)
            assertFalse(pin.anchor(covered = true))
            assertEquals(initial, pin.anchor(covered = false))
        }
    }

    @Test fun `gesture cancels a pending correction and reaching bottom enables future growth`() {
        val pin = ChatBottomPin(true)
        pin.userScroll()
        assertFalse(pin.anchor(scrolling = true))
        pin.settled(nearBottom = false)
        assertFalse(pin.anchor())
        pin.userScroll()
        pin.settled(nearBottom = true)
        assertTrue(pin.anchor())
        pin.release() // Search/reply/notification jump owns the position.
        assertFalse(pin.anchor())
        pin.follow() // Explicit jump-to-latest.
        assertTrue(pin.anchor())
        assertFalse(pin.shouldAnchor(ready = false, covered = false, scrolling = false))
    }

    @Test fun `explicit target or own arrival supersedes a gesture that is settling`() {
        val pin = ChatBottomPin(true)
        pin.userScroll()
        pin.release() // A reply/search jump takes ownership before the fling stops.
        pin.settled(nearBottom = true)
        assertFalse(pin.pinned)
        pin.userScroll()
        assertTrue(pin.arrival(own = true, addressedOffer = false))
        pin.settled(nearBottom = false)
        assertTrue(pin.pinned)
        pin.userScroll() // Further finger input always wins over that arrival.
        assertFalse(pin.pinned)
    }

    @Test fun `composer navigation padding and IME translation are counted once`() {
        // 76 px composer + 24 px navigation bar + existing 12 px gap.
        assertEquals(112, chatBottomOverflow(1000, 1000, 112))
        val lift = chatKeyboardLift(324, 24, 0)
        // Both content and its bottom boundary move up by the same 300 px.
        assertEquals(112, chatBottomOverflow(1000 - lift, 1000 - lift, 112))
        assertEquals(0, chatBottomOverflow(700, 1000, 112)) // Short chat stays in place.
    }
}
