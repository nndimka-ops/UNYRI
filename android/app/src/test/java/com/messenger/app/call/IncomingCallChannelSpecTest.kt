package com.messenger.app.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P0 "incoming call had no sound": the channel spec must carry call-alert
 *  semantics — RING volume, not the muted notification stream. */
class IncomingCallChannelSpecTest {

    @Test
    fun `channel is versioned to escape the immutable silent legacy channel`() {
        assertEquals("incoming_calls_v2", IncomingCallChannelSpec.CHANNEL_ID)
    }

    @Test
    fun `audio usage is ring volume not notification volume`() {
        // USAGE_NOTIFICATION_RINGTONE = 6; USAGE_NOTIFICATION = 5. The legacy
        // silent channel used the notification volume stream.
        assertEquals(6, IncomingCallChannelSpec.AUDIO_USAGE)
    }

    @Test
    fun `audio content type is sonification`() {
        assertEquals(4, IncomingCallChannelSpec.AUDIO_CONTENT_TYPE)
    }

    @Test
    fun `vibration is enabled for the incoming call alert`() {
        assertTrue(IncomingCallChannelSpec.VIBRATION_ENABLED)
    }

    @Test
    fun `vibration pattern is a repeat cadence`() {
        assertTrue(IncomingCallChannelSpec.VIBRATION_PATTERN.size >= 3)
        assertEquals(0L, IncomingCallChannelSpec.VIBRATION_PATTERN[0]) // immediate start
    }
}
