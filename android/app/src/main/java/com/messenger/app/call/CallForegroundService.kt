package com.messenger.app.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.domain.call.CallForegroundServiceTicket
import androidx.core.content.ContextCompat

/** Foreground service alive only while a call is Connecting/Connected
 *  (Этап J п.44) - started/stopped exclusively by [CallForegroundServiceController]
 *  reacting to [com.messenger.app.domain.call.CallController.state]. Never
 *  left running after a call ends (that controller stops it on every
 *  terminal/Idle transition). */
class CallForegroundService : Service() {
    private var promotedTicket: CallForegroundServiceTicket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as? MessengerApp)?.callController
        val ticket = intent?.let { value ->
            val id = value.getStringExtra(EXTRA_CALL_ID) ?: return@let null
            val owner = value.getStringExtra(EXTRA_OWNER) ?: return@let null
            val generation = value.getLongExtra(EXTRA_GENERATION, Long.MIN_VALUE)
            if (generation == Long.MIN_VALUE) null else CallForegroundServiceTicket(id, generation, owner)
        }
        val current = controller?.foregroundServiceTicket()
        if (controller == null || ticket == null || ticket != current) {
            // A delayed old start must not remove the newer call's already
            // promoted service. Otherwise discard it without opening media.
            if (shouldStopStaleCallService(promotedTicket, current)) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val request = callForegroundServiceRequest(controller.state.value, current,
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        if (request == null) { stopSelfResult(startId); return START_NOT_STICKY }
        val admitted = admitCallForegroundService(ticket, controller::foregroundServiceTicket,
            onDenied = { controller.foregroundServiceUnavailable(it); Unit }) {
            // Re-read the authoritative current camera intent; an old queued
            // CAMERA request must not upgrade a now receive-only audio call.
            val notification = buildNotification(request.title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(CallNotificationIds.ACTIVE_CALL, notification, request.foregroundType)
            } else {
                startForeground(CallNotificationIds.ACTIVE_CALL, notification)
            }
            true
        }
        if (admitted) promotedTicket = ticket
        else {
            val now = controller.foregroundServiceTicket()
            if (now == null || now == ticket) {
                promotedTicket = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            } else if (shouldStopStaleCallService(promotedTicket, now)) {
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(title: String): android.app.Notification {
        ensureChannel()
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(com.messenger.app.R.string.notif_call_return))
            .setSmallIcon(com.messenger.app.R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // The active-call FGS notification is informational: the call
            // screen owns the UI and the ringing sound already stopped when
            // the call was answered. A high-importance channel with an
            // implicit default sound adds a second alert right after pickup.
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Call in progress", NotificationManager.IMPORTANCE_LOW).apply {
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_FOREGROUND_TYPE = "foreground_type"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_GENERATION = "call_generation"
        const val EXTRA_OWNER = "call_owner"
        private const val CHANNEL_ID = "calls_ongoing"
    }
}
