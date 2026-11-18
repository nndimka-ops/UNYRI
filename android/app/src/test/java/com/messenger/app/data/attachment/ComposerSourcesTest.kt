package com.messenger.app.data.attachment

import com.messenger.app.domain.repository.QueuedMediaDraft
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerSourcesTest {
    private fun snapshot(coordinator: MediaPreparationCoordinator, restored: ComposerDrafts = ComposerDrafts()): ComposerTraySnapshot {
        val state = coordinator.state.value["chat"] ?: MediaPreparationCoordinator.State()
        return ComposerTraySnapshot.create(restored, state.items, state.forgotten, state.unavailable)
    }

    @Test fun `cancelling a partially prepared import never turns its completed subset into an automatic Send`() = runTest {
        val blocked = CompletableDeferred<Unit>()
        var completed = 0; var failures = 0
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, uri -> if (uri == "B") blocked.await(); "draft:$uri" },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        coordinator.start("chat", listOf("A", "B"), true, onComplete = { completed++ }, onFailure = { failures++ })
        runCurrent(); coordinator.cancel("chat"); runCurrent()
        assertEquals(0, completed); assertEquals(1, failures)
        assertEquals(listOf("draft:A"), snapshot(coordinator).sendableFiles)
    }

    @Test fun `ordinary copies survive restart while large references and pending large imports do not`() = runTest {
        var journal = ComposerDrafts()
        val markers = mutableListOf<Boolean>()
        val pending = CompletableDeferred<Unit>()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, uri -> "draft:$uri" },
            { _, _, uri, _ -> journal = journal.edit(addFiles = listOf(uri)) }, { _, _, active -> markers += active }, { _, _ -> },
            retainOriginal = { _, uri -> if (uri == "pending") pending.await(); uri })
        coordinator.start("chat", listOf("large"), true, originals = true); runCurrent()
        assertTrue(journal.all.isEmpty()); assertTrue(markers.isEmpty())
        assertEquals(listOf("large"), snapshot(coordinator).files)
        coordinator.start("chat", listOf("ordinary"), true); runCurrent()
        assertEquals(listOf("draft:ordinary"), journal.files)
        val priorMarkers = markers.toList()
        coordinator.start("chat", listOf("pending"), true, originals = true); runCurrent()
        assertEquals(priorMarkers, markers)
        val restarted = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, uri -> uri }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        assertEquals(listOf("draft:ordinary"), snapshot(restarted, journal).files)
        assertFalse(restarted.state.value["chat"]?.failed == true)
    }

    @Test fun `a revoked cached original is marked unavailable and does not reject a valid Add`() = runTest {
        var revoked = false
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, uri -> "draft:$uri" },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> }, retainOriginal = { _, uri -> uri },
            sourceAvailable = { _, uri -> if (uri == "A" && revoked) throw SecurityException("revoked"); true })
        coordinator.start("chat", listOf("A"), true, originals = true); runCurrent()
        revoked = true
        coordinator.start("chat", listOf("B"), true); runCurrent()
        val tray = snapshot(coordinator)
        assertEquals(setOf("A"), tray.unavailable)
        assertEquals(listOf("draft:B"), tray.sendableFiles)
        assertEquals(listOf("A", "draft:B"), tray.ordered)
        assertTrue(tray.ready); assertFalse(coordinator.state.value.getValue("chat").failed)
    }

    @Test fun `one unavailable incoming provider does not discard other large selections`() = runTest {
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, _ -> error("No copy") },
            { _, _, _, _ -> error("Session originals must not be journaled") }, { _, _, _ -> error("No durable import marker") }, { _, _ -> },
            retainOriginal = { _, uri -> uri }, inspectOriginals = { _, _, _ -> ComposerOriginals(setOf("B"), setOf("A")) })
        coordinator.start("chat", listOf("A", "B"), true, originals = true); runCurrent()
        assertEquals(listOf("B"), snapshot(coordinator).sendableFiles)
        assertEquals(setOf("A"), snapshot(coordinator).unavailable)
        assertFalse(coordinator.state.value.getValue("chat").active)
        assertFalse(coordinator.state.value.getValue("chat").failed)
    }

    @Test fun `missing ordinary provider is isolated and the remaining source commits`() = runTest {
        val committed = mutableListOf<String>()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, uri -> if (uri == "A") throw FileNotFoundException(); "draft:$uri" },
            { _, _, uri, _ -> committed += uri }, { _, _, _ -> }, { _, _ -> })
        coordinator.start("chat", listOf("A", "B"), true); runCurrent()
        assertEquals(listOf("draft:B"), committed)
        assertEquals(setOf("A"), snapshot(coordinator).unavailable)
        assertEquals(listOf("draft:B"), snapshot(coordinator).sendableFiles)
        assertFalse(coordinator.state.value.getValue("chat").failed)
    }

    @Test fun `compressed upload retains the original selection identity separately from the copy`() = runTest {
        val original = "content://media/videos/42"
        val prepared = prepareComposerItems(listOf(original), { error("Available source") }) {
            QueuedMediaDraft("id", "file:///media-drafts/prepared/video.mp4", "video")
        }
        assertEquals(setOf(original), prepared.selectionUris)
        assertEquals(listOf("file:///media-drafts/prepared/video.mp4"), prepared.media.map { it.uri })
        // The production repository passes selectionUris, rather than upload URIs, to its Room transaction.
        val journal = ComposerDrafts(photos = listOf(original, "keep"))
        assertEquals(listOf("keep"), journal.edit(remove = prepared.selectionUris).photos)
    }

    @Test fun `only successful original identities are consumed when another selection is revoked`() = runTest {
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, uri -> uri },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> }, retainOriginal = { _, uri -> uri })
        coordinator.start("chat", listOf("A", "B"), true, originals = true); runCurrent()
        val batch = prepareComposerItems(listOf("A", "B"), { coordinator.markUnavailable("chat", setOf(it)) }) { source ->
            if (source == "A") throw SecurityException("revoked")
            QueuedMediaDraft("id-B", "file:///copy-B", "video")
        }
        coordinator.consumeCommitted("chat", batch.selectionUris.toList())
        assertEquals(setOf("B"), batch.selectionUris)
        assertEquals(listOf("A"), snapshot(coordinator).ordered)
        assertEquals(setOf("A"), snapshot(coordinator).unavailable)
        assertTrue(snapshot(coordinator).sendableFiles.isEmpty())
    }

    @Test fun `all unavailable sources retain their reselection state and caption without a global preparation error`() = runTest {
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, uri -> uri },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        val caption = coordinator.restoreCaption("chat", "Keep caption")
        coordinator.submitSend("chat", caption = caption) {
            prepareComposerItems(listOf("missing"), { coordinator.markUnavailable("chat", setOf(it)) }) { throw FileNotFoundException() }.items.isNotEmpty()
        }
        runCurrent()
        assertEquals(caption, coordinator.state.value.getValue("chat").caption)
        assertFalse(coordinator.state.value.getValue("chat").failed)
        assertFalse(coordinator.state.value.getValue("chat").sending)
        assertEquals(setOf("missing"), snapshot(coordinator).unavailable)
    }

    @Test fun `cancellation and disk errors are not misclassified as a revoked source`() = runTest {
        assertFailsWith<CancellationException> {
            prepareComposerItems(listOf("A"), { error("Must not mark unavailable") }) { throw CancellationException() }
        }
        assertFailsWith<IOException> {
            prepareComposerItems(listOf("A"), { error("Must not mark unavailable") }) { throw IOException("disk full") }
        }
    }

    @Test fun `source metadata and MIME resolve away from the caller thread and are reused as one value`() = runTest {
        val caller = Thread.currentThread()
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            var reads = 0
            val resolver = ComposerSourceResolver({ source ->
                assertNotSame(caller, Thread.currentThread())
                assertEquals("content://video", source)
                reads++
                ComposerSourceInfo("video.mp4", 150L * 1024 * 1024, "video/mp4")
            }, worker)
            val info = resolver.resolve("content://video")
            assertTrue(info.isVideo); assertEquals("video.mp4", info.name)
            assertEquals(150L * 1024 * 1024, info.bytes); assertEquals(1, reads)
        } finally { worker.close() }
    }

    @Test fun `account session changes drop retained and unavailable selections without a durable marker`() = runTest {
        var epoch = 1
        val coordinator = MediaPreparationCoordinator(backgroundScope, { epoch }, { _, _ -> error("No copy") },
            { _, _, _, _ -> error("No journal") }, { _, _, _ -> error("No marker") }, { _, _ -> }, retainOriginal = { _, uri -> uri })
        coordinator.start("chat", listOf("A"), true, originals = true); runCurrent()
        coordinator.markUnavailable("chat", setOf("A"))
        epoch = 2; coordinator.invalidateSession()
        assertTrue(coordinator.state.value.isEmpty())
    }
}
