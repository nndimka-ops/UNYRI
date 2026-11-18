package com.messenger.app.push

import android.content.Context
import androidx.core.app.NotificationCompat
import com.messenger.app.R

internal fun genericMessagePublicVersion(context: Context) = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_MESSAGES)
    .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.notif_new_message))
    .setContentText(context.getString(R.string.notif_tap_to_open)).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
