package com.messenger.app.data.auth

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import com.messenger.app.MainActivity

/** A private, short-lived process replaces the old dependency graph only after
 * its workspace selection is durable. No logout, copying credentials or database
 * replacement is involved. Old jobs retain their old workspace until they stop. */
class AccountSwitchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previousPid = intent.getIntExtra(EXTRA_PROCESS, -1)
        val previous = getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
            .firstOrNull { it.pid == previousPid && it.uid == Process.myUid() && it.processName == packageName }
        if (previous != null) Process.killProcess(previous.pid)
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.getBundleExtra("notification_route")?.let { open.putExtras(it) }
        startActivity(open)
        finish()
    }

    companion object {
        const val PROCESS_SUFFIX = ":account_switch"
        const val EXTRA_PROCESS = "previous_process"
    }
}
