package com.messenger.app.push

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.MessengerApp
import com.messenger.app.domain.model.AuthSessionState
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColdReminderInstrumentedTest {
    @Test fun alarmColdProcessPostsDueReminderAfterApplicationSettingsInitialization() {
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val owner = (app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user
        assumeTrue(owner?.username in setOf("test", "Dima2") && app.notificationSettings.current.reminderAlertsEnabled)
        assumeTrue(NotificationManagerCompat.from(app).areNotificationsEnabled())
        val device = app.authRepository.currentDeviceId()!!
        val chat = UUID.randomUUID().toString(); val message = UUID.randomUUID().toString()
        val key = "${owner!!.id}/$chat/$message"
        val plans = app.getSharedPreferences("chat-reminder-alarms", Context.MODE_PRIVATE)
        val manager = app.getSystemService(NotificationManager::class.java)
        val tag = ChatReminderWorkspacePolicy.notificationTag(app.accountWorkspaces.activeKey, owner.id, chat, message)
        assertTrue(plans.edit().putLong(key, System.currentTimeMillis() - 100).commit())
        val finished = CountDownLatch(1)
        var receiverPid = -1
        try {
            app.sendOrderedBroadcast(Intent().setComponent(ComponentName(app, "com.messenger.app.push.ColdReminderTestReceiver"))
                .putExtra(ChatReminderReceiver.EXTRA_WORKSPACE, app.accountWorkspaces.activeKey)
                .putExtra("owner", owner.id).putExtra("device", device).putExtra("chat", chat).putExtra("message", message),
                null, object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) { receiverPid = resultCode; finished.countDown() }
                }, null, -1, null, null)
            assertTrue("cold alarm did not complete", finished.await(30, TimeUnit.SECONDS))
            assertTrue("fixture must cold-start another process", receiverPid > 0 && receiverPid != Process.myPid())
            assertTrue("due plan was pruned before receiver could notify", manager.activeNotifications.any { it.tag == tag })
        } finally {
            manager.cancel(tag, 0)
            plans.edit().remove(key).commit()
        }
    }
}
