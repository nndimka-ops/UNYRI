package com.messenger.app.push

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NotificationDecorationTest {
    @Test fun `optional avatar failure cannot fail the posted alert`() = runTest {
        assertNull(loadNotificationDecoration<String> { throw IOException("image unavailable") })
    }
    @Test fun `slow provider times out without failing the alert`() = runTest {
        assertNull(loadNotificationDecoration { delay(10_000); "late image" })
    }
    @Test fun `new notification or logout cancellation still propagates`() = runTest {
        assertFailsWith<CancellationException> {
            loadNotificationDecoration<String> { throw CancellationException("owner changed") }
        }
    }
    @Test fun `available avatar is returned`() = runTest {
        assertEquals("image", loadNotificationDecoration { "image" })
    }
}
