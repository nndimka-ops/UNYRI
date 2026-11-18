package com.messenger.app.data.sync

import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.domain.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Exercises `MessageIngestionPipeline`'s Этап I lifecycle-event handling
 * (edit/delete/reaction) in isolation - the deterministic, sequence-guarded
 * projection-update logic that must survive WS+`/sync` duplication and
 * out-of-order/stale delivery (Этап I п.41-44).
 */
class MessageLifecycleIngestionTest {

    @Test fun `server cannot relabel text as delete or edit`() = runBlocking {
        for (kind in listOf("delete", "edit")) {
            val (pipeline, h) = harness()
            seedContentMessage(h.dao, "srv-1")
            h.decryptor.customResponses["attack"] = MessageContentCodec.authenticate(
                MessageContentCodec.encodeText("hello"), "chat-1", "attack", "content", null)
            pipeline.ingest(listOf(testMessageDto(id = "attack", kind = kind, targetMessageId = "srv-1", sequence = 99)))
            assertEquals("Hello", h.dao.findByServerId("srv-1")?.text)
            assertFalse(h.dao.findByServerId("srv-1")!!.isDeletedForEveryone)
        }
    }

    @Test fun `legacy deletion never authorizes a side effect`() = runBlocking {
        val (pipeline, h) = harness()
        seedContentMessage(h.dao, "srv-1")
        h.decryptor.customResponses["attack"] = MessageContentCodec.encodeDelete()
        pipeline.ingest(listOf(testMessageDto(id = "attack", kind = "delete", targetMessageId = "srv-1", sequence = 99)))
        assertFalse(h.dao.findByServerId("srv-1")!!.isDeletedForEveryone)
    }

    @Test fun `valid deletion cannot select another target client or author`() = runBlocking {
        for (wrongAuthor in listOf(false, true)) {
            val (pipeline, h) = harness()
            seedContentMessage(h.dao, "srv-1")
            h.decryptor.customResponses["attack"] = MessageContentCodec.authenticate(
                MessageContentCodec.encodeDelete(), "chat-1", "attack", "delete", "srv-1",
                if (wrongAuthor) "own-srv-1" else "another-client")
            pipeline.ingest(listOf(testMessageDto(id = "attack", senderId = if (wrongAuthor) "intruder" else "other",
                kind = "delete", targetMessageId = "srv-1", sequence = 99)))
            assertFalse(h.dao.findByServerId("srv-1")!!.isDeletedForEveryone)
        }
    }

    private fun harness(
        auth: FakeAuthRepository = FakeAuthRepository(),
        decryptor: FakeMessageDecryptor = FakeMessageDecryptor(),
        dao: InMemoryMessageDao = InMemoryMessageDao(),
        reactionDao: InMemoryReactionDao = InMemoryReactionDao(),
        attachmentDao: InMemoryAttachmentDao = InMemoryAttachmentDao(),
    ): Pair<MessageIngestionPipeline, Harness> {
        val chatDao = InMemoryChatDao()
        val delivered = FakeDeliveredReporter()
        val cursorStore = InMemorySyncCursorStore()
        val pipeline = MessageIngestionPipeline(
            dao = dao,
            chatDao = chatDao,
            attachmentDao = attachmentDao,
            reactionDao = reactionDao,
            decryptor = decryptor,
            authRepository = auth,
            messagesApi = delivered,
            tokenStore = cursorStore,
        )
        return pipeline to Harness(auth, decryptor, dao, reactionDao, attachmentDao)
    }

    private data class Harness(
        val auth: FakeAuthRepository,
        val decryptor: FakeMessageDecryptor,
        val dao: InMemoryMessageDao,
        val reactionDao: InMemoryReactionDao,
        val attachmentDao: InMemoryAttachmentDao,
    )

