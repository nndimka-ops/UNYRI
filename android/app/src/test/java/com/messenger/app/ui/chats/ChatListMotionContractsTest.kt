package com.messenger.app.ui.chats

import com.messenger.app.domain.model.MessageStatus.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mirrors ios/Tests/ChatListMotionContracts, including the 0.4.14 review contracts. */
class ChatListMotionContractsTest {
    private fun row(id: String, activity: Long, unread: Int = 0, muted: Boolean = false) =
        ChatListLiftPolicy.Row(id, activity, unread, muted)
    private val old = listOf(row("a", 30), row("b", 20), row("c", 10))
    private fun decide(new: List<ChatListLiftPolicy.Row>, live: Boolean = true,
        prior: List<ChatListLiftPolicy.Row> = old, sweep: Boolean = true) =
        ChatListLiftPolicy.decide(prior, new, live) { sweep }

    @Test fun `only row opens animate and the reviewed zoom kill switch remains off`() {
        assertEquals(ChatOpenStyle.Instant, ChatOpenTransitionPolicy.style(false, reduceMotion = false, lowPower = false))
        assertEquals(ChatOpenStyle.Zoom, ChatOpenTransitionPolicy.style(true, reduceMotion = false, lowPower = false, zoom = true))
        assertEquals(ChatOpenStyle.Settle, ChatOpenTransitionPolicy.style(true, reduceMotion = false, lowPower = false))
        assertFalse(ChatOpenTransitionPolicy.zoomEnabled)
        assertEquals(ChatOpenStyle.Settle, ChatOpenTransitionPolicy.style(true, sharedTransitionAvailable = false, reduceMotion = false, lowPower = false, zoom = true))
        assertEquals(ChatOpenStyle.Fade, ChatOpenTransitionPolicy.style(true, reduceMotion = true, lowPower = false))
        assertEquals(ChatOpenStyle.Fade, ChatOpenTransitionPolicy.style(true, reduceMotion = false, lowPower = true))
    }

    @Test fun `a single incoming row lifts and sweeps only on a live cache event`() {
        val incoming = listOf(row("c", 40, unread = 1), row("a", 30), row("b", 20))
        assertEquals(ChatListLiftPolicy.Decision(true, "c", "c"), decide(incoming))
        assertEquals(ChatListLiftPolicy.Decision(), decide(incoming, live = false))
        assertEquals(ChatListLiftPolicy.Decision(), decide(old, prior = emptyList()))
        assertEquals(ChatListLiftPolicy.Decision(false, null, "a"), decide(listOf(row("a", 40, 1), row("b", 20), row("c", 10))))
        assertEquals(ChatListLiftPolicy.Decision(true, "c", null), decide(incoming.map { if (it.id == "c") it.copy(muted = true) else it }))
        assertEquals(ChatListLiftPolicy.Decision(true, "c", null), decide(incoming.map { it.copy(unread = 0) }))
        assertEquals(ChatListLiftPolicy.Decision(true, "c", null), decide(incoming, sweep = false))
    }

    @Test fun `catch-up and multiple live rows do not masquerade as a single message`() {
        assertEquals(ChatListLiftPolicy.Decision(true, "c", null), decide(listOf(row("c", 50, 1), row("b", 40, 1), row("a", 30))))
        assertEquals(ChatListLiftPolicy.Decision(true), decide(listOf(row("d", 50, 1), row("c", 40, 1), row("a", 30), row("b", 20)), prior = old + row("d", 5)))
        assertEquals(ChatListLiftPolicy.Decision(true, "n", "n"), decide(listOf(row("n", 50, 1)) + old))
        val eight = (0..7).map { row("r$it", (100 - it).toLong()) }
        val fourMoved = listOf(row("r7", 200), row("r6", 199), row("r5", 198), row("r4", 197)) + eight.take(4)
        assertEquals(ChatListLiftPolicy.Decision(), decide(fourMoved, prior = eight))
        val fourNew = (1..4).map { row("n$it", (301 - it).toLong()) } + old
        assertEquals(ChatListLiftPolicy.Decision(), decide(fourNew))
        assertEquals(ChatListLiftPolicy.Decision(), decide(old.reversed())) // pin/unpin is not message activity
    }

