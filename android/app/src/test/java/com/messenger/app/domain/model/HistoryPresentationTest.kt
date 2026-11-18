package com.messenger.app.domain.model

import org.junit.Assert.*
import org.junit.Test

class HistoryPresentationTest {
    @Test fun recentFailuresHaveSeparateExpandableMarker() {
        val rows = listOf(message("old", 1, MessageStatus.DECRYPT_FAILED), message("latest", 12, MessageStatus.DECRYPT_FAILED),
            message("readable", 9, MessageStatus.READ), message("warning", 10, MessageStatus.IDENTITY_CHANGED), message("recent", 8, MessageStatus.DECRYPT_FAILED))
        assertEquals(listOf("latest", "readable", "warning"), collapseUnavailableHistory(rows, 5, true).map { it.clientMessageId })
        assertEquals(rows.drop(1), collapseUnavailableHistory(rows, 5, false))
        assertEquals(listOf("latest", "readable", "warning"), collapseUnavailableHistory(rows, 0, true).map { it.clientMessageId })
    }
    private fun message(id: String, sequence: Long?, status: MessageStatus) = Message(
        clientMessageId = id, serverId = id, chatId = "chat", senderId = "peer",
        text = "content", sequence = sequence, createdAt = "2026-09-13T00:00:00Z", status = status,
    )

    @Test fun hidesOldFailuresWithoutAHistoryMarker() {
        val rows = listOf(message("old1", 1, MessageStatus.DECRYPT_FAILED),
            message("readable", 2, MessageStatus.DELIVERED),
            message("old2", 3, MessageStatus.DECRYPT_FAILED),
            message("identity", 4, MessageStatus.IDENTITY_CHANGED),
            message("new", 6, MessageStatus.DECRYPT_FAILED),
            message("pending", null, MessageStatus.PENDING))
        assertEquals(listOf("readable", "identity", "new", "pending"),
            collapseUnavailableHistory(rows, 5).map { it.clientMessageId })
        assertEquals(6, rows.size) // presentation must not mutate storage/input
        assertEquals(rows, collapseUnavailableHistory(rows, 0)) // older server compatibility
    }

    @Test fun recoveringAnOldMessageMakesItVisibleAgain() {
        val rows = listOf(message("one", 1, MessageStatus.DELIVERED), message("two", 2, MessageStatus.DECRYPT_FAILED))
        assertEquals(listOf(rows.first()), collapseUnavailableHistory(rows, 2))
        assertFalse(message("warning", 1, MessageStatus.IDENTITY_CHANGED).isUnavailableHistory(2))
    }

    @Test fun paginationNeverRevealsOldFailureRows() {
        val newest = message("newest", 10, MessageStatus.DECRYPT_FAILED)
        assertEquals(emptyList<Message>(), collapseUnavailableHistory(listOf(newest, message("older", 2, MessageStatus.DECRYPT_FAILED)), 10))
    }
}
