package com.messenger.app.data.directtransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.core.app.NotificationManagerCompat
import com.messenger.app.MainActivity
import com.messenger.app.R
import com.messenger.app.domain.repository.AuthRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** One OS execution lease for the existing bounded OUT/IN coordinator, never another data worker. */
internal class DirectTransferBackgroundController(
    private val context: Context,
    private val auth: AuthRepository,
    private val foreground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {
    @Volatile private var session: DirectExecutionSession? = null
    @Volatile private var progress: DirectExecutionProgress? = null
    @Volatile private var lastBusyAt = 0L
    @Volatile private var requestedAt = 0L
    @Volatile var failureReason: String? = null
        private set
    var interrupt: suspend (String, Int, Set<String>?) -> Unit = { _, _, _ -> }
    var disarm: suspend (String, Int, Boolean) -> Boolean = { _, _, _ -> false }
    var changed: () -> Unit = {}
    private var finishHost: (() -> Unit)? = null
    private var showHost: ((Notification) -> Unit)? = null
    @Volatile private var draining = false

    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.direct_background_channel), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) },
        )
        scope.launch {
            foreground.collect { visible -> if (!visible) changed() }
        }
        scope.launch {
            auth.authState.collect {
                val live = session
                if (live != null && auth.currentSessionEpoch() != live.epoch) retire(live.id, live.epoch)
            }
        }
        scope.launch {
            while (true) {
                delay(1000)
                val live = session ?: continue
                if (live.revoked) continue
                val now = android.os.SystemClock.elapsedRealtime()
                if (auth.currentSessionEpoch() != live.epoch) { retire(live.id, live.epoch); continue }
                val value = progress?.takeIf { it.session == live.id && it.epoch == live.epoch }
                if (live.granted && !notificationsVisible()) { stop(live.id, live.epoch); continue }
                if (value?.busy == true) lastBusyAt = now
                // No permanent offline/passive polling lease. Prefix/final hashes and source staging count as busy.
                if (value != null && !value.busy && !value.unfinished && now - requestedAt >= 2000) finishNaturally(live.id, live.epoch)
                else if ((live.granted || !foreground.value) && now - maxOf(lastBusyAt, requestedAt) >= IDLE_MILLIS) finishNaturally(live.id, live.epoch, idle = true)
                else if (live.granted) try { showHost?.invoke(notification(live.id, live.epoch)) }
                    catch (_: Exception) { stop(live.id, live.epoch) }
            }
        }
    }

    /** Invoked synchronously only at an actual foreground Send/Accept/Continue/Retry. */
    fun request(epoch: Int): String? = request(epoch) { true }

    /** Publish exact pending selection ownership under the lease/disarm monitor. */
    @Synchronized fun request(epoch: Int, register: (String) -> Boolean): String? {
        if (!foreground.value || auth.currentSessionEpoch() != epoch) return null
        if (draining) { failureReason = "background_unavailable"; return null }
        session?.takeIf { it.epoch != epoch }?.let { retire(it.id, it.epoch) }
        session?.takeIf { it.epoch == epoch && !it.revoked && failureReason == null }?.let {
            if (register(it.id)) return it.id
            failureReason = "background_unavailable"; return null
        }
        val retry = session?.takeIf { it.epoch == epoch && !it.revoked }
        val live = retry ?: DirectExecutionSession(UUID.randomUUID().toString(), epoch)
        if (!register(live.id)) { failureReason = "background_unavailable"; return null }
        session = live; if (retry == null) progress = null
        requestedAt = android.os.SystemClock.elapsedRealtime(); lastBusyAt = requestedAt
        failureReason = null
        if (!notificationsVisible()) { failureReason = "background_notification_required"; return live.id }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val extras = PersistableBundle().apply { putString(EXTRA_SESSION, live.id); putInt(EXTRA_EPOCH, epoch) }
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, DirectTransferJobService::class.java))
                    .setUserInitiated(true)
                    .setRequiredNetwork(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build())
                    .setEstimatedNetworkBytes(JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
                    .setExtras(extras).build()
                if (scheduler().schedule(job) != JobScheduler.RESULT_SUCCESS) failureReason = "background_unavailable"
            } else {
                context.startForegroundService(Intent(context, DirectTransferForegroundService::class.java)
                    .putExtra(EXTRA_SESSION, live.id).putExtra(EXTRA_EPOCH, epoch))
            }
        } catch (_: Exception) { failureReason = "background_unavailable" }
        return live.id
    }

    fun mayRun(marker: String?, interrupted: Boolean): Boolean = DirectExecutionPolicy.mayRun(
        marker, interrupted, foreground.value, session, auth.currentSessionEpoch(),
    )
    fun currentSession(): DirectExecutionSession? = session
    fun foregroundVisible(): Boolean = foreground.value
    fun mayStartCapability(epoch: Int): Boolean = foreground.value || session?.let { it.epoch == epoch && it.granted && !it.revoked } == true
    fun update(value: DirectExecutionProgress) { if (session?.let { it.id == value.session && it.epoch == value.epoch } == true) progress = value }
    fun remainingNetworkBytes(id: String, epoch: Int): Pair<Long, Long>? {
        val value = progress?.takeIf { it.session == id && it.epoch == epoch } ?: return null
        val remaining = value.entries.filterNot { it.finished || it.held }
        return remaining.filterNot { it.outgoing }.sumOf { (it.size - it.offset).coerceAtLeast(0) } to
            remaining.filter { it.outgoing }.sumOf { (it.size - it.offset).coerceAtLeast(0) }
    }

    /** A cold/old OS callback cannot recover a former process's lease. */
    @Synchronized fun attach(id: String, epoch: Int, show: (Notification) -> Unit, finish: () -> Unit): Boolean {
        val live = session ?: return false
        if (live.id != id || live.epoch != epoch || live.revoked || epoch != auth.currentSessionEpoch() || !notificationsVisible()) return false
        show(notification(id, epoch))
        showHost = show; finishHost = finish
        session = live.copy(granted = true); failureReason = null
        changed()
        return true
    }

    /** Revocation is synchronous; a callback cannot write another byte while its checkpoint is pending. */
    fun stop(id: String, epoch: Int) {
        synchronized(this) {
            val live = session ?: return
            if (live.id != id || live.epoch != epoch || epoch != auth.currentSessionEpoch() || live.revoked) return
            session = live.copy(revoked = true); draining = true
        }
        scope.launch {
            try { interrupt(id, epoch, null) }
            finally { retire(id, epoch) }
        }
    }

    /** Known quiescence is not an OS/user stop and creates no new manual/interrupt hold. */
    private fun finishNaturally(id: String, epoch: Int, idle: Boolean = false) {
        synchronized(this) {
            val live = session ?: return
            if (live.id != id || live.epoch != epoch || live.revoked || draining) return
            draining = true
        }
        scope.launch {
            val finished = try { disarm(id, epoch, idle) } catch (_: Exception) { stop(id, epoch); false }
            synchronized(this@DirectTransferBackgroundController) {
                val live = session
                if (live?.id == id && live.epoch == epoch && !live.revoked) {
                    if (finished) retire(id, epoch) else draining = false
                }
                // A concurrent real Stop owns its checkpoint and OS release.
            }
        }
    }

    private fun retire(id: String, epoch: Int) {
        synchronized(this) {
            val live = session ?: return
            if (live.id != id || live.epoch != epoch) return
            // Keep request() excluded until all old OS handles and notification cleanup finish.
            draining = true; session = live.copy(revoked = true)
            val finish = finishHost; finishHost = null; showHost = null
            progress = null
            try {
                finish?.invoke()
                if (Build.VERSION.SDK_INT >= 34) scheduler().cancel(JOB_ID)
                else context.stopService(Intent(context, DirectTransferForegroundService::class.java))
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            } finally { draining = false }
        }
    }

    fun notification(id: String, epoch: Int): Notification {
        val value = progress?.takeIf { it.session == id && it.epoch == epoch }
        val entries = value?.entries.orEmpty().filterNot { it.finished || it.held }
        val total = entries.sumOf { it.size }
        val offset = entries.sumOf { it.offset.coerceIn(0, it.size) }
        val text = entries.take(2).joinToString("\n") { entry ->
            val direction = context.getString(if (entry.outgoing) R.string.direct_filter_sending else R.string.direct_filter_receiving)
            "$direction · ${entry.filename.take(80)} · ${if (entry.size > 0) entry.offset * 100 / entry.size else 0}%"
        }.ifEmpty { context.getString(if (value?.staging == true) R.string.direct_manager_preparing else R.string.direct_waiting) }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.direct_background_title)).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open)
            .setCategory(Notification.CATEGORY_PROGRESS).setOnlyAlertOnce(true).setOngoing(true)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setProgress(1000, if (total > 0) (offset * 1000 / total).toInt() else 0, total == 0L)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.direct_background_stop), stopIntent(id, epoch)).build())
            .build()
    }

    private fun stopIntent(id: String, epoch: Int): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, DirectTransferStopReceiver::class.java).setAction(ACTION_STOP)
            .setData(Uri.parse("coco-direct://execution/$id/$epoch"))
            .putExtra(EXTRA_SESSION, id).putExtra(EXTRA_EPOCH, epoch), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notificationsVisible(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    private fun scheduler(): JobScheduler = context.getSystemService(JobScheduler::class.java).forNamespace("coco.direct-transfer")

    companion object {
        const val EXTRA_SESSION = "direct_execution_session"
        const val EXTRA_EPOCH = "direct_execution_epoch"
        const val ACTION_STOP = "com.messenger.app.directtransfer.STOP"
        const val CHANNEL = "direct_transfers"
        const val NOTIFICATION_ID = 0x434f434f
        const val JOB_ID = 1
        const val IDLE_MILLIS = 90_000L
    }
}
