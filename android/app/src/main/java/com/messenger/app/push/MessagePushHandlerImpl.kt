package com.messenger.app.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.messenger.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Privacy-safe message notification (Этап K.6/K.13 + N.2R §104/106/120):
 *  the backend never sends plaintext and this handler never displays any
 *  payload-derived content - a generic "New message" notification only.
 *
 *  This is the SINGLE notification entry point for messages, shared by both
 *  the FCM path and the LAN WebSocket path, so the dedup rules below apply
 *  uniformly:
 *  - foreground user -> no notification (the live UI already shows it);
 *  - currently open chat -> no notification (that chat is on screen);
 *  - per-chat sequence dedup -> a late FCM push after the same message
 *    already arrived via WS does not produce a second notification;
 *  - active call -> still post to the shade but silently (no sound/heads-up
 *    over the call UI).
 *
 *  Grouped per chat with a stable per-chat notification id so a burst of
 *  messages coalesces into one. */
class MessagePushHandlerImpl(
    private val context: Context,
    private val isForeground: () -> Boolean,
    private val activeChatId: () -> String? = { null },
    private val activeCall: () -> Boolean = { false },
    private val isSilenced: (String) -> Boolean = { false },
    private val sessionEpoch: () -> Int = { 0 },
    private val scope: CoroutineScope? = null,
    private val resolveAvatar: suspend (String) -> NotificationAvatar? = { null },
    private val currentUserId: () -> String? = { null },
    private val settings: () -> NotificationSettings = { NotificationSettings() },
    private val currentServer: () -> String = { "" },
) : MessagePushHandler {

    /** Highest server `sequence` already notified per chat (N.2R §106). */
    private val sequences = NotificationSequenceLedger()
    private val batchAlerts = NotificationBatchAlertLedger()

    private val decorations = mutableMapOf<String, Job>()
    private var notificationSession: NotificationSessionKey? = null
    private var notificationServer: String? = null

    private fun currentSession() = NotificationSessionKey(sessionEpoch(), currentUserId())

    private fun prepareSession(session: NotificationSessionKey) {
        val origin = currentServer()
        if (notificationSession != session || notificationServer != origin) {
            decorations.values.forEach { it.cancel() }
            decorations.clear()
            sequences.clear()
            batchAlerts.clear()
            notificationSession = session
            notificationServer = origin
        }
    }

    /** Consumes a gate-suppressed WS/FCM event without any presentation calls.
     * The capture predates the gate's suspend; re-enabling alerts or switching
     * accounts during that wait must never turn suppression into a late alert. */
    @Synchronized
    fun consumeSuppressed(event: PushEvent.NewMessage, expectedEpoch: Int, expectedUserId: String) {
        val current = currentSession()
        val expected = NotificationSessionKey(expectedEpoch, expectedUserId)
        if (expected != current || !isForCurrentUser(event.userId, current.userId)) return
        prepareSession(current)
        sequences.record(event, expected, current)
    }

    @Synchronized
    override fun onNewMessage(event: PushEvent.NewMessage) = onNewMessage(event, false)

    @Synchronized
    fun onNewMessage(event: PushEvent.NewMessage, forceSilent: Boolean, metadata: NotificationMessageMetadata? = null) {
        val session = currentSession()
        val epoch = session.epoch
        prepareSession(session)
        // P0 hardening: a delayed FCM push for a PREVIOUS account must never
        // surface under the current account. The backend binds the recipient
        // user_id into the payload; the physical FCM token is shared across
        // accounts, so this is the authoritative guard (the WS path carries no
        // user_id but is inherently session-scoped by the authenticated socket).
        if (!isForCurrentUser(event.userId, session.userId)) {
            return
        }
        val owner = session.userId ?: return
        val batchSession = NotificationBatchSession(currentServer(), owner, epoch)
        if (metadata != null && !metadata.isReadableFor(event, batchSession)) return
        val batch = metadata?.batch()

        if (!settings().messageAlertsEnabled || isSilenced(event.chatId)) {
            decorations.remove(event.chatId)?.cancel()
            sequences.record(event, session, session)
            NotificationManagerCompat.from(context).cancel("chat:${event.chatId}", 0)
            return
        }

        // Foreground uses only the in-app banner, which itself suppresses
        // the conversation currently visible. Background still uses the shade.
        if (!shouldNotify(isForeground(), activeChatId(), event.chatId)) {
            decorations.remove(event.chatId)?.cancel()
            sequences.record(event, session, session)
            NotificationManagerCompat.from(context).cancel("chat:${event.chatId}", 0)
            return
        }

        val sequence = event.sequence.toLongOrNull()
        // A delayed exact-row lookup can bind an already posted generic wake
        // to its batch without replaying that message's alert.
        batchAlerts.learnResolved(event.messageId, batch, batchSession)
        if (!sequences.shouldPresent(event, session)) return
        val shouldAlertBatch = batchAlerts.shouldAlert(event.messageId, batch, batchSession)

        decorations.remove(event.chatId)?.cancel()
        NotificationChannels.ensure(context)

        val openApp = PendingIntent.getActivity(
            context,
            requestCodeFor(event.chatId),
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ).setData(android.net.Uri.parse("coco://notification/chat/${event.chatId}"))
                .putExtra(EXTRA_CHAT_ID, event.chatId)
                .putExtra(EXTRA_OWNER_ID, currentUserId()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_MESSAGES)
            .setSmallIcon(com.messenger.app.R.drawable.ic_notification)
            .setContentTitle(context.getString(com.messenger.app.R.string.notif_new_message))
            .setContentText(context.getString(com.messenger.app.R.string.notif_tap_to_open))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup("chat:${event.chatId}")
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(!shouldAlertBatch)

        // N.2R §120/121: while a COCO call is active, message notifications
        // must not interrupt it - still land in the shade, but silently.
        if (activeCall() || forceSilent || !settings().messageSound) builder.setSilent(true)
        if (!shouldAlertBatch) {
            // Existing updates keep the first sound playing. If the user
            // already dismissed it, a later member still must not ring again.
            val active = runCatching {
                context.getSystemService(android.app.NotificationManager::class.java)
                    .activeNotifications.any { it.tag == "chat:${event.chatId}" && it.id == 0 }
            }.getOrDefault(false)
            if (!active) builder.setSilent(true)
        }

        runCatching {
            if (!postNotificationIfPermitted(context, "chat:${event.chatId}", 0, builder.build())) return
            sequences.record(event, session, session)
            batchAlerts.recordPosted(event.messageId, batch, batchSession)
            val owner = currentUserId()
            val epoch = sessionEpoch()
            val postedAt = System.currentTimeMillis()
            scope?.let { worker ->
                if (decorations.size >= 32) decorations.keys.firstOrNull()?.let { decorations.remove(it)?.cancel() }
                decorations[event.chatId] = worker.launch {
                    val person = loadNotificationDecoration { resolveAvatar(event.chatId)?.person() } ?: return@launch
                    synchronized(this@MessagePushHandlerImpl) {
                        if (epoch != sessionEpoch() || owner == null || owner != currentUserId() || isForeground() ||
                            !settings().messageAlertsEnabled || isSilenced(event.chatId) ||
                            (sequence != null && sequences.highest(event.chatId, session) != sequence)) return@synchronized
                        val manager = context.getSystemService(android.app.NotificationManager::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= 23 && manager.activeNotifications.none { it.tag == "chat:${event.chatId}" }) return@synchronized
                        val me = androidx.core.app.Person.Builder().setName("UNYRI").build()
                        builder.setStyle(NotificationCompat.MessagingStyle(me).setGroupConversation(false)
                            .addMessage(context.getString(com.messenger.app.R.string.notif_tap_to_open), postedAt, person))
                            // Update decoration without a second alert, but do not
                            // remove the sound of the notification already playing.
                            .setOnlyAlertOnce(true)
                        runCatching { postNotificationIfPermitted(context, "chat:${event.chatId}", 0, builder.build()) }
                    }
                }
            }
        }
    }

    @Synchronized
    fun clearForLogout() {
        clearVisible()
        sequences.clear()
        batchAlerts.clear()
        notificationSession = null
        notificationServer = null
    }

    /** Turning alerts off clears presentation without forgetting replay/dedup state. */
    @Synchronized
    fun clearVisible() {
        decorations.values.forEach { it.cancel() }
        decorations.clear()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith("chat:") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun requestCodeFor(chatId: String): Int = (chatId.hashCode() and 0x7FFFFFFF)

    companion object {
        const val EXTRA_CHAT_ID = "notification_chat_id"
        const val EXTRA_OWNER_ID = "notification_owner_id"
        /** In-app banner in foreground; system notification in background. */
        @Suppress("UNUSED_PARAMETER") // Preserve the shared policy call signature.
        fun shouldNotify(isForeground: Boolean, activeChatId: String?, eventChatId: String): Boolean =
            !isForeground

        /** A push without a bound user_id (authenticated WS path) is only
         *  considered while logged in; a push WITH a bound user_id is
         *  surfaced only when it matches the currently logged-in account. */
        fun isForCurrentUser(eventUserId: String?, currentUserId: String?): Boolean =
            currentUserId != null && (eventUserId == null || eventUserId == currentUserId)
    }
}
