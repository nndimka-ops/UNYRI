package com.messenger.app.ui.components

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HeaderPollingCancellationTest {
    @Test fun `RTT has no requests without opt in a connection and visibility`() = runTest {
        var requests = 0
        for ((enabled, connected, active) in listOf(Triple(false, true, true), Triple(true, false, true), Triple(true, true, false))) {
            sampleHeaderPing(HeaderPingState(), enabled, connected, active) { requests++; 10L }
        }
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, requests)
    }
    @Test fun `entry samples once idle never polls and resume or reconnect samples again`() = runTest {
        val state = HeaderPingState()
        var requests = 0
        suspend fun sample(active: Boolean = true, connected: Boolean = true) =
            sampleHeaderPing(state, true, connected, active) { requests++; 96L }
        sample()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, requests)
        sample(active = false)
        assertEquals(96L, state.value)
        sample()
        assertEquals(2, requests)
        sample(connected = false)
        assertNull(state.value)
        sample()
        assertEquals(3, requests)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(3, requests)
    }
    @Test fun `a failed sample does not install an idle retry`() = runTest {
        val state = HeaderPingState()
        var requests = 0
        sampleHeaderPing(state, true, true, true) { requests++; null }
        advanceTimeBy(120_000); runCurrent()
        assertNull(state.value)
        assertEquals(1, requests)
    }
    @Test fun `cancelling a pending measurement cannot publish its late result`() = runTest {
        val state = HeaderPingState()
        val initial = state.start(true, true, true)!!
        state.accept(initial, 44)
        val started = CompletableDeferred<Unit>()
        val job = launch { sampleHeaderPing(state, true, true, true) {
            started.complete(Unit)
            withContext(NonCancellable) { delay(100); 99L }
        } }
        started.await()
        job.cancel(); advanceUntilIdle(); job.join()
        assertEquals(44L, state.value)
    }
}
