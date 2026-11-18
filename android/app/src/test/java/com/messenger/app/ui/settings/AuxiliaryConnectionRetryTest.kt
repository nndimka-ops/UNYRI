package com.messenger.app.ui.settings

import com.messenger.app.domain.repository.MessageRepository
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuxiliaryConnectionRetryTest {
    @Test fun constructingTheProductionHeaderCallbackDoesNotStartWork() {
        val events = mutableListOf<String>()
        auxiliaryConnectionRetry(repository(events)) { events += "results" }
        assertTrue(events.isEmpty())
    }

    @Test fun eachExplicitTapRequestsRealRepositoryRecoveryBeforeRefreshingResults() {
        val events = mutableListOf<String>()
        val retry = auxiliaryConnectionRetry(repository(events)) { events += "results" }
        retry()
        retry()
        assertEquals(listOf("retryConnectionSync", "results", "retryConnectionSync", "results"), events)
    }

    @Test fun anEmptyOrInactiveResultsRefreshCannotSuppressConnectionRecovery() {
        val events = mutableListOf<String>()
        auxiliaryConnectionRetry(repository(events)) { /* Empty query / inactive local message search. */ }()
        assertEquals(listOf("retryConnectionSync"), events)
        val retryWithFailure = auxiliaryConnectionRetry(repository(events)) { error("Results refresh failed") }
        assertFailsWith<IllegalStateException> { retryWithFailure() }
        assertEquals(listOf("retryConnectionSync", "retryConnectionSync"), events)
    }

    /** Fail on any unexpected repository operation; this is the actual MessageRepository seam used by both hosts. */
    private fun repository(events: MutableList<String>): MessageRepository = Proxy.newProxyInstance(
        MessageRepository::class.java.classLoader, arrayOf(MessageRepository::class.java),
    ) { _, method, _ ->
        check(method.name == "retryConnectionSync") { "Unexpected repository operation: ${method.name}" }
        events += method.name
        null
    } as MessageRepository
}
