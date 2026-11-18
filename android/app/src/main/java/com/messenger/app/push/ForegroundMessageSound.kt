package com.messenger.app.push

import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import androidx.core.app.NotificationManagerCompat

/** One finite system notification chime; obey permission, channel sound and DND. */
object ForegroundMessageSound {
    fun feedback(context: Context, sound: Boolean, vibration: Boolean) {
        if (!sound && !vibration) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        if (sound && audio.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL) play(context)
        if (vibration && audio.ringerMode != android.media.AudioManager.RINGER_MODE_SILENT &&
            android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0) {
            runCatching {
                context.getSystemService(android.os.Vibrator::class.java).vibrate(
                    android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
            }
        }
    }
    fun play(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        runCatching {
            RingtoneManager.getRingtone(context, sound)?.apply {
                audioAttributes = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build()
                play()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stop() }, 4000)
            }
        }
    }
}
