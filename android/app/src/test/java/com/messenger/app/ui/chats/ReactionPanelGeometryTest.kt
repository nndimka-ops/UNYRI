package com.messenger.app.ui.chats

import org.junit.Assert.*
import org.junit.Test

class ReactionPanelGeometryTest {
    private fun frame(width: Float = 390f, height: Float = 844f, bubble: ReactionRect = ReactionRect(160f, 690f, 374f, 752f),
                      top: Float = 47f, bottom: Float = 34f, left: Float = 0f, right: Float = 0f, outgoing: Boolean = true) =
        ReactionPanelGeometry.frame(width, height, bubble, top, bottom, left, right, outgoing)

    @Test fun lastBubblePanelLeavesBarsSearchAndFirstRowClear() {
        val panel = frame()
        assertEquals(16f, panel.left, .001f)
        assertEquals(374f, panel.right, .001f)
        assertTrue(panel.top >= 55f)
        assertTrue(panel.bottom <= 802f)
        assertTrue(panel.height <= 844f * .6f)
        assertTrue(panel.bottom < 690f)
        assertTrue("Search, recents and first row have a useful viewport", panel.height >= 240f)
    }

    @Test fun nearTopBubbleUsesBelowAndLandscapeCapsAt420() {
        val panel = frame(bubble = ReactionRect(16f, 60f, 150f, 108f), outgoing = false)
        assertEquals(116f, panel.top, .001f)
        val landscape = frame(844f, 390f, ReactionRect(600f, 260f, 790f, 320f), 0f, 21f, 47f, 47f)
        assertEquals(420f, landscape.width, .001f)
        assertTrue(landscape.left >= 63f && landscape.right <= 781f)
        assertTrue(landscape.top >= 8f && landscape.bottom <= 361f)
    }

    @Test fun keyboardOcclusionRecomputesPanelWithoutMovingBubble() {
        val bubble = ReactionRect(160f, 580f, 374f, 642f)
        val keyboard = frame(bubble = bubble, bottom = 346f)
        assertTrue(keyboard.bottom <= 490f)
        assertTrue(keyboard.top >= 55f)
        assertTrue(keyboard.height >= 240f)
        assertEquals(580f, bubble.top, 0f)
    }

    @Test fun asymmetricInsetsAndTinyWindowsNeverEscapeSafeRectangle() {
        val panel = frame(720f, 420f, ReactionRect(24f, 175f, 180f, 215f), 32f, 160f, 40f, 12f, false)
        assertTrue(panel.left >= 56f && panel.right <= 692f)
        assertTrue(panel.top >= 40f && panel.bottom <= 252f)
        val tiny = frame(80f, 100f, ReactionRect(1f, 60f, 20f, 90f), 70f, 70f)
        assertTrue(tiny.width >= 0f && tiny.height >= 0f)
        assertTrue(tiny.top >= 78f && tiny.bottom <= 100f)
    }

    @Test fun adjustResizeKeepsTheFullWindowHeightCapWithoutCountingTheKeyboardTwice() {
        val bubble = ReactionRect(160f, 580f, 374f, 642f)
        val fullWindow = frame(bubble = bubble, bottom = 346f)
        val resized = ReactionPanelGeometry.frame(390f, 498f, bubble, 47f, 0f, 0f, 0f,
            outgoing = true, screenHeight = 844f)
        assertEquals(fullWindow, resized)
        assertTrue(resized.height > 498f * .6f)
        assertTrue(resized.bottom <= 490f)
    }
}