    @Test fun `swipe geometry has elasticity magnetic pull and hysteresis`() {
        assertEquals(212f, ChatSwipePolicy.openWidth(3))
        assertEquals(144f, ChatSwipePolicy.openWidth(2))
        assertEquals(0f, ChatSwipePolicy.displayed(-10f, 212f, false))
        assertEquals(100f, ChatSwipePolicy.displayed(100f, 212f, false))
        assertEquals(267f, ChatSwipePolicy.displayed(312f, 212f, false), .001f)
        assertEquals(281f, ChatSwipePolicy.displayed(312f, 212f, true), .001f)
        assertFalse(ChatSwipePolicy.armed(299f, 212f, false))
        assertTrue(ChatSwipePolicy.armed(300f, 212f, false))
        assertTrue(ChatSwipePolicy.armed(276f, 212f, true))
        assertFalse(ChatSwipePolicy.armed(275f, 212f, true))
    }

    @Test fun `swipe release requires travel or half width and armed always commits`() {
        assertEquals(ChatSwipePolicy.Release.Commit, ChatSwipePolicy.release(300f, -1000f, 212f, true))
        assertEquals(ChatSwipePolicy.Release.Open, ChatSwipePolicy.release(20f, 400f, 212f, false))
        assertEquals(ChatSwipePolicy.Release.Close, ChatSwipePolicy.release(10f, 400f, 212f, false))
        assertEquals(ChatSwipePolicy.Release.Close, ChatSwipePolicy.release(200f, -400f, 212f, false))
        assertEquals(ChatSwipePolicy.Release.Open, ChatSwipePolicy.release(106f, 0f, 212f, false))
        assertEquals(ChatSwipePolicy.Release.Close, ChatSwipePolicy.release(105f, 0f, 212f, false))
    }

    @Test fun `only live forward receipts ink and a watched send bypasses the mount gate`() {
        assertTrue(ReceiptInkPolicy.animates(PENDING, SENT, 0, true, false))
        assertFalse(ReceiptInkPolicy.animates(SENT, DELIVERED, 500, true, false))
        assertFalse(ReceiptInkPolicy.animates(SENT, DELIVERED, 799, true, false))
        assertTrue(ReceiptInkPolicy.animates(SENT, DELIVERED, 800, true, false))
        assertTrue(ReceiptInkPolicy.animates(SENT, DELIVERED, 300, true, false, watchedSending = true))
        assertFalse(ReceiptInkPolicy.animates(SENT, DELIVERED, 300, true, true, watchedSending = true))
        assertFalse(ReceiptInkPolicy.animates(READ, DELIVERED, 5000, true, false))
        assertFalse(ReceiptInkPolicy.animates(PENDING, READ, 5000, false, false))
        assertFalse(ReceiptInkPolicy.animates(PENDING, READ, 5000, true, true))
        assertFalse(ReceiptInkPolicy.animates(PENDING, FAILED, 5000, true, false))
        assertFalse(ReceiptInkPolicy.animates(FAILED, SENT, 5000, true, false))
    }

    @Test fun `receipt bursts of four or more are catch-up and land still`() {
        assertEquals(80L, ReceiptInkPolicy.batchWindowMillis)
        assertTrue(ReceiptInkPolicy.batchAnimates(1))
        assertTrue(ReceiptInkPolicy.batchAnimates(3))
        assertFalse(ReceiptInkPolicy.batchAnimates(0))
        assertFalse(ReceiptInkPolicy.batchAnimates(-1))
        assertFalse(ReceiptInkPolicy.batchAnimates(4))
        assertFalse(ReceiptInkPolicy.batchAnimates(100))
    }
}
