package com.messenger.app.ui.chats

import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus.*
import com.messenger.app.security.MessageReadVisibility
import java.io.File
import kotlin.test.*

class ChatMediaPresentationTest {
    private val sources = listOf(
        ChatMediaViewerTarget.History(File("timeline-photo.jpg"), "image/jpeg"),
        ChatMediaViewerTarget.ComposerPhoto("content://fixture/composer-photo"),
    )

    @Test fun `both viewer routes keep read visibility media and timeline blocked through other coverage`() {
        for (source in sources) {
            var presentation: ChatMediaViewerTarget? = null
            var anotherSheet = false
            var resumed = true
            var allowed = true
            fun covered() = presentation != null || anotherSheet
            fun readable() = ChatReadPolicy.readable(resumed, allowed, covered())
            val owner = Any()
            val visibility = MessageReadVisibility().apply {
                bind(owner) { resumed && allowed }
                bindChat(owner) { readable() }
            }
            val hold = MediaPresentationHold<List<Int>>()
            var rows = listOf(1)
            var loads = 0
            fun display() = hold.display(!readable()) { loads++; rows }
            fun assertBlocked(original: List<Int>) {
                assertFalse(visibility.allowsViewedCursor())
                assertFalse(mediaWorkAllowed(resumed, !allowed || covered(), true))
                assertSame(original, display())
            }

            val original = display()
            presentation = source // Also owns preview loading, failure/retry and the nested editor.
            rows = listOf(1, 2)
            assertBlocked(original)
            anotherSheet = true
            presentation = null // Closing the viewer cannot uncover another dialog.
            assertBlocked(original)
            resumed = false
            anotherSheet = false
            assertBlocked(original)
            resumed = true
            allowed = false // A hidden chat route remains blocked even after Activity resume.
            assertBlocked(original)
            allowed = true
            assertTrue(visibility.allowsViewedCursor())
            assertTrue(mediaWorkAllowed(true, false, true))
            assertEquals(rows, display())
            assertEquals(2, loads)
        }
    }

    @Test fun `receipts arriving in either viewer stay historical after editor returns to composer`() {
        for (source in sources) {
            var presentation: ChatMediaViewerTarget? = null
            var clock = 0L
            val visibility = ReceiptVisibilityState(true) { clock }
            val feed = ReceiptStatusFeed(visibility) { clock }
            val pending = Message("client", null, "chat", "owner", "text", null, "", PENDING)
            val id = MessageReceiptPolicy.messageLiveId(pending)
            fun record(message: Message) = feed.record(listOf(message), ReceiptRowVisibility(
                ChatReadPolicy.readable(true, true, presentation != null), setOf(id)))
            record(pending)
            val ink = ReceiptInkObservation(PENDING, visibility.current) { clock }
            presentation = source
            clock = 1_000
            record(pending.copy(status = SENT))
            assertFalse(feed.stamp(id, SENT)!!.isLive(visibility.current))
            clock = 2_000
            record(pending.copy(status = DELIVERED))
            presentation = null
            clock = 3_000
            record(pending.copy(status = DELIVERED))
            assertFalse(ink.observe(DELIVERED, visibility.current, false, feed.stamp(id, DELIVERED)).animate)
            clock += 10
            record(pending.copy(status = READ))
            assertTrue(feed.stamp(id, READ)!!.isLive(visibility.current))
        }
    }
}
