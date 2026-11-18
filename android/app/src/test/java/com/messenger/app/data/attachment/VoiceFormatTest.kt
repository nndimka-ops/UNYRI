package com.messenger.app.data.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure voice-format contract checks (Phase 5 §35): duration formatting,
 *  deterministic waveform, played-fraction clamping and narrow-width bar
 *  count — no Android framework types. */
class VoiceFormatTest {

    @Test
    fun `duration formats m ss`() {
        assertEquals("0:00", VoiceFormat.durationText(0))
        assertEquals("0:01", VoiceFormat.durationText(1_000))
        assertEquals("0:59", VoiceFormat.durationText(59_000))
        assertEquals("1:05", VoiceFormat.durationText(65_000))
        assertEquals("10:00", VoiceFormat.durationText(600_000))
    }

    @Test
    fun `duration switches to hours when needed`() {
        assertEquals("1:00:00", VoiceFormat.durationText(3_600_000))
        assertEquals("100:00:00", VoiceFormat.durationText(360_000_000))
    }

    @Test
    fun `negative duration clamps to zero`() {
        assertEquals("0:00", VoiceFormat.durationText(-5))
    }

    @Test
    fun `waveform heights stay bounded and stable`() {
        for (bar in listOf(Int.MIN_VALUE, -1, 0, 1, 21, Int.MAX_VALUE)) {
            val h = VoiceFormat.waveformHeight(seed = 42, bar = bar)
            assertTrue(h in 6f..28f, "height $h must stay in 6..28")
        }
        assertEquals(
            VoiceFormat.waveformHeight(seed = 42, bar = 7),
            VoiceFormat.waveformHeight(seed = 42, bar = 7),
            "waveform is deterministic",
        )
    }

    @Test
    fun `played fraction clamps to 0 to 1`() {
        assertEquals(0f, VoiceFormat.playedFraction(-10, 5_000))
        assertEquals(1f, VoiceFormat.playedFraction(10_000, 5_000))
        assertEquals(0.5f, VoiceFormat.playedFraction(2_500, 5_000))
        assertEquals(0f, VoiceFormat.playedFraction(0, 0), "zero duration cannot divide by zero")
    }

    @Test
    fun `bar count stays within signature range`() {
        assertEquals(20, VoiceFormat.barCount(60f))
        assertEquals(32, VoiceFormat.barCount(200f))
        for (width in floatArrayOf(60f, 90f, 118f, 150f, 200f)) {
            assertTrue(VoiceFormat.barCount(width) in 20..32, "barCount($width) out of range")
        }
    }
}
