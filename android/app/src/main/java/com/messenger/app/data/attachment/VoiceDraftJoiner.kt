package com.messenger.app.data.attachment

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Join our AAC segments without decoding. Originals remain intact until the caller saves the result. */
object VoiceDraftJoiner {
    fun join(first: File, second: File): File {
        val output = File(second.parentFile, "voice_join_${java.util.UUID.randomUUID()}.m4a")
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            var target = -1
            var offset = 0L
            var expected: MediaFormat? = null
            val buffer = ByteBuffer.allocateDirect(256 * 1024)
            val info = MediaCodec.BufferInfo()
            for (source in listOf(first, second)) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(source.path)
                    val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    val format = extractor.getTrackFormat(track)
                    if (expected == null) {
                        expected = format; target = muxer.addTrack(format); muxer.start(); started = true
                    } else {
                        require(format.getString(MediaFormat.KEY_MIME) == expected.getString(MediaFormat.KEY_MIME))
                        require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == expected.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                        require(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == expected.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    }
                    extractor.selectTrack(track)
                    var origin = -1L
                    var last = offset
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        if (origin < 0) origin = extractor.sampleTime
                        val time = offset + extractor.sampleTime - origin
                        info.set(0, size, time, codecBufferFlagsForExtractedSample(extractor.sampleFlags))
                        muxer.writeSampleData(target, buffer, info)
                        last = time
                        extractor.advance()
                    }
                    require(origin >= 0)
                    offset = last + 1_024_000_000L / format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } finally { extractor.release() }
            }
            muxer.stop(); started = false
            return output
        } catch (failure: Throwable) {
            if (started) runCatching { muxer.stop() }
            output.delete()
            throw failure
        } finally { muxer.release() }
    }
}

/** Extractor and codec flags use different bit values; partial input is not end-of-stream. */
internal fun codecBufferFlagsForExtractedSample(sampleFlags: Int): Int {
    require(sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted voice segment is unsupported" }
    var codecFlags = 0
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
    return codecFlags
}
