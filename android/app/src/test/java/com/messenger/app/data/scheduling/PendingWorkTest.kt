package com.messenger.app.data.scheduling

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PendingWorkTest {
    @Test fun `empty queue sleeps indefinitely and drain cancels the pending retry`() = runTest {
        val pending = MutableStateFlow(false)
        var attempts = 0
        backgroundScope.launch { retryPendingWork(pending) { attempts++ } }
        runCurrent(); advanceTimeBy(120_000); runCurrent(); assertEquals(0, attempts)
        pending.value = true; runCurrent(); assertEquals(1, attempts)
        advanceTimeBy(14_999); runCurrent(); assertEquals(1, attempts)
        advanceTimeBy(1); runCurrent(); assertEquals(2, attempts)
        pending.value = false; runCurrent()
        advanceTimeBy(120_000); runCurrent(); assertEquals(2, attempts)
        pending.value = true; runCurrent(); assertEquals(3, attempts)
    }
}
