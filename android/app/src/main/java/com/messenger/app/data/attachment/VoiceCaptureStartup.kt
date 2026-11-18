package com.messenger.app.data.attachment

import android.media.AudioManager

internal class VoicePlatformCallActive : IllegalStateException("Platform call or communication audio is active")

/** Inspect only: never change platform mode or steal focus from a phone/VoIP call. */
internal class VoiceCaptureStartup(private val mode: () -> Int, private val requestFocus: () -> Boolean) {
    fun requireIdlePlatform() {
        if (mode() != AudioManager.MODE_NORMAL) throw VoicePlatformCallActive()
    }

    fun start(prepare: () -> Unit, record: () -> Unit) {
        requireIdlePlatform()
        prepare()
        // Preparation may take time. Re-read the mode immediately before the focus request.
        requireIdlePlatform()
        check(requestFocus()) { "Microphone audio focus unavailable" }
        record()
    }
}
