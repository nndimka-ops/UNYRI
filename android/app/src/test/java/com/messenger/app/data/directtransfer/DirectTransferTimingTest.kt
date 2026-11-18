package com.messenger.app.data.directtransfer

import org.junit.Assert.*
import org.junit.Test

class DirectTransferTimingTest {
    @Test fun largeOriginalHasFiniteSizeAwareVerificationWithoutPeerRenewal() {
        assertEquals(45_000L, DirectTransferTiming.verificationMillis(1))
        assertEquals(46_000L, DirectTransferTiming.verificationMillis(64L * 1024 * 1024))
        assertEquals(16_414_000L, DirectTransferTiming.verificationMillis(DirectFileWire.MAX_FILE_BYTES))
        assertTrue(runCatching { DirectTransferTiming.verificationMillis(Long.MAX_VALUE) }.isFailure)
    }
    @Test fun lostFirstAcceptanceHasShortBoundedRetriesDespiteLargeFile() {
        assertEquals(90_000L, DirectTransferTiming.requestMillis(DirectFileWire.MAX_FILE_BYTES, 1, false))
        assertEquals(180_000L, DirectTransferTiming.requestMillis(1, 2, false))
        assertEquals(1_800_000L, DirectTransferTiming.requestMillis(1, 6, false))
        assertEquals(16_414_000L, DirectTransferTiming.requestMillis(DirectFileWire.MAX_FILE_BYTES, 1, true))
        assertTrue(runCatching { DirectTransferTiming.requestMillis(1, 7, false) }.isFailure)
    }
}
