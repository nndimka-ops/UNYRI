package com.messenger.app.call

/** P0 "incoming call had no sound" — the channel configuration contract for
 *  the incoming-call notification. Uses inlined SDK constants (no framework calls) so
 *  the sound semantics are unit-testable.
 *
 *  The original `incoming_calls` channel was created with the default
 *  NOTIFICATION sound at [android.media.AudioAttributes.USAGE_NOTIFICATION]
 *  (notification volume) and no vibration — so a quiet notification stream
 *  made the incoming call effectively silent, and never a real ringtone.
 *  Channel sound/importance/attributes are immutable once created, hence the
 *  versioned channel id. */
object IncomingCallChannelSpec {
    const val CHANNEL_ID = "incoming_calls_v2"

    /** [android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE] = 6:
     *  play at RING volume (not the notification stream), the correct
     *  semantics for an incoming-call alert. */
    const val AUDIO_USAGE = android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE

    /** [android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION] = 4. */
    const val AUDIO_CONTENT_TYPE = android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION

    /** The incoming-call alert must vibrate when the system allows it. */
    const val VIBRATION_ENABLED = true

    /** Repeat pattern: short ring-vibrate cadence. */
    val VIBRATION_PATTERN = longArrayOf(0, 500, 500, 500)
}
