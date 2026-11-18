package com.messenger.app.data.directtransfer

import org.junit.Assert.*
import org.junit.Test

class DirectRateEstimatorTest {
    @Test fun receiverUsesDurableBytesAndSharedSmoothedSamples() {
        val resumed = 4L * 1024 * 1024 * 1024
        val mib = 1024 * 1024L
        val estimator = DirectRateEstimator(resumed, 1000)
        val one = estimator.observe(resumed + mib, 1250)
        assertEquals(DirectAckStats(1, 4 * mib), one)
        assertEquals(DirectAckStats(2, 4 * mib), estimator.observe(resumed + 2 * mib, 1500))
        assertEquals(DirectAckStats(3, 5 * mib), estimator.observe(resumed + 4 * mib, 1750))
        assertNull(estimator.idle(resumed + 4 * mib, 2000))
        assertEquals(DirectAckStats(4, 0), estimator.idle(resumed + 4 * mib, 2750))
        assertNull(estimator.idle(resumed + 4 * mib, 3000))
        assertEquals(DirectAckStats(5, 2 * mib), estimator.observe(resumed + 5 * mib, 3250))
        assertEquals(DirectAckStats(1, 4 * mib), DirectRateEstimator(resumed + 5 * mib, 9000).observe(resumed + 6 * mib, 9250))
    }
    @Test fun sub250msDoesNotInventAHighInstantaneousRate() {
        val estimator = DirectRateEstimator(0, 0)
        assertEquals(DirectAckStats(1, 0), estimator.observe(262144, 1))
        assertEquals(DirectAckStats(2, 2097152), estimator.observe(524288, 250))
    }
    @Test fun duplicateSamplesAreIdempotentOnlyForSameOffsetAndRate() {
        val sequence = DirectAckSequence(0)
        sequence.accept(262144, 524288, DirectAckStats(1, 1000000))
        sequence.accept(262144, 524288, DirectAckStats(1, 1000000))
        assertTrue(runCatching { sequence.accept(524288, 524288, DirectAckStats(1, 1000000)) }.isFailure)
        assertTrue(runCatching { sequence.accept(262144, 524288, DirectAckStats(1, 999999)) }.isFailure)
        sequence.accept(524288, 524288, DirectAckStats(2, 2000000))
        assertTrue(runCatching { sequence.accept(524288, 524288, DirectAckStats(1, 1000000)) }.isFailure)
        assertTrue(runCatching { sequence.accept(524289, 524288, DirectAckStats(3, 2000000)) }.isFailure)
    }
    @Test fun overflowAndNegativeStatisticsRejectedBeforeAnySideEffect() {
        assertTrue(runCatching { DirectAckStats(0, 1) }.isFailure)
        assertTrue(runCatching { DirectAckStats(Long.MIN_VALUE, 1) }.isFailure)
        assertTrue(runCatching { DirectAckStats(1, DirectFileWire.MAX_FILE_BYTES + 1) }.isFailure)
        assertEquals(DirectAckStats(Long.MAX_VALUE, DirectFileWire.MAX_FILE_BYTES), DirectAckStats.decode(DirectAckStats(Long.MAX_VALUE, DirectFileWire.MAX_FILE_BYTES).encode()))
    }
}
