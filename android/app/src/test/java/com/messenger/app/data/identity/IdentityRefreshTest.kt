package com.messenger.app.data.identity

import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IdentityRefreshTest {
    @Test fun legacyAndMissingIdentityRetryOnlyAtFiveMinuteBoundary() {
        var now = 0L
        val policy = IdentityRefreshPolicy { now }
        assertTrue(policy.foregroundDue("server|alice", legacy = true, missing = true, refreshedAt = null))
        policy.attempted("server|alice")
        now = IdentityRefreshPolicy.INTERVAL_MILLIS - 1
        assertFalse(policy.foregroundDue("server|alice", true, true, 0L))
        now++
        assertTrue(policy.foregroundDue("server|alice", true, true, 0L))
    }

    @Test fun freshIdentitySkipsForegroundAndStaleIdentityRefreshes() {
        var now = IdentityRefreshPolicy.INTERVAL_MILLIS
        val policy = IdentityRefreshPolicy { now }
        assertFalse(policy.foregroundDue("server|alice", false, false, 1L))
        now++
        assertTrue(policy.foregroundDue("server|alice", false, false, 1L))
        assertTrue(policy.foregroundDue("server|alice", false, true, now))
        assertTrue(policy.foregroundDue("server|alice", true, false, now))
    }

    @Test fun failedAttemptsConsumeBudgetAndAccountsAndServersHaveSeparateBudgets() {
        var now = 10L
        val policy = IdentityRefreshPolicy { now }
        policy.attempted("server|alice") // No successful refresh follows this attempt.
        assertFalse(policy.foregroundDue("server|alice", false, true, null))
        assertTrue(policy.foregroundDue("server|bob", false, true, null))
        assertTrue(policy.foregroundDue("other-server|alice", false, true, null))
        now += IdentityRefreshPolicy.INTERVAL_MILLIS
        assertTrue(policy.foregroundDue("server|alice", false, true, null))
    }

    @Test fun returningToAccountDoesNotResetItsForegroundBudget() {
        var now = 0L
        val policy = IdentityRefreshPolicy { now }
        policy.attempted("server|alice")
        now++
        policy.attempted("server|bob")
        now++
        assertFalse(policy.foregroundDue("server|alice", false, true, null))
        assertFalse(policy.foregroundDue("server|bob", false, true, null))
    }

    @Test fun foregroundRefreshFollowsLifecycleAndSessionEventsWithoutPolling() = runTest {
        val owner = MutableStateFlow<String?>("server|alice|1")
        val active = MutableStateFlow(false)
        val connection = MutableStateFlow(SocketConnectionState.DISCONNECTED)
        val requests = mutableListOf<String>()
        IdentityRefreshCoordinator(owner, active, connection, backgroundScope, { requests += it }, { _, _ -> })
        runCurrent()
        assertTrue(requests.isEmpty())
        active.value = true
        runCurrent()
        assertEquals(listOf("server|alice|1"), requests)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, requests.size)
        active.value = false
        runCurrent()
        active.value = true
        runCurrent()
        owner.value = "server|bob|2"
        runCurrent()
        owner.value = null
        runCurrent()
        active.value = false
        runCurrent()
        active.value = true
        runCurrent()
        assertEquals(listOf("server|alice|1", "server|alice|1", "server|bob|2"), requests)
    }

    @Test fun reconnectForcesRefreshButFirstConnectionUsesExistingIdentity() = runTest {
        val owner = MutableStateFlow<String?>("server|alice|1")
        val active = MutableStateFlow(false)
        val connection = MutableStateFlow(SocketConnectionState.DISCONNECTED)
        val requests = mutableListOf<Pair<String, Boolean>>()
        IdentityRefreshCoordinator(owner, active, connection, backgroundScope, {}, { key, force -> requests += key to force })
        runCurrent()
        connection.value = SocketConnectionState.CONNECTED
        runCurrent()
        connection.value = SocketConnectionState.DISCONNECTED
        runCurrent()
        connection.value = SocketConnectionState.CONNECTED
        runCurrent()
        owner.value = "server|bob|2"
        runCurrent()
        connection.value = SocketConnectionState.DISCONNECTED
        runCurrent()
        connection.value = SocketConnectionState.CONNECTED
        runCurrent()
        assertEquals(listOf("server|alice|1" to false, "server|alice|1" to true,
            "server|bob|2" to false, "server|bob|2" to true), requests)
    }

    @Test fun transientRefreshFailureWaitsForNextEvent() = runTest {
        val owner = MutableStateFlow<String?>("server|alice|1")
        val active = MutableStateFlow(true)
        val connection = MutableStateFlow(SocketConnectionState.DISCONNECTED)
        var attempts = 0
        IdentityRefreshCoordinator(owner, active, connection, backgroundScope,
            { attempts++; error("Offline") }, { _, _ -> })
        runCurrent()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, attempts)
        active.value = false
        runCurrent()
        active.value = true
        runCurrent()
        assertEquals(2, attempts)
    }

    @Test fun reconnectDuringRefreshRetainsOneFollowUpWithoutBlockingConnectionEvents() = runTest {
        val owner = MutableStateFlow<String?>("server|alice|1")
        val active = MutableStateFlow(false)
        val connection = MutableStateFlow(SocketConnectionState.CONNECTED)
        val firstResponse = CompletableDeferred<Unit>()
        val requests = mutableListOf<Boolean>()
        IdentityRefreshCoordinator(owner, active, connection, backgroundScope, {}, { _, force ->
            requests += force
            if (requests.size == 1) firstResponse.await()
        })
        runCurrent()
        repeat(3) {
            connection.value = SocketConnectionState.DISCONNECTED
            runCurrent()
            connection.value = SocketConnectionState.CONNECTED
            runCurrent()
        }
        assertEquals(listOf(false), requests)
        firstResponse.complete(Unit)
        runCurrent()
        assertEquals(listOf(false, true), requests)
    }
}
