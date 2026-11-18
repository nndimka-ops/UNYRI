package com.messenger.app.ui.chats

import com.messenger.app.data.attachment.AttachmentMetadataDto
import com.messenger.app.data.attachment.EncryptionInfoDto
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.message.queueCapturedForwardMessage
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.data.sync.InMemoryAttachmentDao
import com.messenger.app.data.sync.InMemoryMessageDao
import com.messenger.app.domain.model.*
import com.messenger.app.domain.repository.MessageRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForwardPickerPayloadAdapterTest {
    private data class Queued(val target: String, val id: String, val text: String?, val caption: String?, val forwarded: Boolean)
    private class Fixture(queuedMedia: Boolean = false) {
        val text = Message("text", "text", "source", "sender", "Original text", 1, "2026-11-13T00:00:00Z", MessageStatus.SENT)
        val attachment = Attachment("asset", "photo", AttachmentMediaType.IMAGE, "image/jpeg", "original.jpg", 10, 20, 20,
            null, null, "/cached", AttachmentDownloadState.DOWNLOADED, AttachmentUploadState.SENT, 100)
        val photo = text.copy(clientMessageId = "photo", serverId = if (queuedMedia) null else "photo", text = "Photo", caption = "Original caption",
            status = if (queuedMedia) MessageStatus.FAILED else MessageStatus.SENT,
            queuedAttachment = if (queuedMedia) attachment.copy(attachmentId = "photo", uploadState = AttachmentUploadState.FAILED) else null)
        var rows = listOf(text, photo)
        var media = if (queuedMedia) emptyList() else listOf(attachment)
        var current = true
        var failTarget: String? = null
        val queued = mutableListOf<Queued>()
        val prepared = mutableListOf<Triple<Attachment, String, String>>()
        var upload: suspend (String) -> Unit = {}
        private val unusedRepository = Proxy.newProxyInstance(MessageRepository::class.java.classLoader, arrayOf(MessageRepository::class.java)) { _, method, _ ->
            error("Unexpected repository call: ${method.name}")
        } as MessageRepository
        private val dao = InMemoryMessageDao()
        private val attachmentDao = InMemoryAttachmentDao()
        private val auth = FakeAuthRepository()
        val repository = object : MessageRepository by unusedRepository {
            override suspend fun forwardMessage(source: Message, destinationChatId: String, clientMessageId: String, expectedEpoch: Int): Result<Unit> = runCatching {
                for (live in rows) dao.upsert(MessageEntity(live.clientMessageId, live.serverId, live.chatId,
                    live.senderId, live.text, live.sequence, live.createdAt, "sent", caption = live.caption,
                    isDeletedForEveryone = live.isDeletedForEveryone))
                queueCapturedForwardMessage(source, destinationChatId, clientMessageId, expectedEpoch, dao, attachmentDao, auth)
                val accepted = requireNotNull(dao.findByClientMessageId(clientMessageId))
                queued += Queued(accepted.chatId, accepted.clientMessageId, accepted.text, accepted.caption, accepted.forwarded)
            }
            override suspend fun sendAttachmentMessage(chatId: String, clientMessageId: String, attachment: AttachmentMetadataDto, forwarded: Boolean, caption: String?, albumId: String?): Result<Unit> = runCatching {
                if (chatId == failTarget) throw java.io.IOException("Queue rejected")
                queued += Queued(chatId, clientMessageId, null, caption, forwarded)
            }
        }
        var sources = ForwardPickerSources(listOf("text", "photo"), rows, media)
            private set
        private fun adapterFor(captured: ForwardPickerSources) = ForwardPickerPayloadAdapter(captured, repository, auth.currentSessionEpoch(), { rows }, { media },
            { if (!current) throw CancellationException("Account changed") },
            prepareAttachment = { source, target, id ->
                prepared += Triple(source, target, id)
                upload(target)
                AttachmentMetadataDto("new-$id", "image", "object-$id", 10, 26, "image/jpeg", EncryptionInfoDto(sha256 = "hash"))
            })
        var adapter = adapterFor(sources)
            private set
        val picker = ForwardPickerSession().apply {
            open(listOf("text", "photo"), sources.preview, emptyMap(), sourcesResolved = sources.resolved)
            toggle("A"); toggle("B")
        }
        fun reselect(): Boolean {
            val captured = ForwardPickerSources(listOf("text", "photo"), rows, media)
            val replace = picker.open(listOf("text", "photo"), captured.preview, emptyMap(), sourcesResolved = captured.resolved)
            if (replace) { sources = captured; adapter = adapterFor(captured) }
            return replace
        }
        suspend fun send() = picker.send({ current }, adapter::validate, { _, _ -> true }, adapter::forward)
    }

    @Test fun `edit during A upload cannot change text caption or media metadata queued for either recipient`() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.upload = { target -> if (target == "A") gate.await() }
        val send = launch { f.send() }
        runCurrent()
        assertEquals(listOf("Original text"), f.queued.mapNotNull { it.text })
        f.rows = listOf(f.text.copy(text = "Edited while uploading"), f.photo.copy(caption = "Edited caption"))
        f.media = listOf(f.attachment.copy(filename = "changed.jpg", localPath = "/downloaded"))
        gate.complete(Unit); send.join()
        assertEquals(listOf("Original text", "Original text"), f.queued.mapNotNull { it.text })
        assertEquals(listOf("Original caption", "Original caption"), f.queued.mapNotNull { it.caption })
        assertEquals(listOf("A", "A", "B", "B"), f.queued.map { it.target })
        assertTrue(f.queued.all { it.forwarded })
        assertTrue(f.prepared.all { it.first.filename == "original.jpg" })
        assertEquals(listOf("Original text", "Original caption"), f.sources.preview.snippets)
    }

    @Test fun `retry after reopening reuses the captured bytes prepared upload and destination id`() = runTest {
        val f = Fixture(); f.failTarget = "B"
        f.send()
        assertEquals(ForwardPickerFailure.SEND, f.picker.state.value.failure)
        f.picker.dismiss()
        assertFalse(f.picker.open(listOf("text", "photo"), ForwardPickerPreview(2, snippets = listOf("Changed")), emptyMap()))
        f.rows = listOf(f.text.copy(text = "new text"), f.photo.copy(caption = "new caption"))
        f.failTarget = null
        f.send()
        assertEquals(4, f.queued.size)
        assertEquals(2, f.prepared.size)
        assertEquals(f.prepared.single { it.second == "B" }.third, f.queued.last().id)
        assertEquals("Original caption", f.queued.last().caption)
        assertEquals(listOf("Original text", "Original text"), f.queued.mapNotNull { it.text })
    }

    @Test fun `deletion during upload blocks queue admission despite a retained payload`() = runTest {
        val f = Fixture()
        f.upload = { f.rows = listOf(f.text, f.photo.copy(isDeletedForEveryone = true)) }
        assertFalse(f.adapter.forward("photo", "A", null))
        assertTrue(f.queued.isEmpty())
    }

    @Test fun `account change during upload cancels before any queue write`() = runTest {
        val f = Fixture(); f.upload = { f.current = false }
        try {
            f.adapter.forward("photo", "A", null)
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(f.queued.isEmpty())
    }

    @Test fun `failed source upload then successful retry and reselect forwards the mixed batch to every destination`() = runTest {
        val f = Fixture(queuedMedia = true)
        assertFalse(f.sources.resolved)
        assertFalse(f.picker.state.value.visible)
        assertEquals(ForwardPickerFailure.SOURCE, f.picker.state.value.failure)
        f.send()
        assertTrue(f.queued.isEmpty())
        assertTrue(f.prepared.isEmpty())
        // Retrying the original source creates a real asset ID, different from the queued preview's message ID.
        f.rows = listOf(f.text, f.photo.copy(serverId = "sent-photo", status = MessageStatus.SENT, queuedAttachment = null))
        f.media = listOf(f.attachment.copy(attachmentId = "retry-asset"))
        assertTrue(f.reselect())
        assertTrue(f.picker.state.value.visible)
        assertEquals(listOf("A", "B"), f.picker.state.value.selectedIds)
        assertTrue(f.sources.resolved)
        f.send()
        assertEquals(listOf("A", "A", "B", "B"), f.queued.map { it.target })
        assertEquals(listOf("Original text", "Original text"), f.queued.mapNotNull { it.text })
        assertEquals(listOf("Original caption", "Original caption"), f.queued.mapNotNull { it.caption })
        assertTrue(f.prepared.all { it.first.attachmentId == "retry-asset" })
        f.send()
        assertEquals(4, f.queued.size)
    }
}
