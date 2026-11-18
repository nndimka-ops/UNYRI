package com.messenger.app.data.message

import com.messenger.app.conference.ConferenceInvitation
import com.messenger.app.conference.ConferenceInvite
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.data.sync.InMemoryAttachmentDao
import com.messenger.app.data.sync.InMemoryMessageDao
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Executes the admission function used by MessageRepositoryImpl's captured overload. */
class CapturedForwardQueueTest {
    private class Fixture {
        val dao = InMemoryMessageDao()
        val media = InMemoryAttachmentDao()
        val auth = FakeAuthRepository()
        val epoch = auth.currentSessionEpoch()
        val captured = Message("source", "server-source", "chat", "peer", "Captured text", 1,
            "2026-11-13T00:00:00Z", MessageStatus.SENT)
        val live = MessageEntity("source", "server-source", "chat", "peer", "Edited live text", 1,
            captured.createdAt, "sent")
        suspend fun queue(source: Message = captured, destination: String = "A", id: String = "copy-A") =
            queueCapturedForwardMessage(source, destination, id, epoch, dao, media, auth)
    }

    @Test fun `queue uses captured content for every destination and leaves the edited source untouched`() = runTest {
        val f = Fixture(); f.dao.upsert(f.live)
        f.queue()
        f.dao.upsert(f.live.copy(text = "Edited again"))
        f.queue(destination = "B", id = "copy-B")
        for ((id, destination) in listOf("copy-A" to "A", "copy-B" to "B")) {
            val row = f.dao.findByClientMessageId(id)!!
            assertEquals(f.captured.text, row.text)
            assertEquals(destination, row.chatId)
            assertEquals("me", row.senderId)
            assertTrue(row.forwarded)
            assertEquals("pending", row.status)
        }
        assertEquals("Edited again", f.dao.findByClientMessageId("source")!!.text)
    }

    @Test fun `same id retry preserves an acknowledged row and its frozen payload`() = runTest {
        val f = Fixture(); f.dao.upsert(f.live); f.queue()
        val accepted = f.dao.findByClientMessageId("copy-A")!!.copy(serverId = "ack", sequence = 9, status = "read", payloadText = f.captured.text)
        f.dao.upsert(accepted)
        f.queue()
        assertEquals(accepted, f.dao.findByClientMessageId("copy-A"))
    }

    @Test fun `concurrent same id retries admit only one destination record`() = runTest {
        val f = Fixture(); f.dao.upsert(f.live)
        var writes = 0
        val interleavingDao = object : MessageDao by f.dao {
            override suspend fun findByClientMessageId(clientMessageId: String): MessageEntity? {
                val row = f.dao.findByClientMessageId(clientMessageId)
                if (clientMessageId == "copy-A") yield()
                return row
            }
            override suspend fun upsert(message: MessageEntity) { writes++; f.dao.upsert(message) }
        }
        List(8) { launch { queueCapturedForwardMessage(f.captured, "A", "copy-A", f.epoch, interleavingDao, f.media, f.auth) } }.forEach { it.join() }
        assertEquals(1, f.dao.observeMessages("A").first().size)
        assertEquals(1, writes)
    }

    @Test fun `reusing a destination id with changed content or recipient cannot overwrite it`() = runTest {
        val f = Fixture(); f.dao.upsert(f.live); f.queue()
        val accepted = f.dao.findByClientMessageId("copy-A")
        assertTrue(runCatching { f.queue(source = f.captured.copy(text = "different")) }.isFailure)
        assertTrue(runCatching { f.queue(destination = "B") }.isFailure)
        assertEquals(accepted, f.dao.findByClientMessageId("copy-A"))
    }

    @Test fun `live deletion hidden rows warnings and identity mismatch block admission`() = runTest {
        val f = Fixture()
        for (row in listOf(f.live.copy(isDeletedForEveryone = true), f.live.copy(hiddenLocally = true),
            f.live.copy(status = "decrypt_failed"), f.live.copy(status = "identity_changed"),
            f.live.copy(chatId = "other"), f.live.copy(senderId = "someone-else"))) {
            f.dao.upsert(row)
            assertTrue(runCatching { f.queue() }.isFailure)
            assertNull(f.dao.findByClientMessageId("copy-A"))
        }
    }

    @Test fun `account switch is rejected inside the durable write gate`() = runTest {
        val f = Fixture(); f.dao.upsert(f.live)
        f.auth.switchEpochOnWrite = true
        try { f.queue(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertNull(f.dao.findByClientMessageId("copy-A"))
    }

    @Test fun `captured invitation metadata is queued rather than reread from the source`() = runTest {
        val f = Fixture()
        val invite = ConferenceInvite.fresh("https://coco.example")
        val invitation = ConferenceInvitation(id = java.util.UUID.randomUUID().toString(), title = "Captured room",
            server = invite.server, roomId = invite.roomId, invite = invite.legacyLink(), expiresAt = 2000000000)
        f.dao.upsert(f.live.copy(conferenceInviteJson = invitation.copy(title = "Live room").stored()))
        f.queue(f.captured.copy(text = ConferenceInvitation.FALLBACK, conferenceInvitation = invitation))
        val row = f.dao.findByClientMessageId("copy-A")!!
        assertEquals(invitation.stored(), row.conferenceInviteJson)
        assertEquals(ConferenceInvitation.FALLBACK, row.text)
        assertTrue(row.forwarded)
    }

    @Test fun `invalid captured text is rejected without falling back to the valid live text`() = runTest {
        val f = Fixture(); f.dao.upsert(f.live)
        assertTrue(runCatching { f.queue(f.captured.copy(text = "")) }.isFailure)
        assertNull(f.dao.findByClientMessageId("copy-A"))
    }
}
