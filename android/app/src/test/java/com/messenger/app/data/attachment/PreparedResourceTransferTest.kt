package com.messenger.app.data.attachment

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedResourceTransferTest {
    @Test fun `cancellation on return dispatch releases unpublished resource`() = runTest {
        val worker = StandardTestDispatcher(testScheduler, "worker")
        var released: String? = null
        var received: String? = null
        lateinit var job: Job
        job = launch {
            received = transferPreparedResource(worker, create = {
                job.cancel()
                "copied-video"
            }, discard = { released = it })
        }
        runCurrent()
        assertNull(received)
        assertEquals("copied-video", released)
    }

    @Test fun `successful handoff keeps source for durable draft ownership`() = runTest {
        var released = false
        val value = transferPreparedResource(StandardTestDispatcher(testScheduler), { "draft" }, { released = true })
        assertEquals("draft", value)
        assertFalse(released)
    }

    @Test fun `creation failure is preserved without discarding an absent resource`() = runTest {
        var discarded = false
        val failure = java.io.IOException("source missing")
        val thrown = assertFailsWith<java.io.IOException> {
            transferPreparedResource<String>(StandardTestDispatcher(testScheduler), { throw failure }, { discarded = true })
        }
        // Coroutine stack-trace recovery may copy an IOException instance.
        assertEquals(failure.message, thrown.message)
        assertFalse(discarded)
    }
}