    // Positive fixtures model the updated sender; security tests below deliberately bypass this helper.
    private suspend fun Harness.ingest(pipeline: MessageIngestionPipeline,
        dtos: List<com.messenger.app.data.api.MessageDto>, advanceCursor: Boolean = true) {
        for (dto in dtos) if (dto.kind != "content") {
            val payload = decryptor.customResponses[dto.id] ?: when (dto.kind) {
                "delete" -> MessageContentCodec.encodeDelete()
                else -> MessageContentCodec.encodeEdit("decoded:" + dto.id)
            }
            decryptor.customResponses[dto.id] = MessageContentCodec.authenticate(payload, dto.chatId,
                dto.clientMessageId, dto.kind, dto.targetMessageId,
                dto.targetMessageId?.let { dao.findByServerId(it)?.clientMessageId ?: it })
        }
        pipeline.ingest(dtos, advanceCursor)
    }

    /** Seeds an already-ingested "content" message directly into the fake
     *  DAO, as if it had arrived earlier - the target every lifecycle
     *  event test below acts on. */
    private fun seedContentMessage(dao: InMemoryMessageDao, serverId: String, text: String = "Hello") = runBlocking {
        dao.upsert(
            com.messenger.app.data.local.message.MessageEntity(
                clientMessageId = "own-$serverId",
                serverId = serverId,
                chatId = "chat-1",
                senderId = "other",
                text = text,
                sequence = 1,
                createdAt = "2026-01-01T00:00:00Z",
                status = MessageStatus.DELIVERED.name.lowercase(),
            ),
        )
    }

    @Test
    fun `edit and delete before original survive a later history page`() = runBlocking {
        val (pipeline, h) = harness()
        h.ingest(pipeline, listOf(testMessageDto(id = "early-edit", sequence = 20, kind = "edit", targetMessageId = "original")))
        h.ingest(pipeline, listOf(testMessageDto(id = "original", sequence = 10)), advanceCursor = false)
        assertEquals("decoded:early-edit", h.dao.findByServerId("original")?.text)
        h.ingest(pipeline, listOf(testMessageDto(id = "early-delete", sequence = 30, kind = "delete", targetMessageId = "late-original")))
        h.ingest(pipeline, listOf(testMessageDto(id = "late-original", sequence = 15)), advanceCursor = false)
        assertTrue(h.dao.findByServerId("late-original")?.isDeletedForEveryone == true)
    }

    @Test
    fun `edit event updates the target text`() = runBlocking {
        val (pipeline, h) = harness()
        seedContentMessage(h.dao, "srv-1")
        h.decryptor.customResponses["e1"] = MessageContentCodec.encodeEdit("Hello v2")

        val dto = testMessageDto(id = "e1", sequence = 5, kind = "edit", targetMessageId = "srv-1")
        h.ingest(pipeline, listOf(dto))

        val target = h.dao.findByClientMessageId("own-srv-1")
        assertEquals("Hello v2", target?.text)
        assertTrue(target?.isEdited == true)
    }

    @Test
    fun `a stale edit with a lower sequence than the last applied edit is ignored`() = runBlocking {
        val (pipeline, h) = harness()
        seedContentMessage(h.dao, "srv-1")
        h.decryptor.customResponses["e-new"] = MessageContentCodec.encodeEdit("Newest")
        h.decryptor.customResponses["e-old"] = MessageContentCodec.encodeEdit("Stale")

        h.ingest(pipeline, listOf(testMessageDto(id = "e-new", sequence = 10, kind = "edit", targetMessageId = "srv-1")))
        h.ingest(pipeline, listOf(testMessageDto(id = "e-old", sequence = 5, kind = "edit", targetMessageId = "srv-1")))

        assertEquals("Newest", h.dao.findByClientMessageId("own-srv-1")?.text, "a later-arriving-but-earlier-sequence edit must never win")
    }

    @Test
    fun `delete tombstones the target`() = runBlocking {
        val (pipeline, h) = harness()
        seedContentMessage(h.dao, "srv-1")
        h.decryptor.customResponses["d1"] = MessageContentCodec.encodeDelete()

        h.ingest(pipeline, listOf(testMessageDto(id = "d1", sequence = 7, kind = "delete", targetMessageId = "srv-1")))

        assertTrue(h.dao.findByClientMessageId("own-srv-1")?.isDeletedForEveryone == true)
    }

