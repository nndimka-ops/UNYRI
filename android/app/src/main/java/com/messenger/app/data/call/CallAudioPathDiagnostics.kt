package com.messenger.app.data.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Android exposes the output quantum and sample rate, but not route latency. */
class CallAudioPathDiagnostics(context: Context) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var routeRecords = 0
    fun snapshot(status: Int): Map<String, Double>? {
        if (status != 3 && routeRecords++ >= 12) return null
        val sampleRate = manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toDoubleOrNull()
        val frames = manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toDoubleOrNull()
        val type = if (Build.VERSION.SDK_INT >= 31) manager.communicationDevice?.type else {
            @Suppress("DEPRECATION")
            when { manager.isBluetoothScoOn -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO; manager.isWiredHeadsetOn -> AudioDeviceInfo.TYPE_WIRED_HEADSET
                manager.isSpeakerphoneOn -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER; else -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }
        val route = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 1
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 2
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 3
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 4
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26, 27 -> 5 // BLE headset / speaker on API 31+
            else -> 0
        }
        return CallDiagnosticEvent.create(5, 147, mapOf("status" to status, "duration_ms" to
            if (sampleRate != null && sampleRate > 0 && frames != null) frames * 1000 / sampleRate else null,
            "items" to sampleRate?.div(1000), "interface" to route))
    }
}
