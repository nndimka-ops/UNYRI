package com.messenger.app.push

import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstallationTokenRecoveryTest {
    @Test fun `superseded acquisition finishes exactly one reset and durable acknowledgement`() = runTest {
        var inspected = false
        var resets = 0
        val gate = CompletableDeferred<Unit>()
        val recovery = InstallationTokenRecovery({ inspected }, { true }, { resets++; gate.await() },
            { inspected = true }, StandardTestDispatcher(testScheduler))
        val first = launch { recovery.beforeToken() }
        runCurrent()
        first.cancel()
        val replacement = launch { recovery.beforeToken() }
        runCurrent()
        assertEquals(1, resets)
        gate.complete(Unit)
        first.join(); replacement.join()
        assertTrue(inspected)
        assertFalse(recovery.beforeToken())
        assertEquals(1, resets)
    }

    @Test fun `failed reset remains pending for retry`() = runTest {
        var inspected = false
        var resets = 0
        val recovery = InstallationTokenRecovery({ inspected }, { true }, { if (++resets == 1) error("offline") },
            { inspected = true }, StandardTestDispatcher(testScheduler))
        try { recovery.beforeToken(); fail("Failure must leave repair pending") } catch (_: IllegalStateException) { }
        assertFalse(inspected)
        assertTrue(recovery.beforeToken())
        assertTrue(inspected)
        assertEquals(2, resets)
    }

    @Test fun `unaffected inspection is acknowledged once across process reconstruction`() = runTest {
        var inspected = false
        var scans = 0
        var acknowledgements = 0
        fun recovery() = InstallationTokenRecovery({ inspected }, { scans++; false }, { error("must not rotate") },
            { inspected = true; acknowledgements++ }, StandardTestDispatcher(testScheduler))
        assertFalse(recovery().beforeToken())
        assertFalse(recovery().beforeToken())
        assertEquals(1, scans)
        assertEquals(1, acknowledgements)
    }

    @Test fun `a durable binding retry must exist before deleting any SDK token`() = runTest {
        var scheduled = false
        var inspected = false
        var resets = 0
        val recovery = InstallationTokenRecovery({ inspected }, { true }, { check(scheduled); resets++ },
            { inspected = true }, StandardTestDispatcher(testScheduler), prepareReset = { check(scheduled) })
        try { recovery.beforeToken(); fail("No destructive reset without retry work") } catch (_: IllegalStateException) { }
        assertEquals(0, resets); assertFalse(inspected)
        scheduled = true
        assertTrue(recovery.beforeToken())
        assertEquals(1, resets); assertTrue(inspected)
    }

    @Test fun `inspection preparation reset and commit all execute on the IO dispatcher`() = runBlocking {
        val caller = Thread.currentThread()
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "repair-storage-io") }.asCoroutineDispatcher().use { io ->
            val operations = mutableListOf<String>()
            fun record(name: String) {
                assertNotSame(caller, Thread.currentThread())
                assertTrue(Thread.currentThread().name.startsWith("repair-storage-io"))
                operations += name
            }
            val recovery = InstallationTokenRecovery({ record("checked"); false }, { record("scan"); true },
                { record("reset") }, { record("commit") }, io, { record("persist-retry") })
            assertTrue(recovery.beforeToken())
            assertEquals(listOf("checked", "scan", "persist-retry", "reset", "commit"), operations)
        }
    }
}
