package com.messenger.app.domain.call

import com.messenger.app.domain.call.CallOutgoingReadPolicy.Read
import kotlin.test.*

class CallOutgoingReadPolicyTest {
    @Test fun deadlineAndRefreshBudget() {
        val p = CallOutgoingReadPolicy(100)
        p.issued(Read.CREATED, 100)
        assertEquals(6_100L, p.deadline)
        assertEquals(CallOutgoingReadPolicy.Due(Read.CHECK, 6_100), p.next(true))
        p.issued(Read.REFRESH, 2_628)
        assertEquals(6_100L, p.next(true)?.at)
        p.issued(Read.CHECK, 6_100)
        p.issued(Read.REFRESH, 7_000)
        assertEquals(12_000L, p.next(true)?.at)
        repeat(6) { p.issued(Read.CHECK, 12_000L + it * 5_000) }
        assertEquals(CallOutgoingReadPolicy.Due(Read.BACKSTOP, 46_100), p.next(true))
    }

    @Test fun refreshesCannotExtendPollingPastBackstop() {
        val p = CallOutgoingReadPolicy(0)
        p.issued(Read.CREATED, 0)
        p.issued(Read.REFRESH, 45_000)
        assertEquals(CallOutgoingReadPolicy.Due(Read.BACKSTOP, 46_000), p.next(true))
        p.issued(Read.BACKSTOP, 46_000)
        assertNull(p.next(true))
    }

    @Test fun confirmedAndLegacyRetainOneTerminalBackstop() {
        for (awaiting in listOf(false, true)) {
            val p = CallOutgoingReadPolicy(0)
            if (awaiting) repeat(8) { p.issued(Read.CHECK, it * 5_000L) }
            assertEquals(CallOutgoingReadPolicy.Due(Read.BACKSTOP, 46_000), p.next(awaiting))
            p.issued(Read.BACKSTOP, 46_000)
            assertNull(p.next(awaiting))
        }
    }
}
