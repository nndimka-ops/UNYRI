package com.messenger.app.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.ui.theme.LanguagePreference
import com.messenger.app.ui.theme.applyLocale
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** User-enabled local connection; shares the authenticated socket and dedup pipeline. */
class LocalMessagingService : Service() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.applyLocale(LanguagePreference(newBase).current()))
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as MessengerApp
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.localMessaging.setEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!app.localMessaging.enabled.value || app.authRepository.authState.value !is AuthSessionState.LoggedIn) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.local_mode_title), NotificationManager.IMPORTANCE_LOW))
        try {
            val notification = notification(app.localMessaging.connection.value)
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, notification)
        } catch (e: RuntimeException) {
            android.util.Log.w("LocalMessaging", "Cannot enter foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        app.localMessaging.running.value = true
        if (!observing) {
            observing = true
            scope.launch {
                app.authRepository.authState.collect { state ->
                    if (state !is AuthSessionState.LoggedIn) stopSelf()
                }
            }
            scope.launch {
                app.localMessaging.connection.collect { state -> manager.notify(ID, notification(state)) }
            }
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "COCO:LocalMessaging")
                .apply { setReferenceCounted(false) }
            scope.launch {
                while (true) {
                    wakeLock?.acquire(10 * 60 * 1000L)
                    delay(5 * 60 * 1000L)
                }
            }
        }
        return START_STICKY
    }

    private fun notification(state: SocketConnectionState): android.app.Notification {
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, ID, Intent(this, LocalMessagingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.messenger.app.R.drawable.ic_notification)
            .setContentTitle(getString(R.string.local_mode_title))
            .setContentText(getString(if (state == SocketConnectionState.CONNECTED) R.string.local_mode_connected else R.string.local_mode_reconnecting))
            // A user can raise this channel's importance in Android settings.
            // Connection status must remain silent even then: a heads-up from
            // the foreground service can cover the app's navigation on launch.
            // Keep the user's channel settings and real message/call alerts.
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.local_mode_stop), stop)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        app.localMessaging.running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "local_connection"
        private const val ID = 9107
        private const val ACTION_STOP = "com.messenger.app.local.STOP"
    }
}
