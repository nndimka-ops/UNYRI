package com.messenger.app.call

import com.messenger.app.R
import com.messenger.app.domain.model.CallMediaKind
import kotlin.test.Test
import kotlin.test.assertEquals

class CallPermissionMessagesTest {
    private val mic = "android.permission.RECORD_AUDIO"
    private val cam = "android.permission.CAMERA"

    @Test
    fun `audio call with microphone denied gets microphone text`() {
        assertEquals(
            CallPermissionMessages.MICROPHONE_REQUIRED,
            CallPermissionMessages.forDeniedCall(CallMediaKind.AUDIO, setOf(mic)),
        )
    }

    @Test
    fun `video call with camera denied gets camera text`() {
        assertEquals(
            CallPermissionMessages.CAMERA_REQUIRED,
            CallPermissionMessages.forDeniedCall(CallMediaKind.VIDEO, setOf(cam)),
        )
    }

    @Test
    fun `video call with both denied reports the first actually-needed permission`() {
        assertEquals(
            CallPermissionMessages.MICROPHONE_REQUIRED,
            CallPermissionMessages.forDeniedCall(CallMediaKind.VIDEO, setOf(mic, cam)),
        )
    }

    @Test
    fun `video call with only microphone denied gets microphone text`() {
        assertEquals(
            CallPermissionMessages.MICROPHONE_REQUIRED,
            CallPermissionMessages.forDeniedCall(CallMediaKind.VIDEO, setOf(mic)),
        )
    }

    @Test
    fun `permission messages are localized resource ids, not raw english`() {
        assertEquals(R.string.call_permission_microphone, CallPermissionMessages.MICROPHONE_REQUIRED)
        assertEquals(R.string.call_permission_camera, CallPermissionMessages.CAMERA_REQUIRED)
    }
}
