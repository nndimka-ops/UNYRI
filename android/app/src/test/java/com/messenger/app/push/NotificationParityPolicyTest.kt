package com.messenger.app.push

import kotlin.test.*

class NotificationParityPolicyTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val device = "22222222-2222-4222-8222-222222222222"
    private val chat = "33333333-3333-4333-8333-333333333333"
    private val message = "44444444-4444-4444-8444-444444444444"
    @Test fun `message alert has exactly one owner regardless of transport order`() {
        val ledger = MessageAlertOwnership(2)
        val session = NotificationBatchSession("https://one.example", owner, 1)
        assertTrue(ledger.claim(message, session))
        assertFalse(ledger.claim(message, session))
        assertTrue(ledger.claim(message, session.copy(epoch = 2)))
        assertTrue(ledger.claim(message, session.copy(server = "https://two.example")))
        assertTrue(ledger.claim("next", session)); assertTrue(ledger.claim("third", session))
        assertTrue(ledger.claim(message, session))
    }
    @Test fun `tap never routes to wrong workspace user or device and has no age gate`() {
        val target = NotificationTapTarget("legacy", owner, device, chat, message)
        assertTrue(target.valid()); assertTrue(target.addressedTo("legacy", owner, device))
        assertFalse(target.addressedTo("legacy", owner, message))
        assertFalse(target.addressedTo("legacy", message, device))
        assertFalse(target.addressedTo(device, owner, device))
        assertFalse(target.copy(message = "1-1-1-1-1").valid())
        assertFalse(target.copy(workspace = "../other").valid())
    }
    @Test fun `reminders are independent of message alerts and missed plans never replay`() {
        val settings = NotificationSettings(messages = false, reminders = true, previews = false)
        assertFalse(settings.messageAlertsEnabled); assertTrue(settings.reminderAlertsEnabled)
        assertFalse(settings.copy(enabled = false).reminderAlertsEnabled)
        assertEquals(ReminderPolicy.Action.SCHEDULE, ReminderPolicy.action(11, 10, true))
        assertEquals(ReminderPolicy.Action.PAUSE, ReminderPolicy.action(11, 10, false))
        assertEquals(ReminderPolicy.Action.DROP, ReminderPolicy.action(10, 10, true))
        assertEquals(ReminderPolicy.Action.DROP, ReminderPolicy.action(9, 10, false))
    }
    @Test fun `FCM message recipient device and canonical identifiers are preserved`() {
        val data = mapOf("event" to "new_message", "chat_id" to chat, "message_id" to message,
            "user_id" to owner, "device_id" to device, "sequence" to "12")
        assertEquals(device, (PushPayloadParser.parse(data) as PushEvent.NewMessage).deviceId)
        assertIs<PushEvent.Unknown>(PushPayloadParser.parse(data + ("device_id" to "1-1-1-1-1")))
    }
}