    @Test
    fun `a stale edit arriving after delete cannot resurrect the message`() = runBlocking {
        val (pipeline, h) = harness()
        seedContentMessage(h.dao, "srv-1")
        h.decryptor.customResponses["d1"] = MessageContentCodec.encodeDelete()
        h.decryptor.customResponses["e-stale"] = MessageContentCodec.encodeEdit("Resurrected")

        // Delete lands first (higher sequence), then a stale, older edit
        // arrives late (simulating a reordered WS/sync delivery).
        h.ingest(pipeline, listOf(testMessageDto(id = "d1", sequence = 20, kind = "delete", targetMessageId = "srv-1")))
        h.ingest(pipeline, listOf(testMessageDto(id = "e-stale", sequence = 10, kind = "edit", targetMessageId = "srv-1")))

        val target = h.dao.findByClientMessageId("own-srv-1")
        assertTrue(target?.isDeletedForEveryone == true, "delete must remain in effect")
        assertEquals("Hello", target?.text, "the stale edit must never have been applied")
    }

    @Test
    fun `reaction set stores the active emoji`() = runBlocking {
        val (pipeline, h) = harness()
        seedContentMessage(h.dao, "srv-1")
        h.decryptor.customResponses["r1"] = MessageContentCodec.encodeReaction("heart")

        h.ingest(pipeline, listOf(testMessageDto(id = "r1", senderId = "bob", sequence = 3, kind = "reaction", targetMessageId = "srv-1")))

        val reaction = h.reactionDao.all().single()
        assertEquals("heart", reaction.emoji)
        assertEquals("bob", reaction.userId)
    }

    @Test
    fun `changing a reaction replaces the previous one for the same user`() = runBlocking {
        val (pipeline, h) = harness()
        seedContentMessage(h.dao, "srv-1")
        h.decryptor.customResponses["r1"] = MessageContentCodec.encodeReaction("heart")
        h.decryptor.customResponses["r2"] = MessageContentCodec.encodeReaction("laugh")

        h.ingest(pipeline, listOf(testMessageDto(id = "r1", senderId = "bob", sequence = 3, kind = "reaction", targetMessageId = "srv-1")))
        h.ingest(pipeline, listOf(testMessageDto(id = "r2", senderId = "bob", sequence = 4, kind = "reaction", targetMessageId = "srv-1")))

        val reactions = h.reactionDao.all()
        assertEquals(1, reactions.size, "at most one active reaction per user per message")
        assertEquals("laugh", reactions.single().emoji)
    }

    @Test
    fun `removing a reaction clears the active emoji`() = runBlocking {
        val (pipeline, h) = harness()
        seedContentMessage(h.dao, "srv-1")
        h.decryptor.customResponses["r1"] = MessageContentCodec.encodeReaction("heart")
        h.decryptor.customResponses["r2"] = MessageContentCodec.encodeReactionRemoval()

        h.ingest(pipeline, listOf(testMessageDto(id = "r1", senderId = "bob", sequence = 3, kind = "reaction", targetMessageId = "srv-1")))
        h.ingest(pipeline, listOf(testMessageDto(id = "r2", senderId = "bob", sequence = 4, kind = "reaction", targetMessageId = "srv-1")))

        assertTrue(h.reactionDao.all().none { it.emoji != null }, "no active reaction should remain")
    }

    @Test
    fun `a stale reaction event is ignored`() = runBlocking {
        val (pipeline, h) = harness()
        seedContentMessage(h.dao, "srv-1")
        h.decryptor.customResponses["r-new"] = MessageContentCodec.encodeReactionRemoval()
        h.decryptor.customResponses["r-old"] = MessageContentCodec.encodeReaction("heart")

        h.ingest(pipeline, listOf(testMessageDto(id = "r-new", senderId = "bob", sequence = 10, kind = "reaction", targetMessageId = "srv-1")))
        h.ingest(pipeline, listOf(testMessageDto(id = "r-old", senderId = "bob", sequence = 5, kind = "reaction", targetMessageId = "srv-1")))

        assertTrue(h.reactionDao.all().none { it.emoji != null }, "the stale 'set heart' must not resurrect a removed reaction")
    }

