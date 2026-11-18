package com.messenger.app.ui.chats

import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.ui.calls.ChatTimelineItem
import com.messenger.app.ui.calls.chatTimeline
import kotlin.test.*

class ChatActionGroupsTest {
    private fun message(id: String, album: String? = "batch", sender: String = "me", chat: String = "chat") =
        Message(id, id, chat, sender, "", id.toLong(), "2026-11-01T12:00:00Z", MessageStatus.SENT, albumId = album)

    @Test fun `a selection from any tile includes the entire displayed batch only`() {
        val batch = (1..10).map { message(it.toString()) }
        val unrelated = message("11", album = null)
        val timeline = chatTimeline(batch + unrelated, emptyList())
        for (tile in batch) assertEquals(batch, chatMessageActionGroup(timeline, tile))
        val selected = toggleChatMessageGroup(setOf("11"), batch)
        assertEquals((1..11).map(Int::toString).toSet(), selected)
        assertEquals(setOf("11"), toggleChatMessageGroup(selected, batch))
    }

    @Test fun `a partial selection becomes a complete group instead of inverting each tile`() {
        val batch = (1..3).map { message(it.toString()) }
        assertEquals(setOf("1", "2", "3"), toggleChatMessageGroup(setOf("1"), batch))
    }

    @Test fun `matching album identifiers never cross sender chat or bounded timeline row`() {
        val anchor = message("1")
        val rows = listOf(ChatTimelineItem(message = anchor, albumMembers = listOf(anchor, message("2"), message("3", sender = "peer"), message("4", chat = "other"))))
        assertEquals(listOf("1", "2"), chatMessageActionGroup(rows, anchor).map { it.clientMessageId })
        val many = (1..15).map { message(it.toString()) }
        val grouped = chatTimeline(many, emptyList())
        assertEquals((11..15).map(Int::toString), chatMessageActionGroup(grouped, many.last()).map { it.clientMessageId })
    }

    @Test fun `deleted unavailable and absent anchors do not expand destructive scope`() {
        val deleted = message("2").copy(isDeletedForEveryone = true)
        val unavailable = message("3").copy(status = MessageStatus.DECRYPT_FAILED)
        val anchor = message("1")
        val rows = listOf(ChatTimelineItem(message = anchor, albumMembers = listOf(anchor, deleted, unavailable)))
        assertEquals(listOf(anchor), chatMessageActionGroup(rows, anchor))
        assertEquals(listOf(deleted), chatMessageActionGroup(rows, deleted))
        assertEquals(listOf(unavailable), chatMessageActionGroup(rows, unavailable))
        assertEquals(listOf(message("4")), chatMessageActionGroup(rows, message("4")))
    }

    @Test fun `a frozen confirmation never silently expands when more members arrive`() {
        val first = message("1")
        val frozen = chatMessageActionGroup(chatTimeline(listOf(first, message("2")), emptyList()), first)
        chatTimeline((1..5).map { message(it.toString()) }, emptyList())
        assertEquals(listOf("1", "2"), frozen.map { it.clientMessageId })
    }

    @Test fun `cancel captures only current own unsent members and never later appended history`() {
        val pending = message("1").copy(serverId = null, status = MessageStatus.PENDING)
        val failed = message("2").copy(serverId = null, status = MessageStatus.FAILED)
        val delivered = message("3").copy(status = MessageStatus.DELIVERED)
        val rows = chatTimeline(listOf(pending, failed, delivered), emptyList())
        val captured = chatUnsentMessageActionIds(rows, pending, "me")
        assertEquals(listOf("1", "2"), captured)
        val appended = message("4").copy(serverId = null, status = MessageStatus.PENDING)
        chatUnsentMessageActionIds(chatTimeline(listOf(pending, failed, delivered, appended), emptyList()), pending, "me")
        assertEquals(listOf("1", "2"), captured)
        assertTrue(chatUnsentMessageActionIds(rows, pending, "peer").isEmpty())
        assertEquals(listOf("2"), chatUnsentMessageActionIds(chatTimeline(listOf(pending.copy(serverId = "ack", status = MessageStatus.SENT), failed), emptyList()), pending, "me"))
    }

    @Test fun `forwarded whole batch receives a new shared identifier without copying the old one`() {
        val batch = (1..3).map { message(it.toString()) }
        val result = chatForwardingAlbums(chatTimeline(batch, emptyList()), setOf("1", "2", "3")) { "fresh-batch" }
        assertEquals(mapOf("1" to "fresh-batch", "2" to "fresh-batch", "3" to "fresh-batch"), result)
    }

    @Test fun `forwarding one attachment or independent messages never fabricates an album`() {
        val rows = chatTimeline(listOf(message("1"), message("2"), message("3", album = null)), emptyList())
        assertTrue(chatForwardingAlbums(rows, setOf("1")).isEmpty())
        assertTrue(chatForwardingAlbums(rows, setOf("1", "3")).isEmpty())
    }

    @Test fun `separate forwarded batches remain distinct even with matching album ids from different senders`() {
        val all = listOf(message("1"), message("2"), message("3", sender = "peer"), message("4", sender = "peer"))
        var created = 0
        val result = chatForwardingAlbums(chatTimeline(all, emptyList()), all.map { it.clientMessageId }.toSet()) { "fresh-${++created}" }
        assertEquals(result["1"], result["2"])
        assertEquals(result["3"], result["4"])
        assertNotEquals(result["1"], result["3"])
    }
}
