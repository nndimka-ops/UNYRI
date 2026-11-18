package com.messenger.app.data.api

import kotlin.test.*

class DiagnosticsUploadPolicyTest {
    @Test fun `only consented foreground pending batches schedule uploads`() {
        assertEquals(5_000L, DiagnosticsUploadPolicy.delayMillis(true, true, true, true, 0))
        assertNull(DiagnosticsUploadPolicy.delayMillis(false, true, true, true, 5_000))
        assertNull(DiagnosticsUploadPolicy.delayMillis(true, false, true, true, 5_000))
        assertNull(DiagnosticsUploadPolicy.delayMillis(true, true, false, true, 5_000))
        assertNull(DiagnosticsUploadPolicy.delayMillis(true, true, true, false, 5_000))
    }
    @Test fun `failures back off within a minute and success resets batching`() {
        assertEquals(10_000L, DiagnosticsUploadPolicy.afterAttempt(5_000, false))
        assertEquals(60_000L, DiagnosticsUploadPolicy.afterAttempt(60_000, false))
        assertEquals(5_000L, DiagnosticsUploadPolicy.afterAttempt(60_000, true))
    }
}
