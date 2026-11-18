package com.messenger.app.data.avatar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JVM-testable subset of the avatar pipeline: the sample-size math that keeps
 *  a huge camera photo from being decoded at full resolution. (The Bitmap
 *  decode/EXIF/crop-encode steps are Android-only and device-verified.) */
class AvatarImageProcessorTest {

    @Test
    fun `small source uses no downsampling`() {
        assertEquals(1, AvatarImageProcessor.computeSampleSize(512, 512, 2048))
        assertEquals(1, AvatarImageProcessor.computeSampleSize(1000, 800, 2048))
    }

    @Test
    fun `large source is sampled down safely`() {
        // 8000x6000 phone photo into a 2048 edit cap.
        val sample = AvatarImageProcessor.computeSampleSize(8000, 6000, 2048)
        assertTrue(sample >= 2, "a large photo must be sampled down")
        assertTrue(8000 / sample <= 2048, "sampled width fits the cap")
        assertTrue(6000 / sample <= 2048, "sampled height fits the cap")
    }

    @Test
    fun `power of two sampling never over-samples`() {
        val sample = AvatarImageProcessor.computeSampleSize(12000, 9000, 2048)
        assertEquals(1, Integer.bitCount(sample), "sample size is a power of two")
        assertTrue(12000 / sample <= 2048)
    }
}
