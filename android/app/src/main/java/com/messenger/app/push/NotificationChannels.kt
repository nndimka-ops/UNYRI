package com.messenger.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** Notification channels for push (Этап K.7): messages and calls are never
 *  mixed in one channel. Calls are high-importance CATEGORY_CALL. */
object NotificationChannels {
    const val CHANNEL_CALLS = "calls"
    const val CHANNEL_MESSAGES = "messages"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        // Updating names preserves the user's sound/importance preferences.
        for ((id, name, description) in listOf(
            Triple(CHANNEL_CALLS, com.messenger.app.R.string.calls_title, com.messenger.app.R.string.notification_calls_description),
            Triple(CHANNEL_MESSAGES, com.messenger.app.R.string.notification_messages, com.messenger.app.R.string.notification_messages_description))) {
            manager.getNotificationChannel(id)?.let { channel ->
                channel.name = context.getString(name); channel.description = context.getString(description)
                manager.createNotificationChannel(channel)
            }
        }
        if (manager.getNotificationChannel(CHANNEL_CALLS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_CALLS, context.getString(com.messenger.app.R.string.calls_title), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(com.messenger.app.R.string.notification_calls_description)
                    setShowBadge(false)
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_MESSAGES) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_MESSAGES, context.getString(com.messenger.app.R.string.notification_messages), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(com.messenger.app.R.string.notification_messages_description)
                    setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                },
            )
        }
    }
}
