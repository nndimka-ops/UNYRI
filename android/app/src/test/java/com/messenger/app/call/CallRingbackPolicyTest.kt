package com.messenger.app.call

import com.messenger.app.domain.call.CallFailureReason
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.model.CallDirection
import com.messenger.app.domain.model.CallEndReason
import com.messenger.app.domain.model.CallInfo
import com.messenger.app.domain.model.CallMediaKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** N.2R addendum §16/17: ringback must play ONLY while the caller is ringing
 *  and stop on every terminal/connected transition — never into Connected. */
class CallRingbackPolicyTest {

    private fun outgoing() = CallUiState.OutgoingRinging(
        CallInfo("c1", "bob", "Bob", CallMediaKind.AUDIO, CallDirection.OUTGOING),
    )

    @Test
    fun `route changes restart ringback but mute changes do not`() {
        val ringing = outgoing()
        kotlin.test.assertNotEquals(CallRingbackPlayer.playbackKey(ringing), CallRingbackPlayer.playbackKey(ringing.copy(audioRoute = com.messenger.app.data.call.AudioRouteKind.SPEAKER)))
        kotlin.test.assertEquals(CallRingbackPlayer.playbackKey(ringing), CallRingbackPlayer.playbackKey(ringing.copy(isMuted = true)))
        kotlin.test.assertNull(CallRingbackPlayer.playbackKey(CallUiState.Idle))
    }

    @Test
    fun `ringback plays while outgoing ringing`() {
        assertTrue(CallRingbackPlayer.shouldPlay(outgoing()))
    }

    @Test
    fun `ringback does not play when idle`() {
        assertFalse(CallRingbackPlayer.shouldPlay(CallUiState.Idle))
    }

    @Test
    fun `ringback does not play for incoming ringing`() {
        val incoming = CallUiState.IncomingRinging(
            CallInfo("c1", "alice", "Alice", CallMediaKind.AUDIO, CallDirection.INCOMING),
        )
        assertFalse(CallRingbackPlayer.shouldPlay(incoming))
    }

    @Test
    fun `ringback stops when accepted into connecting`() {
        val connecting = CallUiState.Connecting(
            CallInfo("c1", "bob", "Bob", CallMediaKind.AUDIO, CallDirection.OUTGOING),
        )
        assertFalse(CallRingbackPlayer.shouldPlay(connecting))
    }

    @Test
    fun `ringback stops when connected`() {
        val connected = CallUiState.Connected(
            call = CallInfo("c1", "bob", "Bob", CallMediaKind.AUDIO, CallDirection.OUTGOING),
            connectedAtElapsedRealtimeMs = 0,
            localVideoEnabled = false,
            remoteVideoEnabled = false,
            videoMode = com.messenger.app.domain.call.VideoMode.AUDIO,
        )
        assertFalse(CallRingbackPlayer.shouldPlay(connected))
    }

    @Test
    fun `ringback stops on every terminal state`() {
        val call = CallInfo("c1", "bob", "Bob", CallMediaKind.AUDIO, CallDirection.OUTGOING)
        assertFalse(CallRingbackPlayer.shouldPlay(CallUiState.Ended(call, CallEndReason.HANGUP)))
        assertFalse(CallRingbackPlayer.shouldPlay(CallUiState.Ended(call, CallEndReason.CANCELLED)))
        assertFalse(CallRingbackPlayer.shouldPlay(CallUiState.Failed(call, CallFailureReason.GENERIC)))
    }
}
