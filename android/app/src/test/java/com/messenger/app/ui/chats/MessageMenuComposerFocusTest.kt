package com.messenger.app.ui.chats

import kotlin.test.*

class MessageMenuComposerFocusTest {
    private fun mounted() = MessageMenuComposerFocus().apply { inputPlaced() }

    @Test fun `selection updates the target and dismisses before requesting focus`() {
        val focus = mounted()
        val events = mutableListOf<String>()
        focus.choose({
            assertFalse(focus.request.pending)
            events += "target"
        }, {
            assertFalse(focus.request.pending)
            events += "dismiss"
        })
        assertEquals(listOf("target", "dismiss"), events)
        assertTrue(focus.request.pending)
    }

    @Test fun `request waits for both dismissal and host window focus`() {
        val focus = mounted()
        focus.choose({}, {})
        val ticket = focus.request.revision
        assertFalse(focus.consume(ticket, menuOpen = true, windowFocused = true))
        assertFalse(focus.consume(ticket, menuOpen = false, windowFocused = false))
        assertTrue(focus.request.pending)
        assertTrue(focus.consume(ticket, menuOpen = false, windowFocused = true))
    }

    @Test fun `a selected action focuses only once even if window focus changes again`() {
        val focus = mounted()
        focus.choose({}, {})
        val ticket = focus.request.revision
        assertTrue(focus.consume(ticket, false, true))
        assertFalse(focus.consume(ticket, false, false))
        assertFalse(focus.consume(ticket, false, true))
    }

    @Test fun `a replacement action invalidates the older request`() {
        val focus = mounted()
        focus.choose({}, {})
        val old = focus.request.revision
        focus.choose({}, {})
        assertFalse(focus.consume(old, false, true))
        assertTrue(focus.consume(focus.request.revision, false, true))
    }

    @Test fun `opening a replacement menu cancels the pending handoff`() {
        val focus = mounted()
        focus.menuChanged("first")
        focus.choose({}, {})
        val ticket = focus.request.revision
        focus.menuChanged("replacement")
        assertFalse(focus.consume(ticket, false, true))
        focus.menuChanged(null)
        focus.choose({}, {})
        focus.menuChanged(null)
        assertTrue(focus.consume(focus.request.revision, false, true))
    }

    @Test fun `unchanged menu recomposition preserves a selected action while it dismisses`() {
        val focus = mounted()
        focus.menuChanged("message")
        focus.choose({}, {})
        focus.menuChanged("message")
        assertTrue(focus.request.pending)
        focus.menuChanged(null)
        assertTrue(focus.consume(focus.request.revision, false, true))
    }

    @Test fun `disposing the owner invalidates the request without focusing`() {
        val focus = mounted()
        focus.choose({}, {})
        val ticket = focus.request.revision
        focus.cancel()
        assertFalse(focus.consume(ticket, false, true))
    }

    @Test fun `detaching the input cancels focus rather than stealing it after remount`() {
        val focus = mounted()
        focus.choose({}, {})
        val ticket = focus.request.revision
        focus.inputDetached()
        focus.inputPlaced()
        assertFalse(focus.consume(ticket, false, true))
    }

    @Test fun `unavailable input and ordinary dismissal never create a handoff`() {
        val focus = MessageMenuComposerFocus()
        focus.choose({}, {})
        focus.inputPlaced()
        assertFalse(focus.consume(focus.request.revision, false, true))
        assertFalse(mounted().consume(0, false, true))
    }

    @Test fun `an unsuccessful target action cannot dismiss or open the keyboard`() {
        val focus = mounted()
        focus.choose({}, {})
        val old = focus.request.revision
        var dismissed = false
        assertFailsWith<IllegalStateException> {
            focus.choose({ error("Target no longer available") }, { dismissed = true })
        }
        assertFalse(dismissed)
        assertFalse(focus.request.pending)
        assertFalse(focus.consume(old, false, true))
    }
}
