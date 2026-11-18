package com.messenger.app.data.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RendererSlotTest {

    /** Code-review 2 regression: an OLD renderer's disposal must NEVER detach
     *  a NEWER renderer that was attached in the meantime (the background /
     *  rotation black-screen race). */
    @Test
    fun `old renderer dispose does not detach a newer renderer`() {
        val slot = RendererSlot<Any>()
        val old = Any()
        val new = Any()

        assertTrue(slot.attach(old), "first attach must change the slot")
        assertEquals(old, slot.attached)

        assertTrue(slot.attach(new), "attaching a newer renderer must replace the old")
        assertEquals(new, slot.attached)

        // The stale DisposableEffect for `old` fires: it must be a no-op.
        assertFalse(slot.detach(old), "an old renderer must not clear the slot")
        assertEquals(new, slot.attached, "the newer renderer must remain current")

        // The correct disposal still works.
        assertTrue(slot.detach(new))
        assertNull(slot.attached)
    }

    @Test
    fun `attaching the same renderer twice is a no-op`() {
        val slot = RendererSlot<Any>()
        val renderer = Any()

        assertTrue(slot.attach(renderer))
        assertFalse(slot.attach(renderer), "re-attaching the same renderer must be a no-op")
        assertEquals(renderer, slot.attached)
    }

    @Test
    fun `clear is unconditional`() {
        val slot = RendererSlot<Any>()
        val renderer = Any()
        slot.attach(renderer)
        slot.clear()
        assertNull(slot.attached)
    }

    @Test
    fun `detaching null never clears the slot`() {
        val slot = RendererSlot<Any>()
        val renderer = Any()
        slot.attach(renderer)
        assertFalse(slot.detach(null))
        assertEquals(renderer, slot.attached)
    }
}
