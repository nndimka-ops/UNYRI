package com.messenger.app.data.attachment

import android.media.MediaCodec
import android.media.MediaExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VoiceDraftJoinerFlagsTest {
    @Test fun `ordinary and sync samples retain their codec meaning`() {
        assertEquals(0, codecBufferFlagsForExtractedSample(0))
        assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME, codecBufferFlagsForExtractedSample(MediaExtractor.SAMPLE_FLAG_SYNC))
    }

    @Test fun `partial input maps to codec partial flag without premature end of stream`() {
        val flags = codecBufferFlagsForExtractedSample(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)
        assertEquals(MediaCodec.BUFFER_FLAG_PARTIAL_FRAME, flags)
        assertEquals(0, flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    @Test fun `sync and partial sample flags combine in codec namespace`() {
        assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME,
            codecBufferFlagsForExtractedSample(MediaExtractor.SAMPLE_FLAG_SYNC or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))
    }

    @Test fun `encrypted sample is rejected instead of being tagged as codec configuration`() {
        assertFailsWith<IllegalArgumentException> { codecBufferFlagsForExtractedSample(MediaExtractor.SAMPLE_FLAG_ENCRYPTED) }
        assertFailsWith<IllegalArgumentException> {
            codecBufferFlagsForExtractedSample(MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_SYNC)
        }
    }
}
