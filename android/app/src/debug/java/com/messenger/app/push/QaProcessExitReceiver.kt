package com.messenger.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process

/** Shell-only debug QA: an ordinary process death, without the force-stop flag. */
class QaProcessExitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Process.killProcess(Process.myPid())
    }
}
