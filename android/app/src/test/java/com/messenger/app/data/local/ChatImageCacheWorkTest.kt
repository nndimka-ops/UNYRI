package com.messenger.app.data.local

import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ChatImageCacheWorkTest {
    @Test fun `only two previews decode concurrently`() = runBlocking {
        ChatImageCache.clear()
        val active = AtomicInteger(); val peak = AtomicInteger()
        val started = CountDownLatch(2); val release = CountDownLatch(1)
        val jobs = (0..5).map { index -> async(Dispatchers.Default) {
            ChatImageCache.load("preview-$index") {
                val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
                started.countDown()
                try { check(release.await(5, TimeUnit.SECONDS)); null }
                finally { active.decrementAndGet() }
            }
        } }
        try { assertTrue(started.await(5, TimeUnit.SECONDS)); assertEquals(2, active.get()) }
        finally { release.countDown() }
        jobs.awaitAll()
        assertEquals(2, peak.get())
    }

    @Test fun `clearing cache prevents queued old work from decoding`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val count = AtomicInteger()
        val first = async(Dispatchers.Default) { ChatImageCache.load("shared") {
            count.incrementAndGet(); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)); null
        } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { ChatImageCache.load("shared") {
            count.incrementAndGet(); null
        } }
        ChatImageCache.clear()
        release.countDown()
        first.await(); waiting.await()
        assertEquals(1, count.get())
    }
}
