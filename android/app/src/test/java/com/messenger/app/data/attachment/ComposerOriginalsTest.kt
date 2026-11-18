package com.messenger.app.data.attachment

import com.messenger.app.data.directtransfer.DIRECT_NORMAL_SOURCE_LIMIT
import com.messenger.app.data.directtransfer.DirectFileWire
import com.messenger.app.data.directtransfer.DirectFileSourceDisplay
import com.messenger.app.data.directtransfer.DirectAdmissionPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerOriginalsTest {
    private val large = 150L * 1024 * 1024

    @Test fun `an already owned original reserves no second copy of its bytes`() {
        val bytes = 12L * 1024 * 1024 * 1024
        val free = 8L * 1024 * 1024 * 1024
        val retained = DirectFileSourceDisplay("original", bytes, copyRequired = false)
        val external = DirectFileSourceDisplay("original", bytes)
        assertEquals(0L, retained.reservationBytes(8L * 1024 * 1024))
        assertTrue(DirectAdmissionPolicy.mayReserveDisk(free, 0, retained.reservationBytes(8L * 1024 * 1024)))
        assertFalse(DirectAdmissionPolicy.mayReserveDisk(free, 0, external.reservationBytes(8L * 1024 * 1024)))
    }

    @Test fun `actual numeric source bounds choose references at Add`() {
        assertFalse(ComposerOriginals.needsDirect(DIRECT_NORMAL_SOURCE_LIMIT))
        assertTrue(ComposerOriginals.needsDirect(DIRECT_NORMAL_SOURCE_LIMIT + 1))
        assertTrue(ComposerOriginals.needsDirect(large))
        assertTrue(ComposerOriginals.needsDirect(-1))
        assertTrue(ComposerOriginals.needsDirect(DirectFileWire.MAX_FILE_BYTES))
        assertFailsWith<AttachmentTooLargeException> { ComposerOriginals.needsDirect(DirectFileWire.MAX_FILE_BYTES + 1) }
    }

    @Test fun `eight large references plus two ordinary copies fit the ten item tray`() = runTest {
        val sizes = (0 until 10).associate { "content://documents/$it" to if (it < 8) large else 1024L }
        val retained = mutableSetOf<String>()
        val copied = mutableSetOf<String>()
        val committed = mutableListOf<String>()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, _ -> error("Originals use bounded staging") },
            { _, _, uri, _ -> committed += uri }, { _, _, _ -> }, { _, _ -> },
            stageWithLimit = { _, uri, limit -> assertEquals(DIRECT_NORMAL_SOURCE_LIMIT, limit); copied += uri; "draft:$uri" },
            retainOriginal = { _, uri -> retained += uri; uri },
            inspectOriginals = { _, _, uris -> ComposerOriginals(uris.filter { ComposerOriginals.needsDirect(sizes.getValue(it)) }.toSet()) })
        coordinator.start("chat", sizes.keys.toList(), asFile = true, originals = true); runCurrent()
        assertEquals(sizes.keys.take(8).toSet(), retained)
        assertEquals(sizes.keys.drop(8).toSet(), copied)
        assertEquals(2, committed.size) // Large references never enter the durable journal.
        assertEquals(8, coordinator.state.value.getValue("chat").items.count { it.directOriginal })
    }

    @Test fun `nine large picks reject the entire selection before any retention copy or commit`() = runTest {
        val sources = (0 until 9).map { "content://documents/$it" }
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, _ -> error("No copy before capacity") },
            { _, _, _, _ -> error("No partial Add") }, { _, _, _ -> }, { _, _ -> },
            retainOriginal = { _, _ -> error("No retention before capacity") },
            inspectOriginals = { _, _, uris -> ComposerOriginals(uris.filter { ComposerOriginals.needsDirect(large) }.toSet()) })
        coordinator.start("chat", sources, true, originals = true); runCurrent()
        val state = coordinator.state.value.getValue("chat")
        assertTrue(state.items.isEmpty()); assertFalse(state.active)
        assertEquals(MediaPreparationCoordinator.Failure.DIRECT_CAPACITY, state.failure)
    }

    @Test fun `new sessions do not restore large original references`() = runTest {
        var committed = 0
        val markers = mutableListOf<Boolean>()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, _ -> error("No copy") },
            { _, _, _, _ -> committed++ }, { _, _, active -> markers += active }, { _, _ -> },
            retainOriginal = { _, uri -> uri }, inspectOriginals = { _, _, uris -> ComposerOriginals(uris.toSet()) })
        coordinator.start("chat", listOf("newA", "newB"), true, originals = true); runCurrent()
        assertEquals(2, coordinator.state.value.getValue("chat").items.size)
        assertEquals(0, committed); assertTrue(markers.isEmpty())
        val restarted = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, uri -> uri },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        assertTrue(restarted.state.value.isEmpty())
    }

    @Test fun `concurrent Add metadata completions cannot overbook eight original references`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var commits = 0
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, _ -> error("No copy") },
            { _, _, _, _ -> commits++ }, { _, _, _ -> }, { _, _ -> }, retainOriginal = { _, uri -> uri },
            inspectOriginals = { _, _, uris -> gate.await(); ComposerOriginals(uris.toSet()) })
        coordinator.start("chat", (0 until 7).map { "old$it" }, true, originals = true, append = true)
        coordinator.start("chat", listOf("newA", "newB"), true, originals = true, append = true)
        runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(0, commits)
        assertEquals(7, coordinator.state.value.getValue("chat").items.size)
        assertFalse(coordinator.state.value.getValue("chat").active)
    }

    @Test fun `durable direct consumption bypasses another sends user removal guard`() = runTest {
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, uri -> uri },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        coordinator.start("chat", listOf("direct", "photo"), true); runCurrent()
        val gate = CompletableDeferred<Unit>()
        coordinator.submitSend("chat") { gate.await(); true }; runCurrent()
        coordinator.remove("chat", "direct")
        assertEquals(2, coordinator.state.value.getValue("chat").items.size)
        coordinator.consumeCommitted("chat", listOf("direct"), 1)
        val state = coordinator.state.value.getValue("chat")
        assertEquals(listOf("photo"), state.items.map { it.source })
        assertTrue(state.sending)
        assertTrue("direct" in state.forgotten)
        gate.complete(Unit); runCurrent()
    }

    @Test fun `recreated composer observes matching successful caption consumption`() = runTest {
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, uri -> uri },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        val captured = coordinator.restoreCaption("chat", "Caption", "revision-1")
        val gate = CompletableDeferred<Unit>()
        coordinator.submitSend("chat", caption = captured) { gate.await(); true }; runCurrent()
        assertEquals(captured, coordinator.restoreCaption("chat", "stale saved input"))
        gate.complete(Unit); runCurrent()
        assertEquals("", coordinator.restoreCaption("chat", "stale saved input").text)
    }

    @Test fun `editing away and back to the same text preserves the newer caption revision`() = runTest {
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, uri -> uri },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        val captured = coordinator.restoreCaption("chat", "Caption", "revision-1")
        val gate = CompletableDeferred<Unit>()
        coordinator.submitSend("chat", caption = captured) { gate.await(); true }; runCurrent()
        coordinator.editCaption("chat", "Edited")
        val latest = coordinator.editCaption("chat", "Caption")
        assertNotEquals(captured.revision, latest.revision)
        gate.complete(Unit); runCurrent()
        assertEquals(latest, coordinator.restoreCaption("chat", ""))
    }

    @Test fun `failed queue keeps caption available after recreation`() = runTest {
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, uri -> uri },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        val captured = coordinator.restoreCaption("chat", "Caption")
        coordinator.submitSend("chat", caption = captured) { false }; runCurrent()
        assertEquals(captured, coordinator.restoreCaption("chat", ""))
    }

    @Test fun `album retry keys are stable only for the same frozen intent`() {
        val a = ComposerBatchIdentity.key(listOf("A"), "First", "image")
        assertEquals(a, ComposerBatchIdentity.key(listOf("A"), "First", "image"))
        assertNotEquals(a, ComposerBatchIdentity.key(listOf("B"), "Second", "image"))
        assertNotEquals(a, ComposerBatchIdentity.key(listOf("A", "C"), "First", "image"))
        assertNotEquals(a, ComposerBatchIdentity.key(listOf("A"), "Edited", "image"))
        assertNotEquals(a, ComposerBatchIdentity.key(listOf("A"), "First", "file"))
    }
}
