package com.messenger.app.push

import android.app.*
import android.content.*
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.domain.model.AuthSessionState

/** Local-only reminder: never includes message text, and never displays for another account. */
class ChatReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { restore(context); return }
        val owner = intent.getStringExtra("owner") ?: return
        val user = ((context.applicationContext as MessengerApp).authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id
        if (user != owner) return
        val chat = intent.getStringExtra("chat") ?: return
        val message = intent.getStringExtra("message") ?: return
        context.getSharedPreferences("chat-reminder-alarms", Context.MODE_PRIVATE).edit().remove("$owner/$chat/$message").commit()
        val settings = (context.applicationContext as MessengerApp).notificationSettings.current
        if (!settings.messageAlertsEnabled) return
        NotificationChannels.ensure(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setData(Uri.parse("coco://reminder/$owner/$chat/$message"))
            .putExtra(MessagePushHandlerImpl.EXTRA_OWNER_ID, owner)
            .putExtra(MessagePushHandlerImpl.EXTRA_CHAT_ID, chat).putExtra("reminder_message_id", message)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_MESSAGES)
            .setSmallIcon(com.messenger.app.R.drawable.ic_notification)
            .setContentTitle(context.getString(com.messenger.app.R.string.chat_remind))
            .setContentText(context.getString(com.messenger.app.R.string.chat_return_message))
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSilent(!settings.messageSound).build()
        try { NotificationManagerCompat.from(context).notify("reminder:$owner:$chat:$message", 0, notification) } catch (_: SecurityException) { }
    }
    companion object {
        private fun pending(context: Context, owner: String, chat: String, message: String) = PendingIntent.getBroadcast(context, 0,
            Intent(context, ChatReminderReceiver::class.java).setData(Uri.parse("coco://reminder/$owner/$chat/$message"))
                .putExtra("owner", owner).putExtra("chat", chat).putExtra("message", message),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun schedule(context: Context, owner: String, chat: String, message: String, at: Long): Boolean {
            if (((context.applicationContext as MessengerApp).authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id != owner) return false
            if (!(context.applicationContext as MessengerApp).notificationSettings.current.messageAlertsEnabled) return false
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, owner, chat, message))
            val saved = context.getSharedPreferences("chat-reminder-alarms", Context.MODE_PRIVATE).edit().putLong("$owner/$chat/$message", at).commit()
            if (!saved) context.getSystemService(AlarmManager::class.java).cancel(pending(context, owner, chat, message))
            return saved
        }
        fun restore(context: Context) {
            val owner = ((context.applicationContext as MessengerApp).authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: return
            for ((key, value) in context.getSharedPreferences("chat-reminder-alarms", Context.MODE_PRIVATE).all) {
                val parts = key.split("/")
                if (parts.size == 3 && parts[0] == owner && value is Long) {
                    context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(value, System.currentTimeMillis() + 1000), pending(context, owner, parts[1], parts[2]))
                }
            }
        }
        fun clear(context: Context) {
            val prefs = context.getSharedPreferences("chat-reminder-alarms", Context.MODE_PRIVATE)
            for (key in prefs.all.keys.toList()) {
                val parts = key.split("/")
                if (parts.size == 3) cancel(context, parts[0], parts[1], parts[2])
            }
            prefs.edit().clear().commit()
        }
        fun cancel(context: Context, owner: String, chat: String, message: String) {
            context.getSharedPreferences("chat-reminder-alarms", Context.MODE_PRIVATE).edit().remove("$owner/$chat/$message").commit()
            context.getSystemService(AlarmManager::class.java).cancel(pending(context, owner, chat, message))
            NotificationManagerCompat.from(context).cancel("reminder:$owner:$chat:$message", 0)
        }
    }
}
