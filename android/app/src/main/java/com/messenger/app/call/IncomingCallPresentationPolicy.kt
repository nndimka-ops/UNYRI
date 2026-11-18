package com.messenger.app.call

import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.call.VideoMode

/** Pure, JVM-testable presentation/action policy for an incoming call
 *  (Этап J.1-4). Kept free of any Android framework dependency so the
 *  notification decision logic can be unit-tested without instrumentation. */
object IncomingCallPresentationPolicy {
    /** Incoming-window cleanup must not clear the active video screen's flag. */
    fun videoOwnsScreen(state: CallUiState): Boolean = when (state) {
        is CallUiState.Connected -> state.videoMode == VideoMode.VIDEO
        is CallUiState.Reconnecting -> (state.since as? CallUiState.Connected)?.videoMode == VideoMode.VIDEO
        else -> false
    }

    /** Visibility includes the incoming Activity shown above keyguard. */
    fun localRingtoneCall(state: CallUiState?, activityVisible: Boolean): String? =
        (state as? CallUiState.IncomingRinging)?.call?.callId?.takeIf { activityVisible }
    /** Incoming notifications are not foreground-service notifications. Native
     *  CallStyle (API 31+) therefore requires an actually attached full-screen
     *  intent, including Android 12/13. Older SDKs use the compat presentation. */
    fun supportsCallStyle(sdk: Int, hasFullScreenIntent: Boolean): Boolean = sdk < 31 || hasFullScreenIntent
    fun shouldUseFullScreenIntent(isForeground: Boolean, deviceLocked: Boolean, screenInteractive: Boolean): Boolean {
        // Screen-off must not be mistaken for "the user is actively looking at
        // another app": an app can still report foreground while the display
        // sleeps. Locked/sleeping is the condition that permits a call screen;
        // an unlocked background app still gets only a heads-up notification.
        // Keep the parameter for the existing call sites/contract tests.
        val displayAvailable = deviceLocked || !screenInteractive
        return displayAvailable && (!isForeground || !screenInteractive || deviceLocked)
    }

    fun mayLaunchFullScreen(callId: String, previouslyLaunched: String?): Boolean = callId != previouslyLaunched
    fun mayWakeFromIntent(currentCallId: String?, requestedCallId: String?, currentOwner: String?, intentOwner: String?): Boolean =
        currentCallId != null && currentCallId == requestedCallId && currentOwner != null && currentOwner == intentOwner

    fun expandNewCall(isIncoming: Boolean): Boolean = !isIncoming


    /** What the incoming-call notification should do for the current call
     *  state, app foreground status and the call_id that currently owns the
     *  notification (null = none showing). */
    data class Decision(
        val showNotification: Boolean = false,
        val removeNotification: Boolean = false,
        val notificationCallId: String? = null,
    )

    /** - IncomingRinging + background → one notification (deduped by call_id).
     *  - IncomingRinging + foreground (including the call Activity visible
     *    over keyguard) → only in-app UI; the manager owns the foreground
     *    ringtone. Keeping the channel notification here would play the
     *    notification ringtone and the foreground Ringtone at the same time
     *    on OEMs that do not stop channel sound when the Activity opens.
     *  - any other state with a live notification → remove it (call state is
     *    the single source of truth for presentation). */
    fun presentationFor(
        state: CallUiState?,
        isForeground: Boolean,
        currentNotificationCallId: String?,
        deviceLocked: Boolean = false,
        screenInteractive: Boolean = true,
    ): Decision {
        val ringing = state as? CallUiState.IncomingRinging
        return when {
            // The local Ringtone is the single audible owner as soon as the
            // app is foregrounded. This also applies over keyguard: the
            // full-screen Activity is visible, so keeping the system channel
            // notification would layer two ringtones on devices that do not
            // mute the channel when the Activity opens (seen on MIUI).
            ringing != null && isForeground -> Decision(removeNotification = currentNotificationCallId != null)
            ringing != null -> {
                if (currentNotificationCallId == ringing.call.callId) {
                    Decision() // already showing this exact call - no duplicate
                } else {
                    Decision(showNotification = true, notificationCallId = ringing.call.callId)
                }
            }
            currentNotificationCallId != null -> Decision(removeNotification = true)
            else -> Decision()
        }
    }

    /** A notification Accept/Reject action must only route into
     *  [com.messenger.app.domain.call.CallController] for the EXACT call that
     *  is currently ringing. A stale action (call already ended/cancelled/
     *  answered elsewhere) or a foreign call_id returns false, so it can
     *  never resurrect a terminal call. */
    fun shouldRouteAction(state: CallUiState?, callId: String): Boolean =
        (state as? CallUiState.IncomingRinging)?.call?.callId == callId
}
