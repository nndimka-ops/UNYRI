package com.messenger.app.ui.chats

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class ReactionPickerInteractionTest {
    @Test fun openingMenuDuringLongPressKeepsTheOriginalMoveAndRelease() {
        val tracker = ReactionPressTracker()
        val received = mutableListOf<Pair<Offset, Boolean>>()
        tracker.event(Offset(30f, 200f), down = true, wasDown = false)
        tracker.attach { point, released ->
            assertEquals(released, tracker.releasingContinuous)
            received += point to released
        }
        tracker.event(Offset(90f, 100f), down = true, wasDown = true)
        tracker.event(Offset(90f, 100f), down = false, wasDown = true)
        assertEquals(listOf(Offset(30f, 200f) to false, Offset(90f, 100f) to false,
            Offset(90f, 100f) to true), received)
        // A later tap belongs to the pill's normal click handler, never to this release handler.
        tracker.event(Offset(150f, 100f), down = true, wasDown = false)
        tracker.event(Offset(150f, 100f), down = false, wasDown = true)
        assertEquals(3, received.size)
        assertFalse(tracker.releasingContinuous)
    }

    @Test fun menuOpenedAfterReleaseDoesNotTreatANewTapAsSlideSelection() {
        val tracker = ReactionPressTracker()
        tracker.event(Offset.Zero, down = true, wasDown = false)
        tracker.event(Offset.Zero, down = false, wasDown = true)
        var selections = 0
        tracker.attach { _, released -> if (released) selections++ }
        tracker.event(Offset(100f, 20f), down = true, wasDown = false)
        tracker.event(Offset(100f, 20f), down = false, wasDown = true)
        assertEquals(0, selections)
    }

    @Test fun disposedOrExpandedPillCannotSendFromAStaleOriginalRelease() {
        val tracker = ReactionPressTracker()
        tracker.event(Offset.Zero, down = true, wasDown = false)
        var selections = 0
        tracker.attach { _, released -> if (released) selections++ }
        tracker.detach()
        tracker.event(Offset(100f, 20f), down = false, wasDown = true)
        assertEquals(0, selections)
    }
}
