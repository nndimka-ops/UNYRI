package com.messenger.app.ui.chats

import com.messenger.app.domain.model.ReactionSummary
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class B13ReviewFeedbackTest {
    @Test fun `keyboard activation is once per complete press and cancels when focus or availability changes`() {
        val gate = VoiceRecordKeyGate()
        for (key in VoiceRecordKey.entries) {
            gate.down(key); gate.down(key)
            assertTrue(gate.up(key))
            assertFalse(gate.up(key))
            gate.down(key); gate.cancel()
            assertFalse(gate.up(key))
        }
        gate.down(VoiceRecordKey.Enter)
        assertFalse(gate.up(VoiceRecordKey.Space))
        assertTrue(gate.up(VoiceRecordKey.Enter))
    }

    @Test fun `each actual covering window keeps header work paused`() {
        val cases = listOf(ChatListModalCoverage(contactMenu = true), ChatListModalCoverage(deleteConfirmation = true),
            ChatListModalCoverage(batchDeleteConfirmation = true), ChatListModalCoverage(filterPopup = true),
            ChatListModalCoverage(errorDialog = true))
        assertTrue(ChatListModalCoverage().headerVisible)
        for (coverage in cases) { assertTrue(coverage.covered); assertFalse(coverage.headerVisible) }
    }

    @Test fun `contact menu to confirmation handoff has no uncovered state`() {
        val menu = ChatListModalCoverage(contactMenu = true)
        val installingConfirmation = menu.copy(deleteConfirmation = true)
        val confirmation = installingConfirmation.copy(contactMenu = false)
        for (coverage in listOf(menu, installingConfirmation, confirmation)) assertFalse(coverage.headerVisible)
        assertTrue(confirmation.copy(deleteConfirmation = false).headerVisible)
    }

    @Test fun `album selection chooses the member without mutating or scheduling a reaction`() {
        val events = mutableListOf<String>()
        dispatchReactionToggle(listOf(ReactionSummary("👍", 1, true)), "👍", { events += "tick" }, { events += "remove" },
            { events += "intent" }, { events += "toggle" }, onSelect = { events += "select member" })
        assertEquals(listOf("tick", "select member"), events)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `hint timeout expires once and refreshing does not let an older timeout hide it`() = runTest {
        val state = VoiceHoldHintState { testScheduler.currentTime }
        state.tap(true, false)
        val first = launch { expireVoiceHoldHint(state, state.ticket) }
        runCurrent()
        advanceTimeBy(1_000)
        state.tap(true, false)
        val second = launch { expireVoiceHoldHint(state, state.ticket) }
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertTrue(state.visible)
        first.join()
        advanceUntilIdle(); second.join()
        assertFalse(state.visible)
    }
    @Test fun `reaction addition replacement and removal each dispatch once in event order`() {
        val cases = listOf(
            emptyList<ReactionSummary>() to listOf("add", "intent", "toggle"),
            listOf(ReactionSummary("👍", 2, false)) to listOf("add", "intent", "toggle"),
            listOf(ReactionSummary("❤️", 1, true)) to listOf("add", "intent", "toggle"),
            listOf(ReactionSummary("👍", 2, true)) to listOf("remove", "toggle"))
        for ((reactions, expected) in cases) {
            val events = mutableListOf<String>()
            dispatchReactionToggle(reactions, "👍", { events += "add" }, { events += "remove" },
                { events += "intent" }, { events += "toggle" })
            assertEquals(expected, events)
        }
    }

    @Test fun `disabled taps and post hold clicks cannot show a hint or start a second recording`() {
        var clock = 1_000L
        val state = VoiceHoldHintState { clock }
        assertEquals(VoiceTapFeedback.Ignore, state.tap(false, false))
        assertFalse(state.visible)
        assertEquals(VoiceTapFeedback.Hint, state.tap(true, false))
        state.holdStarted()
        assertFalse(state.visible)
        assertEquals(VoiceTapFeedback.Ignore, state.tap(true, true))
        state.holdEnded()
        assertEquals(VoiceTapFeedback.Ignore, state.tap(true, false))
        clock += 501
        assertEquals(VoiceTapFeedback.Hint, state.tap(true, false))
    }

    @Test fun `stale hint expiry cannot dismiss a refreshed hint and accessiblity keeps locked recording`() {
        val state = VoiceHoldHintState { 10_000L }
        state.tap(true, false)
        val first = state.ticket
        state.tap(true, false)
        state.expire(first)
        assertTrue(state.visible)
        state.expire(state.ticket)
        assertFalse(state.visible)
        state.tap(true, false)
        assertEquals(VoiceTapFeedback.LockedRecording, state.tap(true, true))
        assertFalse(state.visible)
        assertEquals(VoiceTapFeedback.Ignore, state.tap(false, true))
    }
}
