package com.messenger.app.domain.call

/** Caller-local acknowledgement: no remote device identity or timestamps are retained. */
data class CallRingIndicator(
    val phase: Phase = Phase.SENDING,
    val expect: Boolean = false,
    val created: Boolean = false,
    val rang: Boolean = false,
) {
    enum class Phase {
        SENDING, RINGING, NOT_ONLINE, ANSWERED, BUSY, DECLINED, CANCELLED, NO_ANSWER, FAILED;
        val isTerminal: Boolean get() = this !in setOf(SENDING, RINGING, NOT_ONLINE)
    }
    val ringbackAllowed: Boolean get() = phase == Phase.RINGING || (phase == Phase.SENDING && created && !expect)
    val awaitsConfirmation: Boolean get() = (phase == Phase.SENDING || phase == Phase.NOT_ONLINE) && created && expect
}

/** Ordered transitions from CALL_RINGING_SPEC section 1 and the iOS contract suite. */
object CallRingingIndicatorPolicy {
    const val confirmDeadlineMs = 6_000L
    const val recheckIntervalMs = 5_000L
    sealed interface Event {
        data object Started : Event
        data class Created(val expect: Boolean) : Event
        data class CreateFailed(val busy: Boolean) : Event
        data object PeerRinging : Event
        data class ServerRow(val state: String, val peerRinging: Boolean?, val expected: Boolean?, val afterDeadline: Boolean) : Event
        data object Accepted : Event
        data class Ended(val reason: String) : Event
        data object CancelledHere : Event
    }
    sealed interface Output {
        data object Haptic : Output
        data class Announce(val phase: CallRingIndicator.Phase) : Output
    }
    data class Transition(val value: CallRingIndicator?, val outputs: List<Output> = emptyList())

    fun reduce(current: CallRingIndicator?, event: Event): Transition {
        if (current == null) return Transition(when (event) {
            Event.Started -> CallRingIndicator()
            is Event.Created -> CallRingIndicator(expect = event.expect, created = true)
            else -> null
        })
        if (current.phase.isTerminal) return Transition(current)
        var next = current
        when (event) {
            Event.Started -> Unit
            is Event.Created -> if (current.phase == CallRingIndicator.Phase.SENDING && !current.created) {
                next = current.copy(expect = event.expect, created = true)
            }
            is Event.CreateFailed -> if (current.phase == CallRingIndicator.Phase.SENDING && !current.created) {
                next = current.copy(phase = if (event.busy) CallRingIndicator.Phase.BUSY else CallRingIndicator.Phase.FAILED)
            }
            Event.PeerRinging -> next = current.copy(phase = CallRingIndicator.Phase.RINGING, rang = true)
            is Event.ServerRow -> {
                if (event.state != "ringing") return Transition(current)
                if (event.peerRinging == true && current.phase in setOf(CallRingIndicator.Phase.SENDING, CallRingIndicator.Phase.NOT_ONLINE)) {
                    next = current.copy(phase = CallRingIndicator.Phase.RINGING, rang = true)
                } else if (event.expected == false) {
                    next = current.copy(expect = false, phase = if (current.phase == CallRingIndicator.Phase.NOT_ONLINE) CallRingIndicator.Phase.SENDING else current.phase)
                } else if (current.phase == CallRingIndicator.Phase.SENDING && event.afterDeadline && current.expect && current.created) {
                    next = current.copy(phase = CallRingIndicator.Phase.NOT_ONLINE)
                }
            }
            Event.Accepted -> next = current.copy(phase = CallRingIndicator.Phase.ANSWERED)
            is Event.Ended -> next = current.copy(phase = when (event.reason) {
                "rejected", "hangup" -> CallRingIndicator.Phase.DECLINED
                "missed" -> CallRingIndicator.Phase.NO_ANSWER
                "cancelled" -> CallRingIndicator.Phase.CANCELLED
                else -> CallRingIndicator.Phase.FAILED
            })
            Event.CancelledHere -> next = current.copy(phase = CallRingIndicator.Phase.CANCELLED)
        }
        if (next.phase == current.phase) return Transition(next)
        val outputs = when (next.phase) {
            CallRingIndicator.Phase.RINGING -> (if (current.rang) emptyList() else listOf(Output.Haptic)) + Output.Announce(next.phase)
            CallRingIndicator.Phase.NOT_ONLINE, CallRingIndicator.Phase.BUSY, CallRingIndicator.Phase.DECLINED,
            CallRingIndicator.Phase.NO_ANSWER, CallRingIndicator.Phase.FAILED -> listOf(Output.Announce(next.phase))
            else -> emptyList()
        }
        return Transition(next, outputs)
    }
}
