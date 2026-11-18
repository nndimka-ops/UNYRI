package com.messenger.app.push

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationDeliveryGateTest {
    @Test fun `cold startup waits for persisted mute instead of empty screen snapshot`() = runTest {
        val preferences = CompletableDeferred<Boolean>()
        val gate = NotificationDeliveryGate({ "A" }, { preferences.await() })
        val result = async { gate.decide("chat") }; runCurrent()
        assertFalse(result.isCompleted)
        preferences.complete(true); runCurrent()
        assertEquals(NotificationDeliveryGate.Decision.SUPPRESS, result.await())
        assertEquals(NotificationDeliveryGate.Decision.ALERT, NotificationDeliveryGate({ "A" }, { false }).decide("chat"))
    }
    @Test fun `owner swap while preferences load never delivers old notification`() = runTest {
        var owner: String? = "serverA|A"
        val preferences = CompletableDeferred<Boolean>()
        val gate = NotificationDeliveryGate({ owner }, { preferences.await() })
        val result = async { gate.decide("chat") }; runCurrent()
        owner = "serverB|B"; preferences.complete(false); runCurrent()
        assertEquals(NotificationDeliveryGate.Decision.SUPPRESS, result.await())
    }
    @Test fun `slow or failed storage keeps generic notification silent rather than losing it`() = runTest {
        val gate = NotificationDeliveryGate({ "A" }, { delay(5000); false })
        val result = async { gate.decide("chat") }; advanceTimeBy(2001); runCurrent()
        assertEquals(NotificationDeliveryGate.Decision.SILENT, result.await())
        assertEquals(NotificationDeliveryGate.Decision.SILENT, NotificationDeliveryGate({ "A" }, { error("disk") }).decide("chat"))
    }
    @Test fun `device toggle off prevents notification without querying chat storage`() = runTest {
        var settings = NotificationSettings(messages = false)
        var reads = 0
        val gate = NotificationDeliveryGate({ "A" }, { reads++; false }, enabled = { settings.messageAlertsEnabled })
        assertEquals(NotificationDeliveryGate.Decision.SUPPRESS, gate.decide("chat"))
        assertEquals(0, reads)
        settings = settings.copy(messages = true)
        assertEquals(NotificationDeliveryGate.Decision.ALERT, gate.decide("chat"))
        assertEquals(1, reads)
    }
    @Test fun `turning master off during pending cold read suppresses the later notification`() = runTest {
        var settings = NotificationSettings(messageSound = false, calls = false)
        val persisted = CompletableDeferred<Boolean>()
        val gate = NotificationDeliveryGate({ "A" }, { persisted.await() }, enabled = { settings.messageAlertsEnabled })
        val result = async { gate.decide("chat") }; runCurrent()
        settings = settings.copy(enabled = false)
        persisted.complete(false); runCurrent()
        assertEquals(NotificationDeliveryGate.Decision.SUPPRESS, result.await())
        settings = settings.copy(enabled = true)
        assertFalse(settings.callAlertsEnabled)
        assertFalse(settings.messageSound)
        assertEquals(NotificationDeliveryGate.Decision.ALERT, gate.decide("chat"))
    }
}
