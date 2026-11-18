package com.messenger.app.ui.chats

import com.messenger.app.domain.model.*
import com.messenger.app.ui.calls.*
import kotlin.test.*

class ChatTimelineCacheTest {
    @Test fun `server acknowledgements cannot shuffle a reserved local album or move it past following text`() {
        val cache = ChatTimelineCache()
        val album = (0..9).map { index -> message("media-$index", "batch").copy(serverId=null, sequence=null,
            status=MessageStatus.PENDING, localMediaPresentation=LocalMediaPresentation("2026-09-13T12:00:00Z",index,1.5f,true)) }
        val text = message("following").copy(createdAt="2026-09-13T12:00:01Z",sequence=200)
        val expected = album.map { it.clientMessageId }
        for (accepted in 0..10) {
            // Out-of-order HTTP/WS receipts and a fresh DAO sort must not move media.
            val snapshot = album.mapIndexed { index, m -> if(index<accepted)m.copy(serverId=m.clientMessageId,
                sequence=100L+9-index,createdAt="2026-09-13T12:00:${20+index}Z",status=MessageStatus.SENT) else m }.reversed()+text
            val rows=cache.update(snapshot,emptyList())
            assertEquals(2,rows.size)
            assertEquals(expected,rows.first().messages.map {it.clientMessageId})
            assertEquals("following",rows.last().message?.clientMessageId)
            assertEquals(chatTimeline(snapshot,emptyList()),rows)
        }
    }
    private fun message(id: String, album: String? = null) = Message(id, id, "chat", "peer", "text", 1,
        "2026-09-13T12:00:00Z", MessageStatus.SENT, albumId = album)

    @Test fun `receipts and reactions remain fresh without replacing unaffected rows`() {
        val cache = ChatTimelineCache()
        val source = listOf(message("1"), message("2"))
        val before = cache.update(source, emptyList())
        val updated = source.map { if (it.clientMessageId == "2") it.copy(status = MessageStatus.READ,
            reactions = listOf(ReactionSummary("heart", 1, true))) else it }
        val after = cache.update(updated, emptyList())
        assertSame(before[0], after[0])
        assertEquals(chatTimeline(updated, emptyList()), after)
    }

    @Test fun `album member edits are refreshed and deletion splits the row`() {
        val cache = ChatTimelineCache()
        var source = listOf(message("1", "album"), message("2", "album"))
        cache.update(source, emptyList())
        source = source.map { if (it.clientMessageId == "2") it.copy(text = "edited", isEdited = true) else it }
        assertEquals(chatTimeline(source, emptyList()), cache.update(source, emptyList()))
        source = source.map { if (it.clientMessageId == "2") it.copy(isDeletedForEveryone = true) else it }
        assertEquals(2, cache.update(source, emptyList()).size)
    }

    @Test fun `insert remove reorder and unavailable state match uncached presentation`() {
        val cache = ChatTimelineCache()
        val a = message("1", "a"); val b = message("2", "a")
        for (source in listOf(listOf(a,b), listOf(b,a), listOf(a), emptyList(),
            listOf(a,b.copy(status = MessageStatus.DECRYPT_FAILED)), listOf(a,b))) {
            assertEquals(chatTimeline(source, emptyList()), cache.update(source, emptyList()))
        }
    }
    @Test fun `call updates and removal invalidate cached placement`() {
        val cache = ChatTimelineCache()
        val source = listOf(message("1"))
        val call = com.messenger.app.data.api.CallDto("call", "peer", "me", "audio", "ringing",
            createdAt = "2026-09-13T11:59:00Z")
        for (calls in listOf(listOf(call), listOf(call.copy(state = "ended", endedAt = "2026-09-13T12:01:00Z")), emptyList())) {
            assertEquals(chatTimeline(source, calls), cache.update(source, calls))
        }
    }

}
