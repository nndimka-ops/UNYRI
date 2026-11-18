package com.messenger.app.data.message

import com.messenger.app.data.attachment.*
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.local.message.QueuedMediaRow
import com.messenger.app.data.sync.InMemoryMessageDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerAlbumSendRegressionTest {
    private fun member(id: String, album: String?, caption: String? = null) = MessageEntity(
        id, null, "chat", "me", caption ?: "[Photo]", null,
        "2026-11-13T03:01:39Z", "pending", caption = caption, albumId = album)

    @Test fun `undecodable selected image aborts admission before caption and tray transfer`() = runTest {
        val journal = ComposerDrafts(photos = listOf("good", "undecodable", "removed")).edit(remove = setOf("removed"))
        val caption = ComposerCaption("QA78-reverse-album")
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, it -> it },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        coordinator.restoreCaption("chat", caption.text, caption.revision)
        var committed = false
        coordinator.submitSend("chat", caption = caption) {
            val batch = prepareComposerItems(journal.photos, { fail("The provider is readable") }) {
                // The real composer validates using ImageProcessor's bounds decoder here.
                if (it == "undecodable") throw MediaPreparationException(IllegalArgumentException("Unsupported image dimensions"))
                com.messenger.app.domain.repository.QueuedMediaDraft(it, it, "image")
            }
            batch.albumDrafts(caption.text, "album")
            committed = true
            true
        }
        runCurrent()
        assertFalse(committed)
        assertEquals(listOf("good", "undecodable"), journal.photos)
        assertEquals(caption, coordinator.state.value.getValue("chat").caption)
        assertFalse(coordinator.state.value.getValue("chat").sending)
        assertTrue(coordinator.state.value.getValue("chat").failed)
    }

    @Test fun `Add three remove one sends healthy members and retries only failed member with its original caption`() = runTest {
        for (failedIndex in 0..1) {
            val dao = InMemoryMessageDao()
            var journal = ComposerDrafts()
            val deleted = mutableSetOf<String>()
            val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source -> "draft:$source" },
                { _, _, uri, _ -> journal = journal.edit(addPhotos = listOf(uri)) }, { _, _, _ -> },
                { _, uri -> if (uri !in journal.all) deleted += uri },
                removeDraftReferences = { _, _, uris -> journal = journal.edit(remove = uris) })
            coordinator.start("chat", listOf("A", "B", "C")); runCurrent()
            val staleRoom = journal
            coordinator.remove("chat", "draft:C"); runCurrent()
            val state = coordinator.state.value.getValue("chat")
            val tray = ComposerTraySnapshot.create(staleRoom, state.items, state.forgotten)
            assertEquals(listOf("draft:A", "draft:B"), tray.sendablePhotos)
            assertTrue(tray.ready)
            assertFalse("draft:A" in deleted || "draft:B" in deleted)
            val caption = "QA78-reverse-album"
            val identity = ComposerBatchIdentity.key(tray.ordered, caption, "image")
            assertEquals(identity, ComposerBatchIdentity.key(tray.ordered, caption, "image"))
            assertNotEquals(identity, ComposerBatchIdentity.key(staleRoom.photos, caption, "image"))
            val album = "frozen-album-$failedIndex"
            val batch = prepareComposerItems(tray.ordered, { fail("No source was removed during preparation") }) {
                com.messenger.app.domain.repository.QueuedMediaDraft(it, it, "image")
            }
            dao.saveMediaJob("chat", "mediaDrafts", ComposerDrafts.encode(journal.photos))
            dao.saveMediaJob("chat", "draft", caption)
            dao.saveMediaJob("chat", "draftRevision", "caption-revision")
            dao.enqueueComposerBatch(batch.albumDrafts(caption, album).map { draft ->
                QueuedMediaRow(member(draft.id, draft.albumId, draft.caption), "{}", "{}")
            }, batch.selectionUris, caption, identity, "caption-revision")
            assertEquals("[]", dao.composerValue("chat", "mediaDrafts"))
            assertNull(dao.composerValue("chat", "draft"))
            assertEquals("caption-revision", dao.composerValue("chat", "draftConsumedRevision"))
            coordinator.consumeCommitted("chat", tray.ordered)
            assertTrue(ComposerTraySnapshot.create(staleRoom, coordinator.state.value.getValue("chat").items,
                coordinator.state.value.getValue("chat").forgotten).ordered.isEmpty())

            val ready = mutableSetOf<String>()
            val preparationCounts = mutableMapOf<String, Int>()
            val delivered = mutableListOf<MessageEntity>()
            var sourceRepaired = false
            val failedId = tray.ordered[failedIndex]
            fun processor() = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
                mediaReadiness = {
                    if (it.albumId == null) OutboxMediaReadiness.NONE
                    else if (it.clientMessageId in ready) OutboxMediaReadiness.READY else OutboxMediaReadiness.WAITING
                }, prepareMedia = {
                    val id = it.clientMessageId
                    preparationCounts[id] = preparationCounts.getOrDefault(id, 0) + 1
                    if (id == failedId && !sourceRepaired) throw MediaPreparationException(IllegalArgumentException("Unsupported image dimensions"))
                    ready += id // persisted encrypted upload; retained across processor restart
                }, attemptSend = {
                    delivered += it
                    dao.upsert(it.copy(status = "sent", serverId = "server:${it.clientMessageId}", sequence = delivered.size.toLong()))
                })
            val first = processor()
            first.flush(); runCurrent()
            assertEquals("failed", dao.findByClientMessageId(failedId)!!.status)
            val healthyId = tray.ordered[1 - failedIndex]
            assertEquals(listOf(healthyId), delivered.map { it.clientMessageId }, "A failed sibling cannot strand the healthy upload")
            assertEquals(caption.takeIf { failedIndex == 1 }, delivered.single().caption)
            // Neither a failed member nor a restart blocks later text.
            dao.upsert(member("QA78target2", null).copy(createdAt = "2026-11-13T03:06:40Z"))
            val restarted = processor()
            restarted.flush(); runCurrent()
            assertEquals(listOf(healthyId, "QA78target2"), delivered.map { it.clientMessageId })

            sourceRepaired = true
            dao.resetToPending(failedId)
            restarted.flush(); runCurrent()
            val photos = delivered.filter { it.albumId != null }
            assertEquals(setOf(healthyId, failedId), photos.map { it.clientMessageId }.toSet())
            assertEquals(caption, photos.single { it.clientMessageId == "draft:A" }.caption)
            assertNull(photos.single { it.clientMessageId == "draft:B" }.caption)
            assertEquals(setOf(album), photos.map { it.albumId }.toSet())
            assertEquals(2, photos.map { it.clientMessageId }.distinct().size)
            assertEquals(2, preparationCounts[failedId])
            assertEquals(1, preparationCounts[tray.ordered[1 - failedIndex]], "Retry must reuse the successful encrypted upload")
            restarted.flush(); runCurrent()
            assertEquals(3, delivered.size)
        }
    }

    @Test fun `healthy caption member sends while sibling upload is suspended`() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(member("A", "album", "caption")); dao.upsert(member("B", "album"))
        val ready = mutableSetOf<String>()
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val processor = MessageOutboxProcessor(dao, backgroundScope, { 1000L }, { "me" },
            mediaReadiness = { if (it.clientMessageId in ready) OutboxMediaReadiness.READY else OutboxMediaReadiness.WAITING },
            prepareMedia = { if (it.clientMessageId == "B") release.await(); ready += it.clientMessageId },
            attemptSend = { sent += it.clientMessageId; dao.updateStatus(it.clientMessageId, "sent") })
        processor.flush(); runCurrent()
        assertEquals(setOf("A"), ready)
        assertEquals(listOf("A"), sent)
        release.complete(Unit); runCurrent()
        assertEquals(listOf("A", "B"), sent)
    }

    @Test fun `transient second upload failure persists backoff across restart without reencrypting first member`() = runTest {
        val dao = InMemoryMessageDao()
        dao.upsert(member("A", "album", "caption")); dao.upsert(member("B", "album"))
        var now = 1000L
        var offline = true
        val uploaded = mutableSetOf<String>()
        val preparations = mutableListOf<String>()
        val sent = mutableListOf<String>()
        fun processor() = MessageOutboxProcessor(dao, backgroundScope, { now }, { "me" },
            mediaReadiness = { if (it.clientMessageId in uploaded) OutboxMediaReadiness.READY else OutboxMediaReadiness.WAITING },
            prepareMedia = {
                preparations += it.clientMessageId
                if (it.clientMessageId == "B" && offline) throw java.io.IOException("offline")
                uploaded += it.clientMessageId
            }, attemptSend = { sent += it.clientMessageId; dao.updateStatus(it.clientMessageId, "sent") })
        processor().flush(); runCurrent()
        val waiting = dao.findByClientMessageId("B")!!
        assertEquals("pending", waiting.status)
        assertTrue(waiting.nextRetryAtEpochMs > now)
        assertEquals(listOf("A"), sent)
        val restarted = processor()
        restarted.flush(); runCurrent()
        assertEquals(listOf("A"), sent)
        offline = false
        now = waiting.nextRetryAtEpochMs
        restarted.flush(); runCurrent()
        assertEquals(listOf("A", "B"), sent)
        assertEquals(listOf("A", "B", "B"), preparations)
        assertEquals("caption", dao.findByClientMessageId("A")!!.caption)
        assertNull(dao.findByClientMessageId("B")!!.caption)
    }

    @Test fun `retry preserves failed siblings pending windows and completed uploads`() = runTest {
        val dao = InMemoryMessageDao()
        val anchor = member("A", "album", "caption").copy(status = "failed")
        val others = listOf(member("B", "album").copy(status = "failed"),
            member("sent", "album").copy(status = "read", serverId = "server", sequence = 1),
            member("hidden", "album").copy(status = "failed", hiddenLocally = true),
            member("deleted", "album").copy(status = "failed", isDeletedForEveryone = true),
            member("peer", "album").copy(status = "failed", senderId = "peer"),
            member("other-chat", "album").copy(status = "failed", chatId = "other"))
        val waiting = member("backoff", "album").copy(retryCount = 2, nextRetryAtEpochMs = 10000)
        (listOf(anchor, waiting) + others).forEach { dao.upsert(it) }
        dao.resetToPending(anchor.clientMessageId)
        assertEquals("pending", dao.findByClientMessageId("A")!!.status)
        assertEquals(waiting, dao.findByClientMessageId("backoff"))
        others.forEach { assertEquals(it, dao.findByClientMessageId(it.clientMessageId)) }
        assertEquals("caption", dao.findByClientMessageId("A")!!.caption)
    }
}
