package com.messenger.app.data.sync

import com.messenger.app.data.attachment.AttachmentMetadataDto
import com.messenger.app.data.attachment.EncryptionInfoDto
import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.signal.MessageDecryptor
import com.messenger.app.domain.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Exercises [MessageIngestionPipeline] in isolation - the single code path
 * every incoming message (WS push, `/sync` catch-up, history pagination)
 * flows through. Crypto correctness itself is covered by SignalCryptoTest.kt
 * (real libsignal); this uses [FakeMessageDecryptor] to focus purely on the
 * pipeline's own orchestration guarantees: dedup/idempotency, forward-only
 * cursor advance, per-chat delivered-watermark batching, and session-epoch
 * abort safety - exactly the class of bug the Этап F `DuplicateMessageException`
 * incident revealed (see memory notes), now enforced systemically here.
 */
class MessageIngestionPipelineTest {
    @Test fun localExclusionDropsBeforeCryptoAcrossReplays() = runBlocking {
        val dao = InMemoryMessageDao(); val decryptor = FakeMessageDecryptor(); val cursor = InMemorySyncCursorStore()
        var excluded = true
        val pipeline = MessageIngestionPipeline(dao, InMemoryChatDao(), InMemoryAttachmentDao(), InMemoryReactionDao(), decryptor,
            FakeAuthRepository(), FakeDeliveredReporter(), cursor, excludeIncoming = { _, _ -> excluded })
        val blocked = testMessageDto("blocked", sequence = 1)
        pipeline.ingest(listOf(blocked))
        pipeline.ingest(listOf(blocked), advanceCursor = false)
        assertTrue(dao.findByClientMessageId(blocked.clientMessageId)!!.hiddenLocally); assertEquals("", dao.findByClientMessageId(blocked.clientMessageId)!!.text); assertTrue(dao.pendingDecrypts().isEmpty())
        assertTrue(decryptor.decryptCallCounts.isEmpty()); assertEquals(1L, cursor.lastSyncedSequence())
        excluded = false
        val next = testMessageDto("next", sequence = 2)
        pipeline.ingest(listOf(next)); assertEquals(1, decryptor.decryptCallCounts[next.id])
        assertEquals(2L, cursor.lastSyncedSequence())
    }

    @Test fun `retained receive retry cannot resurrect a locally hidden failure placeholder`() = runBlocking {
        val fake = FakeMessageDecryptor().apply { failingMessageIds += "hidden" }
        val (pipeline,h) = harness(decryptor=fake)
        val dto = testMessageDto(id="hidden",sequence=1)
        pipeline.ingest(listOf(dto)); h.dao.hideLocally(dto.clientMessageId)
        fake.failingMessageIds.clear()
        pipeline.ingest(listOf(dto))
        assertTrue(h.dao.findByClientMessageId(dto.clientMessageId)!!.hiddenLocally)
        assertTrue(h.dao.pendingDecrypts().isEmpty())
        assertEquals(1,fake.decryptCallCounts[dto.id])
    }
    @Test fun `permanent authenticated rejection retires journals so another peer is never blocked by capacity`() = runBlocking {
        val fake = FakeMessageDecryptor(); val retained = mutableSetOf<String>(); var acknowledgements = 0
        val decryptor = object : MessageDecryptor by fake {
            override suspend fun decryptRetained(dto: com.messenger.app.data.api.MessageDto, signalDeviceId: Int): ByteArray {
                check(retained.size < 512)
                retained += dto.id
                return fake.decrypt(dto.senderId, signalDeviceId, dto.messageType, dto.ciphertext)
            }
            override suspend fun acknowledge(dto: com.messenger.app.data.api.MessageDto) { if (retained.remove(dto.id)) acknowledgements++ }
        }
        val dao = InMemoryMessageDao()
        val pipeline = MessageIngestionPipeline(dao,InMemoryChatDao(),InMemoryAttachmentDao(),InMemoryReactionDao(),decryptor,
            FakeAuthRepository(),FakeDeliveredReporter(),InMemorySyncCursorStore())
        for (i in 1..513) {
            val dto = testMessageDto(id="invalid-$i",sequence=i.toLong())
            fake.customResponses[dto.id] = MessageContentCodec.authenticate(MessageContentCodec.encodeText("malformed"), "wrong-chat", dto.clientMessageId,"content",null)
            pipeline.ingest(listOf(dto))
        }
        assertTrue(retained.isEmpty()); assertEquals(513,acknowledgements); assertTrue(dao.pendingDecrypts().isEmpty())
        val valid = testMessageDto(id="good-other-peer",senderId="another-peer",sequence=514)
        pipeline.ingest(listOf(valid)); assertEquals("decoded:good-other-peer",dao.findByClientMessageId(valid.clientMessageId)?.text)
    }
    private fun uploadedFixture(client: String="m1", chat: String="chat-1") = com.messenger.app.data.local.attachment.AttachmentEntity(
        "att-1",client,chat,"file","video/mp4","fixture.mp4",10,120,80,1000,"thumbnail",
        "/owned/preview.mp4","downloading","uploaded",42,"attachments/att-1",26,1,"key","nonce",null,"hash")

