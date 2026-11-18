package com.messenger.app.ui.media

import com.messenger.app.data.local.MediaExportStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.messenger.app.data.attachment.MediaPreparationCoordinator
import com.messenger.app.data.attachment.ComposerDrafts
import com.messenger.app.data.attachment.ComposerTraySnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoEditorResultTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun acceptedExportStaysReadableUntilImporterCleanupAndCloseIsIdempotent() {
        val store = MediaExportStore(temporary.newFolder())
        val result = PhotoEditorResult(store.create { it.writeText("edited image") })
        // Import acceptance does not mean an asynchronous staging copy has finished.
        val cleanup = result::close
        assertEquals("edited image", result.file.readText())
        val staged = temporary.newFile("composer.jpg")
        result.file.copyTo(staged, overwrite = true)
        cleanup(); cleanup()
        assertFalse(result.file.exists())
        assertEquals("edited image", staged.readText())
    }

    @Test fun failedHandoffRemovesOnlyExportAndLeavesSourceUntouched() {
        val source = temporary.newFile("source.jpg").apply { writeText("original image") }
        val result = PhotoEditorResult(MediaExportStore(temporary.newFolder()).create { it.writeText("edited image") })
        result.close()
        assertFalse(result.file.exists())
        assertEquals("original image", source.readText())
    }

    @Test fun admissionDoesNotCompleteUntilCopyAndDatabaseCommitFinish() = runTest {
        val copied = CompletableDeferred<Unit>(); val committed = CompletableDeferred<Unit>()
        val result = PhotoEditorResult(MediaExportStore(temporary.newFolder()).create { it.writeText("edited") })
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, _ -> copied.await(); "durable" },
            { _, _, _, _ -> committed.await() }, { _, _, _ -> }, { _, _ -> })
        val import = async { result.importToComposer(coordinator, "chat", "export", 1, 10) { 1 } }
        runCurrent()
        assertTrue(coordinator.state.value.getValue("chat").active)
        assertFalse(import.isCompleted)
        assertTrue(result.file.exists())
        copied.complete(Unit); runCurrent()
        assertFalse(import.isCompleted)
        assertNull(coordinator.state.value.getValue("chat").items.single().staged)
        committed.complete(Unit); runCurrent()
        assertTrue(import.await())
        assertEquals("durable", coordinator.state.value.getValue("chat").items.single().staged)
        assertFalse(result.file.exists())
    }

    @Test fun copyAndPersistenceFailuresAfterAdmissionReturnFailureForEditorRetry() = runTest {
        for (failCopy in listOf(true, false)) {
            val gate = CompletableDeferred<Unit>()
            val result = PhotoEditorResult(MediaExportStore(temporary.newFolder()).create { it.writeText("edited") })
            val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, _ ->
                gate.await(); if (failCopy) throw java.io.IOException("Disk full during copy"); "staged"
            }, { _, _, _, _ -> throw java.io.IOException("Database persistence failed") }, { _, _, _ -> }, { _, _ -> })
            val import = async { result.importToComposer(coordinator, "chat", "export", 1, 10) { 1 } }
            runCurrent()
            assertTrue(coordinator.state.value.getValue("chat").active)
            assertFalse(import.isCompleted)
            gate.complete(Unit); runCurrent()
            assertFalse(import.await())
            assertTrue(coordinator.state.value.getValue("chat").items.isEmpty())
            assertFalse(result.file.exists())
        }
    }

    @Test fun uiCancellationCannotReleaseAnAdmittedCopySource() = runTest {
        val gate = CompletableDeferred<Unit>()
        val result = PhotoEditorResult(MediaExportStore(temporary.newFolder()).create { it.writeText("edited") })
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, _ ->
            gate.await(); assertEquals("edited", result.file.readText()); "durable"
        }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        val import = async { result.importToComposer(coordinator, "chat", "export", 1, 10) { 1 } }
        runCurrent(); import.cancelAndJoin(); result.close()
        assertTrue(result.file.exists())
        gate.complete(Unit); runCurrent()
        assertFalse(result.file.exists())
        assertEquals("durable", coordinator.state.value.getValue("chat").items.single().staged)
    }

    @Test fun accountInvalidationCompletesWaiterAndReleasesExport() = runTest {
        var epoch = 1
        val gate = CompletableDeferred<Unit>()
        val result = PhotoEditorResult(MediaExportStore(temporary.newFolder()).create { it.writeText("edited") })
        val coordinator = MediaPreparationCoordinator(backgroundScope, { epoch }, { _, _ -> gate.await(); "staged" },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        val import = async { result.importToComposer(coordinator, "chat", "export", 1, 10) { epoch } }
        runCurrent(); epoch = 2; coordinator.invalidateSession(); runCurrent()
        assertFalse(import.await()); assertFalse(result.file.exists())
    }

    @Test fun durableCommitSurvivesMarkerFailureWithoutAnAttachmentRetry() = runTest {
        val result = PhotoEditorResult(MediaExportStore(temporary.newFolder()).create { it.writeText("edited") })
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, _ -> "durable" },
            { _, _, _, _ -> }, { _, _, active -> if (!active) throw java.io.IOException("Marker clear failed") }, { _, _ -> })
        assertTrue(result.importToComposer(coordinator, "chat", "export", 1, 10) { 1 })
        assertFalse(result.importToComposer(coordinator, "chat", "export", 1, 10) { 1 })
        assertEquals(1, coordinator.state.value.getValue("chat").items.size)
        assertFalse(result.file.exists())
    }

    @Test fun capacityRejectionReleasesUnadmittedExport() = runTest {
        val result = PhotoEditorResult(MediaExportStore(temporary.newFolder()).create { it.writeText("edited") })
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, _ -> error("Must not copy") },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        assertFalse(result.importToComposer(coordinator, "chat", "export", 1, 0) { 1 })
        assertFalse(result.file.exists())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoEditorComposerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun export(source: String? = null, operation: String = java.util.UUID.randomUUID().toString()) =
        PhotoEditorResult(MediaExportStore(temporary.newFolder()).create { it.writeText("edited pixels") }, source, operation)

    private class Store(initial: ComposerDrafts = ComposerDrafts()) : PhotoEditorComposerStore {
        override val chat = "chat"
        override val epoch = 1
        var session = 1
        var drafts = initial
        var items = emptyList<MediaPreparationCoordinator.ImportItem>()
        val caption = "album caption"
        val text = "unsent composer text"
        var order = emptyList<String>()
        var copies = 0
        var writes = 0
        var failCopy = false
        var failWrite = false
        var gate: CompletableDeferred<Unit>? = null
        val discarded = mutableListOf<String>()
        override fun currentEpoch() = session
        override suspend fun stage(file: java.io.File): String {
            gate?.await()
            assertEquals("edited pixels", file.readText())
            if (failCopy) throw java.io.IOException("Copy failed")
            return "edited-${++copies}"
        }
        override suspend fun place(original: String?, edited: String): PhotoEditorPlacement {
            check(session == epoch)
            if (failWrite) throw java.io.IOException("Write failed")
            val tray = PhotoEditorComposer.project(ComposerTraySnapshot.create(drafts, items, emptySet()), order)
            return placeEditedPhoto(drafts, tray, original, edited).also {
                drafts = drafts.copy(photos = it.photos); order = it.order; writes++
            }
        }
        override suspend fun contains(edited: String) = edited in drafts.photos
        override suspend fun discard(staged: String) { if (staged !in drafts.all) discarded += staged }
        override fun retire(original: String?) { items = items.filterNot { it.source == original || it.staged == original } }
    }

    @Test fun historyExportAddsARemovableAttachmentAndPreservesOtherDraftFields() = runTest {
        val store = Store(ComposerDrafts(listOf("original"), listOf("document")))
        val composer = PhotoEditorComposer(backgroundScope)
        val result = export()
        assertTrue(composer.accept(result, store))
        assertEquals(listOf("original", "edited-1"), store.drafts.photos)
        assertEquals(listOf("document"), store.drafts.files)
        assertEquals("album caption", store.caption); assertEquals("unsent composer text", store.text)
        assertFalse(result.file.exists()); assertTrue(composer.pending.value.isEmpty())
        store.drafts = store.drafts.edit(remove = setOf("edited-1"))
        assertEquals(listOf("original"), store.drafts.photos)
    }

    @Test fun trayExportReplacesMiddlePickAndRetiresItsImportIdentityWithoutReordering() = runTest {
        val store = Store(ComposerDrafts(listOf("a", "b", "c"), listOf("document")))
        store.items = listOf("a", "document", "b", "c").mapIndexed { i, uri ->
            MediaPreparationCoordinator.ImportItem("provider-$uri", uri, uri == "document", false, i)
        }
        val composer = PhotoEditorComposer(backgroundScope)
        assertTrue(composer.accept(export("b"), store))
        assertEquals(listOf("a", "edited-1", "c"), store.drafts.photos)
        assertFalse(store.items.any { it.staged == "b" })
        val raw = ComposerTraySnapshot.create(store.drafts, store.items, emptySet())
        val projected = composer.project(raw, PhotoEditorComposer.Key("chat", 1))
        assertEquals(listOf("a", "document", "edited-1", "c"), projected.ordered)
        assertEquals(listOf("a", "edited-1", "c"), projected.photos)
        assertEquals(listOf("document"), projected.files)
    }

    @Test fun fullTrayAllowsReplacementButRejectsAnAdditionalHistoryExport() = runTest {
        val store = Store(ComposerDrafts((1..10).map { "photo-$it" }))
        val composer = PhotoEditorComposer(backgroundScope)
        assertTrue(composer.accept(export("photo-5"), store))
        assertEquals("edited-1", store.drafts.photos[4]); assertEquals(10, store.drafts.photos.size)
        val committed = store.drafts
        assertFalse(composer.accept(export(), store))
        assertEquals(committed, store.drafts); assertEquals(1, store.writes)
        assertEquals(listOf("edited-2"), store.discarded)
        assertTrue(composer.pending.value.isEmpty())
    }

    @Test fun originalRemovedWhileEditingAddsInsteadOfOverwritingAnotherPick() = runTest {
        val store = Store(ComposerDrafts(listOf("survivor")))
        assertTrue(PhotoEditorComposer(backgroundScope).accept(export("removed"), store))
        assertEquals(listOf("survivor", "edited-1"), store.drafts.photos)
    }

    @Test fun copyOrJournalFailurePreservesTheOriginalAndAllowsRetry() = runTest {
        for (copyFailure in listOf(true, false)) {
            val original = ComposerDrafts(listOf("a", "original", "b"), listOf("document"))
            val store = Store(original).apply { failCopy = copyFailure; failWrite = !copyFailure }
            val composer = PhotoEditorComposer(backgroundScope)
            val result = export("original")
            assertFalse(composer.accept(result, store))
            assertEquals(original, store.drafts); assertFalse(result.file.exists())
            assertEquals(if (copyFailure) emptyList<String>() else listOf("edited-1"), store.discarded)
            assertTrue(composer.pending.value.isEmpty())
            store.failCopy = false; store.failWrite = false
            assertTrue(composer.accept(export("original"), store))
            assertEquals(3, store.drafts.photos.size)
        }
    }

    @Test fun cancelledUiKeepsImportAliveAndRetryOfSameDocumentDoesNotAddAgain() = runTest {
        val store = Store().apply { gate = CompletableDeferred() }
        val composer = PhotoEditorComposer(backgroundScope)
        val result = export(operation = "same-document")
        val ui = async { composer.accept(result, store) }
        runCurrent(); ui.cancelAndJoin(); result.close()
        assertTrue(result.file.exists()); assertTrue("same-document" in composer.pending.value)
        store.gate!!.complete(Unit); runCurrent()
        assertFalse(result.file.exists()); assertTrue(composer.pending.value.isEmpty())
        assertTrue(composer.accept(export(operation = "same-document"), store))
        assertEquals(1, store.copies); assertEquals(1, store.writes)
        assertEquals(listOf("edited-1"), store.drafts.photos)
    }

    @Test fun staleAccountCannotReplaceTheJournalAndCleansUpTheExport() = runTest {
        val store = Store(ComposerDrafts(listOf("original"))).apply { gate = CompletableDeferred() }
        val composer = PhotoEditorComposer(backgroundScope)
        val result = export("original")
        val ui = async { composer.accept(result, store) }
        runCurrent(); store.session = 2; store.gate!!.complete(Unit); runCurrent()
        assertFalse(ui.await()); assertFalse(result.file.exists())
        assertEquals(listOf("original"), store.drafts.photos); assertEquals(0, store.writes)
        assertTrue(composer.pending.value.isEmpty())
    }
}
