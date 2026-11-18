package com.messenger.app.call

import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.call.VideoMode
import com.messenger.app.domain.model.CallDirection
import com.messenger.app.domain.model.CallEndReason
import com.messenger.app.domain.model.CallInfo
import com.messenger.app.domain.model.CallMediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncomingCallPresentationPolicyTest {
    @Test fun `incoming window hands screen ownership to video but clears for audio and terminal`() {
        val call = ringing().call
        val video = CallUiState.Connected(call, 0L, videoMode = VideoMode.VIDEO)
        val audio = video.copy(videoMode = VideoMode.AUDIO)
        assertTrue(IncomingCallPresentationPolicy.videoOwnsScreen(video))
        assertTrue(IncomingCallPresentationPolicy.videoOwnsScreen(CallUiState.Reconnecting(call, video)))
        assertFalse(IncomingCallPresentationPolicy.videoOwnsScreen(audio))
        assertFalse(IncomingCallPresentationPolicy.videoOwnsScreen(CallUiState.Reconnecting(call, audio)))
        assertFalse(IncomingCallPresentationPolicy.videoOwnsScreen(CallUiState.Connecting(call)))
        assertFalse(IncomingCallPresentationPolicy.videoOwnsScreen(ringing()))
        assertFalse(IncomingCallPresentationPolicy.videoOwnsScreen(CallUiState.Ended(call, CallEndReason.HANGUP)))
        assertFalse(IncomingCallPresentationPolicy.videoOwnsScreen(CallUiState.Idle))
    }

    @Test fun `ringtone ownership transfers on visibility and clears on call termination`() {
        val state = ringing()
        assertNull(IncomingCallPresentationPolicy.localRingtoneCall(state, false))
        // Activity is visible over keyguard; unlocking is not a prerequisite.
        assertEquals("call-1", IncomingCallPresentationPolicy.localRingtoneCall(state, true))
        assertTrue(IncomingCallPresentationPolicy.presentationFor(state, true, "call-1").removeNotification)
        assertNull(IncomingCallPresentationPolicy.localRingtoneCall(state, false))
        assertTrue(IncomingCallPresentationPolicy.presentationFor(state, false, null).showNotification)
        assertNull(IncomingCallPresentationPolicy.localRingtoneCall(CallUiState.Idle, true))
        assertNull(IncomingCallPresentationPolicy.localRingtoneCall(CallUiState.Ended(state.call, CallEndReason.CANCELLED), true))
    }
    @Test
    fun `unlocked phone never launches incoming full screen over another app`() {
        assertFalse(IncomingCallPresentationPolicy.shouldUseFullScreenIntent(false, false, true))
        assertFalse(IncomingCallPresentationPolicy.shouldUseFullScreenIntent(true, false, true))
    }

    @Test
    fun `locked or sleeping phone permits full screen incoming call`() {
        assertTrue(IncomingCallPresentationPolicy.shouldUseFullScreenIntent(false, true, true))
        assertTrue(IncomingCallPresentationPolicy.shouldUseFullScreenIntent(false, false, false))
        // A process can still report foreground while the display sleeps or
        // the keyguard is up. Those states must still show the call screen.
        assertTrue(IncomingCallPresentationPolicy.shouldUseFullScreenIntent(true, false, false))
        assertTrue(IncomingCallPresentationPolicy.shouldUseFullScreenIntent(true, true, true))
    }

    @Test
    fun `new incoming call starts compact while outgoing call expands`() {
        assertFalse(IncomingCallPresentationPolicy.expandNewCall(true))
        assertTrue(IncomingCallPresentationPolicy.expandNewCall(false))
    }


    private fun ringing(callId: String = "call-1") = CallUiState.IncomingRinging(
        CallInfo(callId, "alice", "Alice", CallMediaKind.AUDIO, CallDirection.INCOMING),
    )

    @Test
    fun `background ringing shows one notification`() {
        val decision = IncomingCallPresentationPolicy.presentationFor(ringing("call-1"), isForeground = false, currentNotificationCallId = null)
        assertTrue(decision.showNotification)
        assertEquals("call-1", decision.notificationCallId)
        assertFalse(decision.removeNotification)
    }

    @Test
    fun `duplicate same call id does not duplicate the notification`() {
        val decision = IncomingCallPresentationPolicy.presentationFor(ringing("call-1"), isForeground = false, currentNotificationCallId = "call-1")
        assertFalse(decision.showNotification)
        assertFalse(decision.removeNotification)
        assertNull(decision.notificationCallId)
    }

    @Test
    fun `foreground ringing uses in-app presentation only`() {
        val decision = IncomingCallPresentationPolicy.presentationFor(ringing("call-1"), isForeground = true, currentNotificationCallId = null)
        assertFalse(decision.showNotification)
        assertFalse(decision.removeNotification)
    }

    @Test
    fun `opening app removes system banner while manager takes over ringtone`() {
        val decision = IncomingCallPresentationPolicy.presentationFor(ringing(), true, "call-1")
        assertTrue(decision.removeNotification)
        assertFalse(decision.showNotification)
    }

    @Test
    fun `ended state removes the notification`() {
        val call = CallInfo("call-1", "alice", "Alice", CallMediaKind.AUDIO, CallDirection.INCOMING)
        val decision = IncomingCallPresentationPolicy.presentationFor(
            CallUiState.Ended(call, CallEndReason.HANGUP),
            isForeground = false,
            currentNotificationCallId = "call-1",
        )
        assertTrue(decision.removeNotification)
        assertFalse(decision.showNotification)
    }

    @Test
    fun `caller cancel removes the notification`() {
        val call = CallInfo("call-1", "alice", "Alice", CallMediaKind.AUDIO, CallDirection.INCOMING)
        val decision = IncomingCallPresentationPolicy.presentationFor(
            CallUiState.Ended(call, CallEndReason.CANCELLED),
            isForeground = false,
            currentNotificationCallId = "call-1",
        )
        assertTrue(decision.removeNotification)
    }

    @Test
    fun `answered elsewhere removes the notification`() {
        val decision = IncomingCallPresentationPolicy.presentationFor(CallUiState.Idle, isForeground = false, currentNotificationCallId = "call-1")
        assertTrue(decision.removeNotification)
    }

    @Test
    fun `accept and reject route only for the currently ringing call`() {
        assertTrue(IncomingCallPresentationPolicy.shouldRouteAction(ringing("call-1"), "call-1"))
        assertFalse(IncomingCallPresentationPolicy.shouldRouteAction(ringing("call-1"), "call-2"))
        assertFalse(IncomingCallPresentationPolicy.shouldRouteAction(CallUiState.Idle, "call-1"))
    }

    @Test
    fun `stale accept cannot resurrect a terminal call`() {
        val call = CallInfo("call-1", "alice", "Alice", CallMediaKind.AUDIO, CallDirection.INCOMING)
        val ended = CallUiState.Ended(call, CallEndReason.CANCELLED)
        assertFalse(IncomingCallPresentationPolicy.shouldRouteAction(ended, "call-1"))
        assertFalse(IncomingCallPresentationPolicy.shouldRouteAction(null, "call-1"))
    }
    @Test fun `foreground call over keyguard removes channel notification before foreground ringtone`() {
        val state = ringing("call-1")
        // Background/locked: the system notification is the only audible owner.
        assertTrue(IncomingCallPresentationPolicy.presentationFor(state, false, null, true, false).showNotification)
        // Once the call Activity is foreground (even over keyguard), the
        // in-app Ringtone owns audio; keeping the channel notification would
        // play two ringtones on MIUI and similar OEMs.
        val started = IncomingCallPresentationPolicy.presentationFor(state, true, "call-1", true, false)
        assertTrue(started.removeNotification); assertFalse(started.showNotification)
        val stopped = IncomingCallPresentationPolicy.presentationFor(state, false, "call-1", true, false)
        assertFalse(stopped.removeNotification); assertFalse(stopped.showNotification)
        assertTrue(IncomingCallPresentationPolicy.presentationFor(state, true, "call-1", false, true).removeNotification)
        // Reposting after going background does not automatically relaunch an already shown call.
        assertFalse(IncomingCallPresentationPolicy.mayLaunchFullScreen("call-1", "call-1"))
        assertTrue(IncomingCallPresentationPolicy.mayLaunchFullScreen("call-2", "call-1"))
        assertTrue(IncomingCallPresentationPolicy.presentationFor(CallUiState.Idle, false, "call-1", true, false).removeNotification)
    }
    @Test fun `stale or different owner incoming intent cannot enable lockscreen window`() {
        assertTrue(IncomingCallPresentationPolicy.mayWakeFromIntent("call-1", "call-1", "A", "A"))
        assertFalse(IncomingCallPresentationPolicy.mayWakeFromIntent(null, "call-1", "A", "A"))
        assertFalse(IncomingCallPresentationPolicy.mayWakeFromIntent("call-2", "call-1", "A", "A"))
        assertFalse(IncomingCallPresentationPolicy.mayWakeFromIntent("call-1", "call-1", "B", "A"))
        assertFalse(IncomingCallPresentationPolicy.mayWakeFromIntent("call-1", "call-1", null, null))
    }

}
