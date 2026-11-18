package com.messenger.app.data.attachment

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerTrayInterleavingsTest {
    /** Fake transactional repository; uses the production journal edit and tray projection. */
    private class Journal {
        private val mutex = Mutex()
        var drafts = ComposerDrafts()
        val queued = mutableListOf<List<String>>()
        val deleted = mutableSetOf<String>()
        suspend fun commit(uri: String, asFile: Boolean, gate: CompletableDeferred<Unit>? = null) = mutex.withLock {
            gate?.await()
            drafts = if (asFile) drafts.edit(addFiles = listOf(uri)) else drafts.edit(addPhotos = listOf(uri))
        }
        suspend fun remove(uris: Set<String>) = mutex.withLock { drafts = drafts.edit(remove = uris) }
        suspend fun queue(snapshot: ComposerTraySnapshot): Boolean = mutex.withLock {
            check(snapshot.ready)
            queued += snapshot.ordered
            drafts = drafts.edit(remove = snapshot.ordered.toSet())
            true
        }
        fun discard(uri: String) { if (uri !in drafts.all && queued.none { uri in it }) deleted += uri }
    }
    private fun snapshot(coordinator: MediaPreparationCoordinator, restored: ComposerDrafts = ComposerDrafts()): ComposerTraySnapshot {
        val state = coordinator.state.value["chat"] ?: MediaPreparationCoordinator.State()
        return ComposerTraySnapshot.create(restored, state.items, state.forgotten)
    }

    @Test fun `remove B while A is committed and Room UI list is stale preserves A and its bytes`() = runTest {
        val journal = Journal()
        val gateB = CompletableDeferred<Unit>()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source ->
            if (source == "B") gateB.await()
            "draft:$source"
        }, { _, _, uri, asFile -> journal.commit(uri, asFile) }, { _, _, _ -> }, { _, uri -> journal.discard(uri) },
            removeDraftReferences = { _, _, uris -> journal.remove(uris) })
        coordinator.start("chat", listOf("A", "B"), asFile = true); runCurrent()
        val staleRoomList = ComposerDrafts()
        assertEquals(listOf("draft:A"), journal.drafts.files)
        assertEquals(setOf("B"), snapshot(coordinator, staleRoomList).importing)
        coordinator.remove("chat", "B"); runCurrent()
        assertEquals(listOf("draft:A"), journal.drafts.files)
        gateB.complete(Unit); runCurrent()
        assertEquals(listOf("draft:A"), snapshot(coordinator, staleRoomList).files)
        assertFalse("draft:A" in journal.deleted)
        assertTrue("draft:B" in journal.deleted)
    }

    @Test fun `remove while commit is suspended retires only that late committed reference`() = runTest {
        val journal = Journal()
        journal.commit("draft:A", true)
        val commitB = CompletableDeferred<Unit>()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source -> "draft:$source" },
            { _, _, uri, file -> journal.commit(uri, file, commitB) }, { _, _, _ -> }, { _, uri -> journal.discard(uri) },
            removeDraftReferences = { _, _, uris -> journal.remove(uris) })
        coordinator.start("chat", listOf("B"), asFile = true); runCurrent()
        coordinator.remove("chat", "B"); runCurrent()
        commitB.complete(Unit); runCurrent()
        assertEquals(listOf("draft:A"), journal.drafts.files)
        assertTrue(snapshot(coordinator).files.isEmpty())
        assertFalse("draft:A" in journal.deleted)
        assertTrue("draft:B" in journal.deleted)
    }

    @Test fun `ready rendered batch is sent before the async Room reload catches up`() = runTest {
        val journal = Journal()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source -> "draft:$source" },
            { _, _, uri, file -> journal.commit(uri, file) }, { _, _, _ -> }, { _, uri -> journal.discard(uri) })
        coordinator.start("chat", listOf("photo")); runCurrent()
        coordinator.start("chat", listOf("document"), asFile = true); runCurrent()
        val rendered = snapshot(coordinator, ComposerDrafts()) // Room still reports no drafts.
        assertTrue(rendered.ready)
        assertEquals(listOf("draft:photo"), rendered.photos)
        assertEquals(listOf("draft:document"), rendered.files)
        assertTrue(coordinator.submitSend("chat") { journal.queue(rendered) })
        runCurrent()
        assertEquals(listOf(rendered.ordered), journal.queued)
        assertTrue(journal.drafts.all.isEmpty())
        assertFalse(coordinator.state.value.getValue("chat").sending)
    }

    @Test fun `stale Room snapshot cannot restore a removed draft or hide the remaining ready one`() = runTest {
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source -> source },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        val stale = ComposerDrafts(files = listOf("A", "B"))
        coordinator.remove("chat", "B"); runCurrent()
        val rendered = snapshot(coordinator, stale)
        assertEquals(listOf("A"), rendered.files)
        assertEquals(listOf("A"), rendered.ordered)
        assertTrue(rendered.ready)
    }

    @Test fun `navigation scope cancellation does not cancel app owned Send now import and queue`() = runTest {
        val screenJob = SupervisorJob()
        val screenScope = CoroutineScope(StandardTestDispatcher(testScheduler) + screenJob)
        val importGate = CompletableDeferred<Unit>()
        val queueGate = CompletableDeferred<Unit>()
        var queued = 0
        var cleaned = 0
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source -> importGate.await(); "draft:$source" },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        screenScope.launch {
            assertTrue(coordinator.submitSend("chat") {
                val imported = coordinator.importForSend("chat", listOf("file"), true, 1, null, false, { cleaned++ })
                assertEquals(listOf("draft:file"), imported)
                queueGate.await(); queued++; true
            })
        }
        runCurrent(); assertTrue(coordinator.state.value.getValue("chat").sending)
        screenJob.cancel(); runCurrent()
        importGate.complete(Unit); runCurrent()
        assertTrue(coordinator.state.value.getValue("chat").sending)
        queueGate.complete(Unit); runCurrent()
        assertEquals(1, queued)
        assertEquals(1, cleaned)
        assertFalse(coordinator.state.value.getValue("chat").sending)
    }

    @Test fun `send ownership releases on cancellation both before dispatch and inside queue`() = runTest {
        for (start in listOf(false, true)) {
            val owner = SupervisorJob()
            val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + owner)
            var completions = 0
            var notStarted = 0
            val coordinator = MediaPreparationCoordinator(appScope, { 1 }, { _, source -> source },
                { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
            coordinator.submitSend("chat", onNotStarted = { notStarted++ }, onComplete = { assertFalse(it); completions++ }) {
                awaitCancellation()
            }
            if (start) runCurrent()
            owner.cancel(); runCurrent()
            assertFalse(coordinator.state.value.getValue("chat").sending)
            assertEquals(1, completions)
            assertEquals(if (start) 0 else 1, notStarted)
        }
    }

    @Test fun `busy rejection and a rejected import never strand or steal send ownership`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var secondCompleted = false
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source -> source },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        coordinator.submitSend("chat") { gate.await(); true }; runCurrent()
        assertFalse(coordinator.submitSend("chat", onComplete = { assertFalse(it); secondCompleted = true }) { error("Must not start") })
        assertTrue(secondCompleted)
        assertTrue(coordinator.state.value.getValue("chat").sending)
        gate.complete(Unit); runCurrent()
        assertFalse(coordinator.state.value.getValue("chat").sending)
        coordinator.start("chat", listOf("already added")); runCurrent()
        var cleaned = 0
        coordinator.submitSend("chat") {
            coordinator.importForSend("chat", listOf("already added"), true, 1, null, false, { cleaned++ }) != null
        }
        runCurrent()
        assertEquals(1, cleaned)
        assertFalse(coordinator.state.value.getValue("chat").sending)
    }

    @Test fun `failed queue is retryable and old session cancellation cannot release the new send`() = runTest {
        var epoch = 1
        val coordinator = MediaPreparationCoordinator(backgroundScope, { epoch }, { _, source -> source },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        coordinator.submitSend("chat") { false }; runCurrent()
        assertFalse(coordinator.state.value.getValue("chat").sending)
        coordinator.submitSend("chat") { awaitCancellation() }; runCurrent()
        epoch = 2; coordinator.invalidateSession()
        val gate = CompletableDeferred<Unit>()
        assertTrue(coordinator.submitSend("chat") { gate.await(); true })
        runCurrent(); assertTrue(coordinator.state.value.getValue("chat").sending)
        gate.complete(Unit); runCurrent()
        assertFalse(coordinator.state.value.getValue("chat").sending)
    }

}
