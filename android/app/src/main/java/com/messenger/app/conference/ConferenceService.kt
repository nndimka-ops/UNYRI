package com.messenger.app.conference

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.R

/** Separate foreground ticket: private calls and conferences never share media ownership. */
class ConferenceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as MessengerApp).conferenceController
        val ticket = intent?.getLongExtra("generation", -1L) ?: -1L
        if (controller.serviceGeneration != ticket) { if (!controller.busy) stopSelfResult(startId); return START_NOT_STICKY }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("conference_active", "Conference in progress", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
            val open = PendingIntent.getActivity(this, 4320, Intent(this, MainActivity::class.java).putExtra("return_conference", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this, "conference_active").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(controller.state.value.title).setContentText("Вернуться в конференцию").setContentIntent(open).setOngoing(true).setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or if (intent?.getBooleanExtra("camera", false) == true) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            if (Build.VERSION.SDK_INT >= 29) startForeground(4320, notification, type) else startForeground(4320, notification)
            controller.serviceReady(ticket)
        } catch (error: Exception) { controller.serviceReady(ticket, error); stopSelfResult(startId) }
        return START_NOT_STICKY
    }
}
