package com.messenger.app.ui.chats

import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus.*
import com.messenger.app.security.MessageReadVisibility
import kotlin.test.*

class ComposerOverlayCoverageTest {
    @Test fun `opening switching and dismissing overlays gates real read visibility and media work`() {
        val overlays = ComposerOverlayState()
        var anotherSheet = false
        val owner = Any()
        val visibility = MessageReadVisibility().apply {
            bind(owner) { true }
            bindChat(owner) { ChatReadPolicy.readable(true, true, anotherSheet || overlays.covered) }
        }
        fun assertAllowed(allowed: Boolean) {
            assertEquals(allowed, visibility.allowsViewedCursor())
            assertEquals(allowed, mediaWorkAllowed(true, anotherSheet || overlays.covered, true))
        }
        assertAllowed(true)
        for (first in ComposerOverlay.entries) {
            val replacement = ComposerOverlay.entries.first { it != first }
            overlays.show(first)
            assertAllowed(false)
            overlays.show(replacement)
            assertAllowed(false)
            overlays.dismiss(first) // Previous popup's delayed onDismiss.
            assertAllowed(false)
            assertEquals(replacement, overlays.active)
            overlays.dismiss(replacement)
            assertAllowed(true)
        }
        anotherSheet = true
        overlays.show(ComposerOverlay.Emoji)
        overlays.clear()
        assertAllowed(false) // Closing the composer must not uncover a different sheet.
        anotherSheet = false
        assertAllowed(true)
    }

    @Test fun `emoji toggle switches atomically and cleanup cannot leave coverage latched`() {
        val overlays = ComposerOverlayState()
        overlays.show(ComposerOverlay.Attachments)
        overlays.toggle(ComposerOverlay.Emoji)
        overlays.dismiss(ComposerOverlay.Attachments)
        assertEquals(ComposerOverlay.Emoji, overlays.active)
        assertTrue(overlays.covered)
        overlays.toggle(ComposerOverlay.Emoji)
        assertFalse(overlays.covered)
        for (overlay in ComposerOverlay.entries) {
            overlays.show(overlay)
            overlays.clear()
            overlays.clear()
            assertNull(overlays.active)
            assertFalse(overlays.covered)
        }
    }

    @Test fun `covered arrivals stay silent across overlay switches and timeline release`() {
        for (first in ComposerOverlay.entries) {
            var clock = 0L
            val overlays = ComposerOverlayState()
            val visibility = ReceiptVisibilityState(true) { clock }
            val feed = ReceiptStatusFeed(visibility) { clock }
            val pending = Message("client", null, "chat", "owner", "text", null, "", PENDING)
            val id = MessageReceiptPolicy.messageLiveId(pending)
            val hold = MediaPresentationHold<List<Message>>()
            var latest = listOf(pending)
            fun record() = feed.record(latest, ReceiptRowVisibility(
                ChatReadPolicy.readable(true, true, overlays.covered), setOf(id)))
            fun display() = hold.display(overlays.covered) { latest }
            record()
            val original = display()
            val ink = ReceiptInkObservation(PENDING, visibility.current) { clock }
            overlays.show(first)
            clock = 1_000
            latest = listOf(pending.copy(status = SENT))
            record()
            assertSame(original, display())
            assertFalse(feed.stamp(id, SENT)!!.isLive(visibility.current))
            val replacement = ComposerOverlay.entries.first { it != first }
            overlays.show(replacement)
            overlays.dismiss(first)
            clock = 2_000
            latest = listOf(pending.copy(status = DELIVERED))
            record()
            assertSame(original, display())
            assertFalse(feed.stamp(id, DELIVERED)!!.isLive(visibility.current))
            overlays.dismiss(replacement)
            clock = 3_000
            record()
            assertEquals(DELIVERED, display().single().status)
            assertFalse(ink.observe(DELIVERED, visibility.current, false, feed.stamp(id, DELIVERED)).animate)
            clock += 10
            latest = listOf(pending.copy(status = READ))
            record()
            assertTrue(feed.stamp(id, READ)!!.isLive(visibility.current))
        }
    }
}
