package com.messenger.app.ui.chats

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.attachment.VoiceWaveformCache
import com.messenger.app.data.attachment.VoiceFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sin

class VoiceEnvelopeInstrumentedTest {
    @Test fun decodedEnvelopeDistinguishesSilenceAndToneAndCachesResult() = runBlocking {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "qa-envelope.m4a")
        val encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 16000, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 32000)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); encoder.start()
            var sample = 0; var ended = false; var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (true) {
                check(android.os.SystemClock.elapsedRealtime() < deadline)
                if (!ended) {
                    val input = encoder.dequeueInputBuffer(10000)
                    if (input >= 0) {
                        val buffer = encoder.getInputBuffer(input)!!.order(ByteOrder.LITTLE_ENDIAN)
                        val count = minOf(1024, 32000 - sample)
                        for (i in 0 until count) {
                            val n = sample + i
                            buffer.putShort(if (n < 16000) 0 else (sin(n * 2 * Math.PI * 440 / 16000) * 24000).toInt().toShort())
                        }
                        encoder.queueInputBuffer(input, 0, count * 2, sample.toLong() * 1000000 / 16000, if (count == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        sample += count; ended = count == 0
                    }
                }
                val output = encoder.dequeueOutputBuffer(info, 10000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(encoder.outputFormat); muxer.start(); started = true }
                else if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) muxer.writeSampleData(track, encoder.getOutputBuffer(output)!!, info)
                    encoder.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally { encoder.stop(); encoder.release(); if (started) muxer.stop(); muxer.release() }
        try {
            val samples = VoiceWaveformCache.load(file.path)
            assertEquals(48, samples.size)
            assertTrue(samples.take(10).maxOrNull()!! < .05f)
            assertTrue(samples.takeLast(10).maxOrNull()!! > .8f)
            assertSame(samples, VoiceWaveformCache.load(file.path))
            assertTrue(VoiceFormat.messageWidth(1000) < VoiceFormat.messageWidth(41000))
            assertEquals(VoiceFormat.messageWidth(120000), VoiceFormat.messageWidth(Long.MAX_VALUE))
        } finally { file.delete() }
    }
}
