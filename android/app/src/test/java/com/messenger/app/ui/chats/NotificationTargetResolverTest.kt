package com.messenger.app.ui.chats

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationTargetResolverTest {
    @Test fun `cached history miss does not lose later synced target`() = runTest {
        val rows = MutableStateFlow<String?>(null)
        var lookups = 0
        val resolved = async { NotificationTargetResolver.await(rows, { it }, { lookups++ }) }
        runCurrent(); assertFalse(resolved.isCompleted)
        advanceTimeBy(20_000); rows.value = "exact-client-id"; runCurrent()
        assertEquals("exact-client-id", resolved.await()); assertEquals(1, lookups)
        assertEquals(0, rows.subscriptionCount.value)
    }
    @Test fun `history failure still permits live sync and already cached target resolves immediately`() = runTest {
        val rows = MutableStateFlow<String?>(null)
        val resolved = async { NotificationTargetResolver.await(rows, { it }, { error("offline history") }) }
        runCurrent(); rows.value = "target"; assertEquals("target", resolved.await())
        assertEquals("target", NotificationTargetResolver.await(rows, { it }, {}))
    }
    @Test fun `deadline and leaving chat cancel observation and in-flight history`() = runTest {
        val rows = MutableStateFlow<String?>(null)
        var canceled = 0
        suspend fun history() { try { awaitCancellation() } finally { canceled++ } }
        assertNull(NotificationTargetResolver.await(rows, { it }, { history() }, timeoutMillis = 500))
        assertEquals(1, canceled); assertEquals(0, rows.subscriptionCount.value)
        val navigation = launch { NotificationTargetResolver.await(rows, { it }, { history() }) }
        runCurrent(); navigation.cancelAndJoin()
        assertEquals(2, canceled); assertEquals(0, rows.subscriptionCount.value)
    }
}
