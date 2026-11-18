package com.messenger.app.ui.call

import kotlin.test.*

class CallMorphPolicyTest {
    @Test fun `system entry TalkBack inactive and concurrent morph take plain transition`() {
        assertEquals(CallMorphPolicy.Style.PLAIN,CallMorphPolicy.style(false,false,true,true,false))
        assertEquals(CallMorphPolicy.Style.PLAIN,CallMorphPolicy.style(false,false,false,false,false))
        assertEquals(CallMorphPolicy.Style.PLAIN,CallMorphPolicy.style(false,false,false,true,true))
        assertEquals(CallMorphPolicy.Style.PLAIN,CallMorphPolicy.style(false,false,false,true,false,true))
    }
    @Test fun `reduce motion is fade power saver finite ease else finite spring`() {
        assertEquals(CallMorphPolicy.Style.FADE,CallMorphPolicy.style(true,true,false,true,false))
        assertEquals(CallMorphPolicy.Style.POWER_SAVER,CallMorphPolicy.style(false,true,false,true,false))
        assertEquals(CallMorphPolicy.Style.SPRING,CallMorphPolicy.style(false,false,false,true,false))
        assertEquals(440,CallMorphPolicy.duration(CallMorphPolicy.Style.SPRING,false))
        assertEquals(400,CallMorphPolicy.duration(CallMorphPolicy.Style.SPRING,true))
        assertEquals(300,CallMorphPolicy.duration(CallMorphPolicy.Style.POWER_SAVER,false))
        assertEquals(280,CallMorphPolicy.duration(CallMorphPolicy.Style.POWER_SAVER,true))
        assertEquals(200,CallMorphPolicy.duration(CallMorphPolicy.Style.FADE,false))
        assertEquals(180,CallMorphPolicy.duration(CallMorphPolicy.Style.FADE,true))
    }
}
