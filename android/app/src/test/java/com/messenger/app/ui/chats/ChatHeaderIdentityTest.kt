package com.messenger.app.ui.chats

import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.model.PublicUser
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHeaderIdentityTest {
    @Test fun emptySavedChatUsesLocalizedIdentityBeforeAnyHistoryIsLoaded() {
        val saved = ChatSummary("saved-id", "saved", null, "", null)
        assertEquals("Избранное", saved.headerTitle("Избранное", "Чат"))
        assertEquals("Saved Messages", saved.headerTitle("Saved Messages", "Chat"))
        assertEquals("Избранное", saved.copy(otherUser = PublicUser("me", "me", "Owner", null)).headerTitle("Избранное", "Чат"))
    }
    @Test fun directChatRetainsPeerNameAndUnknownPeerFallback() {
        val direct = ChatSummary("direct-id", "direct", PublicUser("peer", "peer", "Kseniia", null), "", null)
        assertEquals("Kseniia", direct.headerTitle("Избранное", "Чат"))
        assertEquals("Чат", direct.copy(otherUser = null).headerTitle("Избранное", "Чат"))
    }
}
