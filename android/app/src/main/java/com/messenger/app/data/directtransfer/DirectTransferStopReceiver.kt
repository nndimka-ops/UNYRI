package com.messenger.app.data.directtransfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.messenger.app.MessengerApp

/** Explicit immutable PendingIntent; stale account/session notifications have no authority. */
class DirectTransferStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DirectTransferBackgroundController.ACTION_STOP) return
        val id = intent.getStringExtra(DirectTransferBackgroundController.EXTRA_SESSION) ?: return
        val epoch = intent.getIntExtra(DirectTransferBackgroundController.EXTRA_EPOCH, -1)
        (context.applicationContext as? MessengerApp)?.directBackground?.stop(id, epoch)
    }
}
