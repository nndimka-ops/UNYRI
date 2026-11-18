package com.messenger.app.push

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotificationSequenceLedgerTest {
    @Test fun `WS consumed while alerts off is not replayed by FCM after re-enabling`() = runTest {
        val owner = NotificationSessionKey(11, "alice")
        var settings = NotificationSettings(enabled = false)
        val gate = NotificationDeliveryGate({ "server|alice|11" }, { false }, enabled = { settings.messageAlertsEnabled })
        val ledger = NotificationSequenceLedger()
        val ws = PushEvent.NewMessage("chat", "message", "41", "alice")
        assertEquals(NotificationDeliveryGate.Decision.SUPPRESS, gate.decide(ws.chatId))
        assertTrue(ledger.record(ws, owner, owner))
        settings = settings.copy(enabled = true)
        val lateFcm = ws.copy()
        assertEquals(NotificationDeliveryGate.Decision.ALERT, gate.decide(lateFcm.chatId))
        assertFalse(ledger.shouldPresent(lateFcm, owner))
        val next = ws.copy(messageId = "next", sequence = "42")
        assertTrue(ledger.shouldPresent(next, owner))
        assertTrue(ledger.record(next, owner, owner))
        assertFalse(ledger.shouldPresent(ws, owner))
        assertFalse(ledger.shouldPresent(next, owner))
    }

    @Test fun `old suspended suppression cannot clear or advance the new session ledger`() {
        val ledger = NotificationSequenceLedger()
        val old = NotificationSessionKey(11, "alice")
        val current = NotificationSessionKey(12, "bob")
        val newEvent = PushEvent.NewMessage("chat", "b", "7", "bob")
        assertTrue(ledger.record(newEvent, current, current))
        assertFalse(ledger.record(PushEvent.NewMessage("chat", "a", "999", "alice"), old, current))
        assertEquals(7L, ledger.highest("chat", current))
        assertFalse(ledger.shouldPresent(newEvent, current))
        assertTrue(ledger.shouldPresent(newEvent.copy(sequence = "8"), current))
    }

    @Test fun `same account after relogin has a separate epoch and rejects a delayed suppression`() {
        val ledger = NotificationSequenceLedger()
        val before = NotificationSessionKey(11, "alice")
        val after = NotificationSessionKey(12, "alice")
        val event = PushEvent.NewMessage("chat", "m", "9", "alice")
        assertTrue(ledger.record(event, before, before))
        assertTrue(ledger.shouldPresent(event, after))
        assertFalse(ledger.record(event.copy(sequence = "999"), before, after))
        assertTrue(ledger.shouldPresent(event, after))
        assertFalse(ledger.record(event, after, NotificationSessionKey(13, null)))
    }
}
