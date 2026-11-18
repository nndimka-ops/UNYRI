package com.messenger.app.push

import com.messenger.app.data.api.MessageDto
import kotlin.test.*

class PushReviewPolicyTest {
    @Test fun `creator identity alone and structure alone never authorize a route`() {
        assertFalse(NotificationRouteProofPolicy.accepts(123, null, false))
        assertFalse(NotificationRouteProofPolicy.accepts(123, 456, true))
        assertFalse(NotificationRouteProofPolicy.accepts(123, 123, false))
        assertFalse(NotificationRouteProofPolicy.accepts(-1, -1, true))
        assertTrue(NotificationRouteProofPolicy.accepts(123, 123, true))
    }
    @Test fun `cold application startup preserves due alarm before receiver delivery`() {
        val plans = mutableMapOf("current" to 10L, "future" to 30L)
        for ((id, at) in plans.toMap()) if (ReminderPolicy.action(at, 20, true, startup = true) == ReminderPolicy.Action.DROP) plans.remove(id)
        assertEquals(10L, plans.remove("current")) // Real receiver can still claim it.
        assertEquals(30L, plans["future"])
        assertEquals(ReminderPolicy.Action.SCHEDULE, ReminderPolicy.action(30, 20, true, startup = true))
    }
    @Test fun `disabled startup keeps delivery record but boot and settings never replay missed plans`() {
        assertEquals(ReminderPolicy.Action.KEEP_DUE, ReminderPolicy.action(20, 20, false, startup = true))
        assertEquals(ReminderPolicy.Action.DROP, ReminderPolicy.action(20, 20, true))
        assertEquals(ReminderPolicy.Action.DROP, ReminderPolicy.action(19, 20, false))
        assertEquals(ReminderPolicy.Action.PAUSE, ReminderPolicy.action(30, 20, false, startup = true))
    }
    @Test fun `socket quiet decision survives before any album row exists`() {
        val dto = MessageDto("message", "chat", "client", "peer", "device", 2,
            "2026-11-12T12:00:00Z", 1, "encrypted", notificationBatchAlert = false)
        val event = PushEvent.NewMessage.fromSocket(dto, "recipient")
        assertEquals(false, event.notificationBatchAlert)
        assertFalse(NotificationBatchAlertPolicy.shouldAlert(event.notificationBatchAlert, firstLocalAlert = true))
        assertTrue(NotificationBatchAlertPolicy.shouldAlert(null, true)) // FCM immediate generic fallback.
        assertTrue(NotificationBatchAlertPolicy.shouldAlert(true, true))
        assertFalse(NotificationBatchAlertPolicy.shouldAlert(true, false))
    }
    @Test fun `FCM cannot impersonate authenticated socket batch metadata`() {
        val id = "11111111-1111-4111-8111-111111111111"
        val parsed = PushPayloadParser.parse(mapOf("event" to "new_message", "chat_id" to id,
            "message_id" to id, "sequence" to "1", "user_id" to id, "device_id" to id,
            "notification_batch_alert" to "false")) as PushEvent.NewMessage
        assertNull(parsed.notificationBatchAlert)
    }
}
