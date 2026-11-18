package com.messenger.app.ui.chats
import kotlin.test.*
class MediaPresentationHoldTest {
    @Test fun heldViewerKeepsExactRowsAndCatchesUpOnlyAfterReturnTransition() {
        val hold = MediaPresentationHold<List<Int>>(); var loads = 0
        val original = hold.display(false) { loads++; listOf(1) }
        assertSame(original, hold.display(true) { loads++; listOf(1, 2) })
        assertEquals(1, loads)
        assertEquals(listOf(1, 2), hold.display(false) { loads++; listOf(1, 2) })
        assertEquals(2, loads)
    }

    @Test fun viewerAndReadBlockHandoffsKeepOnePresentationUntilBothRelease() {
        for (viewerFirst in listOf(true, false)) {
            val hold = MediaPresentationHold<List<Int>>()
            var loads = 0
            var latest = listOf(1)
            fun display(viewer: Boolean, readBlocked: Boolean) = hold.display(viewer || readBlocked) { loads++; latest }
            val original = display(false, false)
            latest = listOf(1, 2)
            assertSame(original, display(viewerFirst, !viewerFirst))
            assertSame(original, display(true, true))
            latest = listOf(1, 2, 3)
            assertSame(original, display(!viewerFirst, viewerFirst))
            assertEquals(1, loads) // Even the cache update is deferred while either blocker remains.
            assertEquals(latest, display(false, false))
            assertEquals(2, loads)
        }
    }

    @Test fun firstCoveredPresentationSeedsOnceThenWaitsForUncovering() {
        val hold = MediaPresentationHold<List<Int>>()
        var loads = 0
        val seed = hold.display(true) { loads++; emptyList() }
        assertSame(seed, hold.display(true) { error("covered update must stay deferred") })
        assertEquals(listOf(1), hold.display(false) { loads++; listOf(1) })
        assertEquals(2, loads)
    }
}
