package com.messenger.app.ui.chats

import com.messenger.app.domain.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForwardPickerStateTest {
    private fun picker(ids: List<String> = listOf("one", "two"), albums: Map<String, String> = emptyMap()) =
        ForwardPickerSession().apply {
            open(ids, ForwardPickerPreview(ids.size), albums)
            toggle("a"); toggle("b"); comment("  hello  ")
        }

    @Test fun `selection is ordered bounded and removable without losing search or comment`() {
        val p = picker()
        p.query("person")
        (3..11).forEach { p.toggle(it.toString()) }
        assertEquals(10, p.state.value.selectedIds.size)
        assertTrue(p.state.value.selectionLimitReached)
        p.toggle("a"); p.toggle("11")
        assertEquals("11", p.state.value.selectedIds.last())
        assertEquals("person", p.state.value.query)
        assertEquals("  hello  ", p.state.value.comment)
    }

    @Test fun `dismiss and reopen preserves draft but a different source starts a fresh session`() {
        val p = picker()
        p.query("saved"); p.dismiss()
        p.open(listOf("one", "two"), ForwardPickerPreview(2), emptyMap())
        assertTrue(p.state.value.visible)
        assertEquals(listOf("a", "b"), p.state.value.selectedIds)
        assertEquals("saved", p.state.value.query)
        p.open(listOf("other"), ForwardPickerPreview(1), emptyMap())
        assertEquals(emptyList<String>(), p.state.value.selectedIds)
        assertEquals("", p.state.value.comment)
    }

    @Test fun `each destination receives comment before ordered messages exactly once`() = runTest {
        val p = picker(listOf("one", "one", "two"))
        val records = mutableListOf<String>()
        suspend fun send() = p.send({ true }, { true }, { dest, text -> records += "$dest:$text"; true },
            { id, dest, _ -> records += "$dest:$id"; true })
        send(); send()
        assertEquals(listOf("a:hello", "a:one", "a:two", "b:hello", "b:one", "b:two"), records)
        assertFalse(p.state.value.visible)
        assertEquals(listOf("one", "two"), p.state.value.completedSourceIds)
        assertEquals("", p.state.value.comment)
    }

    @Test fun `partial failure retries only the missing message and retains comment and targets`() = runTest {
        val p = picker()
        val records = mutableListOf<String>()
        var fail = true
        suspend fun send() = p.send({ true }, { true }, { dest, _ -> records += "$dest:comment"; true }, { id, dest, _ ->
            if (fail && dest == "a" && id == "two") false else { records += "$dest:$id"; true }
        })
        send()
        assertEquals(ForwardPickerFailure.SEND, p.state.value.failure)
        assertEquals(1, p.state.value.completedTargets)
        assertTrue(p.state.value.visible)
        assertEquals("  hello  ", p.state.value.comment)
        p.comment("changed after partial acceptance")
        assertEquals("  hello  ", p.state.value.comment)
        p.dismiss(); p.open(listOf("one", "two"), ForwardPickerPreview(2), emptyMap())
        fail = false; send()
        assertEquals(listOf("a:comment", "a:one", "b:comment", "b:one", "b:two", "a:two"), records)
    }

    @Test fun `a failed comment blocks that destination content and remains editable before acceptance`() = runTest {
        val p = picker(); p.toggle("b")
        var forwards = 0
        p.send({ true }, { true }, { _, _ -> false }, { _, _, _ -> forwards++; true })
        assertEquals(0, forwards)
        assertFalse(p.state.value.commentLocked)
        p.comment("replacement")
        assertEquals("replacement", p.state.value.comment)
    }

    @Test fun `invalid selection fails before any side effect and can retry`() = runTest {
        val p = picker()
        var writes = 0
        p.send({ true }, { false }, { _, _ -> writes++; true }, { _, _, _ -> writes++; true })
        assertEquals(0, writes)
        assertEquals(ForwardPickerFailure.SOURCE, p.state.value.failure)
        assertFalse(p.state.value.sending)
        assertEquals(listOf("a", "b"), p.state.value.selectedIds)
    }

    @Test fun `double tap coalesces and closing during send allows accepted work to finish`() = runTest {
        val p = picker(listOf("one")); p.toggle("b"); p.comment("")
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        val first = launch { p.send({ true }, { true }, { _, _ -> true }, { _, _, _ -> writes++; gate.await(); true }) }
        runCurrent()
        p.send({ true }, { true }, { _, _ -> true }, { _, _, _ -> writes++; true })
        p.toggle("b"); p.comment("changed"); p.dismiss()
        assertEquals(listOf("a"), p.state.value.selectedIds)
        gate.complete(Unit); first.join()
        assertEquals(1, writes)
        assertEquals(listOf("one"), p.state.value.completedSourceIds)
    }

    @Test fun `cancellation retains accepted prefix for retry`() = runTest {
        val p = picker(); p.toggle("b"); p.comment("")
        val gate = CompletableDeferred<Unit>(); val records = mutableListOf<String>()
        val job = launch { p.send({ true }, { true }, { _, _ -> true }, { id, _, _ ->
            if (id == "two") gate.await()
            records += id; true
        }) }
        runCurrent(); job.cancel(); job.join()
        assertFalse(p.state.value.sending)
        p.send({ true }, { true }, { _, _ -> true }, { id, _, _ -> records += id; true })
        assertEquals(listOf("one", "two"), records)
    }

    @Test fun `owner change aborts before another destination or message`() = runTest {
        val p = picker(); p.comment("")
        var current = true; var writes = 0
        try {
            p.send({ current }, { true }, { _, _ -> true }, { _, _, _ -> writes++; current = false; true })
            fail("Owner change must cancel")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(1, writes)
        assertFalse(p.state.value.sending)
        assertTrue(p.state.value.completedSourceIds.isEmpty())
    }

    @Test fun `exception in a destination allows the other destination then retry resumes safely`() = runTest {
        val p = picker(listOf("one")); p.comment("")
        val records = mutableListOf<String>(); var fail = true
        suspend fun send() = p.send({ true }, { true }, { _, _ -> true }, { _, dest, _ ->
            if (dest == "a" && fail) error("offline")
            records += dest; true
        })
        send(); fail = false; send()
        assertEquals(listOf("b", "a"), records)
    }

    @Test fun `album identifiers remain stable on retry and differ across destinations`() = runTest {
        val p = picker(albums = mapOf("one" to "album", "two" to "album")); p.comment("")
        val seen = mutableListOf<Triple<String, String, String?>>()
        var fail = true
        suspend fun send() = p.send({ true }, { true }, { _, _ -> true }, { id, dest, album ->
            seen += Triple(id, dest, album)
            !(dest == "a" && id == "two" && fail)
        })
        send(); fail = false; send()
        assertEquals(seen.first().third, seen.last().third)
        assertNotEquals(seen.first().third, seen.first { it.second == "b" }.third)
        assertEquals(1, seen.filter { it.second == "b" }.map { it.third }.distinct().size)
        java.util.UUID.fromString(seen.first().third)
    }

    @Test fun `external sharing is exclusive and does not consume draft or selection`() = runTest {
        val p = picker()
        assertTrue(p.beginShare()); assertFalse(p.beginShare())
        var writes = 0
        p.send({ true }, { true }, { _, _ -> writes++; true }, { _, _, _ -> writes++; true })
        p.endShare(true)
        assertEquals(0, writes)
        assertEquals(ForwardPickerFailure.SHARE, p.state.value.failure)
        assertEquals(listOf("a", "b"), p.state.value.selectedIds)
        assertEquals("  hello  ", p.state.value.comment)
    }

    @Test fun `album preview counts every captured member and keeps distinct captions`() {
        val rows = (1..3).map { row(it.toString()).copy(caption = "Three photos", albumId = "album") }
        val media = rows.map { Attachment(it.clientMessageId, it.clientMessageId, AttachmentMediaType.IMAGE, "image/jpeg", "${it.clientMessageId}.jpg",
            10, 20, 20, null, null, null, AttachmentDownloadState.NOT_DOWNLOADED, AttachmentUploadState.SENT, 100) }
        val preview = forwardPickerPreview(rows, media)
        assertEquals(3, preview.count)
        assertEquals(ForwardPreviewKind.PHOTOS, preview.kind)
        assertEquals(listOf("Three photos"), preview.snippets)
        assertEquals(ForwardPreviewKind.MIXED, forwardPickerPreview(rows + row("text"), media).kind)
    }

    private fun row(id: String) = Message(id, id, "source", "me", id, 1, "2026-11-13T10:00:00Z", MessageStatus.SENT)

    @Test fun `large selection preview is bounded without splitting surrogate pairs`() {
        val rows = (1..50).map { row(it.toString()).copy(text = "🙂".repeat(500) + it) }
        val preview = forwardPickerPreview(rows, emptyList())
        assertEquals(50, preview.count)
        assertTrue(preview.snippets.size <= 3)
        preview.snippets.forEach { text ->
            assertEquals(160, text.codePointCount(0, text.length))
            assertFalse(Character.isHighSurrogate(text.last()))
        }
    }

    @Test fun `selected recipient snapshot survives closing and can be removed after a catalog deletion`() {
        val p = ForwardPickerSession()
        val target = ChatSummary("gone", "direct", null, "", null)
        p.open(listOf("one"), ForwardPickerPreview(1), emptyMap())
        p.toggle("gone", target); p.dismiss()
        p.open(listOf("one"), ForwardPickerPreview(1), emptyMap())
        assertEquals(target, p.state.value.targetSnapshots["gone"])
        p.toggle("gone")
        assertTrue(p.state.value.selectedIds.isEmpty())
        assertTrue(p.state.value.targetSnapshots.isEmpty())
    }

    @Test fun `captured payload and preview retain text caption and attachment metadata after live edits`() {
        val text = row("text").copy(text = "Original text")
        val photo = row("photo").copy(caption = "Original caption")
        val media = Attachment("asset", "photo", AttachmentMediaType.IMAGE, "image/jpeg", "original.jpg", 10, 20, 20,
            null, null, null, AttachmentDownloadState.NOT_DOWNLOADED, AttachmentUploadState.SENT, 100)
        val rows = mutableListOf(text, photo)
        val attachments = mutableListOf(media)
        val captured = ForwardPickerSources(listOf("text", "photo"), rows, attachments)
        rows[0] = text.copy(text = "Edited text")
        rows[1] = photo.copy(caption = "Edited caption")
        attachments[0] = media.copy(filename = "changed.jpg", localPath = "/downloaded")
        assertTrue(captured.available(listOf("text", "photo"), rows, attachments))
        assertEquals("Original text", captured["text"]!!.message.text)
        assertEquals("Original caption", captured["photo"]!!.message.caption)
        assertEquals("original.jpg", captured["photo"]!!.attachment!!.filename)
        assertEquals(listOf("Original text", "Original caption"), captured.preview.snippets)
        rows.clear(); attachments.clear()
        assertEquals("Original text", captured["text"]!!.message.text)
    }

    @Test fun `live source admission rejects deletion warnings and attachment replacement without treating an edit as unavailable`() {
        val source = row("one")
        val captured = ForwardPickerSources(listOf("one"), listOf(source), emptyList())
        assertTrue(captured.available(listOf("one"), listOf(source.copy(text = "new")), emptyList()))
        assertFalse(captured.available(listOf("one"), emptyList(), emptyList()))
        assertFalse(captured.available(listOf("one"), listOf(source.copy(isDeletedForEveryone = true)), emptyList()))
        assertFalse(captured.available(listOf("one"), listOf(source.copy(status = MessageStatus.IDENTITY_CHANGED)), emptyList()))
        assertFalse(captured.available(listOf("one"), listOf(source.copy(chatId = "other")), emptyList()))
    }

    @Test fun `reopen reports whether a new payload snapshot is needed`() {
        val p = ForwardPickerSession()
        assertTrue(p.open(listOf("one"), ForwardPickerPreview(1), emptyMap()))
        p.dismiss()
        assertFalse(p.open(listOf("one"), ForwardPickerPreview(1, snippets = listOf("changed")), emptyMap()))
        assertTrue(p.open(listOf("two"), ForwardPickerPreview(1), emptyMap()))
    }

    @Test fun `reselection after source rejection before dispatch recaptures while retaining the draft`() = runTest {
        val p = picker(); p.query("search")
        var writes = 0
        p.send({ true }, { false }, { _, _ -> writes++; true }, { _, _, _ -> writes++; true })
        assertEquals(0, writes)
        p.dismiss()
        val refreshed = ForwardPickerPreview(2, snippets = listOf("New capture"))
        assertTrue(p.open(listOf("one", "two"), refreshed, emptyMap()))
        assertEquals(refreshed, p.state.value.preview)
        assertEquals(listOf("a", "b"), p.state.value.selectedIds)
        assertEquals("  hello  ", p.state.value.comment)
        assertEquals("search", p.state.value.query)
        assertNull(p.state.value.failure)
    }

    @Test fun `source rejection cannot recapture after a comment or forward might have been accepted`() = runTest {
        for (commentAccepted in listOf(false, true)) {
            val p = picker()
            val original = p.state.value.preview
            p.send({ true }, { true }, { _, _ -> commentAccepted }, { _, _, _ -> false })
            p.send({ true }, { false }, { _, _ -> fail("No second dispatch"); true }, { _, _, _ -> false })
            p.dismiss()
            assertFalse(p.open(listOf("one", "two"), ForwardPickerPreview(2, snippets = listOf("Changed")), emptyMap()))
            assertEquals(original, p.state.value.preview)
            assertEquals(listOf("a", "b"), p.state.value.selectedIds)
        }
    }

    @Test fun `a persistent failed upload is unresolved until a completed upload is captured`() {
        val row = row("photo").copy(caption = "Caption")
        val failed = Attachment("failed-asset", "photo", AttachmentMediaType.IMAGE, "image/jpeg", "photo.jpg", 10, 20, 20,
            null, null, "/cached", AttachmentDownloadState.DOWNLOADED, AttachmentUploadState.FAILED, 0)
        val old = ForwardPickerSources(listOf("photo"), listOf(row), listOf(failed))
        assertFalse(old.resolved)
        assertFalse(old.available(listOf("photo"), listOf(row), listOf(failed)))
        val completed = failed.copy(attachmentId = "completed-asset", uploadState = AttachmentUploadState.UPLOADED, progress = 100)
        val fresh = ForwardPickerSources(listOf("photo"), listOf(row), listOf(completed))
        assertTrue(fresh.resolved)
        assertTrue(fresh.available(listOf("photo"), listOf(row), listOf(completed)))
        assertFalse(old.available(listOf("photo"), listOf(row), listOf(completed)))
    }
}
