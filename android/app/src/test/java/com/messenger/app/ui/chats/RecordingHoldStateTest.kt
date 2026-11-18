package com.messenger.app.ui.chats

import kotlin.test.*

class RecordingHoldStateTest {
    @Test fun `partial and exact boundary slides release to send`() {
        for (width in listOf(240f, 320f, 378f, 402f, 800f)) {
            val hold = RecordingHoldState(width)
            assertEquals(RecordingHoldState.Outcome.Holding, hold.move(-hold.cancelDistance, -hold.lockDistance))
            assertEquals(RecordingHoldState.Outcome.Send, hold.end())
            assertEquals(RecordingHoldState.Outcome.Send, hold.end(interrupted = true))
        }
    }

    @Test fun `real horizontal cancel is terminal even after direction changes or release`() {
        val hold = RecordingHoldState(402f)
        hold.move(-10f, -60f)
        assertEquals(0f, hold.x)
        assertTrue(hold.y < 0)
        assertEquals(RecordingHoldState.Outcome.Cancel, hold.move(-121f, -85f))
        assertEquals(RecordingHoldState.Outcome.Cancel, hold.end())
        assertEquals(RecordingHoldState.Outcome.Cancel, hold.move(0f, -200f))
    }

    @Test fun `diagonal cancel and lock do not depend on the illustrated axis`() {
        val cancel = RecordingHoldState(320f)
        cancel.move(0f, -70f) // vertical visual, then ambiguous diagonal
        assertEquals(RecordingHoldState.Outcome.Cancel, cancel.move(-97f, -89f))
        val lock = RecordingHoldState(402f)
        lock.move(-80f, 0f) // horizontal visual, then ambiguous diagonal
        assertEquals(RecordingHoldState.Outcome.Lock, lock.move(-85f, -91f))
        assertEquals(RecordingHoldState.Outcome.Lock, lock.end())
        assertEquals(RecordingHoldState.Outcome.Cancel, RecordingHoldState(402f).move(-125f, -100f))
    }

    @Test fun `width rule affects actual cancel outcomes on narrow and wide composers`() {
        assertEquals(RecordingHoldState.Outcome.Cancel, RecordingHoldState(240f).move(-85f, 0f))
        assertEquals(RecordingHoldState.Outcome.Holding, RecordingHoldState(378f).move(-85f, 0f))
        assertEquals(RecordingHoldState.Outcome.Holding, RecordingHoldState(800f).move(-119f, 0f))
        assertEquals(RecordingHoldState.Outcome.Cancel, RecordingHoldState(800f).move(-121f, 0f))
    }

    @Test fun `system touch cancellation locks rather than sending or discarding`() {
        val hold = RecordingHoldState(402f)
        hold.move(-40f, -20f)
        assertEquals(RecordingHoldState.Outcome.Lock, hold.end(interrupted = true))
        assertEquals(RecordingHoldState.Outcome.Lock, hold.end())
    }

    @Test fun `sub direction slop does not move the mic and direction can be reevaluated`() {
        val hold = RecordingHoldState(402f)
        hold.move(-8f, -8f)
        assertEquals(0f, hold.x); assertEquals(0f, hold.y)
        hold.move(-40f, -10f)
        assertEquals(-40f, hold.x); assertEquals(0f, hold.y)
        hold.move(-20f, -60f)
        assertEquals(0f, hold.x); assertEquals(-60f, hold.y)
        hold.move(40f, 10f)
        assertEquals(0f, hold.x); assertEquals(0f, hold.y)
    }

    @Test fun `release cancellation and background invalidate pending permission or capture startup`() {
        for (reason in listOf("release", "cancel", "background", "navigation")) {
            val gate = VoiceTakeGate()
            val pending = gate.begin()
            assertTrue(gate.current(pending), reason)
            assertTrue(gate.finish(), reason)
            assertFalse(gate.current(pending), reason)
            assertFalse(gate.finish(), reason)
            val next = gate.begin()
            assertFalse(gate.current(pending), reason)
            assertTrue(gate.current(next), reason)
        }
    }

    @Test fun `short sends discard but stop preserves short review and resumed takes can send`() {
        assertEquals(VoiceTakeDestination.Discard, voiceTakeDestination(true, 999, false))
        assertEquals(VoiceTakeDestination.Send, voiceTakeDestination(true, 1000, false))
        assertEquals(VoiceTakeDestination.Review, voiceTakeDestination(false, 400, false))
        assertEquals(VoiceTakeDestination.Send, voiceTakeDestination(true, 400, true))
        assertEquals(VoiceTakeDestination.Review, voiceTakeDestination(false, 3000, true))
    }
}
