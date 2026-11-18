package com.messenger.app.data.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderedFrameWindowTest {
    @Test fun `a frame after a long idle interval is not a stall or a late frame`() {
        val window = RenderedFrameWindow(60f)
        assertNull(window.frame(8_000_000, 0)!!.summary)
        val afterIdle = assertNotNull(window.frame(9_000_000, 60_000_000_000))
        assertFalse(afterIdle.stalled)
        val summary = assertNotNull(afterIdle.summary)
        assertEquals(2, summary.frames)
        assertEquals(0, summary.lateFrames)
        assertEquals(9.0, summary.maximumDurationMillis)
    }

    @Test fun `lateness follows the display rendering budget and actual total duration`() {
        val window = RenderedFrameWindow(120f)
        window.frame(12_500_000, 0) // Exactly 1.5 budgets is not late.
        val summary = assertNotNull(window.frame(12_500_001, 5_000_000_000)!!.summary)
        assertEquals(2, summary.frames)
        assertEquals(1, summary.lateFrames)
        assertEquals(12.500001, summary.maximumDurationMillis, .000001)
    }

    @Test fun `only long rendered frames emit bounded stall marks`() {
        val window = RenderedFrameWindow(60f)
        assertFalse(window.frame(80_000_000, 0)!!.stalled)
        assertTrue(window.frame(81_000_000, 1)!!.stalled)
        assertFalse(window.frame(90_000_000, 500_000_000)!!.stalled)
        assertTrue(window.frame(100_000_000, 1_000_000_002)!!.stalled)
    }

    @Test fun `missing metrics do not count and each summary starts a fresh bounded window`() {
        val window = RenderedFrameWindow(Float.NaN)
        assertEquals(60.0, window.maximumFps)
        assertNull(window.frame(-1, 0))
        window.frame(30_000_000, 1)
        val first = assertNotNull(window.frame(10_000_000, 5_000_000_001)!!.summary)
        assertEquals(2, first.frames)
        assertEquals(1, first.lateFrames)
        val next = assertNotNull(window.frame(8_000_000, 10_000_000_001)!!.summary)
        assertEquals(1, next.frames)
        assertEquals(0, next.lateFrames)
        assertEquals(8.0, next.maximumDurationMillis)
    }
}
