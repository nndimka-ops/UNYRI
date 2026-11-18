package com.messenger.app.ui.chats

import org.junit.Assert.*
import org.junit.Test

class EmojiInputStateTest {
    @Test fun productionToggleCoversReadReceiptsAndTimelineMediaUntilDismissed() {
        val state = EmojiInputState()
        val overlays = ComposerOverlayState()
        val owner = Any()
        val visibility = com.messenger.app.security.MessageReadVisibility().apply {
            bind(owner) { true }
            bindChat(owner) { ChatReadPolicy.readable(true, true, composerCoveredForRead(overlays, state)) }
        }
        val receiptVisibility = ReceiptVisibilityState(true) { 0L }
        val feed = ReceiptStatusFeed(receiptVisibility) { 0L }
        val pending = com.messenger.app.domain.model.Message("client", null, "chat", "owner", "text", null, "",
            com.messenger.app.domain.model.MessageStatus.PENDING)
        val id = MessageReceiptPolicy.messageLiveId(pending)
        fun allowed() = !composerCoveredForRead(overlays, state)
        feed.record(listOf(pending), ReceiptRowVisibility(allowed(), setOf(id)))
        overlays.show(ComposerOverlay.Attachments)
        overlays.toggleEmoji(state) // Exact production button transition, not the old Emoji overlay.
        assertNull(overlays.active)
        assertFalse(visibility.allowsViewedCursor())
        assertFalse(mediaWorkAllowed(true, composerCoveredForRead(overlays, state), true))
        val sent = pending.copy(status = com.messenger.app.domain.model.MessageStatus.SENT)
        feed.record(listOf(sent), ReceiptRowVisibility(allowed(), setOf(id)))
        assertFalse(feed.stamp(id, sent.status)!!.isLive(receiptVisibility.current))
        overlays.toggleEmoji(state)
        assertTrue(visibility.allowsViewedCursor())
        assertTrue(mediaWorkAllowed(true, composerCoveredForRead(overlays, state), true))
        overlays.show(ComposerOverlay.Attachments)
        state.dismiss()
        assertFalse(visibility.allowsViewedCursor())
    }

    @Test fun shortAndTallDockedImeTransitionsKeepComposerAndHistoryCoordinates() {
        for (height in listOf(180, 700)) {
            val learned = dockedEmojiImeHeight(height, height, navigation = 24, available = 900)!!
            val composerTop = 900 - height - 60
            val historyAnchor = composerTop - 113
            for (ime in listOf(height, height / 2, 0, height / 2, height)) {
                val reserved = emojiReservedBottom(ime, learned, 900, reserve = true)
                assertEquals(composerTop, 900 - reserved - 60)
                assertEquals(historyAnchor, 900 - reserved - 60 - 113)
            }
            assertEquals(height, emojiReservedBottom(height, learned, 900, reserve = false))
        }
        assertNull(dockedEmojiImeHeight(24, 24, 24, 900)) // floating/hardware: navigation only
        assertNull(dockedEmojiImeHeight(0, 0, 24, 900))
        assertNull(dockedEmojiImeHeight(90, 180, 24, 900)) // animation frame
        assertNull(dockedEmojiImeHeight(1000, 1000, 24, 900))
    }

    @Test fun sharedChatActionBlursBeforeSemanticOrTouchPlaybackAndOnlyOnce() {
        val state = EmojiInputState()
        val calls = mutableListOf<String>()
        state.toggle()
        repeat(2) { state.chatAction({ calls += "blur" }, { calls += "play" }) }
        assertEquals(listOf("blur", "play", "play"), calls)
        assertEquals(EmojiInputMode.Hidden, state.mode)
    }

    @Test fun toggleKeepsFocusAndReservesSpaceUntilSystemArrives() {
        val state = EmojiInputState()
        state.focusChanged(true)
        state.toggle()
        assertTrue(state.showingEmoji)
        state.focusChanged(true) // placing/focusing the retained editor must not reset the mode
        assertTrue(state.showingEmoji)
        state.toggle()
        assertFalse(state.showingEmoji)
        assertTrue(state.reservesKeyboard)
        state.systemArrived()
        assertEquals(EmojiInputMode.System, state.mode)
        assertFalse(state.reservesKeyboard)
    }

    @Test fun blurAndBackResetTheNextFocusToSystemKeyboard() {
        for (blur in listOf(true, false)) {
            val state = EmojiInputState()
            state.toggle()
            if (blur) state.focusChanged(false) else state.dismiss()
            assertEquals(EmojiInputMode.Hidden, state.mode)
            state.systemArrived() // a delayed inset callback cannot resurrect the panel
            assertEquals(EmojiInputMode.Hidden, state.mode)
            state.focusChanged(true)
            assertEquals(EmojiInputMode.System, state.mode)
        }
    }

    @Test fun rapidToggleAndLateSystemArrivalCannotCloseEmoji() {
        val state = EmojiInputState()
        state.toggle(); state.toggle(); state.toggle()
        state.systemArrived()
        assertTrue(state.showingEmoji)
        state.focusChanged(false)
        assertFalse(state.reservesKeyboard)
    }

    @Test fun insetSwapUsesMaximumThroughoutBothAnimationDirections() {
        val height = 407
        for (ime in listOf(407, 300, 120, 0, 120, 300, 407)) {
            assertEquals(height, emojiReservedBottom(ime, height, 900, reserve = true))
        }
        assertEquals(380, emojiReservedBottom(380, height, 900, reserve = false))
        assertEquals(0, emojiReservedBottom(0, height, 900, reserve = false))
        assertEquals(270, emojiReservedBottom(0, height, 270, reserve = true))
    }

    @Test fun recentsMoveRepeatedCompoundEmojiToFrontWithoutSplittingIt() {
        val family = "👩🏽‍💻"
        val result = rememberEmoji(listOf("🇺🇦", family, "😀"), family)
        assertEquals(listOf(family, "🇺🇦", "😀"), result)
        assertEquals(result, rememberEmoji(result, family))
    }

    @Test fun recentsEvictOldestAndRejectCorruptEntries() {
        var recents = emptyList<String>()
        repeat(40) { recents = rememberEmoji(recents, "item$it") }
        assertEquals((39 downTo 8).map { "item$it" }, recents)
        for (bad in listOf("", " ", "a\nb", "x".repeat(80))) {
            assertEquals(recents, rememberEmoji(recents, bad))
        }
    }
}
