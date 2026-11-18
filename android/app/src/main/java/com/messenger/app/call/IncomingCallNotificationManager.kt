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
import com.messenger.app.domain.call.CallRingConfirmationPolicy
import com.messenger.app.MainActivity
import com.messenger.app.domain.call.CallController
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.model.CallMediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val displays = context.getSystemService(android.hardware.display.DisplayManager::class.java)
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (id == android.view.Display.DEFAULT_DISPLAY &&
                callController.state.value is CallUiState.IncomingRinging) refresh()
        }
    }
    private var avatarJob: kotlinx.coroutines.Job? = null
    private var notificationTimeoutJob: Job? = null
    private var watchedRingingCallId: String? = null
    private var currentNotificationCallId: String? = null
    private var notificationWasForeground = false
    private var notificationWasSilentForeground = false
    private var notificationRequestedFullScreen = false
    private var visibleIncomingCallId: String? = null

    /** Called by the composed incoming surface on layout/focus/lifecycle changes.
     * A started Activity alone is not evidence that the call UI is visible. */
    fun incomingCallSurfaceVisibility(callId: String, visible: Boolean) {
        val current = (callController.state.value as? CallUiState.IncomingRinging)?.call?.callId
        val next = when {
            visible && callId == current -> callId
            visibleIncomingCallId == callId -> null
            else -> visibleIncomingCallId
        }
        if (next != visibleIncomingCallId) { visibleIncomingCallId = next; refresh() }
    }

    private var foregroundRingtone: android.media.Ringtone? = null
    private var foregroundRingtoneJob: Job? = null
    private val foregroundVibrator = context.getSystemService(android.os.Vibrator::class.java)
    private var foregroundRingingCall: String? = null
    private var foregroundAlert: AlertCapabilities? = null
    private data class SoundOwner(val callId: String, val session: String?)
    private val soundResolver = IncomingCallSoundResolver<SoundOwner, android.net.Uri>(scope, Dispatchers.IO,
        resolve = { sound -> com.messenger.app.domain.call.CallRingtonePolicy.resolve(sound,
            RingtoneManager::getDefaultType) { RingtoneManager.getActualDefaultRingtoneUri(context, it) } },
        onReady = { refresh() })
    private val ringtoneObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            soundResolver.invalidate()
            refresh()
        }
    }
    private fun updateForegroundRingtone(state: CallUiState?, alert: AlertCapabilities = AlertCapabilities(null, false, false, false)) {
        // A full-screen call Activity can be visible over keyguard. Samsung
        // stops the notification sound when opening that Activity, so it must
        // take over ringing even while the device remains locked.
        val id = IncomingCallPresentationPolicy.localRingtoneCall(state, isForeground.value)
            .takeIf { usesForegroundRingtone() && alert.wouldAlert }
        if (id == foregroundRingingCall && alert == foregroundAlert) return
        foregroundAlert = alert
        foregroundRingtoneJob?.cancel(); foregroundRingtoneJob = null
        foregroundRingtone?.stop(); foregroundVibrator?.cancel(); foregroundRingtone = null; foregroundRingingCall = id
        if (id != null) {
            // The foreground ringtone must honor the same user-selected sound
            // and mute settings as the background call notification.
            if (alert.vibrationAllowed) {
                runCatching { foregroundVibrator?.vibrate(android.os.VibrationEffect.createWaveform(IncomingCallChannelSpec.VIBRATION_PATTERN, 0), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()) }
            }
            if (!alert.soundAllowed) return
            val sound = alert.soundUri ?: return
            val owner = presentationOwner(context)
            foregroundRingtoneJob = scope.launch {
                var prepared: android.media.Ringtone? = null
                var adopted = false
                try {
                    withContext(Dispatchers.IO) {
                        prepared = RingtoneManager.getRingtone(context, sound)?.apply {
                            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                            if (Build.VERSION.SDK_INT >= 28) isLooping = true
                        }
                    }
                    if (foregroundRingingCall != id || foregroundAlert != alert || owner != presentationOwner(context) ||
                        !notificationSettings.value.callAlertsEnabled || !isForeground.value || !usesForegroundRingtone() ||
                        (callController.state.value as? CallUiState.IncomingRinging)?.call?.callId != id) return@launch
                    prepared?.play()
                    foregroundRingtone = prepared
                    adopted = true
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w(TAG, "foreground ringtone unavailable: ${error.javaClass.simpleName}")
                } finally {
                    // A cancelled provider/media load must not leak or play into a newer call.
                    if (!adopted) prepared?.stop()
                }
            }
        }
    }

    fun start() {
        ensureChannel()
        // Denied-FSI HUN may wake a dark display into doze without resuming our
        // Activity. Confirm the available display after that event, not on post.
        displays.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
        // Symbolic defaults can change without the channel URI changing.
        for (uri in listOf(android.provider.Settings.System.DEFAULT_RINGTONE_URI,
            android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI)) {
            context.contentResolver.registerContentObserver(uri, false, ringtoneObserver)
        }
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
            soundResolver.invalidate()
            removeIncomingNotification()
            updateForegroundRingtone(null)
            return
        }
        val ringing = state as? CallUiState.IncomingRinging
        if (ringing == null) {
            soundResolver.invalidate()
            if (currentNotificationCallId != null) removeIncomingNotification()
            updateForegroundRingtone(null)
            return
        }
        // Replace the system sound owner at a foreground/background handoff.
        // Foreground posts silently while the visible UI owns the ringtone.
        val channel = notificationManager.getNotificationChannel(IncomingCallChannelSpec.CHANNEL_ID)
        val soundOwner = SoundOwner(ringing.call.callId, presentationOwner(context))
        val soundUri = soundResolver.soundFor(soundOwner, channel?.sound)
        val alert = alertCapabilities(channel, soundUri)
        val silentForeground = isForeground.value && usesForegroundRingtone()
        val needsPost = currentNotificationCallId != ringing.call.callId || notificationWasForeground != isForeground.value ||
            notificationWasSilentForeground != silentForeground
        if (needsPost && currentNotificationCallId != null) removeIncomingNotification()
        updateForegroundRingtone(state, alert)
        if (needsPost && canPostNotifications()) post(ringing, alert)
        else if (currentNotificationCallId == ringing.call.callId) {
            // Resolution may complete after the notification is already visible.
            // Confirm the current owner without restarting its notification sound.
            confirmPresented(ringing.call.callId, alert)
        }
    }

    private fun post(ringing: CallUiState.IncomingRinging, alert: AlertCapabilities) {
        avatarJob?.cancel()
        val callId = ringing.call.callId
        val foreground = isForeground.value
        // P0.1 ownership: persist the owning call_id BEFORE posting, so a
        // later terminal push / process restart can prove which call owns
        // this slot (id 4301) before canceling it.
        ownerStore.set(callId)

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

        val callerName = com.messenger.app.domain.model.DisplayNameSanitizer.peer(ringing.call.otherDisplayName)
        val callPerson = androidx.core.app.Person.Builder()
            .setKey(ringing.call.otherUserId)
            .setName(callerName)
            .setImportant(true)
            .build()
        val builder = NotificationCompat.Builder(context, IncomingCallChannelSpec.CHANNEL_ID)
            .setSmallIcon(com.messenger.app.R.drawable.ic_notification)
            .setContentTitle(callerName)
            .setContentText(contentText)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addPerson(callPerson)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setTimeoutAfter(60_000L)
            .setContentIntent(openAppIntent)
        // Only the channel notification inherits the effective priority DND policy.
        // Use it as the sound owner in that mode instead of manual playback.
        val silentForeground = foreground && usesForegroundRingtone()
        if (silentForeground) builder.setSilent(true)

        // Android 14+ (API 34+) refuses a CallStyle notification that is not
        // backed by a foreground service / user-initiated job and carries no
        // fullScreenIntent: posting one throws and kills the process. The
        // Keep a requested FSI even when permission is denied: Android 14+
        // demotes it to a persistent heads-up alert, including on a dark display.
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val showFullScreen = IncomingCallPresentationPolicy.shouldUseFullScreenIntent(
            isForeground.value, keyguard.isKeyguardLocked || keyguard.isDeviceLocked, power.isInteractive,
        )
        val requestFullScreen = showFullScreen &&
            IncomingCallPresentationPolicy.mayLaunchFullScreen(callId, ownerStore.lastFullScreenCall())
        val launchFullScreen = requestFullScreen && canUseFullScreenIntent()
        if (requestFullScreen) builder.setFullScreenIntent(openAppIntent, showFullScreen)
        val usesCallStyle = if (launchFullScreen) {
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(callPerson, rejectPendingIntent, acceptPendingIntent).setIsVideo(isVideo))
            true
        } else if (IncomingCallPresentationPolicy.supportsCallStyle(Build.VERSION.SDK_INT, false)) {
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(callPerson, rejectPendingIntent, acceptPendingIntent).setIsVideo(isVideo))
            true
        } else {
            // Use plain actions on the conservative compatibility path, but keep
            // the FSI request so NMS can set FLAG_FSI_REQUESTED_BUT_DENIED.
            builder.addAction(0, context.getString(com.messenger.app.R.string.call_accept), acceptPendingIntent)
            builder.addAction(0, context.getString(com.messenger.app.R.string.call_decline), rejectPendingIntent)
            false
        }

        val notification = builder.setOngoing(true).build().apply {
            if (!silentForeground) flags = flags or android.app.Notification.FLAG_INSISTENT
        }
        try {
            notificationManager.notify(CallNotificationIds.INCOMING_CALL, notification)
        } catch (error: Exception) {
            // Permission/channel changes can race the post. A failed post is
            // never proof of presentation, and must remain eligible to retry.
            if (ownerStore.get() == callId) ownerStore.set(null)
            android.util.Log.w(TAG, "incoming notification post failed: ${error.javaClass.simpleName}")
            return
        }
        currentNotificationCallId = callId
        notificationWasForeground = foreground
        notificationWasSilentForeground = silentForeground
        notificationRequestedFullScreen = requestFullScreen
        confirmPresented(callId, alert)
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
                builder.setLargeIcon(bitmap).setContentTitle(person.name)
                if (usesCallStyle) {
                    builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(person, rejectPendingIntent, acceptPendingIntent).setIsVideo(isVideo))
                }
                try {
                    notificationManager.notify(CallNotificationIds.INCOMING_CALL, builder.build().apply {
                        if (!silentForeground) flags = flags or android.app.Notification.FLAG_INSISTENT
                    })
                } catch (error: Exception) {
                    android.util.Log.w(TAG, "incoming avatar update failed: ${error.javaClass.simpleName}")
                }
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
        notificationRequestedFullScreen = false
        ownerStore.set(null)
    }

    private fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            notificationManager.canUseFullScreenIntent()

    private fun canPostNotifications(): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private data class AlertCapabilities(val soundUri: android.net.Uri?, val soundAllowed: Boolean, val vibrationAllowed: Boolean,
        val wouldAlert: Boolean, val appEnabled: Boolean = false, val channelEnabled: Boolean = false,
        val dndAllowed: Boolean = false, val channelImportance: Int = 0,
        val headsUpAllowed: Boolean = false)

    private fun confirmPresented(callId: String, alert: AlertCapabilities) {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val displayAvailable = when (displays.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state) {
            android.view.Display.STATE_ON, android.view.Display.STATE_DOZE, android.view.Display.STATE_DOZE_SUSPEND -> true
            else -> false
        }
        val visible = CallRingConfirmationPolicy.visualSurfaceAvailable(
            visibleIncomingCallId == callId, power.isInteractive, alert.channelImportance,
            notificationRequestedFullScreen, canUseFullScreenIntent(), Build.VERSION.SDK_INT,
            alert.headsUpAllowed && !notificationWasSilentForeground, displayAvailable)
        callController.incomingNotificationPosted(callId, CallRingConfirmationPolicy.presented(
            alert.appEnabled, alert.channelEnabled, alert.channelImportance, alert.dndAllowed, alert.wouldAlert || visible))
    }

    /** Hidden settings may be unreadable on some builds (SecurityException since
     * Android 12 for keys without @Readable). The ringing path must never crash. */
    private inline fun systemSetting(fallback: Int, read: () -> Int): Int =
        try { read() } catch (_: SecurityException) { fallback } catch (_: IllegalArgumentException) { fallback }

    private fun usesForegroundRingtone(): Boolean =
        notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL

    private fun alertCapabilities(channel: NotificationChannel?, soundUri: android.net.Uri?): AlertCapabilities {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val groupBlocked = Build.VERSION.SDK_INT >= 28 && channel?.group?.let {
            notificationManager.getNotificationChannelGroup(it)?.isBlocked
        } == true
        val filter = notificationManager.currentInterruptionFilter
        val policy = if (Build.VERSION.SDK_INT >= 30) notificationManager.consolidatedNotificationPolicy
            else notificationManager.notificationPolicy
        // We post a CATEGORY_CALL with an app-keyed Person and no tel/mailto/
        // Contacts lookup URI. Its platform contact affinity is UNKNOWN, even
        // if it is an app contact or Person.isImportant. Use the same metadata
        // for initial, plain-action, CallStyle and avatar-updated notifications.
        val dndAllowed = CallRingConfirmationPolicy.dndAllowsCalls(filter, channel?.canBypassDnd() == true,
            policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0,
            policy.priorityCallSenders, CallRingConfirmationPolicy.CallerAffinity.UNKNOWN)
        val ringerMode = CallRingConfirmationPolicy.effectiveRingerMode(audio.ringerMode,
            systemSetting(-1) { android.provider.Settings.Global.getInt(context.contentResolver, "mode_ringer", -1) }, filter, dndAllowed)
        val sound = soundUri != null && ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL &&
            audio.getStreamVolume(android.media.AudioManager.STREAM_RING) > 0
        val vibration = channel?.shouldVibrate() == true && foregroundVibrator?.hasVibrator() == true &&
            ringerMode != android.media.AudioManager.RINGER_MODE_SILENT
        val appEnabled = canPostNotifications() && notificationSettings.value.callAlertsEnabled
        val channelEnabled = channel != null && !groupBlocked
        val importance = channel?.importance ?: NotificationManager.IMPORTANCE_NONE
        val permitted = com.messenger.app.domain.call.CallRingConfirmationPolicy.wouldAlert(
            appEnabled, channelEnabled, importance,
            sound, vibration,
            dndAllowed,
        )
        val peekSuppressed = filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            (policy.suppressedVisualEffects and (NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK or
                NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON)) != 0
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val lockscreenVisible = (power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) ||
            systemSetting(1) { android.provider.Settings.Secure.getInt(context.contentResolver, "lock_screen_show_notifications", 1) } != 0
        val headsUp = !peekSuppressed && lockscreenVisible && systemSetting(1) { android.provider.Settings.Global.getInt(context.contentResolver,
            "heads_up_notifications_enabled", 1) } != 0
        return AlertCapabilities(soundUri, sound, vibration, permitted, appEnabled, channelEnabled,
            dndAllowed, importance, headsUp)
    }

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
