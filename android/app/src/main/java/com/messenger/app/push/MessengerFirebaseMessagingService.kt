package com.messenger.app.push

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.messenger.app.BuildConfig
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp

/** FCM entry point only (Этап K). No business logic, no networking, no
 *  crypto, no media here - onMessageReceived parses and routes; onNewToken
 *  hands the token to the registration repository. */
class MessengerFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val app = applicationContext as? MessengerApp ?: return
        val data = message.data
        val event = PushPayloadParser.parse(data)
        // Release diagnostics contain no tokens, identifiers or message content.
        val kind = when (event) {
            is PushEvent.IncomingCall -> "incoming_call"
            is PushEvent.CallEnded -> "call_ended"
            is PushEvent.NewMessage -> "new_message"
            is PushEvent.AdminNotice -> "admin_notice"
            is PushEvent.Unknown -> "unknown"
        }
        Log.i("FcmTimeline", "received type=$kind priority=${message.priority} original_priority=${message.originalPriority}")
        // Firebase calls this on a worker thread. Keep its process-lifetime
        // allowance until the bounded call lookup AND notification post finish.
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                val owner = (app.authRepository.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id
                // FCM is not tied to a live authenticated socket. Server outbox binds every recipient.
                if (owner == null || event.userId != owner) {
                    Log.i("FcmTimeline", "ignored: recipient/session mismatch")
                    return@withContext
                }
                if (event is PushEvent.IncomingCall) {
                    val job = app.callController.showIncomingCallFromPush(event.callId, event.mediaKind)
                    if (job != null && kotlinx.coroutines.withTimeoutOrNull(4_500L) { job.join(); true } != true) {
                        Log.i("IncomingWake", "callback_deadline")
                        job.cancel()
                    }
                } else if (event is PushEvent.NewMessage) app.routeMessagePush(event)
                else if (event is PushEvent.AdminNotice) postAdminNotice(event)
                else app.pushRouter.route(event)
            }
        }
    }

    override fun onNewToken(token: String) {
        val app = applicationContext as? MessengerApp ?: return
        app.pushRegistrationRepository.onNewToken(token)
    }

    private fun postAdminNotice(event: PushEvent.AdminNotice) {
        val preferences = (applicationContext as? MessengerApp)?.notificationSettings?.current ?: return
        if (!preferences.enabled) return
        NotificationChannels.ensure(this)
        val openApp = PendingIntent.getActivity(
            this,
            event.noticeId.hashCode() and 0x7FFFFFFF,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, NotificationChannels.CHANNEL_MESSAGES)
            .setSmallIcon(com.messenger.app.R.drawable.ic_notification)
            .setContentTitle(event.title)
            .setContentText(event.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setSilent(!preferences.messageSound)
            .build()
        postNotificationIfPermitted(this, "admin:${event.noticeId}", 0, notification)
    }
}
