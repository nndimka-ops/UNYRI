package com.messenger.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process

/** Debug-only process fixture: real Application initialization precedes real alarm delivery. */
class ColdReminderTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ChatReminderReceiver().onReceive(context, intent)
        resultCode = Process.myPid()
        // Finish the ordered broadcast before discarding this isolated QA process.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 1000)
    }
}
