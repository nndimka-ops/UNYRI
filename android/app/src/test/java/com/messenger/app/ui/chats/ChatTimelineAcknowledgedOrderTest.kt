package com.messenger.app.ui.chats

import com.messenger.app.data.api.CallDto
import com.messenger.app.domain.model.LocalMediaPresentation
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.ui.calls.ChatTimelineCache
import com.messenger.app.ui.calls.chatTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ChatTimelineAcknowledgedOrderTest {
    private val reservation = LocalMediaPresentation("2026-11-01T17:47:58.903158Z", 0, 1.5f, true)
    private val first = Message("text", "server-text", "chat", "me", "first", 482,
        "2026-11-01T17:47:59.049180Z", MessageStatus.SENT)
    private val photo = Message("photo", "server-photo", "chat", "me", "photo", 483,
        "2026-11-01T17:48:00.241739Z", MessageStatus.SENT, localMediaPresentation = reservation)

    private fun keys(messages: List<Message>) = chatTimeline(messages, emptyList()).map { it.key }

    @Test fun `real device clock skew cannot leave an acknowledged photo before earlier text`() {
        assertEquals(listOf("text", "photo"), keys(listOf(photo, first)))
        assertSame(reservation, chatTimeline(listOf(photo, first), emptyList()).last().message?.localMediaPresentation)
    }

    @Test fun `cache reorders a standalone reservation only when complete acknowledgement arrives`() {
        val cache = ChatTimelineCache()
        val pending = photo.copy(serverId = null, sequence = null, status = MessageStatus.PENDING)
        assertEquals(listOf("photo", "text"), cache.update(listOf(first, pending), emptyList()).map { it.key })
        assertEquals(listOf("text", "photo"), cache.update(listOf(first, photo), emptyList()).map { it.key })
    }

    @Test fun `same timestamp ack changes cached tie order using server sequence`() {
        val cache = ChatTimelineCache()
        val sharedTime = "2026-11-01T17:47:59Z"
        val text = first.copy(createdAt = sharedTime)
        val pending = photo.copy(serverId = null, sequence = null, createdAt = sharedTime,
            status = MessageStatus.PENDING, localMediaPresentation = reservation.copy(createdAt = sharedTime))
        assertEquals(listOf("photo", "text"), cache.update(listOf(text, pending), emptyList()).map { it.key })
        val acknowledged = pending.copy(serverId = "server-photo", sequence = 483, status = MessageStatus.SENT)
        assertEquals(listOf("text", "photo"), cache.update(listOf(text, acknowledged), emptyList()).map { it.key })
    }

    @Test fun `all completed receipt states use canonical placement without changing geometry`() {
        for (status in listOf(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ)) {
            val message = photo.copy(status = status)
            val rows = chatTimeline(listOf(message, first), emptyList())
            assertEquals(listOf("text", "photo"), rows.map { it.key })
            assertSame(reservation, rows.last().message?.localMediaPresentation)
        }
    }

    @Test fun `incomplete acknowledgement pending failure and security rows keep their reservation`() {
        val cases = listOf(photo.copy(serverId = null), photo.copy(serverId = ""), photo.copy(sequence = null),
            photo.copy(sequence = 0), photo.copy(sequence = -1), photo.copy(status = MessageStatus.PENDING),
            photo.copy(status = MessageStatus.FAILED), photo.copy(status = MessageStatus.DECRYPT_FAILED),
            photo.copy(status = MessageStatus.IDENTITY_CHANGED), photo.copy(isDeletedForEveryone = true))
        for (message in cases) assertEquals(listOf("photo", "text"), keys(listOf(first, message)))
    }

    @Test fun `albums keep their existing reservation and original member index order`() {
        val members = listOf(photo.copy(clientMessageId = "a", albumId = "album", sequence = 484),
            photo.copy(clientMessageId = "b", albumId = "album", sequence = 483,
                localMediaPresentation = reservation.copy(index = 1)))
        val rows = chatTimeline(listOf(first) + members.reversed(), emptyList())
        assertEquals(listOf("a", "b"), rows.first().messages.map { it.clientMessageId })
        assertEquals("text", rows.last().key)
        assertEquals(listOf("text", "photo"), keys(listOf(first, photo.copy(albumId = " "))))
    }

    @Test fun `acknowledged standalone photo merges after the intervening call`() {
        val call = CallDto("call", "peer", "me", "audio", "ended", createdAt = "2026-11-01T17:47:59.500Z")
        assertEquals(listOf("text", "call:call", "photo"), chatTimeline(listOf(photo, first), listOf(call)).map { it.key })
    }
}
