package com.messenger.app.data.attachment

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPreparationCoordinatorTest {
    @Test fun `busy send fallback can explicitly add while another provider imports and capacity stays reserved`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        var marker = false
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source -> if (source == "slow") gate.await(); source },
            { _, _, source, _ -> saved += source }, { _, _, active -> marker = active }, { _, _ -> })
        coordinator.start("a", listOf("slow"), flies = true, maximumItems = 2); runCurrent()
        assertTrue(coordinator.start("a", listOf("added"), flies = true, append = true, maximumItems = 2)); runCurrent()
        assertFalse(coordinator.start("a", listOf("overflow"), flies = true, append = true, maximumItems = 2))
        assertTrue(coordinator.state.value.getValue("a").active)
        assertTrue(marker)
        assertEquals(listOf("added"), saved)
        gate.complete(Unit); runCurrent()
        assertFalse(coordinator.state.value.getValue("a").active)
        assertFalse(marker)
        assertEquals(listOf("added", "slow"), saved)
    }
    @Test fun `add reserves visible slots before a provider suspends and lands once across chat returns`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source -> gate.await(); "staged:$source" },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        assertTrue(coordinator.start("a", listOf("photo", "file"), flies = true))
        val arrivals = coordinator.state.value.getValue("a").items
        assertEquals(listOf("photo", "file"), arrivals.map { it.source })
        assertTrue(arrivals.all { it.staged == null && it.flies })
        assertEquals(listOf(0, 1), arrivals.map { it.order })
        assertTrue(coordinator.land(arrivals.first().arrival))
        assertFalse(coordinator.land(arrivals.first().arrival))
        gate.complete(Unit); runCurrent()
        val ready = coordinator.state.value.getValue("a").items
        assertEquals(listOf("staged:photo", "staged:file"), ready.map { it.staged })
        assertSame(arrivals.first().arrival, ready.first().arrival)
        assertTrue(coordinator.hasLanded(ready.first().arrival))
        assertTrue(coordinator.state.value["b"]?.items.isNullOrEmpty())
    }

    @Test fun `source URI de-duplicates after staging and removed sources can be added again`() = runTest {
        var copies = 0
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source -> copies++; "copy:$source" },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        coordinator.start("a", listOf("content://same", "content://same"), flies = true); runCurrent()
        assertFalse(coordinator.start("a", listOf("content://same")))
        assertFalse(coordinator.start("a", listOf("copy:content://same")))
        assertEquals(1, copies)
        val oldArrival = coordinator.state.value.getValue("a").items.single().arrival
        coordinator.forget("a", "copy:content://same")
        assertTrue(coordinator.start("a", listOf("content://same"), flies = true)); runCurrent()
        assertNotSame(oldArrival, coordinator.state.value.getValue("a").items.single().arrival)
        assertEquals(2, copies)
    }

    @Test fun `removing an importing placeholder prevents a late provider from restoring it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source -> gate.await(); source },
            { _, _, source, _ -> saved += source }, { _, _, _ -> }, { _, source -> discarded += source })
        coordinator.start("a", listOf("removed"), flies = true); runCurrent()
        coordinator.forget("a", "removed"); gate.complete(Unit); runCurrent()
        assertTrue(saved.isEmpty())
        assertEquals(listOf("removed"), discarded)
        assertTrue(coordinator.state.value.getValue("a").items.isEmpty())
    }

    @Test fun `send and import have independent ownership and send now never flies`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var sends = 0
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source -> gate.await(); source },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        val send = coordinator.newSendToken("a")
        coordinator.start("a", listOf("now"), onComplete = { if (coordinator.ownsSend("a", send)) sends++ })
        assertTrue(coordinator.state.value.getValue("a").sending)
        assertFalse(coordinator.state.value.getValue("a").items.single().flies)
        coordinator.finishSend("a", send)
        assertFalse(coordinator.state.value.getValue("a").sending)
        assertTrue(coordinator.state.value.getValue("a").active)
        gate.complete(Unit); runCurrent()
        assertEquals(0, sends)
        assertEquals("now", coordinator.state.value.getValue("a").items.single().staged)
    }

    @Test fun `failed import clears placeholders and never sends a partial selection`() = runTest {
        var sends = 0
        var failures = 0
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source -> if (source == "bad") error("provider") else source },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        coordinator.start("a", listOf("good", "bad"), onComplete = { sends++ }, onFailure = { failures++ }); runCurrent()
        assertEquals(0, sends)
        assertEquals(1, failures)
        assertEquals(listOf("good"), coordinator.state.value.getValue("a").items.map { it.source })
    }
    @Test fun `picker result from previous account is rejected before opening source`() = runTest {
        var epoch = 1
        var copies = 0
        val coordinator = MediaPreparationCoordinator(backgroundScope, { epoch }, { _, file -> copies++; file },
            { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
        epoch = 2
        assertFalse(coordinator.start("old-chat", listOf("old-file"), expectedEpoch = 1))
        runCurrent()
        assertEquals(0, copies)
        assertTrue(coordinator.state.value.isEmpty())
    }
    @Test fun `delayed session event must not cancel new account import`() = runTest {
        var epoch = 1
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { epoch }, { _, file -> gate.await(); file },
            { _, _, file, _ -> saved += file }, { _, _, _ -> }, { _, _ -> })
        epoch = 2
        coordinator.start("new-chat", listOf("new-account-file")); runCurrent()
        coordinator.invalidateSession()
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("new-account-file"), saved)
    }
    @Test fun `cancel before dispatcher starts cannot leave a permanent busy flag`() = runTest {
        val saved = mutableListOf<String>()
        var cleanups = 0
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, file -> file },
            { _, _, file, _ -> saved += file }, { _, _, _ -> }, { _, _ -> })
        coordinator.start("chat", listOf("old"), cleanup = { cleanups++ })
        coordinator.cancel("chat")
        runCurrent()
        assertFalse(coordinator.state.value.getValue("chat").active)
        assertEquals(1, cleanups)
        assertTrue(coordinator.start("chat", listOf("new")))
        runCurrent(); assertEquals(listOf("new"), saved)
    }

    @Test fun `marker storage failure must unlock and never copy source`() = runTest {
        var copies = 0
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, file -> copies++; file },
            { _, _, _, _ -> }, { _, _, _ -> error("storage unavailable") }, { _, _ -> })
        coordinator.start("chat", listOf("video")); runCurrent()
        assertEquals(0, copies)
        assertFalse(coordinator.state.value.getValue("chat").active)
        assertTrue(coordinator.state.value.getValue("chat").failed)
    }

    @Test fun `one blocked chat does not block another and cancellation retains ready items`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<Pair<String, String>>()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, file ->
            if (file == "blocked") gate.await()
            file
        }, { _, chat, file, _ -> saved += chat to file }, { _, _, _ -> }, { _, _ -> })
        coordinator.start("a", listOf("ready", "blocked")); runCurrent()
        coordinator.start("b", listOf("other")); runCurrent()
        coordinator.cancel("a"); runCurrent()
        assertEquals(listOf("a" to "ready", "b" to "other"), saved)
        assertTrue(coordinator.state.value.values.none { it.active })
    }

    @Test fun `navigation does not own import and partial failure retains completed files`() = runTest {
        val saved = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var pending = false
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source ->
            if (source == "second") { gate.await(); error("provider failed") }
            source
        }, { _, _, file, _ -> saved += file }, { _, _, active -> pending = active }, { _, _ -> })
        coordinator.start("chat", listOf("first", "second"))
        runCurrent()
        assertEquals(listOf("first"), saved)
        assertTrue(pending)
        // No screen/VM scope participates; it can be destroyed while the provider waits.
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("first"), saved)
        assertFalse(pending)
        assertFalse(coordinator.state.value.getValue("chat").active)
        assertTrue(coordinator.state.value.getValue("chat").failed)
        assertTrue(coordinator.start("chat", listOf("third")))
        runCurrent(); assertEquals(listOf("first", "third"), saved)
    }

    @Test fun `cancellation releases ownership and allows a new import`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, source ->
            if (source == "old") gate.await()
            source
        }, { _, _, file, _ -> saved += file }, { _, _, _ -> }, { _, _ -> })
        coordinator.start("chat", listOf("old")); runCurrent()
        assertFalse(coordinator.start("chat", listOf("duplicate")))
        coordinator.cancel("chat"); runCurrent()
        assertFalse(coordinator.state.value.getValue("chat").active)
        assertTrue(coordinator.start("chat", listOf("new"))); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("new"), saved)
    }

    @Test fun `old account provider completion cannot write into new account`() = runTest {
        var epoch = 1
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { epoch }, { _, source ->
            // Some content providers ignore cancellation until a copy completes.
            withContext(NonCancellable) { gate.await() }
            source
        }, { _, _, file, _ -> saved += file }, { _, _, _ -> }, { _, file -> discarded += file })
        coordinator.start("chat", listOf("private")); runCurrent()
        epoch = 2; coordinator.invalidateSession(); gate.complete(Unit); runCurrent()
        assertTrue(saved.isEmpty())
        assertTrue(coordinator.state.value.isEmpty())
        assertEquals(listOf("private"), discarded)
    }

    @Test fun `failed persistence discards unowned file and unlocks importer`() = runTest {
        val discarded = mutableListOf<String>()
        val coordinator = MediaPreparationCoordinator(backgroundScope, { 1 }, { _, file -> file },
            { _, _, _, _ -> error("disk full") }, { _, _, _ -> }, { _, file -> discarded += file })
        coordinator.start("chat", listOf("video")); runCurrent()
        assertEquals(listOf("video"), discarded)
        assertFalse(coordinator.state.value.getValue("chat").active)
        assertTrue(coordinator.state.value.getValue("chat").failed)
    }
}
