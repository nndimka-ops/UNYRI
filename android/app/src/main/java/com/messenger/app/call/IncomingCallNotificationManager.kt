package com.messenger.app.call

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.messenger.app.MainActivity
import com.messenger.app.domain.call.CallController
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.model.CallMediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Shows exactly one incoming-call notification while the app is backgrounded
 *  and [CallController] is in [CallUiState.IncomingRinging] (Этап J.1-4).
 *  Call state is the single source of truth: accept/reject/cancel/timeout/
 *  answered-elsewhere all remove the notification without user interaction. */
class IncomingCallNotificationManager(
    private val context: Context,
    private val callController: CallController,
    private val isForeground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val ownerStore: IncomingNotificationOwnerStore,
    private val resolveAvatar: suspend (String) -> com.messenger.app.push.NotificationAvatar? = { null },
    private val notificationSettings: StateFlow<com.messenger.app.push.NotificationSettings> =
        kotlinx.coroutines.flow.MutableStateFlow(com.messenger.app.push.NotificationSettings()),
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private var avatarJob: kotlinx.coroutines.Job? = null
    private var notificationTimeoutJob: Job? = null
    private var watchedRingingCallId: String? = null
    private var currentNotificationCallId: String? = null

    private var foregroundRingtone: android.media.Ringtone? = null
    private val foregroundVibrator = context.getSystemService(android.os.Vibrator::class.java)
    private var foregroundRingingCall: String? = null
    private fun updateForegroundRingtone(state: CallUiState?) {
        // A full-screen call Activity can be visible over keyguard. Samsung
        // stops the notification sound when opening that Activity, so it must
        // take over ringing even while the device remains locked.
        val id = IncomingCallPresentationPolicy.localRingtoneCall(state, isForeground.value)
        if (id == foregroundRingingCall) return
        foregroundRingtone?.stop(); foregroundVibrator?.cancel(); foregroundRingtone = null; foregroundRingingCall = id
        if (id != null) {
            if (!canPostNotifications()) return
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            if (audio.ringerMode == android.media.AudioManager.RINGER_MODE_SILENT) return
            val channel = notificationManager.getNotificationChannel(IncomingCallChannelSpec.CHANNEL_ID)
            // The foreground ringtone must honor the same user-selected sound
            // and mute settings as the background call notification.
            if (channel != null && channel.importance < NotificationManager.IMPORTANCE_DEFAULT) return
            if (channel?.shouldVibrate() != false && notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                runCatching { foregroundVibrator?.vibrate(android.os.VibrationEffect.createWaveform(IncomingCallChannelSpec.VIBRATION_PATTERN, 0), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()) }
            }
            if (audio.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return
            val sound = if (channel == null) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) else channel.sound
            if (sound == null) return
            foregroundRingtone = runCatching {
                RingtoneManager.getRingtone(context, sound)?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                    if (Build.VERSION.SDK_INT >= 28) isLooping = true
                    play()
                }
            }.getOrNull()
        }
    }

    fun start() {
        ensureChannel()
        // P0.1: do NOT blindly cancel the incoming slot here — a process
        // restart while the call is still RINGING must keep it. The
        // authoritative reconcile (and owner-scoped cleanup) is driven by
        // [CallController.reconcileIncomingNotificationOwner] from
        // MessengerApp.onCreate, using the persisted owner.
        scope.launch {
            combine(callController.state, isForeground, notificationSettings) { state, foreground, _ -> state to foreground }
                .collect { (state, foreground) ->
                    val decision = presentation(state, foreground)
                    android.util.Log.d(TAG, "state=${state?.javaClass?.simpleName} fg=$foreground current=$currentNotificationCallId -> show=${decision.showNotification} remove=${decision.removeNotification} id=${decision.notificationCallId}")
                    apply(decision, state)
                }
        }
    }

    /** Also called before Firebase's worker callback returns. Main-thread only. */
    fun refresh() {
        val state = callController.state.value
        apply(presentation(state, isForeground.value), state)
    }

    private fun presentation(state: CallUiState?, foreground: Boolean): IncomingCallPresentationPolicy.Decision {
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        return IncomingCallPresentationPolicy.presentationFor(state, foreground, currentNotificationCallId,
            keyguard.isKeyguardLocked || keyguard.isDeviceLocked, power.isInteractive)
    }

    private fun apply(decision: IncomingCallPresentationPolicy.Decision, state: CallUiState?) {
        watchRingingCall(state)
        if (!notificationSettings.value.callAlertsEnabled) {
            removeIncomingNotification()
            updateForegroundRingtone(null)
            return
        }
        if (decision.removeNotification) {
            removeIncomingNotification()
        }
        updateForegroundRingtone(state)
        val ringing = decision.notificationCallId?.let { state as? CallUiState.IncomingRinging }
        if (decision.showNotification && ringing != null) {
            if (canPostNotifications()) {
                post(ringing)
            } else {
                android.util.Log.w(TAG, "cannot post: POST_NOTIFICATIONS not granted")
            }
        }
    }

    private fun post(ringing: CallUiState.IncomingRinging) {
        avatarJob?.cancel()
        val callId = ringing.call.callId
        // P0.1 ownership: persist the owning call_id BEFORE posting, so a
        // later terminal push / process restart can prove which call owns
        // this slot (id 4301) before canceling it.
        ownerStore.set(callId)
        currentNotificationCallId = callId

        val isVideo = ringing.call.mediaKind == CallMediaKind.VIDEO
        val contentText = context.getString(if (isVideo) com.messenger.app.R.string.call_incoming_video else com.messenger.app.R.string.call_incoming_audio)

        // Этап K.5: user-visible Accept goes through a DIRECT Activity
        // PendingIntent (MainActivity reads call_id and reconciles the
        // authoritative call) - never a broadcast-receiver startActivity. The
        // media is only started after the user taps Accept in the visible
        // Activity (mic/camera permissions are checked there).
        val acceptPendingIntent = PendingIntent.getActivity(
            context,
            ACCEPT_REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_MEDIA_KIND, if (isVideo) "video" else "audio")
                .putExtra(NotificationAcceptToken.EXTRA, NotificationAcceptToken.issue(context, callId))
                .putExtra("notification_accept", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Reject needs no Activity - a background broadcast is fine (Этап K.5).
        val rejectPendingIntent = PendingIntent.getBroadcast(
            context,
            REJECT_REQUEST_CODE,
            Intent(context, IncomingCallActionReceiver::class.java)
                .setAction(IncomingCallActionReceiver.ACTION_REJECT)
                .putExtra(EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ).putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_PRESENTATION_OWNER, presentationOwner(context))
                .putExtra(EXTRA_MEDIA_KIND, if (isVideo) "video" else "audio"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val callPerson = androidx.core.app.Person.Builder()
            .setName(ringing.call.otherDisplayName.ifBlank { "UNYRI" })
            .setImportant(true)
            .build()
        val builder = NotificationCompat.Builder(context, IncomingCallChannelSpec.CHANNEL_ID)
            .setSmallIcon(com.messenger.app.R.drawable.ic_notification)
            .setContentTitle(ringing.call.otherDisplayName)
            .setContentText(contentText)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setTimeoutAfter(60_000L)
            .setContentIntent(openAppIntent)

        // Android 14+ (API 34+) refuses a CallStyle notification that is not
        // backed by a foreground service / user-initiated job and carries no
        // fullScreenIntent: posting one throws and kills the process. The
        // fullScreenIntent must therefore be present whenever the platform
        // grants it — its highPriority flag only decides whether the system
        // actually launches it full-screen (kept from the presentation policy).
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val showFullScreen = IncomingCallPresentationPolicy.shouldUseFullScreenIntent(
            isForeground.value, keyguard.isKeyguardLocked || keyguard.isDeviceLocked, power.isInteractive,
        )
        val launchFullScreen = showFullScreen && canUseFullScreenIntent() &&
            IncomingCallPresentationPolicy.mayLaunchFullScreen(callId, ownerStore.lastFullScreenCall())
        val usesCallStyle = if (launchFullScreen) {
            builder.setFullScreenIntent(openAppIntent, showFullScreen)
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(callPerson, rejectPendingIntent, acceptPendingIntent).setIsVideo(isVideo))
            true
        } else if (IncomingCallPresentationPolicy.supportsCallStyle(Build.VERSION.SDK_INT, false)) {
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(callPerson, rejectPendingIntent, acceptPendingIntent).setIsVideo(isVideo))
            true
        } else {
            // USE_FULL_SCREEN_INTENT revoked on Android 14+: CallStyle would
            // crash. Fall back to plain Accept/Decline actions.
            builder.addAction(0, context.getString(com.messenger.app.R.string.call_accept), acceptPendingIntent)
            builder.addAction(0, context.getString(com.messenger.app.R.string.call_decline), rejectPendingIntent)
            false
        }

        val notification = builder.setOngoing(true).build().apply {
            flags = flags or android.app.Notification.FLAG_INSISTENT
        }
        notificationManager.notify(CallNotificationIds.INCOMING_CALL, notification)
        // A failed post must remain eligible for a full-screen retry.
        if (launchFullScreen) ownerStore.markFullScreenCall(callId)
        // Updating a full-screen notification can re-trigger its PendingIntent on some devices.
        if (launchFullScreen) return
        avatarJob = scope.launch {
            val person = com.messenger.app.push.loadNotificationDecoration {
                val avatar = resolveAvatar(ringing.call.otherUserId)
                    ?: com.messenger.app.push.NotificationAvatar(ringing.call.otherUserId, ringing.call.otherDisplayName, null)
                avatar.person()
            } ?: return@launch
            val active = callController.state.value as? CallUiState.IncomingRinging
            if (!notificationSettings.value.callAlertsEnabled || active?.call?.callId != callId || currentNotificationCallId != callId) return@launch
            person.icon?.toIcon(context)?.loadDrawable(context)?.let { drawable ->
                val bitmap = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, 128, 128)
                drawable.draw(android.graphics.Canvas(bitmap))
                // Keep the existing looping ringtone. ONLY_ALERT_ONCE on an
                // insistent notification update can stop it in NotificationManager.
                builder.setLargeIcon(bitmap).setContentTitle(person.name).addPerson(person)
                if (usesCallStyle) {
                    builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(person, rejectPendingIntent, acceptPendingIntent).setIsVideo(isVideo))
                }
                notificationManager.notify(CallNotificationIds.INCOMING_CALL, builder.build().apply {
                    flags = flags or android.app.Notification.FLAG_INSISTENT
                })
            }
        }
    }

    private fun watchRingingCall(state: CallUiState?) {
        val callId = (state as? CallUiState.IncomingRinging)?.call?.callId
        if (callId == watchedRingingCallId) return
        notificationTimeoutJob?.cancel()
        notificationTimeoutJob = null
        watchedRingingCallId = callId
        if (callId == null) return
        // Follow call state across foreground/background changes. Removing a
        // banner must not restart the deadline or stop foreground reconciliation.
        notificationTimeoutJob = scope.launch {
            while (watchedRingingCallId == callId) {
                delay(CALL_NOTIFICATION_TIMEOUT_MS)
                callController.reconcileRingingNotification(callId)
            }
        }
    }

    /** Owner-scoped cancel (P0.1): cancels the incoming-call slot ONLY when
     *  [callId] matches the persisted owner, so a delayed terminal push for
     *  an old call can never cancel a newer call's notification. Pass null
     *  for an authoritative unconditional cleanup. */
    fun cancelIncoming(callId: String?) {
        if (!shouldCancelFor(ownerStore.get(), callId)) return
        removeIncomingNotification()
    }

    private fun removeIncomingNotification() {
        avatarJob?.cancel()
        avatarJob = null
        notificationManager.cancel(CallNotificationIds.INCOMING_CALL)
        currentNotificationCallId = null
        ownerStore.set(null)
    }

    private fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            notificationManager.canUseFullScreenIntent()

    private fun canPostNotifications(): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(IncomingCallChannelSpec.CHANNEL_ID) == null) {
                // P0 (incoming call had no sound): see [IncomingCallChannelSpec].
                val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val attributes = AudioAttributes.Builder()
                    .setUsage(IncomingCallChannelSpec.AUDIO_USAGE)
                    .setContentType(IncomingCallChannelSpec.AUDIO_CONTENT_TYPE)
                    .build()
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        IncomingCallChannelSpec.CHANNEL_ID,
                        "Incoming calls",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Incoming call alerts"
                        setSound(ringtone, attributes)
                        enableVibration(IncomingCallChannelSpec.VIBRATION_ENABLED)
                        vibrationPattern = IncomingCallChannelSpec.VIBRATION_PATTERN
                        setShowBadge(false)
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_PRESENTATION_OWNER = "call_presentation_owner"
        fun presentationOwner(context: Context): String? {
            val app = context.applicationContext as? com.messenger.app.MessengerApp ?: return null
            return (app.authRepository.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.let {
                "${com.messenger.app.data.api.ServerEndpoint.current()}|${it.user.id}|${app.authRepository.currentSessionEpoch()}"
            }
        }
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_MEDIA_KIND = "call_media_kind"
        private const val TAG = "IncomingCallNotif"
        private const val CALL_NOTIFICATION_TIMEOUT_MS = 60_000L
        private const val ACCEPT_REQUEST_CODE = 4301
        private const val REJECT_REQUEST_CODE = 4302

        /** P0.1 ownership rule (pure): a terminal [terminalCallId] may cancel
         *  the incoming slot only when it matches [storedOwner]; null means
         *  an authoritative unconditional cleanup. */
        fun shouldCancelFor(storedOwner: String?, terminalCallId: String?): Boolean =
            terminalCallId == null || storedOwner == terminalCallId
    }
}
