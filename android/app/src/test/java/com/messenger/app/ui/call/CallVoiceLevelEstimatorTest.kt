package com.messenger.app.ui.call

import com.messenger.app.data.call.*
import kotlin.test.*

class CallVoiceLevelEstimatorTest {
    @Test fun `attack release silence and receiver replacement match iOS`() {
        val e=CallVoiceLevelEstimator()
        assertEquals(0f,e.update(CallAudioEnergy(0.0,0.0,0.0)))
        val on=e.update(CallAudioEnergy(.14,.14,1000.0))
        assertEquals(.5f,on)
        assertEquals(.44f,e.update(CallAudioEnergy(.14,.28,2000.0)),.0001f)
        assertEquals(0f,e.update(CallAudioEnergy(.14,.42,2000.0)))
        assertEquals(0f,e.update(CallAudioEnergy(0.0,0.0,0.0)))
        assertEquals(0f,e.update(null))
        assertEquals(0f,e.update(CallAudioEnergy(Double.NaN,1.0,1.0)))
    }
    @Test fun `silent growing counters settle without a repeating presentation update`() {
        val e=CallVoiceLevelEstimator()
        e.update(CallAudioEnergy(0.0,0.0,0.0))
        e.update(CallAudioEnergy(.14,.14,1000.0))
        var value=1f
        for(i in 2..60) value=e.update(CallAudioEnergy(.14,i*.14,i*1000.0))
        assertEquals(0f,value)
        for(i in 61..80) assertEquals(0f,e.update(CallAudioEnergy(.14,i*.14,i*1000.0)))
    }
}
