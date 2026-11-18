package com.messenger.app.ui.chats

import org.junit.Assert.*
import org.junit.Test

class ChatTailTrackerTest {
    @Test fun unchangedAndPrependedHistoryDoNotFollow() {
        val tracker = ChatTailTracker("b", "b", setOf("a", "b"))
        assertFalse(tracker.update("b", "b", setOf("a", "b")))
        assertFalse(tracker.update("b", "b", setOf("older", "a", "b")))
        assertFalse(tracker.update("older", "older", setOf("older")))
    }
    @Test fun deletingOwnTailDoesNotBecomeANewMessage() {
        val tracker = ChatTailTracker("b", "b", setOf("a", "b"))
        assertFalse(tracker.update("a", "a", setOf("a")))
        assertFalse(tracker.update(null, null, emptySet()))
    }
    @Test fun incomingMessageDuringDeletionStillFollows() {
        val tracker = ChatTailTracker("b", "b", setOf("a", "b"))
        assertTrue(tracker.update("new", "new", setOf("a", "new")))
        assertFalse(tracker.update("new", "new", setOf("a", "new")))
    }
    @Test fun firstMessageAndNewCallFollow() {
        val tracker = ChatTailTracker(null, null, emptySet())
        assertTrue(tracker.update("a", "a", setOf("a")))
        assertTrue(tracker.update("a", "call", setOf("a", "call")))
        assertFalse(tracker.update("a", "a", setOf("a")))
    }
    @Test fun appendingAlbumMemberWithSameRowKeyFollows() {
        val tracker = ChatTailTracker("a", "a", setOf("a"))
        assertTrue(tracker.update("b", "a", setOf("a", "b")))
    }
}
