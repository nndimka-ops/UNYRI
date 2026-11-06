package com.messenger.app.data.directtransfer

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import com.messenger.app.MessengerApp

/** Holds OS lifetime only; all Signal, RTC, file and admission work stays in the coordinator. */
class DirectTransferJobService : JobService() {
    private var owned: Pair<String, Int>? = null
    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val id = params.extras.getString(DirectTransferBackgroundController.EXTRA_SESSION) ?: return false
        val epoch = params.extras.getInt(DirectTransferBackgroundController.EXTRA_EPOCH, -1)
        val controller = (application as MessengerApp).directBackground
        return try {
            controller.attach(id, epoch, show = { notification ->
                setNotification(params, DirectTransferBackgroundController.NOTIFICATION_ID, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
                controller.remainingNetworkBytes(id, epoch)?.let { (download, upload) -> updateEstimatedNetworkBytes(params, download, upload) }
            }, finish = { if (owned == (id to epoch)) { owned = null; jobFinished(params, false) } }).also { attached ->
                if (attached) owned = id to epoch
            }
        } catch (_: Exception) { controller.stop(id, epoch); false }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val id = params.extras.getString(DirectTransferBackgroundController.EXTRA_SESSION) ?: return false
        val epoch = params.extras.getInt(DirectTransferBackgroundController.EXTRA_EPOCH, -1)
        if (owned == (id to epoch)) owned = null
        (application as MessengerApp).directBackground.stop(id, epoch)
        // No OS reschedule can override an ambiguous/user stop. Explicit Continue creates a fresh lease.
        return false
    }
}
