package com.messenger.app.ui.call

import kotlin.test.Test
import kotlin.test.assertEquals

class CallDurationTest {

    @Test
    fun `formatCallDuration formats mm ss`() {
        assertEquals("00:00", formatCallDuration(0))
        assertEquals("00:05", formatCallDuration(5))
        assertEquals("01:05", formatCallDuration(65))
        assertEquals("59:59", formatCallDuration(3599))
    }

    @Test
    fun `formatCallDuration allows minutes over 59`() {
        assertEquals("100:00", formatCallDuration(6000))
    }
}
