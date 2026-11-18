package com.messenger.app.ui.chats

import com.messenger.app.data.api.CallDto
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.domain.model.PublicUser
import kotlin.test.*

class ChatCallActivityTest {
    private fun call(id: String, at: String, peer: String = "bob") = CallDto(id, "me", peer, "audio", "ended", createdAt = at)
    @Test fun `only calls for the current account affect order`() {
        val calls = listOf(call("1", "2026-09-20T10:00:00Z"), call("2", "2026-09-20T11:00:00Z"))
        assertTrue(ChatCallActivity.latestByPeer(calls, "other").isEmpty())
        assertTrue(ChatCallActivity.latestByPeer(calls, null).isEmpty())
        assertEquals("2", ChatCallActivity.latestByPeer(calls.reversed(), "me")["bob"]?.id)
    }
    @Test fun `latest call per peer keeps newest timestamp then id and ignores hidden calls`() {
        val older = call("a", "2026-09-20T10:00:00Z")
        val sameTimeHigherId = call("b", "2026-09-20T10:00:00Z")
        val hiddenNewer = CallDto("c", "me", "bob", "audio", "ended", createdAt = "2026-09-20T12:00:00Z", chatVisible = false)
        assertEquals("b", ChatCallActivity.latestByPeer(listOf(older, sameTimeHigherId, hiddenNewer), "me")["bob"]?.id)
    }
    @Test fun `calls promote older chats but do not overwrite message metadata`() {
        val old = ChatSummary("old", "direct", PublicUser("bob", "bob", "Bob", null), "2026-09-19T10:00:00Z", "2026-09-20T09:00:00Z")
        val newer = old.copy(chatId = "new", otherUser = null, lastMessageAt = "2026-09-20T10:00:00Z")
        val call = call("c", "2026-09-20T14:00:00+03:00")
        val peers = ChatCallActivity.latestByPeer(listOf(call), "me")
        assertEquals(listOf("old", "new"), ChatCallActivity.sorted(listOf(newer, old), peers).map { it.chatId })
        assertTrue(ChatCallActivity.callIsLatest(old, call))
        assertEquals("2026-09-20T09:00:00Z", old.lastMessageAt)
        val newest = newer.copy(lastMessageAt = "2026-09-20T12:00:00Z")
        assertEquals("new", ChatCallActivity.sorted(listOf(old, newest), peers).first().chatId)
    }
}
