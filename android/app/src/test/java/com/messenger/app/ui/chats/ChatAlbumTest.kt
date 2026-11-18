package com.messenger.app.ui.chats

import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.ui.calls.chatTimeline
import kotlin.test.*

class ChatAlbumTest {
    private fun message(id: String, album: String?, owner: String = "one") = Message(id, id, "chat", owner, "photo", id.toLong(), "2026-09-13T12:00:00Z", MessageStatus.SENT, albumId = album)
    @Test fun `one send remains one album rather than separate pairs`() {
        val rows = chatTimeline((1..5).map { message(it.toString(), "album") }, emptyList())
        assertEquals(listOf(5), rows.map { it.messages.size })
        assertEquals(listOf("1","2","3","4","5"), rows.flatMap { it.messages }.map { it.clientMessageId })
    }
    @Test fun `matching album id cannot merge different senders or separate albums`() {
        val rows = chatTimeline(listOf(message("1","album"),message("2","album","other"),message("3",null),message("4","second-album")),emptyList())
        assertEquals(4,rows.size)
    }
    @Test fun `large batch preserves every message and separates the next batch`() {
        val first = (1..51).map { message(it.toString(), "first") }
        val second = (52..61).map { message(it.toString(), "second") }
        val rows = chatTimeline(first + second, emptyList())
        assertEquals((first + second).map { it.clientMessageId }, rows.flatMap { it.messages }.map { it.clientMessageId })
        assertTrue(rows.all { it.messages.size in 1..10 })
        assertTrue(rows.all { row -> row.messages.map { it.albumId }.distinct().size == 1 })
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
    }
    @Test fun `each batch from two to ten is exactly one timeline object`() {
        for(count in 2..10) {
            val rows=chatTimeline((1..count).map {message(it.toString(),"batch")},emptyList())
            assertEquals(1,rows.size,"$count items must be one group")
            assertEquals(count,rows.single().messages.size)
        }
    }
    @Test fun `text accepted during upload does not split the album`() {
        val batch=(1..10).map {message(it.toString(),"batch").copy(createdAt="2026-09-13T12:00:${it.toString().padStart(2,'0')}Z")}
        val text=message("11",null).copy(createdAt="2026-09-13T12:00:05.500Z")
        val rows=chatTimeline(batch+text,emptyList())
        assertEquals(listOf(10,1),rows.map {it.messages.size})
        assertEquals(batch.map {it.clientMessageId},rows.first().messages.map {it.clientMessageId})
    }
    @Test fun `album key stays stable while member timestamps are reconciled`() {
        val pending=(1..4).map {message(it.toString(),"batch")}
        val before=chatTimeline(pending,emptyList()).single().key
        val after=chatTimeline(pending.map {if(it.clientMessageId=="1")it.copy(createdAt="2026-09-13T12:00:15Z")else it},emptyList()).single().key
        assertEquals(before,after)
    }
    @Test fun `deleted and unreadable members do not squeeze warnings into media tiles`() {
        for (broken in listOf(message("2", "a").copy(isDeletedForEveryone = true),
            message("2", "a").copy(status = MessageStatus.DECRYPT_FAILED),
            message("2", "a").copy(status = MessageStatus.IDENTITY_CHANGED))) {
            val rows = chatTimeline(listOf(message("1", "a"), broken, message("3", "a")), emptyList())
            assertEquals(listOf(1, 1, 1), rows.map { it.messages.size })
        }
    }
    @Test fun `rich text retains ordinary text and only styles balanced inline markers`() {
        assertEquals("Привет bold code *unfinished", chatRichText("Привет **bold** `code` *unfinished").text)
        assertEquals("plain", chatRichText("plain").text)
    }
    @Test fun `link cards reject credentials and executable schemes and trim punctuation`() {
        assertNull(chatLinkAddress("javascript:alert(1)"))
        assertNull(chatLinkAddress("https://user:secret@example.com/path"))
        assertEquals("example.com", chatLinkAddress("Look https://example.com/path.")?.host)
        assertEquals("/path", chatLinkAddress("Look https://example.com/path.")?.path)
    }
}
