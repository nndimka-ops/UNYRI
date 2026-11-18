package com.messenger.app.ui.call

import com.messenger.app.domain.call.*
import com.messenger.app.domain.model.*
import kotlin.test.*

class CallMinimizePolicyTest {
    private val call = CallInfo("call-a", "peer", "Peer", CallMediaKind.AUDIO, CallDirection.OUTGOING)
    private val connected = CallUiState.Connected(call, 12L)

    @Test fun `outgoing can collapse before answer and survives connecting and reconnecting`() {
        listOf(CallUiState.OutgoingRinging(call), CallUiState.Connecting(call), connected,
            CallUiState.Reconnecting(call, connected)).forEach {
            assertEquals(call.callId, CallMinimizePolicy.callId(it))
            assertTrue(CallMinimizePolicy.canComplete(call.callId, it))
        }
    }

    @Test fun `incoming terminal and stale call callbacks cannot collapse another call`() {
        listOf(CallUiState.Idle, CallUiState.IncomingRinging(call),
            CallUiState.Ended(call, CallEndReason.HANGUP), CallUiState.Failed(call, CallFailureReason.NETWORK)).forEach {
            assertNull(CallMinimizePolicy.callId(it))
            assertFalse(CallMinimizePolicy.canComplete(call.callId, it))
        }
        assertFalse(CallMinimizePolicy.canComplete(null, connected))
        assertFalse(CallMinimizePolicy.canComplete("call-old", connected))
        assertFalse(CallMinimizePolicy.canComplete(call.callId, connected.copy(call = call.copy(callId = "call-b"))))
    }

    @Test fun `tap short horizontal upward and invalid drags do not collapse`() {
        listOf(0f to 0f, 0f to 63f, 100f to 80f, -100f to 80f,
            0f to -120f, Float.NaN to 100f, 0f to Float.POSITIVE_INFINITY).forEach { (x, y) ->
            assertFalse(CallMinimizePolicy.isDownwardSwipe(x, y), "Rejected drag $x $y")
        }
        assertTrue(CallMinimizePolicy.isDownwardSwipe(0f, 64f))
        assertTrue(CallMinimizePolicy.isDownwardSwipe(-20f, 90f))
    }

    @Test fun `camera clamp excludes header without moving safe right corner or covering controls`() {
        assertEquals(200f to 16f, CallLayoutPolicy.previewOrigin(200f, 16f, 220f, 400f, 16f, 108f, 80f))
        assertEquals(16f to 80f, CallLayoutPolicy.previewOrigin(16f, 16f, 220f, 400f, 16f, 108f, 80f))
        assertEquals(108f to 16f, CallLayoutPolicy.previewOrigin(16f, 16f, 400f, 30f, 16f, 108f, 80f))
        assertNull(CallLayoutPolicy.previewOrigin(16f, 16f, 60f, 30f, 16f, 108f, 80f))
        assertEquals(16f to 80f, CallLayoutPolicy.previewOrigin(-10f, -30f, 60f, 100f, 16f, 108f, 80f))
    }
}
