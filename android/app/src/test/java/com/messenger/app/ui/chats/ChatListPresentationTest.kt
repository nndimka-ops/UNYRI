package com.messenger.app.ui.chats

import com.messenger.app.domain.model.ChatSummary
import kotlin.test.*

class ChatListPresentationTest {
    private fun row(id: String, at: String?, unread: Int = 0) =
        ChatSummary(id, "direct", null, "2026-11-12T08:00:00Z", at, unreadCount = unread)

    @Test fun `sort and motion share a single parsed activity for each row`() {
        val presentation = ChatListPresentation()
        val recent = row("recent", "2026-11-12T12:00:00.123456+03:00")
        val oldPinned = row("pinned", "2026-11-12T08:00:00Z")
        val missing = row("missing", null)
        val order = presentation.ordered(listOf(missing, recent, oldPinned), setOf("pinned"), emptySet(), false, false, emptyMap(), emptySet())
        assertEquals(listOf("pinned", "recent", "missing"), order.rows.map { it.chatId })
        assertEquals(1794474000123L, order.activity["recent"])
        assertEquals(Long.MIN_VALUE, order.activity["missing"])
        assertEquals(presentation.timestamp(recent.lastMessageAt), order.activity["recent"])
    }

    @Test fun `equal activity uses id descending and filters preserve activity`() {
        val rows = listOf(row("a", "2026-11-12T08:00:00Z", 1), row("b", "2026-11-12T08:00:00Z", 1), row("c", null))
        val order = ChatListPresentation().ordered(rows, emptySet(), setOf("b"), false, true, emptyMap(), emptySet())
        assertEquals(listOf("a"), order.rows.map { it.chatId })
        assertEquals(order.activity["a"], order.activity["b"])
        val archive = ChatListPresentation().ordered(rows, emptySet(), setOf("b"), true, false, emptyMap(), emptySet())
        assertEquals(listOf("b"), archive.rows.map { it.chatId })
    }

    @Test fun `invalid dates stay before known activity`() {
        val presentation = ChatListPresentation()
        assertEquals(Long.MIN_VALUE, presentation.timestamp("not-a-date"))
        assertEquals(presentation.timestamp("2026-11-12T09:00:00Z"), presentation.timestamp("2026-11-12T12:00:00+03:00"))
    }

    @Test fun `live gate waits one second and return gate waits 550 milliseconds`() {
        val clock = ChatListMotionClock()
        assertFalse(clock.live(0))
        clock.stamp(true, 100)
        assertFalse(clock.live(1099)); assertTrue(clock.live(1100))
        clock.returnedAt = 1200
        assertFalse(clock.live(1749)); assertTrue(clock.live(1750))
        clock.stamp(false, 1800); assertFalse(clock.live(10000))
    }

    @Test fun `sweep cooldown and one shot token survive a row rebind`() {
        val clock = ChatListMotionClock()
        assertTrue(clock.allowSweep("a", 100)); assertFalse(clock.allowSweep("a", 2599))
        assertTrue(clock.allowSweep("a", 2600)); assertTrue(clock.allowSweep("b", 200))
        assertTrue(clock.consume(1)); assertFalse(clock.consume(1)); assertTrue(clock.consume(2))
    }
}
