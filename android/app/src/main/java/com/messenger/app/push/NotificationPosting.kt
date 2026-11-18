package com.messenger.app.push

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Post only with the user's system permission; it may be revoked after the check. */
internal fun postNotificationIfPermitted(context: Context, tag: String, id: Int, notification: Notification): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        return false
    }
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return false
    return try {
        manager.notify(tag, id, notification)
        true
    } catch (_: SecurityException) {
        false
    }
}
