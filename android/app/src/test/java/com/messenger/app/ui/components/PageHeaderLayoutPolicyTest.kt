package com.messenger.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class PageHeaderLayoutPolicyTest {
    @Test fun shortWordsKeepPageCentering() {
        assertEquals(PageHeaderPlacement(PageHeaderMode.Symmetric, 108, 144), pageHeaderPlacement(360, 40, 100, 8, 100))
    }

    @Test fun editProfileWordUsesSpaceBetweenControls() {
        assertEquals(PageHeaderPlacement(PageHeaderMode.Asymmetric, 48, 204), pageHeaderPlacement(360, 40, 100, 8, 180))
    }

    @Test fun largeTextMovesControlsBelowFullWidthTitle() {
        assertEquals(PageHeaderPlacement(PageHeaderMode.ActionsBelow, 0, 360), pageHeaderPlacement(360, 56, 160, 8, 250))
    }

    @Test fun exactFitDoesNotMoveControls() {
        assertEquals(PageHeaderMode.Asymmetric, pageHeaderPlacement(360, 40, 100, 8, 204).mode)
        assertEquals(PageHeaderMode.Symmetric, pageHeaderPlacement(360, 40, 100, 8, 144).mode)
    }

    @Test fun oneControlAndRtlHaveTheSameWidthBudget() {
        assertEquals(264, pageHeaderPlacement(360, 40, 0, 8, 200).titleWidth)
        assertEquals(PageHeaderMode.Asymmetric, pageHeaderPlacement(360, 0, 100, 8, 220).mode)
    }
}
