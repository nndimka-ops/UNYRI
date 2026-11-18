package com.messenger.app.ui.chats

import org.junit.Assert.*
import org.junit.Test

class ChatViewportPolicyTest {
    @Test fun readingPreviousPhotoDoesNotFollowNewMessages() {
        assertFalse(ChatViewportPolicy.isNearBottom(10, 8, 900, 800, 120))
    }
    @Test fun tallLastPhotoDoesNotCountAsReadingTheEnd() {
        assertFalse(ChatViewportPolicy.isNearBottom(10, 9, 1500, 800, 120))
    }
    @Test fun composerOcclusionIsExcludedByCaller() {
        assertFalse(ChatViewportPolicy.isNearBottom(10, 9, 950, 800, 120))
        assertTrue(ChatViewportPolicy.isNearBottom(10, 9, 900, 800, 120))
    }
    @Test fun shortChatAndExactThresholdFollow() {
        assertTrue(ChatViewportPolicy.isNearBottom(1, 0, 200, 800, 120))
        assertTrue(ChatViewportPolicy.isNearBottom(10, 9, 920, 800, 120))
    }
    @Test fun emptyLayoutDoesNotFollow() {
        assertFalse(ChatViewportPolicy.isNearBottom(0, -1, 0, 800, 120))
    }
    @Test fun jumpRequiresMeasuredScrollableHistory() {
        fun show(count: Int = 10, measured: Int = count, height: Int = 800,
                 coherent: Boolean = true, scrollable: Boolean = true, near: Boolean = false, ready: Boolean = true) =
            ChatViewportPolicy.showJumpToLatest(ready, count, measured, height, coherent, scrollable, near)
        assertFalse(show(count = 0))
        assertFalse(show(measured = 0))
        assertFalse(show(height = 0))
        assertFalse(show(coherent = false)) // restored/prepended keys not measured yet
        assertFalse(show(ready = false))
        assertFalse(show(scrollable = false, near = false)) // actual end, even stale geometry/count
        assertFalse(show(near = true)) // pending badge cannot force visibility
        assertTrue(show()) // reading older rows
        assertTrue(show(near = ChatViewportPolicy.isNearBottom(10, 9, 1500, 800, 120))) // tall final media
        assertFalse(show(count = 1, scrollable = false, near = true)) // short chat
        assertFalse(show(measured = 9)) // inserted row awaiting layout
    }

}
