package com.messenger.app.ui.chats

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmojiRecentsStoreTest {
    private class Storage : EmojiRecentsStorage {
        val records = mutableMapOf<String, EmojiSaved>()
        val reads = mutableListOf<EmojiOwner>()
        val writes = mutableListOf<EmojiOwner>()
        var gate: CompletableDeferred<Unit>? = null
        var finishReadAfterCancellation = false
        override suspend fun read(owner: EmojiOwner): EmojiSaved {
            reads += owner
            if (finishReadAfterCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
            return records[owner.storageKey] ?: EmojiSaved()
        }
        override suspend fun write(owner: EmojiOwner, saved: EmojiSaved) {
            writes += owner; records[owner.storageKey] = saved
        }
    }
    private val alice = EmojiOwner("workspace", "https://one.example", "alice", 1)

    @Test fun constructionAndCallbacksNeverPerformSynchronousStorageWork() = runTest {
        val storage = Storage()
        val store = EmojiRecentsStore(alice, storage, backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
        store.use("😀"); store.rememberHeight(180f)
        assertTrue(storage.reads.isEmpty()); assertTrue(storage.writes.isEmpty())
        assertFalse(store.state.value.loaded)
        runCurrent()
        assertEquals(listOf("😀"), store.state.value.saved.recents)
        assertEquals(180f, storage.records[alice.storageKey]!!.keyboardHeightDp)
        assertTrue(store.state.value.loaded)
    }

    @Test fun selectionWhileColdLoadIsPendingMergesWithSavedRecentsInOrder() = runTest {
        val storage = Storage().apply {
            records[alice.storageKey] = EmojiSaved(listOf("😂", "🇺🇦"), 180f)
            gate = CompletableDeferred()
        }
        val store = EmojiRecentsStore(alice, storage, backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        store.use("👩🏽‍💻"); store.use("😂")
        assertFalse(store.state.value.loaded)
        storage.gate!!.complete(Unit); runCurrent()
        assertEquals(listOf("😂", "👩🏽‍💻", "🇺🇦"), store.state.value.saved.recents)
        assertEquals(listOf("😂", "🇺🇦"), store.state.value.initialRecents)
        assertEquals(store.state.value.saved, storage.records[alice.storageKey])
    }

    @Test fun serverAccountAndWorkspaceRemainSeparateAcrossRecreation() = runTest {
        val storage = Storage()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val identities = listOf(alice, alice.copy(userId = "bob"), alice.copy(origin = "https://two.example"),
            alice.copy(workspace = "other-workspace"))
        val choices = listOf("😀", "❤️", "🇺🇦", "👩🏽‍💻")
        identities.forEachIndexed { index, owner ->
            val store = EmojiRecentsStore(owner, storage, backgroundScope, dispatcher = dispatcher)
            store.use(choices[index]); runCurrent(); store.close()
        }
        identities.forEachIndexed { index, owner ->
            val recreated = EmojiRecentsStore(owner.copy(epoch = 77), storage, backgroundScope, dispatcher = dispatcher)
            runCurrent()
            assertEquals(listOf(choices[index]), recreated.state.value.saved.recents)
            recreated.close()
        }
        assertEquals(4, storage.records.size)
    }

    @Test fun logoutClearsObservableStateAndRejectsRetainedOldCallbacks() = runTest {
        val storage = Storage()
        val owners = MutableStateFlow<EmojiOwner?>(alice)
        val registry = EmojiRecentsRegistry(owners, { owners.value }, storage, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        val old = registry.stores.value
        old.use("😀"); runCurrent()
        owners.value = null
        assertFalse(old.acceptsCurrentOwner) // Render/callback guard also covers collector scheduling latency.
        runCurrent()
        assertEquals(emptyList<String>(), old.state.value.saved.recents)
        old.use("😡"); old.rememberHeight(333f)
        owners.value = alice.copy(userId = "bob", epoch = 2); runCurrent()
        assertTrue(registry.stores.value.state.value.saved.recents.isEmpty())
        assertEquals(listOf("😀"), storage.records[alice.storageKey]!!.recents)
        assertEquals(1, storage.writes.size)
    }

    @Test fun lateOldLoadCannotPublishIntoNewOwnerAndSameAccountLoginGetsFreshState() = runTest {
        val storage = Storage().apply { gate = CompletableDeferred(); finishReadAfterCancellation = true }
        val owners = MutableStateFlow<EmojiOwner?>(alice)
        val registry = EmojiRecentsRegistry(owners, { owners.value }, storage, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        val old = registry.stores.value
        owners.value = alice.copy(epoch = 2); runCurrent()
        val replacement = registry.stores.value
        assertNotSame(old, replacement)
        storage.gate!!.complete(Unit); runCurrent()
        old.use("😡"); replacement.use("🇺🇦"); runCurrent()
        assertFalse(old.state.value.loaded)
        assertEquals(listOf("🇺🇦"), replacement.state.value.saved.recents)
        assertEquals(listOf(alice.copy(epoch = 2)), storage.writes)
    }
}
