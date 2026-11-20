package com.messenger.app.ui.admin

import org.junit.Assert.*
import org.junit.Test

class ServerCardValuePolicyTest {
    @Test fun acceptsZeroAndBinaryLimitsWithoutOverflow() {
        assertEquals(0L, ServerCardValuePolicy.parseLimit("0"))
        assertEquals(104857600L, ServerCardValuePolicy.parseLimit("104857600"))
        assertEquals(5368709120L, ServerCardValuePolicy.parseLimit("5368709120"))
    }
    @Test fun omitsAbsentMalformedOrNegativeLimits() {
        listOf("", "null", "-1", "1.5", "99999999999999999999999").forEach {
            assertNull(ServerCardValuePolicy.parseLimit(it))
        }
    }
}
