package com.messenger.app.push

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedAccountPushBindingsTest {
    private val a = SavedPushOwner(SavedPushAccount("legacy", "https://a.example"), "alice", "a-device")
    private val b = SavedPushOwner(SavedPushAccount("workspace-b", "https://b.example"), "bob", "b-device")

    @Test fun `repair migrates inactive A and active B independently and retries only unacknowledged owners after restart`() = runTest {
        val acks = mutableSetOf<Pair<SavedPushOwner, String>>()
        val requests = mutableListOf<SavedPushOwner>()
        var offlineA = true
        fun bindings() = SavedAccountPushBindings({ listOf(a.account, b.account) }, { if (it == a.account) a else b },
            { owner, token -> owner to token in acks }, { owner, _ -> requests += owner; if (owner == a && offlineA) error("offline A") },
            { true }, { owner, token -> acks += owner to token })
        assertFalse(bindings().reconcile("replacement"))
        assertEquals(listOf(a, b), requests)
        assertEquals(setOf(b to "replacement"), acks)
        offlineA = false; requests.clear()
        assertTrue(bindings().reconcile("replacement"))
        assertEquals(listOf(a), requests)
        requests.clear()
        assertTrue(bindings().reconcile("next-token"))
        assertEquals(listOf(a, b), requests)
    }

    @Test fun `inaccessible saved storage is retryable without blocking the other account`() = runTest {
        val sent = mutableListOf<SavedPushOwner>()
        val bindings = SavedAccountPushBindings({ listOf(a.account, b.account) }, {
            if (it == a.account) error("keystore unavailable") else b
        }, { _, _ -> false }, { owner, _ -> sent += owner }, { true }, { _, _ -> })
        assertFalse(bindings.reconcile("token"))
        assertEquals(listOf(b), sent)
    }

    @Test fun `changed owner cannot acknowledge another sessions registration`() = runTest {
        var current = true
        var acknowledged = false
        val bindings = SavedAccountPushBindings({ listOf(a.account) }, { a }, { _, _ -> false },
            { _, _ -> current = false }, { current }, { _, _ -> acknowledged = true })
        assertFalse(bindings.reconcile("token"))
        assertFalse(acknowledged)
    }

    @Test fun `a new login on the same device cannot reuse the old sessions acknowledgement`() = runTest {
        var current = a
        val acks = mutableSetOf<Pair<SavedPushOwner, String>>()
        val sent = mutableListOf<SavedPushOwner>()
        fun bindings() = SavedAccountPushBindings({ listOf(a.account) }, { current },
            { owner, token -> owner to token in acks }, { owner, _ -> sent += owner },
            { it == current }, { owner, token -> acks += owner to token })
        assertTrue(bindings().reconcile("same-token"))
        current = a.copy(generation = "new-login")
        assertTrue(bindings().reconcile("same-token"))
        assertEquals(listOf(a, current), sent)
    }

    @Test fun `replacement waits for bounded retired-token write and then installs newest binding`() = runTest {
        val mutex = Mutex()
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        fun bindings() = SavedAccountPushBindings({ listOf(a.account) }, { a }, { _, _ -> false },
            { _, token -> if (token == "old") gate.await(); writes += token }, { true }, { _, _ -> }, mutex)
        val old = launch { bindings().reconcile("old") }
        runCurrent(); old.cancel()
        val replacement = launch { bindings().reconcile("new") }
        runCurrent(); assertTrue(writes.isEmpty())
        gate.complete(Unit)
        old.join(); replacement.join()
        assertEquals(listOf("old", "new"), writes)
    }
}
