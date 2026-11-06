package com.messenger.app.call

import androidx.annotation.StringRes
import com.messenger.app.R
import com.messenger.app.domain.model.CallMediaKind

/** User-facing permission-denial messages (Этап J.1-4 п.14) - split so a
 *  CAMERA denial no longer claims the microphone is missing. Returns
 *  localized string RESOURCE ids, never raw English (code-review 7).
 *  Pure/JVM testable (no `Manifest` dependency - the permission names are
 *  stable Android string constants). */
object CallPermissionMessages {
    @StringRes
    val MICROPHONE_REQUIRED: Int = R.string.call_permission_microphone

    @StringRes
    val CAMERA_REQUIRED: Int = R.string.call_permission_camera

    private const val PERMISSION_RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    private const val PERMISSION_CAMERA = "android.permission.CAMERA"

    /** For an audio call only the microphone matters; for a video call both
     *  mic+camera are requested and the message reports the first permission
     *  actually missing. */
    @StringRes
    fun forDeniedCall(mediaKind: CallMediaKind, deniedPermissions: Set<String>): Int = when (mediaKind) {
        CallMediaKind.AUDIO -> MICROPHONE_REQUIRED
        CallMediaKind.VIDEO ->
            if (PERMISSION_RECORD_AUDIO in deniedPermissions) MICROPHONE_REQUIRED else CAMERA_REQUIRED
    }
}
