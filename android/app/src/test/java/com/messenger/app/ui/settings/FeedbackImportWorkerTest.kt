package com.messenger.app.ui.settings

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FeedbackImportWorkerTest {
    @Test fun blockedProviderTimesOutWithoutJoiningAndCannotAccumulateWorkers() = runBlocking {
        val worker = FeedbackImportWorker()
        val release = CountDownLatch(1)
        val closeRequested = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()
        var delivered = false
        val request = async {
            worker.run(200) { cancellation ->
                cancellation.register { closeRequested.countDown() }
                entered.complete(Unit)
                release.await(5, TimeUnit.SECONDS) // Provider intentionally ignores close.
                "late image"
            }.also { delivered = true }
        }
        try {
            withTimeout(2_000) { entered.await() }
            try { withTimeout(2_000) { request.await() }; fail("Expected provider timeout") }
            catch (_: TimeoutCancellationException) { }
            assertTrue(closeRequested.await(1, TimeUnit.SECONDS))
            repeat(20) {
                try { worker.run { fail("Must not schedule more blocked providers") }; fail("Expected occupied slot") }
                catch (_: IllegalStateException) { }
            }
            assertFalse(delivered)
        } finally { release.countDown() }
        withTimeout(2_000) {
            while (true) {
                try { assertEquals("new", worker.run { "new" }); break }
                catch (_: IllegalStateException) { delay(10) }
            }
        }
        assertFalse("Late timed-out response must not reach UI", delivered)
    }

    @Test fun dismissCancellationRequestsCloseAndDropsLateResult() = runBlocking {
        val worker = FeedbackImportWorker()
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()
        var delivered = false
        val request = launch {
            worker.run {
                it.register { closed.countDown() }
                entered.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
                "old account image"
            }
            delivered = true
        }
        try {
            withTimeout(2_000) { entered.await() }
            withTimeout(2_000) { request.cancelAndJoin() }
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            assertFalse(delivered)
        } finally { release.countDown() }
    }

    @Test fun completedImportsDoNotRunTheirCancellationClosers() = runBlocking {
        val worker = FeedbackImportWorker()
        var closes = 0
        repeat(3) { index ->
            assertEquals(index, worker.run { cancellation ->
                val detach = cancellation.register { closes++ }
                detach()
                index
            })
        }
        assertEquals(0, closes)
    }
}
