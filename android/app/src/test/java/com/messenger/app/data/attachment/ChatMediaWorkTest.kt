package com.messenger.app.data.attachment

import kotlin.test.*
import kotlinx.coroutines.*

class ChatMediaWorkTest {
    @Test fun deletionCancelsOnlyItsChatAndRejectsLateWork() = runBlocking {
        val registry = ChatMediaWork()
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val work = async { registry.run(1, "deleted", { true }) {
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        } }
        started.await()
        registry.close(1, listOf("deleted"))
        assertTrue(stopped.isCompleted)
        assertFailsWith<CancellationException> { work.await() }
        assertFailsWith<CancellationException> { registry.run(1, "deleted", { true }) { error("late work") } }
        assertEquals("ok", registry.run(1, "retained", { true }) { "ok" })
        assertEquals("new account", registry.run(2, "deleted", { true }) { "new account" })
    }
}
