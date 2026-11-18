package com.messenger.app.push

import android.app.*
import android.content.*
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.data.auth.AccountWorkspaceLayout
import com.messenger.app.domain.model.AuthSessionState

/** Local-only reminder: never includes message text, and never displays for another account. */
class ChatReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { restoreAll(context); return }
        val app = context.applicationContext as MessengerApp
        val workspace = intent.getStringExtra(EXTRA_WORKSPACE) ?: AccountWorkspaceLayout.LEGACY
        val owner = intent.getStringExtra("owner") ?: return
        val account = if (workspace != app.accountWorkspaces.activeKey)
            NotificationAccounts(app).find(owner, intent.getStringExtra("device"), workspace) ?: return else null
        val storage = account?.storage ?: app
        val device = account?.session?.deviceId() ?: app.authRepository.currentDeviceId() ?: return
        if (account == null && ((app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id != owner ||
            intent.getStringExtra("device")?.let { it != device } == true)) return
        val chat = intent.getStringExtra("chat") ?: return
        val message = intent.getStringExtra("message") ?: return
        val target = NotificationTapTarget(workspace, owner, device, chat, message)
        if (!target.valid()) return
        val plans = preferences(storage)
        val key = "$owner/$chat/$message"
        if (!plans.contains(key) || plans.getLong(key, Long.MAX_VALUE) > System.currentTimeMillis()) return
        if (!plans.edit().remove(key).commit()) return
        val settings = NotificationSettingsStore(storage).current
        if (!settings.reminderAlertsEnabled) return
        NotificationChannels.ensure(context)
        val open = PendingIntent.getActivity(context, 0, NotificationIntents.open(context, target)
            .setData(Uri.parse(ChatReminderWorkspacePolicy.uri(workspace, owner, chat, message))),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_MESSAGES)
            .setSmallIcon(com.messenger.app.R.drawable.ic_notification)
            .setContentTitle(context.getString(com.messenger.app.R.string.chat_remind))
            .setContentText(context.getString(com.messenger.app.R.string.chat_return_message))
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSilent(!settings.messageSound).build()
        try { NotificationManagerCompat.from(context).notify(ChatReminderWorkspacePolicy.notificationTag(workspace, owner, chat, message), 0, notification) } catch (_: SecurityException) { }
    }
    companion object {
        const val EXTRA_WORKSPACE = "reminder_workspace"
        private fun workspace(context: Context) = (context.applicationContext as MessengerApp).accountWorkspaces.activeKey
        private fun preferences(context: Context) = context.getSharedPreferences("chat-reminder-alarms", Context.MODE_PRIVATE)
        private fun pending(context: Context, owner: String, chat: String, message: String,
            workspace: String = workspace(context), device: String? = (context.applicationContext as MessengerApp).authRepository.currentDeviceId()): PendingIntent {
            return PendingIntent.getBroadcast(context, 0,
                Intent(context, ChatReminderReceiver::class.java).setData(Uri.parse(ChatReminderWorkspacePolicy.uri(workspace, owner, chat, message)))
                    .putExtra(EXTRA_WORKSPACE, workspace)
                    .putExtra("device", device)
                    .putExtra("owner", owner).putExtra("chat", chat).putExtra("message", message),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        /** Replace pre-workspace PendingIntents only while their original workspace is active. */
        private fun cancelLegacyAlarm(context: Context, owner: String, chat: String, message: String) {
            if (workspace(context) != AccountWorkspaceLayout.LEGACY) return
            val legacy = PendingIntent.getBroadcast(context, 0,
                Intent(context, ChatReminderReceiver::class.java).setData(Uri.parse("coco://reminder/$owner/$chat/$message")),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
            context.getSystemService(AlarmManager::class.java).cancel(legacy)
            legacy.cancel()
        }
        fun schedule(context: Context, owner: String, chat: String, message: String, at: Long): Boolean {
            if (((context.applicationContext as MessengerApp).authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id != owner) return false
            if (!(context.applicationContext as MessengerApp).notificationSettings.current.reminderAlertsEnabled || at <= System.currentTimeMillis()) return false
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            cancelLegacyAlarm(context, owner, chat, message)
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, owner, chat, message))
            val saved = preferences(context).edit().putLong("$owner/$chat/$message", at).commit()
            if (!saved) context.getSystemService(AlarmManager::class.java).cancel(pending(context, owner, chat, message))
            return saved
        }
        fun restore(context: Context, startup: Boolean = false) {
            val owner = ((context.applicationContext as MessengerApp).authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: return
            restoreWorkspace(context, workspace(context), owner, (context.applicationContext as MessengerApp).authRepository.currentDeviceId(), startup)
        }
        private fun restoreAll(context: Context) {
            val app = context.applicationContext as MessengerApp
            restore(app)
            for (record in app.accountWorkspaces.accounts().filter { it.key != app.accountWorkspaces.activeKey }) {
                val storage = com.messenger.app.data.auth.AccountWorkspaceContext(app.baseContext, record.key)
                val session = runCatching { com.messenger.app.data.secure.SecureTokenStore(storage) }.getOrNull() ?: continue
                if (session.refreshToken() != null) session.userId()?.let { restoreWorkspace(storage, record.key, it, session.deviceId()) }
            }
        }
        private fun restoreWorkspace(context: Context, workspace: String, owner: String, device: String?, startup: Boolean = false) {
            val enabled = NotificationSettingsStore(context).current.reminderAlertsEnabled
            for ((key, value) in preferences(context).all) {
                val parts = key.split("/")
                if (parts.size == 3 && parts[0] == owner && value is Long) {
                    val action = ReminderPolicy.action(value, System.currentTimeMillis(), enabled, startup)
                    // Application.onCreate precedes alarm delivery. Do not cancel or
                    // remove the due plan the framework is about to give this receiver.
                    if (action == ReminderPolicy.Action.KEEP_DUE) continue
                    if (workspace == AccountWorkspaceLayout.LEGACY) cancelLegacyAlarm(context, owner, parts[1], parts[2])
                    val alarm = pending(context, owner, parts[1], parts[2], workspace, device)
                    val manager = context.getSystemService(AlarmManager::class.java)
                    when (action) {
                        ReminderPolicy.Action.SCHEDULE -> manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, value, alarm)
                        ReminderPolicy.Action.PAUSE -> manager.cancel(alarm)
                        ReminderPolicy.Action.DROP -> { manager.cancel(alarm); preferences(context).edit().remove(key).commit() }
                        ReminderPolicy.Action.KEEP_DUE -> Unit
                    }
                }
            }
            if (!enabled) context.getSystemService(NotificationManager::class.java).activeNotifications
                .filter { it.tag?.startsWith("reminder:$workspace:$owner:") == true }
                .forEach { context.getSystemService(NotificationManager::class.java).cancel(it.tag, it.id) }
        }
        fun clear(context: Context) {
            val prefs = preferences(context)
            for (key in prefs.all.keys.toList()) {
                val parts = key.split("/")
                if (parts.size == 3) cancel(context, parts[0], parts[1], parts[2])
            }
            prefs.edit().clear().commit()
        }
        fun cancel(context: Context, owner: String, chat: String, message: String) {
            preferences(context).edit().remove("$owner/$chat/$message").commit()
            context.getSystemService(AlarmManager::class.java).cancel(pending(context, owner, chat, message))
            cancelLegacyAlarm(context, owner, chat, message)
            NotificationManagerCompat.from(context).cancel(ChatReminderWorkspacePolicy.notificationTag(workspace(context), owner, chat, message), 0)
            if (workspace(context) == AccountWorkspaceLayout.LEGACY) NotificationManagerCompat.from(context).cancel("reminder:$owner:$chat:$message", 0)
        }
    }
}