    @Test
    fun `deleting the target message clears its reactions`() = runBlocking {
        val (pipeline, h) = harness()
        seedContentMessage(h.dao, "srv-1")
        h.decryptor.customResponses["r1"] = MessageContentCodec.encodeReaction("heart")
        h.decryptor.customResponses["d1"] = MessageContentCodec.encodeDelete()

        h.ingest(pipeline, listOf(testMessageDto(id = "r1", senderId = "bob", sequence = 3, kind = "reaction", targetMessageId = "srv-1")))
        h.ingest(pipeline, listOf(testMessageDto(id = "d1", sequence = 4, kind = "delete", targetMessageId = "srv-1")))

        assertTrue(h.reactionDao.all().isEmpty(), "a deleted message must not keep showing reactions")
    }

    @Test
    fun `duplicate lifecycle event via WS and sync is applied exactly once`() = runBlocking {
        val (pipeline, h) = harness()
        seedContentMessage(h.dao, "srv-1")
        h.decryptor.customResponses["e1"] = MessageContentCodec.encodeEdit("Edited once")

        val dto = testMessageDto(id = "e1", sequence = 8, kind = "edit", targetMessageId = "srv-1")
        // Simulate the same event arriving twice (e.g. once via WS, once
        // via a /sync catch-up that raced with it) - same clientMessageId.
        h.ingest(pipeline, listOf(dto))
        h.ingest(pipeline, listOf(dto))

        assertEquals(1, h.decryptor.decryptCallCounts["e1"], "an already-processed event must never be decrypted twice")
        assertEquals("Edited once", h.dao.findByClientMessageId("own-srv-1")?.text)
    }

    @Test
    fun `a reply content message stores its snapshot and target`() = runBlocking {
        val (pipeline, h) = harness()
        val snapshot = com.messenger.app.data.attachment.ReplySnapshotDto(
            senderId = "other",
            contentType = com.messenger.app.data.attachment.ReplySnapshotDto.CONTENT_TYPE_TEXT,
            preview = "Hello",
        )
        h.decryptor.customResponses["c1"] = MessageContentCodec.encodeText("Hi back", replySnapshot = snapshot)

        h.ingest(pipeline, listOf(testMessageDto(id = "c1", sequence = 2, targetMessageId = "srv-1")))

        val stored = h.dao.findByClientMessageId("c1")
        assertEquals("srv-1", stored?.targetMessageId)
        assertTrue(stored?.replySnapshotJson?.contains("Hello") == true)
    }

    @Test
    fun `an edit event never creates a visible chat bubble of its own`() = runBlocking {
        val (pipeline, h) = harness()
        seedContentMessage(h.dao, "srv-1")
        h.decryptor.customResponses["e1"] = MessageContentCodec.encodeEdit("Edited")

        h.ingest(pipeline, listOf(testMessageDto(id = "e1", sequence = 9, kind = "edit", targetMessageId = "srv-1")))

        val eventRow = h.dao.findByClientMessageId("e1")
        assertEquals("edit", eventRow?.kind)
        // observeMessages filters kind != "content" - verified at the DAO
        // query level (see MessageDao), this asserts the event row itself
        // carries the right kind so that filter actually excludes it.
    }

    @Test
    fun `an edit targeting a message not present locally is a harmless no-op`() = runBlocking {
        val (pipeline, h) = harness()
        h.decryptor.customResponses["e1"] = MessageContentCodec.encodeEdit("Edited")

        h.ingest(pipeline, listOf(testMessageDto(id = "e1", sequence = 1, kind = "edit", targetMessageId = "never-seen")))

        assertNull(h.dao.findByServerId("never-seen"))
        assertFalse(h.dao.all().any { it.text == "Edited" && it.kind == "content" })
    }
}
