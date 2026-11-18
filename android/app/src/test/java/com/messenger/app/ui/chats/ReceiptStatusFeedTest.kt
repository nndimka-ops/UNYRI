package com.messenger.app.ui.chats

import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.domain.model.MessageStatus.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptStatusFeedTest {
    private fun message(status: MessageStatus, id: String = "client", album: String? = null) =
        Message(id, null, "chat", "owner", "text", null, "", status, albumId = album)

    @Test fun `viewer hold and unreadable hold do not freeze receipt arrival history`() {
        var clock = 0L
        val visibility = ReceiptVisibilityState(true) { clock }
        val feed = ReceiptStatusFeed(visibility) { clock }
        val pending = message(PENDING); val id = MessageReceiptPolicy.messageLiveId(pending)
        val hold = MediaPresentationHold<List<Message>>()
        var latest = listOf(pending)
        var loads = 0
        fun display(viewer: Boolean, readBlocked: Boolean) = hold.display(viewer || readBlocked) { loads++; latest }
        feed.record(latest, ReceiptRowVisibility(true, setOf(id)))
        val original = display(false, false)
        val ink = ReceiptInkObservation(PENDING, visibility.current) { clock }
        clock = 1_000
        assertSame(original, display(true, true))
        latest = listOf(pending.copy(status = SENT))
        feed.record(latest, ReceiptRowVisibility(false, setOf(id))) // Upstream of the held display.
        clock = 2_000
        assertSame(original, display(false, true)) // Viewer returned, another sheet still covers the chat.
        assertEquals(1, loads)
        clock = 3_000
        val shown = display(false, false).single()
        assertEquals(2, loads)
        assertEquals(SENT, shown.status)
        assertFalse(ink.observe(shown.status, visibility.current, false, feed.stamp(id, shown.status)).animate)
        clock += 10
        latest = listOf(pending.copy(status = DELIVERED))
        feed.record(latest, ReceiptRowVisibility(true, setOf(id)))
        assertTrue(ink.observe(DELIVERED, visibility.current, false, feed.stamp(id, DELIVERED)).animate)
    }

    @Test fun `production flow adapter stamps completion before frozen PENDING unfreezes`() = runTest {
        for (completed in listOf(SENT, DELIVERED, READ)) {
            var clock = 0L
            val visibility = ReceiptVisibilityState(true) { clock }
            val feed = ReceiptStatusFeed(visibility) { clock }
            val pending = message(PENDING)
            val id = MessageReceiptPolicy.messageLiveId(pending)
            val source = MutableStateFlow<List<Message>?>(listOf(pending))
            var latest = pending
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                source.withReceiptStatusStamps(feed) { ReceiptRowVisibility(true, setOf(id)) }
                    .collect { latest = it!!.single() }
            }
            val row = ReceiptInkObservation(PENDING, visibility.current) { clock }
            clock = 1_000; visibility.update(false)
            row.observe(PENDING, visibility.current, true, feed.stamp(id, PENDING))
            clock = 20_000; visibility.update(true)
            assertFalse(row.observe(PENDING, visibility.current, false, feed.stamp(id, PENDING)).animate)
            clock += 10
            source.value = listOf(pending.copy(status = completed))
            assertEquals(completed, latest.status)
            assertEquals(20_010L, feed.stamp(id, completed)!!.observedAtMillis)
            // Render an old held snapshot, then unfreeze much later. Arrival time stays fixed.
            row.observe(PENDING, visibility.current, false, feed.stamp(id, PENDING))
            clock = 40_000
            assertFalse(row.observe(latest.status, visibility.current, false, feed.stamp(id, latest.status)).animate)
            collector.cancel()
        }
    }

    @Test fun `album stamps use aggregate status and actual album row visibility`() {
        var clock = 0L
        val visibility = ReceiptVisibilityState(true) { clock }
        val feed = ReceiptStatusFeed(visibility) { clock }
        val first = message(PENDING, "first", "album"); val second = message(PENDING, "second", "album")
        val id = MessageReceiptPolicy.albumLiveId(first)
        val rows = ReceiptRowVisibility(true, setOf(id))
        feed.record(listOf(first, second), rows)
        clock = 1_000; feed.record(listOf(first.copy(status = SENT), second), rows)
        assertFalse(feed.stamp(id, PENDING)!!.changed)
        clock += 10; feed.record(listOf(first.copy(status = SENT), second.copy(status = SENT)), rows)
        assertTrue(feed.stamp(id, SENT)!!.isLive(visibility.current))
        visibility.update(false)
        feed.record(listOf(first.copy(status = READ), second.copy(status = READ)), rows)
        clock = 20_000; visibility.update(true)
        assertFalse(feed.stamp(id, READ)!!.isLive(visibility.current))
    }

    @Test fun `initial history duplicate snapshots and covered rows do not manufacture live changes`() {
        var clock = 1_000L
        val visibility = ReceiptVisibilityState(true) { clock }
        val feed = ReceiptStatusFeed(visibility) { clock }
        val pending = message(PENDING); val id = MessageReceiptPolicy.messageLiveId(pending)
        clock = 2_000; feed.record(listOf(pending), ReceiptRowVisibility(true, setOf(id)))
        assertFalse(feed.stamp(id, PENDING)!!.changed)
        clock += 10; feed.record(listOf(pending.copy(status = SENT)), ReceiptRowVisibility(false, setOf(id)))
        val stamp = feed.stamp(id, SENT)!!
        clock = 20_000; feed.record(listOf(pending.copy(status = SENT)), ReceiptRowVisibility(true, setOf(id)))
        assertEquals(stamp, feed.stamp(id, SENT))
        assertFalse(stamp.isLive(visibility.current))
    }

    @Test fun `removed rows are pruned and a later historical reload is a baseline`() {
        var clock = 0L
        val visibility = ReceiptVisibilityState(true) { clock }
        val feed = ReceiptStatusFeed(visibility) { clock }
        val pending = message(PENDING); val id = MessageReceiptPolicy.messageLiveId(pending)
        val rows = ReceiptRowVisibility(true, setOf(id))
        feed.record(listOf(pending), rows)
        clock = 1_000; feed.record(listOf(pending.copy(status = SENT)), rows)
        assertTrue(feed.stamp(id, SENT)!!.isLive(visibility.current))
        feed.record(emptyList(), rows)
        assertNull(feed.stamp(id, SENT))
        clock = 2_000; feed.record(listOf(pending.copy(status = READ)), rows)
        assertFalse(feed.stamp(id, READ)!!.changed)
    }
}
