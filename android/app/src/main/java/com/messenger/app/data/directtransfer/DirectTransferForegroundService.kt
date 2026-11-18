package com.messenger.app.data.directtransfer

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.messenger.app.MessengerApp

/** API26–33 fallback; never sticky, never starts from boot, push, or a list/network event. */
class DirectTransferForegroundService : Service() {
    private var owned: Pair<String, Int>? = null
    private var wake: PowerManager.WakeLock? = null
    private var wakeRenewAt = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(DirectTransferBackgroundController.EXTRA_SESSION)
        val epoch = intent?.getIntExtra(DirectTransferBackgroundController.EXTRA_EPOCH, -1) ?: -1
        if (Build.VERSION.SDK_INT >= 34 || id == null) { stopSelf(startId); return START_NOT_STICKY }
        val controller = (application as MessengerApp).directBackground
        val attached = try {
            controller.attach(id, epoch, show = { notification ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (now >= wakeRenewAt) {
                    val lock = wake ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:direct-transfer").also {
                        it.setReferenceCounted(false); wake = it
                    }
                    lock.acquire(10 * 60 * 1000L); wakeRenewAt = now + 5 * 60 * 1000L
                }
                if (Build.VERSION.SDK_INT >= 29) startForeground(DirectTransferBackgroundController.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else startForeground(DirectTransferBackgroundController.NOTIFICATION_ID, notification)
            }, finish = { if (owned == (id to epoch)) { owned = null; releaseWake(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } })
        } catch (_: Exception) { controller.stop(id, epoch); false }
        if (attached) owned = id to epoch else stopSelf(startId)
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        owned?.let { (application as MessengerApp).directBackground.stop(it.first, it.second) }
        releaseWake(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        owned?.let { (application as MessengerApp).directBackground.stop(it.first, it.second) }
        owned = null
        releaseWake()
        super.onDestroy()
    }
    private fun releaseWake() { wake?.let { if (it.isHeld) it.release() }; wake = null; wakeRenewAt = 0 }
}
