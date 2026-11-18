package com.messenger.app.ui.call

import com.messenger.app.data.call.*
import kotlin.test.*

class CallAudioRouteIdentityTest {
    @Test fun `two equally named headsets remain independently selectable`() {
        val a=AudioRoute(AudioRouteKind.BLUETOOTH,"Headset",11)
        val b=AudioRoute(AudioRouteKind.BLUETOOTH,"Headset",12)
        assertNotEquals(a,b)
        val devices=listOf(11 to a.kind,12 to b.kind,1 to AudioRouteKind.SPEAKER)
        assertEquals(12,CallAudioPolicy.preferredDeviceId(devices,b.kind,11,b.deviceId))
        assertEquals(11,CallAudioPolicy.preferredDeviceId(devices,a.kind,12,a.deviceId))
        assertNull(CallAudioPolicy.preferredDeviceId(devices,a.kind,11,99))
        assertNull(CallAudioPolicy.preferredDeviceId(devices,AudioRouteKind.SPEAKER,11,12))
        assertEquals(11,CallAudioPolicy.preferredDeviceId(devices,a.kind,11))
    }
}
