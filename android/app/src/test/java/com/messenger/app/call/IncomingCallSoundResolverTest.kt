package com.messenger.app.call

import com.messenger.app.domain.call.CallRingConfirmationPolicy
import com.messenger.app.domain.call.CallRingtonePolicy
import java.util.concurrent.Executors
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*

class IncomingCallSoundResolverTest {
    @Test fun `provider lookup runs off the presentation thread and is cached within the call`() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CompletableDeferred<Thread>()
        val release = CompletableDeferred<Unit>()
        val ready = Channel<Unit>(Channel.UNLIMITED)
        val presentationThread = Thread.currentThread()
        var lookups = 0
        val cache = IncomingCallSoundResolver<String, String>(this, dispatcher, resolve = {
            lookups++
            entered.complete(Thread.currentThread())
            release.await()
            "actual-ringtone"
        }, onReady = {
            assertEquals(presentationThread, Thread.currentThread())
            ready.trySend(Unit)
        })
        try {
            withTimeout(5_000) {
                assertNull(cache.soundFor("session/call", "symbolic-default"), "presentation proceeds before provider returns")
                assertNotEquals(presentationThread, entered.await())
                assertNull(cache.soundFor("session/call", "symbolic-default"))
                assertEquals(1, lookups)
                release.complete(Unit)
                ready.receive()
                assertEquals("actual-ringtone", cache.soundFor("session/call", "symbolic-default"))
                assertEquals(1, lookups, "posting/refreshing does not repeat provider I/O")
            }
        } finally { cache.invalidate(); dispatcher.close(); ready.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `late provider result cannot acknowledge a replaced call or account`() = runTest {
        val oldProvider = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String?>()
        lateinit var cache: IncomingCallSoundResolver<String, String>
        cache = IncomingCallSoundResolver(backgroundScope, StandardTestDispatcher(testScheduler), resolve = {
            if (it == "old") withContext(NonCancellable) { oldProvider.await() }
            it
        }, onReady = { delivered += cache.soundFor("new-session/new-call", "new") })
        cache.soundFor("old-session/old-call", "old"); runCurrent()
        cache.soundFor("new-session/new-call", "new"); runCurrent()
        assertEquals(listOf<String?>("new"), delivered)
        oldProvider.complete(Unit); runCurrent()
        assertEquals(listOf<String?>("new"), delivered)
        assertEquals("new", cache.soundFor("new-session/new-call", "new"))
        cache.invalidate()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `settings invalidation resolves silent default again without treating pending sound as audible`() = runTest {
        var selectedDefault: String? = "audible"
        var lookups = 0
        var completions = 0
        val cache = IncomingCallSoundResolver<String, String>(backgroundScope, StandardTestDispatcher(testScheduler), resolve = {
            CallRingtonePolicy.resolve(it, { 1 }, { lookups++; selectedDefault })
        }, onReady = { completions++ })
        assertNull(cache.soundFor("call", "symbolic-default")); runCurrent()
        assertEquals("audible", cache.soundFor("call", "symbolic-default"))
        selectedDefault = null // Settings content observer invalidates the same symbolic URI.
        cache.invalidate()
        assertNull(cache.soundFor("call", "symbolic-default")); runCurrent()
        val soundAllowed = cache.soundFor("call", "symbolic-default") != null
        assertFalse(CallRingConfirmationPolicy.wouldAlert(true, true, 4, soundAllowed, false, true))
        assertTrue(CallRingConfirmationPolicy.wouldAlert(true, true, 4, soundAllowed, true, true))
        assertEquals(2, lookups)
        assertEquals(2, completions)
        cache.invalidate()
    }
}
