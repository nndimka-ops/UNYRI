package com.messenger.app.data.sync

import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.api.MessageDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Этап I performance verification (DoD closeout) - ingests a large,
 * realistic catch-up batch (1000+ content + 100 edit + 100 reaction + 50
 * delete = 1250+ events, matching the same shape as a real `/sync`
 * catch-up after a long offline period) through the REAL
 * [MessageIngestionPipeline] in one call, and checks:
 *  - no ANR-class slowness (generous threshold, this runs on a plain JVM
 *    thread with in-memory fakes - the real regression signal is an
 *    accidental O(n^2)/O(n log n) blowup in the pipeline's OWN control
 *    flow, not real disk I/O, which is covered separately by on-device
 *    SQLite EXPLAIN QUERY PLAN measurements - see project memory notes);
 *  - no duplicate effects on a second identical ingest() call (idempotency
 *    holds at scale, not just for the small examples in
 *    MessageLifecycleIngestionTest.kt);
 *  - deterministic final projection (last edit/reaction/delete by
 *    sequence wins, regardless of batch order).
 */
class MessageIngestionPerformanceTest {

    @Test
    fun `a 1250-event catch-up batch ingests correctly, quickly, and idempotently`() = runBlocking {
        val dao = InMemoryMessageDao()
        val reactionDao = InMemoryReactionDao()
        val attachmentDao = InMemoryAttachmentDao()
        val chatDao = InMemoryChatDao()
        val delivered = FakeDeliveredReporter()
        val cursorStore = InMemorySyncCursorStore()
        val decryptor = FakeMessageDecryptor()
        val auth = FakeAuthRepository()

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

        val contentCount = 1000
        val editCount = 100
        val reactionCount = 100
        val deleteCount = 50

        val dtos = mutableListOf<MessageDto>()

        // 1000 content messages, sequence 1..1000.
        for (i in 1..contentCount) {
            val id = "c$i"
            decryptor.customResponses[id] = MessageContentCodec.encodeText("message $i")
            dtos += testMessageDto(id = id, senderId = "other", sequence = i.toLong())
        }
        // 100 edits targeting every 10th content message, sequence 1001..1100.
        for (i in 1..editCount) {
            val id = "e$i"
            val targetContentIndex = ((i - 1) * 10 + 1)
            decryptor.customResponses[id] = MessageContentCodec.encodeEdit("edited $i")
            dtos += testMessageDto(id = id, senderId = "other", sequence = (contentCount + i).toLong(), kind = "edit", targetMessageId = "c$targetContentIndex")
        }
        // 100 reactions targeting every 10th content message (different
        // ones than the edits), sequence 1101..1200.
        for (i in 1..reactionCount) {
            val id = "r$i"
            val targetContentIndex = ((i - 1) * 10 + 5).coerceAtMost(contentCount)
            decryptor.customResponses[id] = MessageContentCodec.encodeReaction("heart")
            dtos += testMessageDto(
                id = id, senderId = "bob", sequence = (contentCount + editCount + i).toLong(),
                kind = "reaction", targetMessageId = "c$targetContentIndex",
            )
        }
        // 50 deletes targeting every 20th content message, sequence 1201..1250.
        for (i in 1..deleteCount) {
            val id = "d$i"
            val targetContentIndex = ((i - 1) * 20 + 3).coerceAtMost(contentCount)
            decryptor.customResponses[id] = MessageContentCodec.encodeDelete()
            dtos += testMessageDto(
                id = id, senderId = "other", sequence = (contentCount + editCount + reactionCount + i).toLong(),
                kind = "delete", targetMessageId = "c$targetContentIndex",
            )
        }

        for (dto in dtos) {
            decryptor.customResponses[dto.id] = MessageContentCodec.authenticate(
                decryptor.customResponses.getValue(dto.id), dto.chatId, dto.clientMessageId,
                dto.kind, dto.targetMessageId, if (dto.kind == "content") null else dto.targetMessageId)
        }
        val totalEvents = dtos.size
        assertEquals(1250, totalEvents)

        // Shuffle to simulate realistic out-of-order arrival within a page
        // (the pipeline itself sorts by sequence before applying).
        val shuffled = dtos.shuffled(kotlin.random.Random(42))

        val elapsedMs = kotlin.system.measureTimeMillis {
            val result = pipeline.ingest(shuffled)
            assertTrue(result.advancedFully)
            assertEquals(totalEvents.toLong(), result.highestSequence)
        }

        // Generous threshold - this is an in-memory-fake pipeline-orchestration
        // check, not a real-disk-I/O benchmark (see class doc). A genuine
        // O(n^2) bug in this size range would show up as seconds, not ms.
        assertTrue(elapsedMs < 5_000, "ingesting $totalEvents events took ${elapsedMs}ms - looks like an algorithmic regression")

        // Correctness: every content message landed exactly once.
        assertEquals(contentCount + editCount + reactionCount + deleteCount, dao.all().size)

        // Deterministic edit projection: content message c1 was targeted by edit e1 only.
        assertEquals("edited 1", dao.findByClientMessageId("c1")?.text)
        assertTrue(dao.findByClientMessageId("c1")?.isEdited == true)

        // Deterministic delete projection: content message c3 was targeted by delete d1.
        assertTrue(dao.findByClientMessageId("c3")?.isDeletedForEveryone == true)

        // Deterministic reaction projection: content message c5 got a reaction from bob.
        assertEquals(1, reactionDao.all().count { it.targetMessageId == "c5" && it.emoji == "heart" })

        // No duplicate decryption/processing on a second identical ingest
        // (simulates a WS+/sync race redelivering the same page).
        val secondResult = pipeline.ingest(shuffled)
        assertTrue(secondResult.advancedFully)
        assertEquals(contentCount + editCount + reactionCount + deleteCount, dao.all().size, "a re-ingested identical batch must never create duplicate rows")
        for (i in 1..contentCount) {
            assertEquals(1, decryptor.decryptCallCounts["c$i"], "content message c$i must never be decrypted twice")
        }
        for (i in 1..editCount) {
            assertEquals(1, decryptor.decryptCallCounts["e$i"], "edit event e$i must never be re-applied")
        }
    }
}
