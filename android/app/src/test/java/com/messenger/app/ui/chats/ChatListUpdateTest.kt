package com.messenger.app.ui.chats

import com.messenger.app.domain.model.ChatSummary
import kotlin.test.*

class ChatListUpdateTest {
    private fun row(id: String, seconds: Int, unread: Int = 0) = ChatSummary(id, "direct", null,
        "2026-11-12T08:00:00Z", "2026-11-12T08:00:${seconds.toString().padStart(2, '0')}Z", unreadCount = unread)
    private fun snapshot(rows: List<ChatSummary>, pins: Set<String> = emptySet()): ChatListSnapshot = ChatListSnapshot(
        ChatListPresentation().ordered(rows, pins, emptySet(), false, false, emptyMap(), emptySet()), pins, "all", rows)

    @Test fun `pending pin snapshot cannot consume the applied reorder decision`() {
        val clock = ChatListMotionClock()
        val updates = ChatListUpdates(clock)
        val rows = listOf(row("A", 30), row("B", 20), row("C", 10))
        val before = updates.apply(snapshot(rows), false, 0)
        val pending = snapshot(rows, setOf("C"))
        assertEquals(listOf("A", "B", "C"), before.snapshot.order.rows.map { it.chatId })
        assertTrue(before.snapshot.pins.isEmpty())
        assertTrue(clock.lastPinned.isEmpty())
        val applied = updates.apply(pending, true, 2000)
        assertEquals(listOf("C", "A", "B"), applied.snapshot.order.rows.map { it.chatId })
        assertEquals(setOf("C"), applied.snapshot.pins)
        assertTrue(applied.decision.animateOrder)
        assertNull(applied.decision.lifted); assertNull(applied.decision.swept)
        val unpinned = updates.apply(snapshot(rows), true, 3000)
        assertEquals(listOf("A", "B", "C"), unpinned.snapshot.order.rows.map { it.chatId })
        assertTrue(unpinned.decision.animateOrder)
    }

    @Test fun `one hydration dispatches independent lift and sweep to different rows`() {
        val clock = ChatListMotionClock()
        val updates = ChatListUpdates(clock)
        updates.apply(snapshot(listOf(row("A", 30), row("B", 20), row("C", 10))), false, 0)
        clock.stamp(true, 0)
        val frame = updates.apply(snapshot(listOf(row("C", 50), row("A", 40, 1), row("B", 20))), true, 2000)
        assertEquals("C", frame.decision.lifted)
        assertEquals("A", frame.decision.swept)
        val signals = frame.signals().associateBy { it.chatId }
        assertEquals(setOf("A", "C"), signals.keys)
        assertTrue(signals.getValue("C").lift); assertFalse(signals.getValue("C").sweep)
        assertTrue(signals.getValue("A").sweep); assertFalse(signals.getValue("A").lift)
        assertNotEquals(signals.getValue("A").token, signals.getValue("C").token)
    }

    @Test fun `lift and sweep on the same row use one combined one-shot signal`() {
        val clock = ChatListMotionClock()
        val updates = ChatListUpdates(clock)
        updates.apply(snapshot(listOf(row("A", 30), row("B", 20))), false, 0)
        clock.stamp(true, 0)
        val frame = updates.apply(snapshot(listOf(row("B", 40, 1), row("A", 30))), true, 2000)
        val signal = frame.signals().single()
        assertTrue(signal.lift && signal.sweep)
        assertTrue(clock.consume(signal.token)); assertFalse(clock.consume(frame.signals().single().token))
    }
}
