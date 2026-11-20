package com.messenger.app.ui

import kotlin.test.*

class DrawerCaptionPolicyTest {
    @Test fun aNameThatFitsTwoLinesKeepsTheNormalSize() {
        val attempts = mutableListOf<Pair<Float, Int>>()
        val chosen = DrawerCaptionPolicy.nameSize { size, lines ->
            attempts += size to lines
            lines >= 2
        }
        assertEquals(17f, chosen)
        assertEquals(listOf(17f to 2), attempts)
    }

    @Test fun overflowChoosesTheLargestFittingSizeWithTwoLinesAtEveryAttempt() {
        val attempts = mutableListOf<Pair<Float, Int>>()
        val chosen = DrawerCaptionPolicy.nameSize { size, lines ->
            attempts += size to lines
            size <= 15.5f
        }
        assertEquals(15.5f, chosen)
        assertEquals(listOf(17f to 2, 16.5f to 2, 16f to 2, 15.5f to 2), attempts)
    }

    @Test fun anUnfittableLegacyNameStopsAtTheFloorForTailEllipsis() {
        val attempts = mutableListOf<Float>()
        assertEquals(13f, DrawerCaptionPolicy.nameSize { size, _ -> attempts += size; false })
        assertEquals(9, attempts.size)
        assertEquals(17f, attempts.first())
        assertEquals(13f, attempts.last())
        assertTrue(attempts.all { it >= 13f })
    }

    @Test fun captionUsesTheIosTextSizesAndCompactGaps() {
        assertEquals(17f, DrawerCaptionPolicy.nameSizeSp)
        assertEquals(13f, DrawerCaptionPolicy.minimumNameSizeSp)
        assertEquals(21f, DrawerCaptionPolicy.nameLineHeightSp)
        assertEquals(12f, DrawerCaptionPolicy.numberSizeSp)
        assertEquals(15f, DrawerCaptionPolicy.numberLineHeightSp)
        assertEquals(2, DrawerCaptionPolicy.nameMaxLines)
        assertEquals(2f, DrawerCaptionPolicy.textGapDp)
        assertEquals(8f, DrawerCaptionPolicy.rowGapDp)
    }
}
