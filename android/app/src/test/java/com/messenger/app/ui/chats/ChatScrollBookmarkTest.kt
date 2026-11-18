package com.messenger.app.ui.chats

import org.junit.Assert.*
import org.junit.Test

class ChatScrollBookmarkTest {
    @Test fun returningFromBottomFollowsNewMessages() {
        assertEquals(3 to 0, ChatScrollBookmark("m1", 15, true).resolve(listOf("m0", "m1", "call", "m2")))
    }
    @Test fun readingHistoryKeepsAnchorAfterPrepend() {
        assertEquals(2 to 27, ChatScrollBookmark("m1", 27, false).resolve(listOf("older", "call", "m1", "m2")))
    }
    @Test fun missingAnchorAndEmptyHistoryAreSafe() {
        val bookmark = ChatScrollBookmark("deleted", 27, false)
        assertEquals(1 to 0, bookmark.resolve(listOf("m1", "m2")))
        assertNull(bookmark.resolve(emptyList()))
    }
}
