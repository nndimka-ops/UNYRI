package com.messenger.app.ui.call

import com.messenger.app.domain.call.*
import com.messenger.app.domain.model.CallEndReason

object CallStatusPolicy {
    enum class Kind { CALLING, RINGING, NOT_ONLINE, CONNECTING, SECURED, RECONNECTING, WAITING_NETWORK,
        ENDED, DECLINED, CANCELLED, NO_ANSWER, BUSY, FAILED, HIDDEN }
    fun kind(state: CallUiState, ring: CallRingIndicator.Phase?, reconnectForMs: Long = 0,
        offlineForMs: Long = 0): Kind = when {
        state is CallUiState.Idle -> Kind.HIDDEN
        state is CallUiState.Failed -> Kind.FAILED
        state is CallUiState.Ended -> when(state.reason) {
            CallEndReason.HANGUP -> Kind.ENDED
            CallEndReason.CANCELLED -> Kind.CANCELLED
            CallEndReason.REJECTED -> Kind.DECLINED
            CallEndReason.MISSED, CallEndReason.TIMEOUT -> Kind.NO_ANSWER
            CallEndReason.BUSY -> Kind.BUSY
            else -> Kind.FAILED
        }
        offlineForMs >= 1_200 -> Kind.WAITING_NETWORK
        state is CallUiState.Reconnecting && reconnectForMs >= 1_200 -> Kind.RECONNECTING
        CallPresentationPolicy.connected(state) != null -> Kind.SECURED
        state is CallUiState.OutgoingRinging -> when(ring) {
            CallRingIndicator.Phase.RINGING -> Kind.RINGING
            CallRingIndicator.Phase.NOT_ONLINE -> Kind.NOT_ONLINE
            else -> Kind.CALLING
        }
        else -> Kind.CONNECTING
    }
    fun showsTimer(kind: Kind) = kind == Kind.SECURED
    fun alert(kind: Kind) = kind == Kind.RECONNECTING || kind == Kind.WAITING_NETWORK || kind == Kind.NOT_ONLINE

    enum class LinkProblem { WEAK, POOR }
    enum class Subtitle { WEAK, POOR, WAITING_FOR_PEER }
    enum class RouteGlyph { DIRECT, SERVER }

    /** Route words belong to accessibility/details, never the status subtitle. */
    fun subtitle(kind: Kind, problem: LinkProblem?): Subtitle? = when {
        kind == Kind.NOT_ONLINE -> Subtitle.WAITING_FOR_PEER
        kind != Kind.SECURED -> null
        problem == LinkProblem.WEAK -> Subtitle.WEAK
        problem == LinkProblem.POOR -> Subtitle.POOR
        else -> null
    }

    fun routeGlyph(kind: Kind, relay: Boolean?): RouteGlyph? =
        if (kind != Kind.SECURED || relay == null) null else if (relay) RouteGlyph.SERVER else RouteGlyph.DIRECT

    fun problem(kind: Kind, link: LinkProblem?): Boolean = alert(kind) || (kind == Kind.SECURED && link != null)
    fun showsBars(kind: Kind, link: LinkProblem?): Boolean = problem(kind, link)
}

/** Captures connectedAt before the end update clears it and freezes duration once. */
class CallDurationMemory {
    private var id: String? = null
    private var start: Long? = null
    private var frozen: Long? = null
    fun observe(state: CallUiState, nowMs: Long): Long? {
        val connected = CallPresentationPolicy.connected(state)
        val nextId = connected?.call?.callId ?: when(state) {
            is CallUiState.Ended -> state.call.callId
            is CallUiState.OutgoingRinging -> state.call.callId
            is CallUiState.Connecting -> state.call.callId
            else -> null
        }
        if (nextId != id) { id = nextId; start = null; frozen = null }
        if (connected != null) start = connected.connectedAtElapsedRealtimeMs
        if (state is CallUiState.Ended && frozen == null) frozen = start?.let { (nowMs-it).coerceAtLeast(0)/1000 }
        return frozen
    }
}
