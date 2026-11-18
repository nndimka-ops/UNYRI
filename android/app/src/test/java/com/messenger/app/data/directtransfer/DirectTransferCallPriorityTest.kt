package com.messenger.app.data.directtransfer

import com.messenger.app.domain.call.CallFailureReason
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.model.*
import kotlin.test.*

class DirectTransferCallPriorityTest {
    private val call = CallInfo("call", "peer", "Peer", CallMediaKind.AUDIO, CallDirection.OUTGOING)

    @Test fun earlyRecoveryCannotBorrowIdleBeforeBootstrapAndCanReevaluateAfterReady() {
        var current: CallUiState? = null
        assertFalse(directTransferCanStream(current, false))
        current = CallUiState.Idle
        assertTrue(directTransferCanStream(current, false))
        current = CallUiState.Connecting(call)
        assertFalse(directTransferCanStream(current, false))
        current = CallUiState.Ended(call, CallEndReason.HANGUP)
        assertTrue(directTransferCanStream(current, false))
    }

    @Test fun everyLiveCallAndConferenceReservationKeepsExistingPriority() {
        val connecting = CallUiState.Connecting(call)
        for (state in listOf(CallUiState.OutgoingRinging(call), CallUiState.IncomingRinging(call), connecting,
            CallUiState.Connected(call, 123), CallUiState.Reconnecting(call, connecting))) {
            assertFalse(directTransferCanStream(state, false))
            assertFalse(directTransferCanStream(state, true))
        }
        for (state in listOf(CallUiState.Idle, CallUiState.Ended(call, CallEndReason.HANGUP),
            CallUiState.Failed(call, CallFailureReason.NETWORK))) {
            assertFalse(directTransferCanStream(state, true))
            assertTrue(directTransferCanStream(state, false))
        }
    }
}
