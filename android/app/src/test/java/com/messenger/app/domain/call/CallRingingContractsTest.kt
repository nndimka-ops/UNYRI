package com.messenger.app.domain.call

import com.messenger.app.domain.call.CallRingIndicator.Phase
import com.messenger.app.domain.call.CallRingingIndicatorPolicy.Event
import com.messenger.app.domain.call.CallRingingIndicatorPolicy.Output
import org.junit.Assert.*
import org.junit.Test

/** Same observable transitions and reordered delivery cases as ios/Tests/CallRingingContracts. */
class CallRingingContractsTest {
    private val sending = CallRingIndicator()
    private val expected = CallRingIndicator(expect = true, created = true)
    private val legacy = CallRingIndicator(created = true)
    private val notOnline = expected.copy(phase = Phase.NOT_ONLINE)
    private val ringing = expected.copy(phase = Phase.RINGING, rang = true)
    private fun row(peer: Boolean? = false, expect: Boolean? = true, after: Boolean = true, state: String = "ringing") = Event.ServerRow(state, peer, expect, after)
    private fun reduce(value: CallRingIndicator?, event: Event) = CallRingingIndicatorPolicy.reduce(value, event).value
    private fun trace(vararg events: Event): CallRingIndicator? = events.fold(null as CallRingIndicator?) { value, event -> reduce(value, event) }
    private val allEvents get() = listOf(Event.Started, Event.Created(true), Event.Created(false), Event.CreateFailed(true), Event.CreateFailed(false),
        Event.PeerRinging, row(), row(null, null), row(false, false), row(true, false), row(true, true), row(false, true, false), row(false, null, false),
        row(state = "accepted"), row(state = "ended"), Event.Accepted, Event.Ended("rejected"), Event.Ended("hangup"), Event.Ended("missed"),
        Event.Ended("cancelled"), Event.Ended("failed"), Event.Ended("timeout"), Event.Ended("device_revoked"), Event.Ended("unknown"), Event.CancelledHere)

    @Test fun orderedTransitionTable() {
        assertEquals(sending, reduce(null, Event.Started))
        assertEquals(expected, reduce(null, Event.Created(true)))
        assertEquals(expected, reduce(sending, Event.Created(true)))
        assertEquals(expected, reduce(expected, Event.Created(false)))
        assertEquals(legacy, reduce(legacy, Event.Created(true)))
        assertEquals(Phase.BUSY, reduce(sending, Event.CreateFailed(true))?.phase)
        assertEquals(Phase.FAILED, reduce(sending, Event.CreateFailed(false))?.phase)
        assertEquals(expected, reduce(expected, Event.CreateFailed(true)))
        for (origin in listOf(expected, notOnline)) {
            for (event in listOf(Event.PeerRinging, row(true))) {
                val result = CallRingingIndicatorPolicy.reduce(origin, event)
                assertEquals(ringing, result.value)
                assertEquals(listOf(Output.Haptic, Output.Announce(Phase.RINGING)), result.outputs)
            }
        }
        assertEquals(Phase.SENDING, reduce(notOnline, row(false, false))?.phase)
        for (origin in listOf(expected, notOnline, ringing)) assertFalse(reduce(origin, row(false, false))!!.expect)
        assertEquals(Phase.RINGING, reduce(ringing, row(false, false))?.phase)
        assertEquals(Phase.NOT_ONLINE, reduce(expected, row())?.phase)
        assertEquals(Phase.NOT_ONLINE, reduce(expected, row(null, null))?.phase)
        assertEquals(sending, reduce(sending, row()))
        assertEquals(expected, reduce(expected, row(after = false)))
        assertEquals(legacy, reduce(legacy, row()))
        assertEquals(notOnline, reduce(notOnline, row()))
        assertEquals(ringing, reduce(ringing, row()))
        assertEquals(Phase.RINGING, reduce(expected, row(true, false))?.phase)
        for (origin in listOf(sending, expected, notOnline, ringing)) {
            assertEquals(origin, reduce(origin, row(state = "accepted")))
            assertEquals(origin, reduce(origin, row(state = "ended")))
            assertEquals(Phase.ANSWERED, reduce(origin, Event.Accepted)?.phase)
            assertEquals(Phase.CANCELLED, reduce(origin, Event.CancelledHere)?.phase)
            for ((reason, phase) in mapOf("rejected" to Phase.DECLINED, "hangup" to Phase.DECLINED, "missed" to Phase.NO_ANSWER,
                "cancelled" to Phase.CANCELLED, "failed" to Phase.FAILED, "timeout" to Phase.FAILED, "device_revoked" to Phase.FAILED, "unknown" to Phase.FAILED)) {
                assertEquals(reason, phase, reduce(origin, Event.Ended(reason))?.phase)
            }
        }
    }

