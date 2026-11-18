package com.messenger.app.domain.model

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ChatNotificationPreferencesTest {
    private val now = Instant.parse("2026-09-20T12:00:00Z")
    private val chat = ChatSummary("chat", "direct", null, now.toString(), null)

    @Test fun muteExpiresWithoutARefresh() {
        val muted = chat.copy(isMuted = true, mutedUntil = "2026-09-20T12:01:00Z")
        assertTrue(muted.suppressesNotifications(now))
        assertFalse(muted.suppressesNotifications(now.plusSeconds(60)))
        assertFalse(muted.suppressesNotifications(now.plusSeconds(120)))
    }

    @Test fun blockAndIndefiniteMuteRemainSilent() {
        assertFalse(chat.suppressesNotifications(now))
        assertTrue(chat.copy(isMuted = true).suppressesNotifications(now))
        assertTrue(chat.copy(isBlocked = true).suppressesNotifications(now))
        assertTrue(chat.copy(isMuted = true, mutedUntil = "invalid").suppressesNotifications(now))
        assertFalse(chat.copy(isMuted = false, mutedUntil = "invalid").suppressesNotifications(now))
    }
}
