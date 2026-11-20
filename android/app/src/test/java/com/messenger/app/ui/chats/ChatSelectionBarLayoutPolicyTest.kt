package com.messenger.app.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSelectionBarLayoutPolicyTest {
    @Test fun islandKeepsParityMarginsAndCornerRadius() {
        assertEquals(12f, ChatSelectionBarLayoutPolicy.sideMarginDp, 0f)
        assertEquals(6f, ChatSelectionBarLayoutPolicy.navigationGapDp, 0f)
        assertEquals(26f, ChatSelectionBarLayoutPolicy.cornerRadiusDp, 0f)
    }

    @Test fun islandClearsAbsentGestureAndThreeButtonNavigation() {
        for (navigationBottomDp in listOf(0f, 24f, 48f)) {
            assertEquals(navigationBottomDp + 6f,
                ChatSelectionBarLayoutPolicy.bottomInsetDp(0f, navigationBottomDp), 0f)
        }
    }

    @Test fun scaffoldAndNavigationInsetsAreNotAddedTwice() {
        for (navigationBottomDp in listOf(24f, 48f)) {
            assertEquals(navigationBottomDp + 6f,
                ChatSelectionBarLayoutPolicy.bottomInsetDp(navigationBottomDp, navigationBottomDp), 0f)
        }
    }

    @Test fun largerHostInsetIsPreserved() {
        assertEquals(70f, ChatSelectionBarLayoutPolicy.bottomInsetDp(64f, 48f), 0f)
        assertEquals(54f, ChatSelectionBarLayoutPolicy.bottomInsetDp(24f, 48f), 0f)
    }

    @Test fun selectingListKeepsSpacingWhileFlowBarReservesNavigationClearance() {
        for (hostBottomDp in listOf(0f, 24f, 48f)) {
            assertEquals(32f,
                ChatSelectionBarLayoutPolicy.listBottomPaddingDp(true, 32f, hostBottomDp), 0f)
            assertEquals(32f + hostBottomDp,
                ChatSelectionBarLayoutPolicy.listBottomPaddingDp(false, 32f, hostBottomDp), 0f)
        }
    }
}