    @Test fun terminalAbsorptionDerivedOutputsAndNoDuplicateAnnouncements() {
        for (phase in Phase.entries) {
            for (created in listOf(false, true)) for (expect in listOf(false, true)) {
                val value = CallRingIndicator(phase, expect, created, phase == Phase.RINGING)
                assertEquals(phase == Phase.RINGING || (phase == Phase.SENDING && created && !expect), value.ringbackAllowed)
                assertEquals((phase == Phase.SENDING || phase == Phase.NOT_ONLINE) && created && expect, value.awaitsConfirmation)
            }
            if (phase.isTerminal) {
                val terminal = CallRingIndicator(phase, expect = true, created = true, rang = true)
                for (event in allEvents) {
                    assertEquals(terminal, reduce(terminal, event))
                    assertTrue(CallRingingIndicatorPolicy.reduce(terminal, event).outputs.isEmpty())
                }
            }
        }
        for (origin in listOf(sending, expected, legacy, notOnline, ringing)) for (event in allEvents) {
            val next = reduce(origin, event) ?: continue
            if (origin.phase == Phase.RINGING) assertTrue(next.phase != Phase.SENDING && next.phase != Phase.NOT_ONLINE)
            if (origin.created && !origin.expect) assertFalse(next.expect)
            if (next.phase == Phase.NOT_ONLINE && origin.phase != Phase.NOT_ONLINE) {
                assertTrue(origin.created && origin.expect && event is Event.ServerRow && event.afterDeadline)
            }
            assertTrue("duplicate $event from $origin", CallRingingIndicatorPolicy.reduce(next, event).outputs.isEmpty())
        }
        assertEquals(listOf(Output.Announce(Phase.RINGING)), CallRingingIndicatorPolicy.reduce(notOnline.copy(rang = true), Event.PeerRinging).outputs)
        assertEquals(listOf(Output.Announce(Phase.NOT_ONLINE)), CallRingingIndicatorPolicy.reduce(expected, row()).outputs)
        assertEquals(6_000L, CallRingingIndicatorPolicy.confirmDeadlineMs)
        assertEquals(5_000L, CallRingingIndicatorPolicy.recheckIntervalMs)
    }

    @Test fun reorderedEventsRecoveryPrivacyAndLegacyRaces() {
        assertEquals(Phase.RINGING, trace(Event.Created(true), row(true, true, false))?.phase) // 1: pre-bind event recovered by GET
        assertEquals(Phase.ANSWERED, trace(Event.Created(true), Event.Accepted, Event.PeerRinging)?.phase) // 2
        for (order in listOf(listOf(Event.PeerRinging, Event.Accepted), listOf(Event.Accepted, Event.PeerRinging))) {
            assertEquals(Phase.ANSWERED, (listOf(Event.Created(true)) + order).fold(null as CallRingIndicator?) { state, event -> reduce(state, event) }?.phase)
        }
        for (reason in listOf("rejected", "cancelled", "missed")) {
            val ended = trace(Event.Created(true), Event.Ended(reason))
            assertEquals(ended, reduce(ended, Event.PeerRinging)) // 4
        }
        assertTrue(CallRingingIndicatorPolicy.reduce(ringing, Event.PeerRinging).outputs.isEmpty()) // 5
        assertEquals(ringing, trace(Event.Created(true), Event.PeerRinging, Event.PeerRinging, row(true))) // 6
        assertEquals(Phase.CANCELLED, trace(Event.Created(true), Event.CancelledHere, Event.PeerRinging)?.phase) // 7
        assertEquals(Phase.RINGING, trace(Event.Created(true), row(), row(true))?.phase) // 8
        assertEquals(Phase.RINGING, trace(Event.Created(true), Event.PeerRinging, row())?.phase) // 9
        assertEquals(expected, trace(Event.Created(true))) // 10: transport error has no authoritative row
        assertNull(reduce(null, row(true))) // 11: no restoration after relaunch
        assertEquals(Phase.BUSY, trace(Event.Started, Event.CreateFailed(true))?.phase) // 13
        assertEquals(Phase.DECLINED, reduce(expected, Event.Ended("rejected"))?.phase) // 14
        assertEquals(Phase.NOT_ONLINE, reduce(expected, row())?.phase) // 15: report suppressed by DND
        assertEquals(Phase.RINGING, trace(Event.Created(true), row(), Event.PeerRinging)?.phase) // 16
        val missed = reduce(notOnline, Event.Ended("missed"))!!
        assertEquals(Phase.NO_ANSWER, missed.phase)
        assertFalse(missed.ringbackAllowed)
        assertFalse(missed.awaitsConfirmation) // 17
        assertEquals(Phase.DECLINED, reduce(expected, Event.Ended("hangup"))?.phase) // 18
        assertEquals(Phase.FAILED, reduce(ringing, Event.Ended("device_revoked"))?.phase) // 19
        assertEquals(legacy, reduce(legacy, row())) // 21/23 hidden presence, old build
        assertEquals(Phase.SENDING, reduce(notOnline, row(false, false))?.phase)
        assertEquals(notOnline, reduce(notOnline, row())) // 22 unreachable old build
        assertTrue(legacy.ringbackAllowed)
        assertTrue(reduce(notOnline, row(false, false))!!.ringbackAllowed) // 24 kill switch
        assertEquals(Phase.BUSY, trace(Event.Started, Event.CreateFailed(true))?.phase) // 25 simultaneous caller
    }
}
