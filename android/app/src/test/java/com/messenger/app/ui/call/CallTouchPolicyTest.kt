package com.messenger.app.ui.call

import com.messenger.app.domain.call.CallFailureReason
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.call.VideoMode
import com.messenger.app.domain.model.CallDirection
import com.messenger.app.domain.model.CallEndReason
import com.messenger.app.domain.model.CallInfo
import com.messenger.app.domain.model.CallMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTouchPolicyTest {
    private val call = CallInfo("touch-call", "peer", "Peer", CallMediaKind.VIDEO, CallDirection.OUTGOING)
    private val connected = CallUiState.Connected(call, 0, videoMode = VideoMode.VIDEO)

    @Test fun everyExpandedPresentationOwnsTheBackdropIncludingCameraOffAndRecovery() {
        val states = listOf(
            CallUiState.IncomingRinging(call), CallUiState.OutgoingRinging(call),
            CallUiState.Connecting(call), connected,
            connected.copy(videoMode = VideoMode.AUDIO),
            CallUiState.Reconnecting(call, connected),
        )
        states.forEach { assertTrue(it.toString(), CallTouchPolicy.ownsBackdrop(it, true)) }
    }

    @Test fun compactAndMinimizedCallsKeepChatUsableButFailureNoticeOwnsItsScreen() {
        listOf(CallUiState.IncomingRinging(call), CallUiState.OutgoingRinging(call),
            CallUiState.Connecting(call), connected, CallUiState.Reconnecting(call, connected)
        ).forEach { assertFalse(it.toString(), CallTouchPolicy.ownsBackdrop(it, false)) }
        assertTrue(CallTouchPolicy.ownsBackdrop(CallUiState.Failed(call, CallFailureReason.NETWORK), false))
        for (expanded in listOf(false, true)) {
            assertFalse(CallTouchPolicy.ownsBackdrop(CallUiState.Idle, expanded))
            assertFalse(CallTouchPolicy.ownsBackdrop(CallUiState.Ended(call, CallEndReason.HANGUP), expanded))
        }
    }

    @Test fun ownershipRoundTripInvalidatesAGestureEvenWhenTheFinalVisibilityMatches() {
        val ownership = CallTouchOwnership(true)
        val downRevision = ownership.revision
        ownership.update(false)
        ownership.update(true)
        assertEquals(2L, ownership.revision - downRevision)
    }

    @Test fun cameraMuteRouteAndOrdinaryRecompositionsDoNotChangeTouchOwnership() {
        val ownership = CallTouchOwnership(false)
        repeat(50) { ownership.update(false) }
        assertEquals(0L, ownership.revision)
        ownership.update(true)
        repeat(50) { ownership.update(true) }
        assertEquals(1L, ownership.revision)
    }
}
