package com.messenger.app.ui.chats

import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.domain.model.MessageStatus.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uses the same upstream stamps and state owner as the renderer, with a monotonic test clock. */
class ReceiptInkObservationTest {
    private class Fixture(initial: MessageStatus) {
        var clock = 0L
        val visibility = ReceiptVisibilityState(true) { clock }
        val feed = ReceiptStatusFeed(visibility) { clock }
        val message = Message("client", null, "chat", "owner", "text", null, "", initial)
        val id = MessageReceiptPolicy.messageLiveId(message)
        var rowVisible = true
        private var observation = ReceiptInkObservation(initial, visibility.current) { clock }
        init { arrive(initial) }
        fun arrive(status: MessageStatus) = feed.record(listOf(message.copy(status = status)),
            ReceiptRowVisibility(true, if (rowVisible) setOf(id) else emptySet()))
        fun mount(status: MessageStatus) { observation = ReceiptInkObservation(status, visibility.current) { clock } }
        fun render(status: MessageStatus, calm: Boolean = !visibility.current.active) =
            observation.observe(status, visibility.current, calm, feed.stamp(id, status)).animate
    }

    @Test fun `frozen sent timeline resumes quietly and delayed catch-up does not ink`() {
        val f = Fixture(SENT)
        f.clock = 1_000; f.visibility.update(false)
        assertFalse(f.render(SENT))
        f.clock = 20_000; f.visibility.update(true)
        assertFalse(f.render(SENT))
        f.clock += 10; f.arrive(DELIVERED)
        assertFalse(f.render(DELIVERED))
        f.clock += 10; f.arrive(READ)
        assertFalse(f.render(READ))
    }

    @Test fun `latest receipt arriving in the resume snapshot is never a live transition`() {
        for (latest in listOf(DELIVERED, READ)) {
            val f = Fixture(SENT)
            f.clock = 1_000; f.visibility.update(false); f.render(SENT)
            f.clock = 2_000; f.arrive(latest)
            f.clock = 20_000; f.visibility.update(true)
            assertFalse(f.render(latest))
        }
    }

    @Test fun `a previously watched send cannot bypass the new visible interval mount gate`() {
        val f = Fixture(PENDING)
        f.clock = 1_000; f.arrive(SENT)
        assertTrue(f.render(SENT))
        f.visibility.update(false); f.render(SENT)
        f.clock = 20_000; f.visibility.update(true); f.render(SENT)
        f.clock += 10; f.arrive(DELIVERED)
        assertFalse(f.render(DELIVERED))
        f.clock += 800; f.arrive(READ)
        assertTrue(f.render(READ))
    }

    @Test fun `pause resume without an intermediate composition still invalidates live history`() {
        val f = Fixture(PENDING)
        f.clock = 1_000; f.arrive(SENT); assertTrue(f.render(SENT))
        f.visibility.update(false)
        f.clock = 2_000; f.arrive(DELIVERED)
        f.clock = 20_000; f.visibility.update(true)
        assertFalse(f.render(DELIVERED))
        f.clock += 10; f.arrive(READ)
        assertFalse(f.render(READ))
    }

    @Test fun `updates observed while inactive stay settled after returning`() {
        val f = Fixture(SENT)
        f.clock = 1_000; f.visibility.update(false); f.render(SENT)
        f.arrive(DELIVERED); assertFalse(f.render(DELIVERED))
        f.arrive(READ); assertFalse(f.render(READ))
        f.clock = 20_000; f.visibility.update(true)
        assertFalse(f.render(READ))
    }

    @Test fun `visible live changes retain the mount gate and rapid sending animation`() {
        val f = Fixture(SENT)
        f.clock = 800; f.arrive(DELIVERED); assertTrue(f.render(DELIVERED))
        f.clock += 10; f.arrive(READ); assertTrue(f.render(READ))
        val send = Fixture(PENDING)
        send.clock = 1_000; send.mount(PENDING)
        send.clock += 10; send.arrive(SENT); assertTrue(send.render(SENT))
        send.clock += 10; send.arrive(DELIVERED); assertTrue(send.render(DELIVERED))
        send.arrive(READ); assertFalse(send.render(READ, calm = true))
    }

    @Test fun `frozen PENDING cannot enable the bypass for background or unfreeze completion`() {
        for (completed in listOf(SENT, DELIVERED, READ)) for (arrivedInBackground in listOf(true, false)) {
            val f = Fixture(PENDING)
            f.clock = 1_000; f.visibility.update(false); f.render(PENDING)
            if (arrivedInBackground) { f.clock = 2_000; f.arrive(completed) }
            f.clock = 20_000; f.visibility.update(true)
            assertFalse(f.render(PENDING)) // The retained timeline is still frozen.
            f.clock += 10
            if (!arrivedInBackground) f.arrive(completed)
            assertFalse(f.render(completed), "$completed, background=$arrivedInBackground")
        }
    }

    @Test fun `unfreeze arrival never becomes live when composition is delayed beyond the window`() {
        val f = Fixture(PENDING)
        f.clock = 1_000; f.visibility.update(false); f.render(PENDING)
        f.clock = 20_000; f.visibility.update(true); f.render(PENDING)
        f.clock += 10; f.arrive(SENT)
        f.clock = 40_000; f.arrive(SENT) // A duplicate snapshot cannot refresh the stamp.
        assertEquals(20_010L, f.feed.stamp(f.id, SENT)!!.observedAtMillis)
        assertFalse(f.render(SENT))
        f.clock += 10; f.arrive(DELIVERED)
        assertTrue(f.render(DELIVERED)) // A genuinely new visible change remains live.
    }

    @Test fun `an offscreen arrival cannot become live when the row becomes visible`() {
        val f = Fixture(PENDING)
        f.rowVisible = false
        f.clock = 1_000; f.arrive(SENT)
        f.rowVisible = true
        f.clock = 2_000; f.arrive(SENT)
        assertFalse(f.render(SENT))
        f.clock += 10; f.arrive(DELIVERED)
        assertTrue(f.render(DELIVERED))
    }
}
