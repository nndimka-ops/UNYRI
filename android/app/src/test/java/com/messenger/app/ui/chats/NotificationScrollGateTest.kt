package com.messenger.app.ui.chats

import kotlin.test.*

class NotificationScrollGateTest {
    @Test fun `a new tap for the same message rearms its completed jump and invalidates queued tail follows`() {
        val gate = NotificationScrollGate("message") // A search jump has no notification nonce.
        gate.complete(gate.generation)
        val tail = gate.followTicket()!!
        gate.updateTarget("message")
        assertNotNull(gate.followTicket()) // Recomposition/hydration is not another tap.
        gate.updateTarget("message", "tap-1")
        assertNull(gate.followTicket())
        assertFalse(gate.mayFollow(tail))
        assertTrue(gate.holds(gate.generation))
    }

    @Test fun `a superseded lookup for the same message cannot complete the new tap`() {
        val gate = NotificationScrollGate("message", "tap-1")
        val stale = gate.generation
        gate.updateTarget("message", "tap-2")
        val current = gate.generation
        gate.complete(stale)
        assertTrue(gate.holds(current))
        gate.complete(current)
        assertNotNull(gate.followTicket())
    }

    @Test fun `late target owns scrolling through its layout frame then new messages may follow`() {
        val gate = NotificationScrollGate("server-target")
        val owner = gate.generation
        assertNull(gate.followTicket()) // Initial cached snapshot.
        assertNull(gate.followTicket()) // Target plus newer own tail arrives.
        assertTrue(gate.holds(owner)) // Target scroll has run; its frame has not.
        gate.complete(owner)
        val next = gate.followTicket()!!
        assertTrue(gate.mayFollow(next))
    }
    @Test fun `tail follow already suspended for a frame cannot overwrite a completed target jump`() {
        val gate = NotificationScrollGate(null)
        val queued = gate.followTicket()!!
        gate.updateTarget("late-target")
        val owner = gate.generation
        assertFalse(gate.mayFollow(queued))
        gate.complete(owner)
        assertFalse(gate.mayFollow(queued)) // Still invalid after ownership releases.
        assertTrue(gate.mayFollow(gate.followTicket()!!))
    }
    @Test fun `old lookup cannot release new target and an unchanged route does not restart ownership`() {
        val gate = NotificationScrollGate("one")
        val old = gate.generation
        gate.updateTarget("two")
        gate.complete(old)
        assertNull(gate.followTicket())
        val current = gate.generation
        gate.updateTarget("two")
        assertEquals(current, gate.generation)
        gate.complete(current)
        assertNotNull(gate.followTicket()) // Also how an unavailable target times out.
        gate.updateTarget(null)
        assertTrue(gate.mayFollow(gate.followTicket()!!))
    }
}
