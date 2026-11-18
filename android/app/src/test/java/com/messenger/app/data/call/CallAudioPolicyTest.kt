package com.messenger.app.data.call

import com.messenger.app.domain.model.CallMediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallAudioPolicyTest {
    private val phone = listOf(AudioRouteKind.EARPIECE, AudioRouteKind.SPEAKER)

    @Test fun `new audio call replaces an inherited speaker with receiver`() {
        assertEquals(AudioRouteKind.EARPIECE, CallAudioPolicy.initialRoute(phone, AudioRouteKind.SPEAKER))
    }

    @Test fun `default preserves the active external output and prefers connected private outputs`() {
        val all = phone + AudioRouteKind.BLUETOOTH + AudioRouteKind.WIRED_HEADSET
        assertEquals(AudioRouteKind.BLUETOOTH, CallAudioPolicy.initialRoute(all, AudioRouteKind.BLUETOOTH))
        assertEquals(AudioRouteKind.WIRED_HEADSET, CallAudioPolicy.initialRoute(all, AudioRouteKind.WIRED_HEADSET))
        assertEquals(AudioRouteKind.WIRED_HEADSET, CallAudioPolicy.initialRoute(all, AudioRouteKind.SPEAKER))
    }

    @Test fun `speaker fallback requires hardware with no private output`() {
        assertEquals(AudioRouteKind.SPEAKER, CallAudioPolicy.initialRoute(listOf(AudioRouteKind.SPEAKER), AudioRouteKind.EARPIECE))
        assertNull(CallAudioPolicy.initialRoute(emptyList(), AudioRouteKind.SPEAKER))
    }

    @Test fun `device selection preserves actual current headset among two of the same kind`() {
        val devices = listOf(7 to AudioRouteKind.BLUETOOTH, 42 to AudioRouteKind.BLUETOOTH, 1 to AudioRouteKind.EARPIECE)
        assertEquals(42, CallAudioPolicy.preferredDeviceId(devices, AudioRouteKind.BLUETOOTH, 42))
        assertEquals(7, CallAudioPolicy.preferredDeviceId(devices, AudioRouteKind.BLUETOOTH, 99))
        assertNull(CallAudioPolicy.preferredDeviceId(devices, AudioRouteKind.SPEAKER, 42))
    }

    @Test fun `own live camera enables automatic speaker and disabling it restores receiver`() {
        val policy = CallAudioRoutePolicy()
        assertNull(policy.devicesChanged(phone, AudioRouteKind.EARPIECE))
        assertEquals(AudioRouteKind.SPEAKER, policy.localCameraChanged(true, phone, AudioRouteKind.EARPIECE))
        assertEquals(AudioRouteKind.EARPIECE, policy.localCameraChanged(false, phone, AudioRouteKind.SPEAKER))
    }

    @Test fun `manual receiver or speaker overrides camera transitions`() {
        val policy = CallAudioRoutePolicy()
        policy.selectedManually(AudioRouteKind.EARPIECE)
        assertNull(policy.localCameraChanged(true, phone, AudioRouteKind.EARPIECE))
        policy.selectedManually(AudioRouteKind.SPEAKER)
        assertNull(policy.localCameraChanged(false, phone, AudioRouteKind.SPEAKER))
        assertNull(policy.devicesChanged(phone, AudioRouteKind.SPEAKER))
    }

    @Test fun `headset takes automatic video output and unplugging it returns video speaker`() {
        val policy = CallAudioRoutePolicy()
        val wired = phone + AudioRouteKind.WIRED_HEADSET
        assertNull(policy.localCameraChanged(true, wired, AudioRouteKind.WIRED_HEADSET))
        assertEquals(AudioRouteKind.SPEAKER, policy.devicesChanged(phone, AudioRouteKind.EARPIECE))
        assertEquals(AudioRouteKind.WIRED_HEADSET, policy.devicesChanged(wired, AudioRouteKind.SPEAKER))
    }

    @Test fun `lost manual headset falls back privately even with local video enabled`() {
        val policy = CallAudioRoutePolicy()
        policy.selectedManually(AudioRouteKind.BLUETOOTH)
        assertEquals(AudioRouteKind.EARPIECE, policy.localCameraChanged(true, phone, AudioRouteKind.SPEAKER))
        assertEquals(AudioRouteKind.BLUETOOTH, policy.devicesChanged(phone + AudioRouteKind.BLUETOOTH, AudioRouteKind.EARPIECE))
    }

    @Test fun `a new call clears manual preference and previous camera ownership`() {
        val policy = CallAudioRoutePolicy()
        policy.selectedManually(AudioRouteKind.EARPIECE)
        policy.localCameraChanged(true, phone, AudioRouteKind.EARPIECE)
        policy.reset()
        assertNull(policy.devicesChanged(phone, AudioRouteKind.EARPIECE))
        assertEquals(AudioRouteKind.SPEAKER, policy.localCameraChanged(true, phone, AudioRouteKind.EARPIECE))
    }

    @Test
    fun `audio call requires only the microphone`() {
        assertEquals(
            listOf(CallAudioPolicy.PERMISSION_RECORD_AUDIO),
            CallAudioPolicy.requiredCallPermissions(CallMediaKind.AUDIO),
        )
    }

    @Test
    fun `video call requires microphone and camera`() {
        assertEquals(
            listOf(CallAudioPolicy.PERMISSION_RECORD_AUDIO, CallAudioPolicy.PERMISSION_CAMERA),
            CallAudioPolicy.requiredCallPermissions(CallMediaKind.VIDEO),
        )
    }

    @Test
    fun `no optional bluetooth permission before android 12`() {
        assertEquals(emptyList(), CallAudioPolicy.optionalCallPermissions(30))
        assertEquals(emptyList(), CallAudioPolicy.optionalCallPermissions(26))
    }

    @Test
    fun `bluetooth connect is requested on android 12 and above`() {
        assertEquals(
            listOf(CallAudioPolicy.PERMISSION_BLUETOOTH_CONNECT),
            CallAudioPolicy.optionalCallPermissions(31),
        )
        assertEquals(
            listOf(CallAudioPolicy.PERMISSION_BLUETOOTH_CONNECT),
            CallAudioPolicy.optionalCallPermissions(34),
        )
    }

    @Test
    fun `all permissions combine required and optional in order`() {
        assertEquals(
            listOf(CallAudioPolicy.PERMISSION_RECORD_AUDIO, CallAudioPolicy.PERMISSION_BLUETOOTH_CONNECT),
            CallAudioPolicy.allCallPermissions(CallMediaKind.AUDIO, 31),
        )
    }

    @Test
    fun `bluetooth denial is not blocking for an audio call`() {
        val denied = setOf(CallAudioPolicy.PERMISSION_BLUETOOTH_CONNECT)
        assertEquals(emptySet(), CallAudioPolicy.blockingDenials(CallMediaKind.AUDIO, denied))
    }

    @Test
    fun `microphone denial is blocking for an audio call`() {
        val denied = setOf(CallAudioPolicy.PERMISSION_RECORD_AUDIO, CallAudioPolicy.PERMISSION_BLUETOOTH_CONNECT)
        assertEquals(
            setOf(CallAudioPolicy.PERMISSION_RECORD_AUDIO),
            CallAudioPolicy.blockingDenials(CallMediaKind.AUDIO, denied),
        )
    }

    @Test
    fun `camera denial is blocking for a video call`() {
        val denied = setOf(CallAudioPolicy.PERMISSION_CAMERA, CallAudioPolicy.PERMISSION_BLUETOOTH_CONNECT)
        assertEquals(
            setOf(CallAudioPolicy.PERMISSION_CAMERA),
            CallAudioPolicy.blockingDenials(CallMediaKind.VIDEO, denied),
        )
    }

    @Test
    fun `default labels cover every route kind`() {
        assertEquals("Phone", CallAudioPolicy.defaultLabel(AudioRouteKind.EARPIECE))
        assertEquals("Speaker", CallAudioPolicy.defaultLabel(AudioRouteKind.SPEAKER))
        assertEquals("Bluetooth", CallAudioPolicy.defaultLabel(AudioRouteKind.BLUETOOTH))
        assertEquals("Wired headset", CallAudioPolicy.defaultLabel(AudioRouteKind.WIRED_HEADSET))
    }

    @Test
    fun `label prefers the real device name and never returns blank`() {
        assertEquals("Pixel Buds", CallAudioPolicy.label(AudioRouteKind.BLUETOOTH, "Pixel Buds"))
        assertEquals("Bluetooth", CallAudioPolicy.label(AudioRouteKind.BLUETOOTH, null))
        assertEquals("Bluetooth", CallAudioPolicy.label(AudioRouteKind.BLUETOOTH, "  "))
    }

    @Test
    fun `audio route carries a kind and a non-empty label`() {
        val route = AudioRoute(AudioRouteKind.BLUETOOTH, "Pixel Buds")
        assertEquals(AudioRouteKind.BLUETOOTH, route.kind)
        assertTrue(route.label.isNotBlank())
    }

    @Test
    fun `accepting an audio call requires only the microphone`() {
        assertEquals(
            listOf(CallAudioPolicy.PERMISSION_RECORD_AUDIO),
            CallAudioPolicy.requiredCallPermissionsForAccept(CallMediaKind.AUDIO),
        )
    }

    @Test
    fun `accepting a video call does not require camera`() {
        // Code-review 10: you can accept a video call with your own camera
        // unavailable - receive remote video, keep your camera OFF.
        assertEquals(
            listOf(CallAudioPolicy.PERMISSION_RECORD_AUDIO),
            CallAudioPolicy.requiredCallPermissionsForAccept(CallMediaKind.VIDEO),
        )
    }

    @Test
    fun `camera denial never blocks accepting a video call`() {
        val denied = setOf(CallAudioPolicy.PERMISSION_CAMERA)
        assertEquals(emptySet(), CallAudioPolicy.blockingDenialsForAccept(CallMediaKind.VIDEO, denied))
    }

    @Test
    fun `microphone denial blocks accepting a video call`() {
        val denied = setOf(CallAudioPolicy.PERMISSION_RECORD_AUDIO, CallAudioPolicy.PERMISSION_CAMERA)
        assertEquals(
            setOf(CallAudioPolicy.PERMISSION_RECORD_AUDIO),
            CallAudioPolicy.blockingDenialsForAccept(CallMediaKind.VIDEO, denied),
        )
    }

    @Test
    fun `accept permissions combine microphone and optional bluetooth`() {
        assertEquals(
            listOf(CallAudioPolicy.PERMISSION_RECORD_AUDIO, CallAudioPolicy.PERMISSION_BLUETOOTH_CONNECT),
            CallAudioPolicy.allCallPermissionsForAccept(CallMediaKind.VIDEO, 31),
        )
    }
}
