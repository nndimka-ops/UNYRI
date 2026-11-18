package com.messenger.app.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatKeyboardLiftTest {
    @Test fun closedFloatingAndNavigationOnlyDoNotLift() {
        assertEquals(0, chatKeyboardLift(0, 24, 0))
        assertEquals(0, chatKeyboardLift(24, 24, 0))
        assertEquals(0, chatKeyboardLift(12, 24, 0))
        assertEquals(0, chatKeyboardLift(-1, -1, -1))
    }

    @Test fun systemImeUsesEveryFrameBeyondTheNavigationInset() {
        for (bottom in 0..700) assertEquals((bottom - 24).coerceAtLeast(0), chatKeyboardLift(bottom, 24, 0))
        for (bottom in 700 downTo 0) assertEquals((bottom - 24).coerceAtLeast(0), chatKeyboardLift(bottom, 24, 0))
        assertEquals(700, chatKeyboardLift(700, 0, 0))
    }

    @Test fun emojiAndImeShareOneFootprintInBothHandoffDirections() {
        assertEquals(676, chatKeyboardLift(0, 24, 700))
        for (ime in 0..700) assertEquals(676, chatKeyboardLift(ime, 24, 700))
        assertEquals(776, chatKeyboardLift(800, 24, 700))
        for (reserved in 700 downTo 0) assertEquals(676, chatKeyboardLift(700, 24, reserved))
    }

    @Test fun shortChatRowsStayUntilTheKeyboardReachesTheNewestRow() {
        // Rows end 900 px above the composer edge: a 860 px keyboard moves nothing.
        val slack = timelineBottomSlack(lastVisibleIndex = 3, totalItems = 4, lastVisibleEnd = 600, contentLimit = 1500)
        assertEquals(900, slack)
        assertEquals(0, chatTimelineShift(860, slack))
        // The newest row sits 300 px above the edge: only the covered 560 px move.
        assertEquals(560, chatTimelineShift(860, timelineBottomSlack(3, 4, 1200, 1500)))
        // Every animation frame stays continuous and never exceeds the input lift.
        for (lift in 0..860) assertEquals((lift - 300).coerceAtLeast(0), chatTimelineShift(lift, 300))
    }

    @Test fun emptyChatNeverMovesAndLongOrScrolledChatMovesFully() {
        assertEquals(0, chatTimelineShift(860, timelineBottomSlack(null, 0, 0, 1500)))
        // Newest row at the composer edge, newest row off screen, or no layout yet.
        assertEquals(860, chatTimelineShift(860, timelineBottomSlack(40, 41, 1500, 1500)))
        assertEquals(860, chatTimelineShift(860, timelineBottomSlack(20, 41, 1500, 1500)))
        assertEquals(860, chatTimelineShift(860, timelineBottomSlack(null, 41, 0, 1500)))
        // Rows already past the edge never produce negative space.
        assertEquals(860, chatTimelineShift(860, timelineBottomSlack(40, 41, 1600, 1500)))
        assertEquals(0, chatTimelineShift(-5, 0))
    }

    @Test fun reservationCloseReturnsExactlyToTheOriginalWindow() {
        for (bottom in 700 downTo 0) assertEquals((bottom - 24).coerceAtLeast(0), chatKeyboardLift(0, 24, bottom))
    }
}
