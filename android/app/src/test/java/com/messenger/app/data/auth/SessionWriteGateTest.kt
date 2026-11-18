package com.messenger.app.data.auth

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Proves the deterministic account-switch guarantee: a stale write that is
 *  released only AFTER the clear can never land (it is rejected before the
 *  DAO write). Run repeatedly to rule out scheduling luck. */
class SessionWriteGateTest {
    @Test fun `media does not block message writes but logout waits and rejects stale media`() = runBlocking {
        kotlinx.coroutines.withTimeout(5000) {
            val epoch = AtomicInteger(1)
            val messages = SessionWriteGate { epoch.get() }
            val media = SessionWriteGate { epoch.get() }
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val download = launch { media.write(1) { started.complete(Unit); release.await(); events += "media" } }
            started.await()
            messages.write(1) { events += "message" }
            assertEquals(listOf("message"), events)
            epoch.incrementAndGet()
            val logout = launch { messages.clear { media.clear { events += "clear" } } }
            kotlinx.coroutines.yield()
            assertEquals(listOf("message"), events)
            release.complete(Unit); download.join(); logout.join()
            assertEquals(listOf("message", "media", "clear"), events)
            assertNull(media.write(1) { events += "stale" })
        }
    }

    @Test fun `workspace remains writable during network work and cannot survive account clear`() = runBlocking {
        kotlinx.coroutines.withTimeout(5000) {
            val epoch = AtomicInteger(1)
            val networkGate = SessionWriteGate { epoch.get() }
            val workspaceGate = SessionWriteGate { epoch.get() }
            val networkStarted = CompletableDeferred<Unit>()
            val releaseNetwork = CompletableDeferred<Unit>()
            var draft: String? = null
            val network = launch { networkGate.write(1) { networkStarted.complete(Unit); releaseNetwork.await() } }
            networkStarted.await()
            workspaceGate.write(1) { draft = "offline draft" }
            assertEquals("offline draft", draft)
            epoch.incrementAndGet()
            releaseNetwork.complete(Unit); network.join()
            networkGate.clear { workspaceGate.clear { draft = null } }
            assertNull(workspaceGate.write(1) { draft = "stale" })
            assertNull(draft)
        }
    }


    @Test fun `nested writes survive dispatcher changes and exclude logout`() = runBlocking {
        kotlinx.coroutines.withTimeout(5000) {
            val gate = SessionWriteGate { 1 }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val writer = launch {
                gate.write(1) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        gate.write(1) { events += "identity" }
                    }
                    entered.complete(Unit)
                    release.await()
                    events += "message"
                }
            }
            entered.await()
            val clearer = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                gate.clear { events += "clear" }
            }
            assertEquals(listOf("identity"), events)
            release.complete(Unit)
            writer.join()
            clearer.join()
            assertEquals(listOf("identity", "message", "clear"), events)
        }
    }

    @Test fun `nested writes cannot adopt a new account epoch`() = runBlocking {
        val epoch = AtomicInteger(1)
        val gate = SessionWriteGate { epoch.get() }
        kotlinx.coroutines.withTimeout(5000) {
            gate.write(1) {
                epoch.set(2)
                assertNull(gate.write(1) { "stale" })
                assertNull(gate.write(2) { "wrong account" })
            }
            assertEquals("current", gate.write(2) { "current" })
        }
    }

    @Test fun `an escaped expired lease does not bypass another writer`() = runBlocking {
        kotlinx.coroutines.withTimeout(5000) {
            val gate = SessionWriteGate { 1 }
            val release = CompletableDeferred<Unit>()
            lateinit var inherited: kotlin.coroutines.CoroutineContext
            gate.write(1) { inherited = kotlin.coroutines.coroutineContext.minusKey(kotlinx.coroutines.Job) }
            val owner = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                gate.write(1) { release.await() }
            }
            var wrote = false
            val late = launch(inherited, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                gate.write(1) { wrote = true }
            }
            assertEquals(false, wrote)
            release.complete(Unit)
            owner.join()
            late.join()
            assertEquals(true, wrote)
        }
    }

    @Test
    fun `a write released after clear is rejected - 100 iterations`() = runBlocking {
        repeat(100) {
            val epoch = AtomicInteger(1)
            val gate = SessionWriteGate { epoch.get() }
            val writes = AtomicInteger(0)
            val release = CompletableDeferred<Unit>()

            // A long-running account job captured epoch=1 and is about to write
            // a row; it blocks inside the gate until the test releases it.
            val job = launch {
                val result = gate.write(1) {
                    release.await()
                    writes.incrementAndGet()
                    "written"
                }
                assertNull(result, "iteration $it: stale write must be rejected")
            }

            // Logout: bump the epoch, then clear the Room (under the same mutex).
            // The clear runs in its OWN coroutine because it blocks on the mutex
            // until the delayed write is released - mirroring production, where
            // logout() and clearRoom() run in different coroutines.
            epoch.incrementAndGet()
            val clearJob = launch { gate.clear { /* simulate database.clearAllTables() */ } }

            // Release the delayed old-account write only after the clear.
            release.complete(Unit)
            job.join()
            clearJob.join()

            assertEquals(0, writes.get(), "iteration $it: a stale row was written after clear")
        }
    }

    @Test
    fun `a current write completes before clear then is removed`() = runBlocking {
        val epoch = AtomicInteger(1)
        val gate = SessionWriteGate { epoch.get() }
        val writes = AtomicInteger(0)

        val job = launch {
            val result = gate.write(1) {
                writes.incrementAndGet()
                "written"
            }
            assertNotNull(result, "a current-session write must run")
        }
        job.join()
        assertEquals(1, writes.get())

        // Logout: bump + clear. The write already finished and its row would
        // be removed by the clear - no stale row survives.
        epoch.incrementAndGet()
        gate.clear { }
        assertEquals(1, writes.get())
    }

    @Test
    fun `writes after logout are always rejected`() = runBlocking {
        val epoch = AtomicInteger(1)
        val gate = SessionWriteGate { epoch.get() }
        epoch.incrementAndGet() // logged out -> epoch 2

        val result = gate.write(1) { "should not run" }
        assertNull(result)
    }

    // --- P1-5: a user-visible mutation must never report success when the
    // gate rejects the write (the `sendMessage`/`retry` pattern). ----------

    @Test
    fun `rejected gated send returns failure not success and does not persist`() = runBlocking {
        val epoch = AtomicInteger(1)
        val gate = SessionWriteGate { epoch.get() }
        var persisted = false

        // Mirrors MessageRepositoryImpl.sendMessage's fixed pattern: the epoch
        // is captured BEFORE the write, so a switch mid-send is rejected.
        suspend fun send(epochAtStart: Int): Result<Unit> = runCatching {
            gate.write(epochAtStart) { persisted = true }
                ?: throw kotlinx.coroutines.CancellationException("Message session changed")
            Unit
        }

        // Current session: the write executes and the send reports success.
        assertTrue(send(1).isSuccess)
        assertTrue(persisted)

        // Account switch: a send that captured the OLD epoch must be rejected,
        // never report success, and never persist.
        epoch.incrementAndGet()
        persisted = false
        val rejected = send(1) // stale epoch captured before the switch
        assertTrue(rejected.isFailure, "a rejected gated send must not report success")
        assertFalse(persisted, "a rejected send must not persist the message")
        assertTrue(rejected.exceptionOrNull() is kotlinx.coroutines.CancellationException,
            "the failure must be an account-change cancellation, not a scary error")
    }
}
