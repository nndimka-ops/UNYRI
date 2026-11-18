package com.messenger.app.push

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPostTransactionTest {
    @Test fun `concurrent duplicates wait until post and persistence finish`() = runTest {
        val rows = mutableMapOf<String, List<String>>()
        val events = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val tx = NotificationPostTransaction({ rows[it].orEmpty() }, { owner, ids -> events += "save"; rows[owner] = ids },
            dispatcher = StandardTestDispatcher(testScheduler))
        val first = async { tx.post("one", "id") { events += "post"; entered.complete(Unit); finish.await(); true } }
        entered.await()
        val duplicate = async { tx.post("one", "id") { fail("duplicate post") } }
        runCurrent(); assertFalse(duplicate.isCompleted)
        finish.complete(Unit)
        assertTrue(first.await()); assertFalse(duplicate.await())
        assertEquals(listOf("post", "save"), events)
    }
    @Test fun `failed posts stay retryable and owners are independent with bounded ids`() = runTest {
        val rows = mutableMapOf<String, List<String>>()
        val tx = NotificationPostTransaction({ rows[it].orEmpty() }, { owner, ids -> rows[owner] = ids },
            capacity = 2, dispatcher = StandardTestDispatcher(testScheduler))
        assertFalse(tx.post("one", "id") { false }); assertNull(rows["one"])
        for (id in listOf("a", "b", "c")) assertTrue(tx.post("one", id) { true })
        assertEquals(listOf("b", "c"), rows["one"])
        assertTrue(tx.post("two", "c") { true })
        assertFalse(tx.post("one", "c") { fail() })
        assertTrue(tx.post("one", "a") { true })
    }
    @Test fun `cancellation after successful post cannot skip durable write`() = runTest {
        val saved = mutableListOf<String>()
        val tx = NotificationPostTransaction({ emptyList() }, { _, ids -> saved += ids },
            dispatcher = StandardTestDispatcher(testScheduler))
        val job = launch {
            val caller = currentCoroutineContext().job
            tx.post("owner", "id") { caller.cancel(); true }
        }
        job.join()
        assertEquals(listOf("id"), saved)
    }
    @Test fun `disk callbacks are dispatched away from caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val tx = NotificationPostTransaction({ assertNotSame(caller, Thread.currentThread()); emptyList() },
            { _, _ -> assertNotSame(caller, Thread.currentThread()) })
        assertTrue(tx.post("owner", "id") { true })
    }
}
