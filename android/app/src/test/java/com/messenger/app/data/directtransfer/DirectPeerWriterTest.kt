package com.messenger.app.data.directtransfer

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DirectPeerWriterTest {
    @Test fun revokedGrantStopsBothBackpressuredAndMutexWaitingFramesBeforeNativeEnqueue() = runTest {
        val writer = DirectPeerWriter()
        val buffered = AtomicLong(DirectPeerPolicy.HIGH_WATER)
        val allowed = AtomicBoolean(true)
        val entered = CompletableDeferred<Unit>()
        var nativeSends = 0
        suspend fun send() = runCatching {
            writer.send(16384, checkCurrent = {
                entered.complete(Unit)
                if (!allowed.get()) throw DirectOperationRetired()
            }, buffered = buffered::get, enqueue = { nativeSends++ })
        }.exceptionOrNull()
        val first = async { send() }
        entered.await()
        val waitingForWriter = async { send() }
        runCurrent()
        allowed.set(false); buffered.set(0)
        advanceTimeBy(20); runCurrent()
        assertIs<DirectOperationRetired>(first.await())
        assertIs<DirectOperationRetired>(waitingForWriter.await())
        assertEquals(0, nativeSends)
        // Another independently owned role is not globally blocked by this writer's stop.
        DirectPeerWriter().send(16384, {}, { 0 }, { nativeSends++ })
        assertEquals(1, nativeSends)
    }

    @Test fun homeBeforeGrantRechecksForegroundAfterBufferWait() = runTest {
        val writer = DirectPeerWriter()
        var foreground = true
        var buffered = DirectPeerPolicy.HIGH_WATER
        var nativeSends = 0
        val session = DirectExecutionSession("10000000-0000-4000-8000-000000000010", 5)
        val frame = async { runCatching {
            writer.send(16384, checkCurrent = {
                if (!DirectExecutionPolicy.mayRun(session.id, false, foreground, session, 5)) throw DirectOperationRetired()
            }, buffered = { buffered }, enqueue = { nativeSends++ })
        }.exceptionOrNull() }
        runCurrent()
        foreground = false; buffered = 0
        advanceTimeBy(20); runCurrent()
        assertIs<DirectOperationRetired>(frame.await()); assertEquals(0, nativeSends)
    }
}