    @Test fun `confirmed sync replay repairs upload state without decrypting or overwriting download cache`() = runBlocking {
        val decryptor=FakeMessageDecryptor()
        val (pipeline,h)=harness(decryptor=decryptor)
        val message=MessageEntity("m1","server-1","chat-1","me","confirmed",10,"2026-01-01T00:00:00Z","read")
        val attachment=uploadedFixture()
        h.dao.upsert(message);h.attachmentDao.upsert(attachment)
        val dto=testMessageDto("server-1",clientMessageId="m1",senderId="me",sequence=10)
        pipeline.ingest(listOf(dto));pipeline.ingest(listOf(dto))
        assertEquals(attachment.copy(uploadState="sent"),h.attachmentDao.findByAttachmentId("att-1"))
        assertEquals(message,h.dao.findByClientMessageId("m1"))
        assertTrue(decryptor.decryptCallCounts.isEmpty(),"Confirmed Signal ciphertext must never be replay-decrypted")
    }

    @Test fun `confirmed replay cannot repair unrelated incomplete deleted or cancelled upload`() = runBlocking {
        val confirmed=MessageEntity("m1","server-1","chat-1","me","confirmed",10,"2026-01-01T00:00:00Z","read")
        val invalid=listOf(confirmed.copy(serverId="foreign"),confirmed.copy(serverId=null),confirmed.copy(sequence=null),
            confirmed.copy(sequence=0),confirmed.copy(sequence=9),confirmed.copy(chatId="other-chat"),
            confirmed.copy(senderId="other"),confirmed.copy(status="pending"),confirmed.copy(status="failed"),
            confirmed.copy(isDeletedForEveryone=true),confirmed.copy(hiddenLocally=true),confirmed.copy(kind="edit"))
        for(message in invalid) {
            val (pipeline,h)=harness();val attachment=uploadedFixture()
            h.dao.upsert(message);h.attachmentDao.upsert(attachment)
            pipeline.ingest(listOf(testMessageDto("server-1",clientMessageId="m1",senderId="me",sequence=10)))
            assertEquals(attachment,h.attachmentDao.findByAttachmentId("att-1"))
        }
        for(attachment in listOf(uploadedFixture().copy(uploadState="cancelled"),uploadedFixture(client="other-client"),uploadedFixture(chat="other-chat"))) {
            val (pipeline,h)=harness();h.dao.upsert(confirmed);h.attachmentDao.upsert(attachment)
            pipeline.ingest(listOf(testMessageDto("server-1",clientMessageId="m1",senderId="me",sequence=10)))
            assertEquals(attachment,h.attachmentDao.findByAttachmentId("att-1"))
        }
    }

    @Test fun `authenticated attachment ingestion repairs existing matching local upload only`() = runBlocking {
        val metadata=AttachmentMetadataDto(attachmentId="att-1",mediaType="file",objectKey="attachments/att-1",size=10,
            encryptedSize=26,mimeType="video/mp4",encryption=EncryptionInfoDto(version=1,key="key",nonce="nonce",sha256="hash"))
        val decryptor=FakeMessageDecryptor().apply { customResponses["m1"]=MessageContentCodec.encodeAttachment(metadata) }
        val (pipeline,h)=harness(decryptor=decryptor);val attachment=uploadedFixture()
        h.attachmentDao.upsert(attachment)
        pipeline.ingest(listOf(testMessageDto("m1",senderId="me",sequence=10)))
        assertEquals(attachment.copy(uploadState="sent"),h.attachmentDao.findByAttachmentId("att-1"))
    }

