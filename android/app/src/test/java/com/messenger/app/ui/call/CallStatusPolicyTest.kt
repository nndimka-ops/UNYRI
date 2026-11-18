package com.messenger.app.ui.call

import com.messenger.app.domain.call.*
import com.messenger.app.domain.model.*
import kotlin.test.*

class CallStatusPolicyTest {
    @Test fun normalPhasesAreOnePlainLineWithoutBarsOrRouteWords() {
        for (kind in CallStatusPolicy.Kind.entries.filterNot(CallStatusPolicy::alert)) {
            assertNull(CallStatusPolicy.subtitle(kind, null))
            assertFalse(CallStatusPolicy.problem(kind, null))
            assertFalse(CallStatusPolicy.showsBars(kind, null))
        }
        assertEquals(CallStatusPolicy.RouteGlyph.DIRECT, CallStatusPolicy.routeGlyph(CallStatusPolicy.Kind.SECURED, false))
        assertEquals(CallStatusPolicy.RouteGlyph.SERVER, CallStatusPolicy.routeGlyph(CallStatusPolicy.Kind.SECURED, true))
        assertNull(CallStatusPolicy.routeGlyph(CallStatusPolicy.Kind.SECURED, null))
        for (kind in CallStatusPolicy.Kind.entries.filter { it != CallStatusPolicy.Kind.SECURED })
            assertNull(CallStatusPolicy.routeGlyph(kind, true))
    }
    @Test fun onlyProblemsAcquireSurfaceSubtitleAndBarsInEveryLayout() {
        val secured = CallStatusPolicy.Kind.SECURED
        for (link in CallStatusPolicy.LinkProblem.entries) {
            assertTrue(CallStatusPolicy.problem(secured, link))
            assertTrue(CallStatusPolicy.showsBars(secured, link))
            assertNotNull(CallStatusPolicy.subtitle(secured, link))
            assertTrue(CallStatusPolicy.showsTimer(secured))
        }
        for (kind in listOf(CallStatusPolicy.Kind.RECONNECTING, CallStatusPolicy.Kind.WAITING_NETWORK, CallStatusPolicy.Kind.NOT_ONLINE)) {
            assertTrue(CallStatusPolicy.problem(kind, null))
            assertTrue(CallStatusPolicy.showsBars(kind, null))
            assertFalse(CallStatusPolicy.showsTimer(kind))
        }
        assertEquals(CallStatusPolicy.Subtitle.WAITING_FOR_PEER, CallStatusPolicy.subtitle(CallStatusPolicy.Kind.NOT_ONLINE, null))
        for (kind in listOf(CallStatusPolicy.Kind.ENDED, CallStatusPolicy.Kind.FAILED, CallStatusPolicy.Kind.CONNECTING)) {
            assertFalse(CallStatusPolicy.problem(kind, CallStatusPolicy.LinkProblem.POOR))
            assertNull(CallStatusPolicy.subtitle(kind, CallStatusPolicy.LinkProblem.POOR))
        }
    }
    private val call=CallInfo("a","p","Peer",CallMediaKind.VIDEO,CallDirection.OUTGOING)
    private val connected=CallUiState.Connected(call,1_000)
    @Test fun `short drop keeps secured timer shown drop and network use same deadline`() {
        val drop=CallUiState.Reconnecting(call,connected)
        assertEquals(CallStatusPolicy.Kind.SECURED,CallStatusPolicy.kind(drop,null,1_199))
        assertTrue(CallStatusPolicy.showsTimer(CallStatusPolicy.kind(drop,null,1_199)))
        assertEquals(CallStatusPolicy.Kind.RECONNECTING,CallStatusPolicy.kind(drop,null,1_200))
        assertEquals(CallStatusPolicy.Kind.WAITING_NETWORK,CallStatusPolicy.kind(drop,null,1_200,1_200))
        assertEquals(CallStatusPolicy.Kind.WAITING_NETWORK,CallStatusPolicy.kind(CallUiState.OutgoingRinging(call),null,offlineForMs=1_200))
    }
    @Test fun `acknowledged ring and terminal words never confuse connecting or hangup`() {
        assertEquals(CallStatusPolicy.Kind.CALLING,CallStatusPolicy.kind(CallUiState.OutgoingRinging(call),CallRingIndicator.Phase.SENDING))
        assertEquals(CallStatusPolicy.Kind.RINGING,CallStatusPolicy.kind(CallUiState.OutgoingRinging(call),CallRingIndicator.Phase.RINGING))
        assertEquals(CallStatusPolicy.Kind.NOT_ONLINE,CallStatusPolicy.kind(CallUiState.OutgoingRinging(call),CallRingIndicator.Phase.NOT_ONLINE))
        assertEquals(CallStatusPolicy.Kind.CONNECTING,CallStatusPolicy.kind(CallUiState.Connecting(call),null))
        for ((reason,kind) in listOf(CallEndReason.HANGUP to CallStatusPolicy.Kind.ENDED,CallEndReason.CANCELLED to CallStatusPolicy.Kind.CANCELLED,
            CallEndReason.REJECTED to CallStatusPolicy.Kind.DECLINED,CallEndReason.MISSED to CallStatusPolicy.Kind.NO_ANSWER,
            CallEndReason.BUSY to CallStatusPolicy.Kind.BUSY,CallEndReason.FAILED to CallStatusPolicy.Kind.FAILED))
            assertEquals(kind,CallStatusPolicy.kind(CallUiState.Ended(call,reason),null,offlineForMs=8_000))
    }
    @Test fun `ended duration frozen once and resets on new call`() {
        val memory=CallDurationMemory()
        assertNull(memory.observe(connected,2_000))
        val ended=CallUiState.Ended(call,CallEndReason.HANGUP)
        assertEquals(9,memory.observe(ended,10_999))
        assertEquals(9,memory.observe(ended,15_000))
        assertNull(memory.observe(CallUiState.OutgoingRinging(call.copy(callId="b")),20_000))
        assertNull(memory.observe(CallUiState.Ended(call.copy(callId="b"),CallEndReason.CANCELLED),21_000))
    }
}
