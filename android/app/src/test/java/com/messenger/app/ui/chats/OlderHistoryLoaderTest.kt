package com.messenger.app.ui.chats

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OlderHistoryLoaderTest {
    @Test fun endOfHistoryStopsFurtherRequests() = runBlocking {
        val gate = OlderHistoryLoader()
        var calls = 0
        repeat(3) { gate.load { calls++; Result.success(false) } }
        assertEquals(1, calls)
    }
    @Test fun failuresAndMorePagesRemainRetryable() = runBlocking {
        val gate = OlderHistoryLoader()
        var calls = 0
        gate.load { calls++; Result.failure(IllegalStateException("offline")) }
        gate.load { calls++; Result.success(true) }
        gate.load { calls++; Result.success(false) }
        assertEquals(3, calls)
    }
    @Test fun concurrentRequestIsSuppressed() = runBlocking {
        val gate = OlderHistoryLoader()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch { gate.load { calls++; entered.complete(Unit); release.await(); Result.success(true) } }
        entered.await()
        gate.load { calls++; Result.success(true) }
        release.complete(Unit)
        job.join()
        assertEquals(1, calls)
    }
    @Test fun cancellationDoesNotLeaveLoadingStuck() = runBlocking {
        val gate = OlderHistoryLoader()
        try { gate.load { throw CancellationException("cancelled") } } catch (_: CancellationException) { }
        var calls = 0
        gate.load { calls++; Result.success(true) }
        assertEquals(1, calls)
    }
}