    @Test fun `conference invitation is authenticated persisted and replayed without raw capability text`(): Unit = runBlocking {
        val vectorText = listOf(java.io.File("../shared/conference-v2-vectors.json"), java.io.File("../../shared/conference-v2-vectors.json")).first { it.isFile }.readText()
        val vectors = kotlinx.serialization.json.Json.parseToJsonElement(vectorText) as kotlinx.serialization.json.JsonObject
        val metadata = (((vectors.getValue("metadataValid") as kotlinx.serialization.json.JsonArray).first() as kotlinx.serialization.json.JsonObject).getValue("metadata"))
        val card = requireNotNull(com.messenger.app.conference.ConferenceInvitation.decode(metadata))
        val decryptor = FakeMessageDecryptor()
        decryptor.customResponses[card.id] = MessageContentCodec.authenticate(
            MessageContentCodec.encodeText(com.messenger.app.conference.ConferenceInvitation.FALLBACK, conferenceInvite = card), "chat-1", card.id, "content", null)
        val (pipeline, h) = harness(decryptor = decryptor)
        val dto = testMessageDto(card.id, sequence = 1)
        pipeline.ingest(listOf(dto))
        val stored = requireNotNull(h.dao.findByClientMessageId(card.id))
        assertEquals(com.messenger.app.conference.ConferenceInvitation.FALLBACK, stored.text)
        assertEquals(card, com.messenger.app.conference.ConferenceInvitation.decodeStored(stored.conferenceInviteJson))
        pipeline.ingest(listOf(dto))
        assertEquals(1, decryptor.decryptCallCounts[card.id])
        assertEquals(stored.conferenceInviteJson, h.dao.findByClientMessageId(card.id)?.conferenceInviteJson)
    }
    @Test fun `completed empty sync retries old deletion without a new prekey`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "old-delete" }
        val (pipeline, h) = harness(decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto("original", sequence = 1)))
        decryptor.customResponses["old-delete"] = MessageContentCodec.authenticate(
            MessageContentCodec.encodeDelete(), "chat-1", "old-delete", "delete", "original", "original")
        pipeline.ingest(listOf(testMessageDto("old-delete", kind = "delete", targetMessageId = "original", sequence = 2)))
        decryptor.failingMessageIds.clear()
        pipeline.ingest(emptyList())
        pipeline.recoverRetainedAfterSync()
        assertTrue(h.dao.pendingDecrypts().isEmpty())
        assertEquals("delivered", h.dao.findByClientMessageId("old-delete")?.status)
        assertTrue(h.dao.findByClientMessageId("original")!!.isDeletedForEveryone)
        pipeline.recoverRetainedAfterSync()
        assertEquals(2, decryptor.decryptCallCounts["old-delete"])
    }

    @Test fun `catchup recovery is bounded and never marks failed decrypt as deleted`() = runBlocking {
        val ids = (1..140).map { "broken-$it" }
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += ids }
        val (pipeline, h) = harness(decryptor = decryptor)
        pipeline.ingest(ids.mapIndexed { i, id -> testMessageDto(id, kind = "delete", sequence = i.toLong() + 1) })
        pipeline.recoverRetainedAfterSync()
        assertEquals(128, decryptor.decryptCallCounts.values.count { it == 2 })
        assertEquals(140, h.dao.pendingDecrypts().size)
        assertTrue(h.dao.all().all { it.status == "decrypt_failed" && !it.isDeletedForEveryone })
        pipeline.recoverRetainedAfterSync()
        assertEquals(128, decryptor.decryptCallCounts.values.count { it == 2 })
    }

    @Test
    fun `successful wire prekey retries retained events only for its sender and only once`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += setOf("alice-old", "bob-old") }
        val (pipeline, h) = harness(decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto("alice-old", senderId = "alice", sequence = 1),
            testMessageDto("bob-old", senderId = "bob", sequence = 2)))
        decryptor.failingMessageIds.clear()
        val prekey = testMessageDto("alice-new", senderId = "alice", sequence = 3, messageType = 1)
        pipeline.ingest(listOf(prekey))
        assertEquals(MessageStatus.DELIVERED.name.lowercase(), h.dao.findByClientMessageId("alice-old")?.status)
        assertEquals(2, decryptor.decryptCallCounts["alice-old"])
        assertEquals(1, decryptor.decryptCallCounts["bob-old"])
        pipeline.ingest(listOf(prekey))
        assertEquals(2, decryptor.decryptCallCounts["alice-old"])
        assertEquals(1, decryptor.decryptCallCounts["alice-new"])
    }

    @Test
    fun `failed wire prekey never triggers retained event retries`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += setOf("old", "new") }
        val (pipeline, h) = harness(decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto("old", sequence = 1)))
        pipeline.ingest(listOf(testMessageDto("new", sequence = 2, messageType = 1)))
        assertEquals(1, decryptor.decryptCallCounts["old"])
        assertEquals(2, h.dao.pendingDecrypts().size)
    }

    @Test fun `receipt transport does not delay durable realtime messages`() = runBlocking {
        val scheduled = mutableListOf<suspend () -> Unit>()
        val (pipeline, h) = harness(scheduleDelivery = { scheduled += it })
        pipeline.ingest(listOf(testMessageDto("first", sequence = 1)), false)
        pipeline.ingest(listOf(testMessageDto("second", sequence = 2)), false)
        assertEquals(2, h.dao.all().size)
        assertTrue(h.delivered.calls.isEmpty())
        h.auth.switchSession()
        scheduled.forEach { it() }
        assertTrue(h.delivered.calls.isEmpty(), "old-account transport must be discarded")
    }

    @Test fun `delivery advances past old failed decryption`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "bad" }
        val (pipeline, h) = harness(decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto(id = "ok", sequence = 1), testMessageDto(id = "bad", sequence = 2), testMessageDto(id = "later", sequence = 3)), false)
        assertEquals(3L, h.delivered.calls.last().sequence)
        decryptor.failingMessageIds.clear()
        pipeline.ingest(listOf(testMessageDto(id = "bad", sequence = 2), testMessageDto(id = "later", sequence = 3)), false)
        assertEquals(3L, h.delivered.calls.last().sequence)
    }

    private fun harness(
        auth: FakeAuthRepository = FakeAuthRepository(),
        decryptor: FakeMessageDecryptor = FakeMessageDecryptor(),
        delivered: FakeDeliveredReporter = FakeDeliveredReporter(),
        cursorStore: InMemorySyncCursorStore = InMemorySyncCursorStore(),
        dao: InMemoryMessageDao = InMemoryMessageDao(),
        chatDao: InMemoryChatDao = InMemoryChatDao(),
        attachmentDao: InMemoryAttachmentDao = InMemoryAttachmentDao(),
        reactionDao: InMemoryReactionDao = InMemoryReactionDao(),
        scheduleDelivery: ((suspend () -> Unit) -> Unit)? = null,
    ) = MessageIngestionPipeline(
        dao = dao,
        chatDao = chatDao,
        attachmentDao = attachmentDao,
        reactionDao = reactionDao,
        decryptor = decryptor,
        authRepository = auth,
        messagesApi = delivered,
        tokenStore = cursorStore,
        scheduleDelivery = scheduleDelivery,
    ) to Harness(auth, decryptor, delivered, cursorStore, dao, chatDao, attachmentDao, reactionDao)

    private data class Harness(
        val auth: FakeAuthRepository,
        val decryptor: FakeMessageDecryptor,
        val delivered: FakeDeliveredReporter,
        val cursorStore: InMemorySyncCursorStore,
        val dao: InMemoryMessageDao,
        val chatDao: InMemoryChatDao,
        val attachmentDao: InMemoryAttachmentDao,
        val reactionDao: InMemoryReactionDao,
    )

    @Test
    fun `realtime and history never skip unseen global sync messages`() = runBlocking {
        val (pipeline, h) = harness()
        pipeline.ingest(listOf(testMessageDto(id = "new", chatId = "chat-b", sequence = 100)), advanceCursor = false)
        assertEquals(0L, h.cursorStore.lastSyncedSequence())
        pipeline.ingest(listOf(testMessageDto(id = "unseen", chatId = "chat-a", sequence = 50)))
        assertEquals(50L, h.cursorStore.lastSyncedSequence())
        assertEquals("decoded:unseen", h.dao.findByClientMessageId("unseen")?.text)
        pipeline.ingest(listOf(testMessageDto(id = "new", chatId = "chat-b", sequence = 100)))
        assertEquals(100L, h.cursorStore.lastSyncedSequence())
        assertEquals(1, h.decryptor.decryptCallCounts["new"])
    }

    @Test
    fun `missing access token cannot commit a cursor for dropped messages`() = runBlocking {
        val (pipeline, h) = harness(auth = FakeAuthRepository(token = null))
        assertFalse(pipeline.ingest(listOf(testMessageDto(id = "lost", sequence = 10))).advancedFully)
        assertEquals(0L, h.cursorStore.lastSyncedSequence())
        assertTrue(h.dao.all().isEmpty())
    }

    @Test
    fun `empty batch is a no-op`() = runBlocking {
        val (pipeline, h) = harness()
        val result = pipeline.ingest(emptyList())
        assertTrue(result.advancedFully)
        assertEquals(0, result.highestSequence)
        assertTrue(h.dao.all().isEmpty())
    }

    @Test
    fun `incoming message from another user is decrypted and marked delivered`() = runBlocking {
        val (pipeline, h) = harness()
        val dto = testMessageDto(id = "m1", senderId = "other", sequence = 1)

        val result = pipeline.ingest(listOf(dto))

        assertTrue(result.advancedFully)
        assertEquals(1, result.highestSequence)
        val stored = h.dao.findByClientMessageId("m1")
        assertEquals(MessageStatus.DELIVERED.name.lowercase(), stored?.status)
        assertEquals("decoded:m1", stored?.text)
        assertEquals(1L, h.cursorStore.lastSyncedSequence())
    }

    @Test
    fun `own echoed message is marked sent, not delivered`() = runBlocking {
        val (pipeline, h) = harness()
        val dto = testMessageDto(id = "m1", senderId = "me", sequence = 1)

        pipeline.ingest(listOf(dto))

        assertEquals(MessageStatus.SENT.name.lowercase(), h.dao.findByClientMessageId("m1")?.status)
    }

    @Test
    fun `a message already durably processed is never decrypted twice`() = runBlocking {
        val (pipeline, h) = harness()
        val dto = testMessageDto(id = "m1", sequence = 1)

        pipeline.ingest(listOf(dto))
        pipeline.ingest(listOf(dto)) // e.g. WS push + a /sync page racing for the same message

        assertEquals(1, h.decryptor.decryptCallCounts["m1"])
    }

    @Test
    fun `a previously failed decrypt is retried on the next ingest`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(decryptor = decryptor)
        val dto = testMessageDto(id = "m1", sequence = 1)

        pipeline.ingest(listOf(dto))
        assertEquals(MessageStatus.DECRYPT_FAILED.name.lowercase(), h.dao.findByClientMessageId("m1")?.status)
        // Retained once; there is no immediate second attempt at batch end.
        assertEquals(1, h.dao.pendingDecrypts().size)

        decryptor.failingMessageIds.clear()
        pipeline.ingest(listOf(dto))

        assertEquals(2, h.decryptor.decryptCallCounts["m1"])
        assertEquals(MessageStatus.DELIVERED.name.lowercase(), h.dao.findByClientMessageId("m1")?.status)
        assertEquals(0, h.dao.pendingDecrypts().size)
    }

    @Test
    fun `unrelated new messages do not retry retained failures`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "old" }
        val (pipeline, h) = harness(decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto(id = "old", senderId = "alice", sequence = 1)))
        repeat(50) { i -> pipeline.ingest(listOf(testMessageDto(id = "new$i", senderId = "bob", sequence = 2L + i))) }
        assertEquals(1, h.decryptor.decryptCallCounts["old"])
        assertEquals(1, h.dao.pendingDecrypts().size)
        decryptor.failingMessageIds.clear()
        pipeline.retryPendingDecrypts("alice")
        assertEquals(2, h.decryptor.decryptCallCounts["old"])
        assertEquals(0, h.dao.pendingDecrypts().size)
    }

    @Test
    fun `a decrypt failure still advances the durable cursor`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(decryptor = decryptor)
        val dto = testMessageDto(id = "m1", sequence = 5)

        val result = pipeline.ingest(listOf(dto))

        assertTrue(result.advancedFully)
        assertEquals(5L, h.cursorStore.lastSyncedSequence())
    }

    @Test
    fun `session epoch change mid-batch aborts remaining messages`() = runBlocking {
        val auth = FakeAuthRepository()
        val (pipeline, h) = harness(auth = auth)
        val dto1 = testMessageDto(id = "m1", sequence = 1)
        val dto2 = testMessageDto(id = "m2", sequence = 2)

        // The epoch check happens once per message within a single ingest()
        // call; simulate a login/logout racing in between item 1 and item 2
        // of this one batch (see switchEpochAfterCall's doc comment).
        auth.switchEpochAfterCall = 2
        val result = pipeline.ingest(listOf(dto1, dto2))

        assertFalse(result.advancedFully)
        assertEquals(MessageStatus.DELIVERED.name.lowercase(), h.dao.findByClientMessageId("m1")?.status)
        assertNull(h.dao.findByClientMessageId("m2"))
    }

    @Test
    fun `delivered watermark is reported once per chat with the max sequence, not once per message`() = runBlocking {
        val (pipeline, h) = harness()
        val dtos = listOf(
            testMessageDto(id = "m1", chatId = "chat-a", sequence = 1),
            testMessageDto(id = "m2", chatId = "chat-a", sequence = 2),
            testMessageDto(id = "m3", chatId = "chat-b", sequence = 3),
        )

        pipeline.ingest(dtos)

        assertEquals(2, h.delivered.calls.size)
        assertEquals(2L, h.delivered.calls.first { it.chatId == "chat-a" }.sequence)
        assertEquals(3L, h.delivered.calls.first { it.chatId == "chat-b" }.sequence)
    }

    @Test
    fun `cursor never regresses across separate ingest calls`() = runBlocking {
        val (pipeline, h) = harness()
        pipeline.ingest(listOf(testMessageDto(id = "m2", sequence = 5)))
        assertEquals(5L, h.cursorStore.lastSyncedSequence())

        // A late/out-of-order duplicate delivery of an older message must
        // never move the cursor backwards.
        pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 2)))
        assertEquals(5L, h.cursorStore.lastSyncedSequence())
    }

    @Test
    fun `messages are processed in ascending sequence order regardless of batch order`() = runBlocking {
        val (pipeline, h) = harness()
        val dtos = listOf(
            testMessageDto(id = "m3", sequence = 3),
            testMessageDto(id = "m1", sequence = 1),
            testMessageDto(id = "m2", sequence = 2),
        )

        val result = pipeline.ingest(dtos)

        assertEquals(3L, result.highestSequence)
        assertEquals(setOf("m1", "m2", "m3"), h.dao.all().map { it.clientMessageId }.toSet())
    }

    @Test
    fun `not authenticated drops the message without crashing`() = runBlocking {
        val auth = FakeAuthRepository().apply { setToken(null) }
        val (pipeline, h) = harness(auth = auth)

        val result = pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 1)))

        assertFalse(result.advancedFully)
        assertEquals(0L, h.cursorStore.lastSyncedSequence())
        assertNull(h.dao.findByClientMessageId("m1"))
    }

    @Test
    fun `a failed delivered report is swallowed and never crashes ingestion`() = runBlocking {
        val delivered = FakeDeliveredReporter().apply { failFor = "chat-1" }
        val (pipeline, h) = harness(delivered = delivered)

        val result = pipeline.ingest(listOf(testMessageDto(id = "m1", chatId = "chat-1", sequence = 1)))

        assertTrue(result.advancedFully)
        assertEquals(MessageStatus.DELIVERED.name.lowercase(), h.dao.findByClientMessageId("m1")?.status)
    }

    @Test
    fun `delayed old-account ingest released after clear writes zero rows - 100 iterations`() = runBlocking {
        repeat(100) { iteration ->
            val auth = FakeAuthRepository(userId = "A")
            val decryptor = GatedDecryptor()
            val dao = InMemoryMessageDao()
            val pipeline = MessageIngestionPipeline(
                dao = dao,
                chatDao = InMemoryChatDao(),
                attachmentDao = InMemoryAttachmentDao(),
                reactionDao = InMemoryReactionDao(),
                decryptor = decryptor,
                authRepository = auth,
                messagesApi = FakeDeliveredReporter(),
                tokenStore = InMemorySyncCursorStore(),
            )

            // An old-account (A) batch starts and blocks inside decrypt while
            // holding the shared session gate.
            val job = launch { pipeline.ingest(listOf(testMessageDto(id = "m1", senderId = "B", sequence = 1))) }
            while (!decryptor.entered) yield()

            // Account switch: logout bumps the epoch; clearRoom JOINS the
            // in-flight write (blocks on the shared gate) and then wipes Room.
            // The clear runs in its own coroutine because it blocks on the gate
            // until the delayed write is released (mirroring production).
            auth.logout()
            val clearJob = launch { auth.clearRoom { dao.clear() } }

            // Release the delayed old-account operation only after the clear.
            decryptor.release.complete(Unit)
            job.join()
            clearJob.join()

            assertEquals(0, dao.all().size, "iteration $iteration: an old-account row leaked after the switch")
        }
    }

    // --- P1-4: durable decrypt recovery -----------------------------------

    @Test
    fun `successful decrypt leaves no pending retry row`() = runBlocking {
        val (pipeline, h) = harness()
        pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 1)))
        assertEquals(0, h.dao.pendingDecrypts().size)
    }

    @Test
    fun `transient decrypt failure retains the encrypted envelope durably`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 1)))

        val pending = h.dao.pendingDecrypts().single()
        assertEquals("m1", pending.clientMessageId)
        assertEquals("plaintext-for:m1", pending.ciphertext)
        assertEquals(1L, pending.sequence)
        assertEquals("content", pending.kind)
    }

    @Test
    fun `cursor does not advance when the failed envelope cannot be durably retained`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(decryptor = decryptor)
        h.dao.throwOnPendingInsert = true
        val error = runCatching { pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 5))) }.exceptionOrNull()
        assertTrue(error != null, "retention failure must surface, not silently advance")
        assertEquals(0L, h.cursorStore.lastSyncedSequence())
    }

    @Test
    fun `cursor may advance after the pending decrypt is durably stored`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(decryptor = decryptor)
        val result = pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 5)))
        assertTrue(result.advancedFully)
        assertEquals(5L, h.cursorStore.lastSyncedSequence())
        assertEquals(1, h.dao.pendingDecrypts().size)
    }

    @Test
    fun `retry later succeeds and removes the pending row`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 1)))
        assertEquals(1, h.dao.pendingDecrypts().size)

        decryptor.failingMessageIds.clear()
        pipeline.retryPendingDecrypts()

        assertEquals(0, h.dao.pendingDecrypts().size)
        assertEquals(MessageStatus.DELIVERED.name.lowercase(), h.dao.findByClientMessageId("m1")?.status)
        assertEquals("decoded:m1", h.dao.findByClientMessageId("m1")?.text)
    }

    @Test
    fun `retry success replaces the decrypt_failed projection without duplicates`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 1)))

        decryptor.failingMessageIds.clear()
        pipeline.retryPendingDecrypts()

        assertEquals(1, h.dao.all().count { it.clientMessageId == "m1" })
        assertEquals(MessageStatus.DELIVERED.name.lowercase(), h.dao.findByClientMessageId("m1")?.status)
    }

    @Test
    fun `same envelope via ws and sync yields one pending record`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(decryptor = decryptor)
        val dto = testMessageDto(id = "m1", sequence = 1)
        pipeline.ingest(listOf(dto), advanceCursor = false)
        pipeline.ingest(listOf(dto), advanceCursor = true)
        assertEquals(1, h.dao.pendingDecrypts().size)
    }

    @Test
    fun `same envelope retried many times yields one visible message`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(decryptor = decryptor)
        val dto = testMessageDto(id = "m1", sequence = 1)
        repeat(3) { pipeline.ingest(listOf(dto), advanceCursor = false) }

        decryptor.failingMessageIds.clear()
        pipeline.retryPendingDecrypts()

        assertEquals(1, h.dao.all().count { it.clientMessageId == "m1" })
        assertEquals(0, h.dao.pendingDecrypts().size)
    }

    @Test
    fun `pending retry survives a process restart`() = runBlocking {
        val dao = InMemoryMessageDao()
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val auth = FakeAuthRepository()
        fun newPipeline() = MessageIngestionPipeline(
            dao = dao, chatDao = InMemoryChatDao(), attachmentDao = InMemoryAttachmentDao(),
            reactionDao = InMemoryReactionDao(), decryptor = decryptor, authRepository = auth,
            messagesApi = FakeDeliveredReporter(), tokenStore = InMemorySyncCursorStore(),
        )
        newPipeline().ingest(listOf(testMessageDto(id = "m1", sequence = 1)))
        assertEquals(1, dao.pendingDecrypts().size)

        // "Process death": a brand-new pipeline over the SAME durable store.
        decryptor.failingMessageIds.clear()
        newPipeline().retryPendingDecrypts()

        assertEquals(0, dao.pendingDecrypts().size)
        assertEquals(MessageStatus.DELIVERED.name.lowercase(), dao.findByClientMessageId("m1")?.status)
    }

    @Test
    fun `alice pending retry never runs under bob`() = runBlocking {
        val auth = FakeAuthRepository(userId = "alice")
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(auth = auth, decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 1)))
        assertEquals(1, h.dao.pendingDecrypts().size)

        // Logout clears Room (Alice's pending row) before Bob logs in.
        h.dao.clear()
        auth.switchSession("bob")
        decryptor.failingMessageIds.clear()
        pipeline.retryPendingDecrypts()

        assertTrue(h.dao.pendingDecrypts().isEmpty())
        assertTrue(h.dao.all().isEmpty())
    }

    @Test
    fun `pending envelopes from one sender retry in sequence order`() = runBlocking {
        val decryptor = FakeMessageDecryptor()
        val (pipeline, h) = harness(decryptor = decryptor)
        decryptor.failingMessageIds += "m1"
        pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 1)))
        decryptor.failingMessageIds += "m2"
        pipeline.ingest(listOf(testMessageDto(id = "m2", sequence = 2)))

        decryptor.failingMessageIds.clear()
        decryptor.decryptOrder.clear()
        pipeline.retryPendingDecrypts()

        assertEquals(listOf("m1", "m2"), decryptor.decryptOrder)
        assertEquals(0, h.dao.pendingDecrypts().size)
    }

    @Test
    fun `permanent failure never strands the ciphertext and does not spin`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 1)))

        repeat(15) { pipeline.retryPendingDecrypts() }

        // The ciphertext is retained indefinitely (no permanent abandonment),
        // and retryCount is diagnostics only.
        val pending = h.dao.pendingDecrypts().single()
        assertEquals("plaintext-for:m1", pending.ciphertext)
        assertTrue(pending.retryCount >= 15)

        // Recoverability is preserved: once the failure clears, the same
        // pending record decrypts.
        decryptor.failingMessageIds.clear()
        pipeline.retryPendingDecrypts()
        assertEquals(0, h.dao.pendingDecrypts().size)
        assertEquals(MessageStatus.DELIVERED.name.lowercase(), h.dao.findByClientMessageId("m1")?.status)
    }

    @Test
    fun `edit retry follows the normal edit path`() = runBlocking {
        val decryptor = FakeMessageDecryptor()
        val (pipeline, h) = harness(decryptor = decryptor)
        h.dao.upsert(MessageEntity(
            clientMessageId = "own-srv-1", serverId = "srv-1", chatId = "chat-1", senderId = "other",
            text = "Hello", sequence = 1, createdAt = "2026-01-01T00:00:00Z", status = "delivered",
        ))
        decryptor.customResponses["edit-1"] = MessageContentCodec.authenticate(
            MessageContentCodec.encodeEdit("edited"), "chat-1", "edit-1", "edit", "srv-1", "own-srv-1",
        )
        decryptor.failingMessageIds += "edit-1"
        pipeline.ingest(listOf(testMessageDto(id = "edit-1", senderId = "other", kind = "edit", targetMessageId = "srv-1", sequence = 2)))
        assertEquals(1, h.dao.pendingDecrypts().size)
        assertEquals("Hello", h.dao.findByServerId("srv-1")?.text)

        decryptor.failingMessageIds.clear()
        pipeline.retryPendingDecrypts()

        assertEquals(0, h.dao.pendingDecrypts().size)
        assertEquals("edited", h.dao.findByServerId("srv-1")?.text)
        assertTrue(h.dao.findByServerId("srv-1")!!.isEdited)
    }

    @Test
    fun `delete retry follows the normal delete path`() = runBlocking {
        val decryptor = FakeMessageDecryptor()
        val (pipeline, h) = harness(decryptor = decryptor)
        h.dao.upsert(MessageEntity(
            clientMessageId = "own-srv-1", serverId = "srv-1", chatId = "chat-1", senderId = "other",
            text = "Hello", sequence = 1, createdAt = "2026-01-01T00:00:00Z", status = "delivered",
        ))
        decryptor.customResponses["del-1"] = MessageContentCodec.authenticate(
            MessageContentCodec.encodeDelete(), "chat-1", "del-1", "delete", "srv-1", "own-srv-1",
        )
        decryptor.failingMessageIds += "del-1"
        pipeline.ingest(listOf(testMessageDto(id = "del-1", senderId = "other", kind = "delete", targetMessageId = "srv-1", sequence = 2)))
        assertEquals(1, h.dao.pendingDecrypts().size)

        decryptor.failingMessageIds.clear()
        pipeline.retryPendingDecrypts()

        assertEquals(0, h.dao.pendingDecrypts().size)
        assertTrue(h.dao.findByServerId("srv-1")!!.isDeletedForEveryone)
    }

    @Test
    fun `reaction retry follows the normal reaction path`() = runBlocking {
        val decryptor = FakeMessageDecryptor()
        val (pipeline, h) = harness(decryptor = decryptor)
        h.dao.upsert(MessageEntity(
            clientMessageId = "own-srv-1", serverId = "srv-1", chatId = "chat-1", senderId = "other",
            text = "Hello", sequence = 1, createdAt = "2026-01-01T00:00:00Z", status = "delivered",
        ))
        decryptor.customResponses["react-1"] = MessageContentCodec.authenticate(
            MessageContentCodec.encodeReaction("👍"), "chat-1", "react-1", "reaction", "srv-1", "own-srv-1",
        )
        decryptor.failingMessageIds += "react-1"
        pipeline.ingest(listOf(testMessageDto(id = "react-1", senderId = "other", kind = "reaction", targetMessageId = "srv-1", sequence = 2)))
        assertEquals(1, h.dao.pendingDecrypts().size)
        assertEquals(0, h.reactionDao.all().size)

        decryptor.failingMessageIds.clear()
        pipeline.retryPendingDecrypts()

        assertEquals(0, h.dao.pendingDecrypts().size)
        assertEquals(1, h.reactionDao.all().size)
        assertEquals("👍", h.reactionDao.all().single().emoji)
    }

    @Test
    fun `attachment-bearing message retry follows the normal ingestion path`() = runBlocking {
        val decryptor = FakeMessageDecryptor()
        val attachment = AttachmentMetadataDto(
            attachmentId = "att-1", mediaType = "image", objectKey = "attachments/att-1",
            size = 10, encryptedSize = 20, mimeType = "image/jpeg",
            encryption = EncryptionInfoDto(version = 1, key = "k", nonce = "n", sha256 = "s"),
        )
        decryptor.customResponses["m1"] = MessageContentCodec.encodeAttachment(attachment)
        decryptor.failingMessageIds += "m1"
        val (pipeline, h) = harness(decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 1)))
        assertEquals(1, h.dao.pendingDecrypts().size)
        assertEquals(0, h.attachmentDao.all().size)

        decryptor.failingMessageIds.clear()
        pipeline.retryPendingDecrypts()

        assertEquals(0, h.dao.pendingDecrypts().size)
        assertEquals(1, h.attachmentDao.all().size)
        assertEquals("att-1", h.attachmentDao.all().single().attachmentId)
        assertEquals(1, h.dao.all().count { it.clientMessageId == "m1" })
    }

    @Test
    fun `stale account retry cannot write into a new account`() = runBlocking {
        val auth = FakeAuthRepository(userId = "alice")
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(auth = auth, decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 1)))
        assertEquals(1, h.dao.pendingDecrypts().size)

        // The pending envelope would now decrypt, but a logout lands exactly
        // before the gate check of the in-flight retry: the gate rejects the
        // stale write (captured Alice epoch != new epoch).
        decryptor.failingMessageIds.clear()
        auth.switchEpochOnWrite = true
        pipeline.retryPendingDecrypts()

        assertEquals(MessageStatus.DECRYPT_FAILED.name.lowercase(), h.dao.findByClientMessageId("m1")?.status)
        assertEquals(1, h.dao.pendingDecrypts().size)
    }

    // --- P1-4/B.1: retry exhaustion + acceptNewKey trigger ---------------

    @Test
    fun `identity-changed pending survives many retry opportunities and recovers after accept`() = runBlocking {
        val decryptor = FakeMessageDecryptor()
        val (pipeline, h) = harness(decryptor = decryptor)
        decryptor.identityChangedMessageIds += "m1"
        pipeline.ingest(listOf(testMessageDto(id = "m1", senderId = "other", sequence = 1)))
        assertEquals(1, h.dao.pendingDecrypts().size)
        assertEquals(MessageStatus.IDENTITY_CHANGED.name.lowercase(), h.dao.findByClientMessageId("m1")?.status)

        // Many retry opportunities pass while the user has not accepted yet.
        repeat(15) { pipeline.retryPendingDecrypts("other") }
        assertEquals(1, h.dao.pendingDecrypts().size)
        assertEquals("plaintext-for:m1", h.dao.pendingDecrypts().single().ciphertext)

        // User accepts the new key -> the pending envelope becomes decryptable.
        decryptor.identityChangedMessageIds.clear()
        pipeline.retryPendingDecrypts("other")

        assertEquals(0, h.dao.pendingDecrypts().size)
        assertEquals(MessageStatus.DELIVERED.name.lowercase(), h.dao.findByClientMessageId("m1")?.status)
        assertEquals("decoded:m1", h.dao.findByClientMessageId("m1")?.text)
    }

    @Test
    fun `accept-triggered scoped retry only retries that sender`() = runBlocking {
        val decryptor = FakeMessageDecryptor()
        val (pipeline, h) = harness(decryptor = decryptor)
        decryptor.failingMessageIds += "a1"
        pipeline.ingest(listOf(testMessageDto(id = "a1", senderId = "alice", sequence = 1)))
        decryptor.failingMessageIds += "b1"
        pipeline.ingest(listOf(testMessageDto(id = "b1", senderId = "bob", sequence = 2)))

        // Only bob's failure clears; alice stays failing. A scoped retry for
        // "bob" must process ONLY bob's record (which now decrypts), leaving
        // alice's pending record untouched.
        decryptor.failingMessageIds -= "b1"
        decryptor.decryptOrder.clear()
        pipeline.retryPendingDecrypts("bob")

        assertEquals(listOf("b1"), decryptor.decryptOrder)
        assertEquals(1, h.dao.pendingDecrypts().size)
        assertEquals("a1", h.dao.pendingDecrypts().single().clientMessageId)
    }

    @Test
    fun `pending row is removed only after successful durable ingestion`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 1)))

        pipeline.retryPendingDecrypts() // still failing
        assertEquals(1, h.dao.pendingDecrypts().size)

        decryptor.failingMessageIds.clear()
        pipeline.retryPendingDecrypts()
        assertEquals(0, h.dao.pendingDecrypts().size)
    }

    @Test
    fun `identity-changed retry updates the same message without duplicate`() = runBlocking {
        val decryptor = FakeMessageDecryptor()
        val (pipeline, h) = harness(decryptor = decryptor)
        decryptor.identityChangedMessageIds += "m1"
        pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 1)))

        decryptor.identityChangedMessageIds.clear()
        pipeline.retryPendingDecrypts()

        assertEquals(1, h.dao.all().count { it.clientMessageId == "m1" })
        assertEquals(MessageStatus.DELIVERED.name.lowercase(), h.dao.findByClientMessageId("m1")?.status)
        assertEquals("decoded:m1", h.dao.findByClientMessageId("m1")?.text)
    }

    @Test
    fun `a single retry pass does not loop over the same record`() = runBlocking {
        val decryptor = FakeMessageDecryptor().apply { failingMessageIds += "m1" }
        val (pipeline, h) = harness(decryptor = decryptor)
        pipeline.ingest(listOf(testMessageDto(id = "m1", sequence = 1)))

        decryptor.decryptOrder.clear()
        pipeline.retryPendingDecrypts()

        // One invocation = exactly one attempt per pending record (bounded pass).
        assertEquals(1, decryptor.decryptOrder.count { it == "m1" })
    }

    @Test
    fun `process restart then accept recovers identity-changed pending`() = runBlocking {
        val dao = InMemoryMessageDao()
        val decryptor = FakeMessageDecryptor()
        val auth = FakeAuthRepository()
        fun newPipeline() = MessageIngestionPipeline(
            dao = dao, chatDao = InMemoryChatDao(), attachmentDao = InMemoryAttachmentDao(),
            reactionDao = InMemoryReactionDao(), decryptor = decryptor, authRepository = auth,
            messagesApi = FakeDeliveredReporter(), tokenStore = InMemorySyncCursorStore(),
        )
        decryptor.identityChangedMessageIds += "m1"
        newPipeline().ingest(listOf(testMessageDto(id = "m1", sequence = 1)))
        assertEquals(1, dao.pendingDecrypts().size)

        // "Process death" + later accept: a fresh pipeline over the same
        // durable store, then the user accepts the changed key.
        decryptor.identityChangedMessageIds.clear()
        newPipeline().retryPendingDecrypts("other")

        assertEquals(0, dao.pendingDecrypts().size)
        assertEquals(MessageStatus.DELIVERED.name.lowercase(), dao.findByClientMessageId("m1")?.status)
    }
}

/** Decryptor that blocks inside [decrypt] until released - simulates a slow/
 *  delayed old-account operation still holding the shared session gate. */
private class GatedDecryptor : MessageDecryptor {
    val release = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Volatile var entered = false

    override suspend fun resolveSignalDeviceId(userId: String, deviceId: String, accessToken: String): Int = 1

    override suspend fun decrypt(userId: String, signalDeviceId: Int, messageType: Int, ciphertextBase64: String): ByteArray {
        entered = true
        release.await()
        return "decoded".toByteArray(Charsets.UTF_8)
    }
}
