package com.messenger.app.ui.call

import kotlin.test.*

class CallVoiceHaloPolicyTest {
    @Test fun `gate silence invalid samples and map forty decibels above minus fifty five`() {
        for(value in listOf(null,0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY,.001)) assertEquals(0f,CallVoiceHaloPolicy.target(value))
        assertTrue(CallVoiceHaloPolicy.target(.0178) in .49f.. .51f)
        assertEquals(1f,CallVoiceHaloPolicy.target(1.0))
    }
}
