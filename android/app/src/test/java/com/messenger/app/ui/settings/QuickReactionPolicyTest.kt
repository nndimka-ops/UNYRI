package com.messenger.app.ui.settings

import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import org.junit.Assert.*
import org.junit.Test

class QuickReactionPolicyTest {
    private fun message(status: MessageStatus = MessageStatus.SENT) = Message("client", "server", "chat", "peer", "hello", 1L, "2026-09-20T12:00:00Z", status)
    @Test fun onlyDeliveredContentCanReceiveQuickReactions() {
        MessageStatus.entries.forEach { status ->
            assertEquals(status.name, status in listOf(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ), QuickReactionPolicy.eligible(message(status), false))
        }
    }
    @Test fun selectionDeletionAndMissingServerTargetSuppressReactions() {
        assertFalse(QuickReactionPolicy.eligible(message(), true))
        assertFalse(QuickReactionPolicy.eligible(message().copy(isDeletedForEveryone = true), false))
        assertFalse(QuickReactionPolicy.eligible(message().copy(serverId = null), false))
    }
    @Test fun disabledPreferenceSurvivesWhileUnknownValueUsesDefault() {
        assertEquals("", QuickReactionPolicy.normalize(""))
        assertEquals("👍", QuickReactionPolicy.normalize("invalid"))
        assertEquals("👍", QuickReactionPolicy.normalize(null))
        QuickReactionPolicy.choices.forEach { assertEquals(it, QuickReactionPolicy.normalize(it)) }
    }
}
