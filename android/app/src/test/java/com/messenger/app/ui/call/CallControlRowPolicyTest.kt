package com.messenger.app.ui.call

import com.messenger.app.ui.call.CallControlRowPolicy.Control.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallControlRowPolicyTest {
    @Test fun `same phases as iOS CallControlSlotsContracts`() {
        assertEquals(listOf(END), CallControlRowPolicy.audio(false, false, false))
        assertEquals(listOf(MUTE, END, OUTPUT), CallControlRowPolicy.audio(false, true, false))
        assertEquals(listOf(VIDEO, MUTE, END, OUTPUT), CallControlRowPolicy.audio(true, true, false))
        assertEquals(listOf(MUTE, END, OUTPUT), CallControlRowPolicy.audio(false, true, false)) // upgrading
        assertTrue(CallControlRowPolicy.audio(true, true, true).isEmpty())
        assertEquals(listOf(END), CallControlRowPolicy.video(false, false))
        assertEquals(listOf(CAMERA, MUTE, END, OUTPUT), CallControlRowPolicy.video(true, false))
        for (live in listOf(false, true)) {
            assertTrue(CallControlRowPolicy.video(live, true).isEmpty())
            assertTrue(CallControlRowPolicy.audio(true, live, true).isEmpty())
        }
    }

    @Test fun `same control sizes and even gaps with no empty square across all phases`() {
        for (size in listOf(52, 60, 64)) for (spacing in listOf(8, 16, 24)) for (count in 1..4) {
            val width = 4 * size + 3 * spacing
            val geometry = CallControlRowPolicy.geometry(count, width, size, spacing, 16)
            assertEquals(count, geometry.positions.size)
            assertEquals(size, geometry.height)
            val first = geometry.positions.first()
            val last = geometry.positions.last()
            assertTrue(kotlin.math.abs((first.x + last.x + size) / 2f - width / 2f) <= .5f)
            geometry.positions.zipWithNext().forEach { (a, b) -> assertEquals(size + spacing, b.x - a.x) }
        }
    }

    @Test fun `wrapped rows each center and empty row has no height`() {
        assertEquals(CallControlRowPolicy.Geometry(emptyList(), 0), CallControlRowPolicy.geometry(0, 200, 64, 8, 16))
        for (count in 1..4) {
            val geometry = CallControlRowPolicy.geometry(count, 160, 64, 8, 16, 2)
            for (row in geometry.positions.groupBy { it.y }.values) {
                assertTrue(kotlin.math.abs((row.first().x + row.last().x + 64) / 2f - 80f) <= .5f)
            }
            assertEquals(if (count > 2) 144 else 64, geometry.height)
        }
    }

    @Test fun `remove animations uses fade only without placement or scale`() {
        assertEquals(150, CallControlRowPolicy.motion(true).fadeMillis)
        assertEquals(1f, CallControlRowPolicy.motion(true).initialScale)
        assertFalse(CallControlRowPolicy.motion(true).move)
        assertEquals(.8f, CallControlRowPolicy.motion(false).initialScale)
        assertTrue(CallControlRowPolicy.motion(false).move)
    }
}
