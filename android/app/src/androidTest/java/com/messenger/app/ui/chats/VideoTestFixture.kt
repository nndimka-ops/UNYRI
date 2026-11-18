package com.messenger.app.ui.chats

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File

/** A real 3-second clip independent of screen activity. screenrecord can emit
 * only two frames/22ms when the test Activity is static on an emulator. */
internal fun createVideoTestFixture(file: File, rotation: Int = 0): File {
    val width = 640
    val height = 360
    val frames = 90
    val encoder = MediaCodec.createEncoderByType("video/avc")
    val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    muxer.setOrientationHint(rotation)
    var muxerStarted = false
    try {
        val supported = encoder.codecInfo.getCapabilitiesForType("video/avc").colorFormats
        val colorFormat = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        ).first { it in supported }
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, 400_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        // Neutral chroma works with both planar and interleaved YUV layouts.
        val pixels = ByteArray(width * height * 3 / 2) { 128.toByte() }
        val info = MediaCodec.BufferInfo()
        var frame = 0
        var inputEnded = false
        var outputEnded = false
        var track = -1
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!outputEnded) {
            check(SystemClock.elapsedRealtime() < deadline) { "Video fixture encoder timed out" }
            if (!inputEnded) {
                val input = encoder.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val timestamp = frame * 1_000_000L / 30
                    if (frame == frames) {
                        encoder.queueInputBuffer(input, 0, 0, timestamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        pixels.fill((32 + frame * 2).toByte(), 0, width * height)
                        encoder.getInputBuffer(input)!!.apply { clear(); put(pixels) }
                        encoder.queueInputBuffer(input, 0, pixels.size, timestamp, 0)
                        frame++
                    }
                }
            }
            when (val output = encoder.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted)
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                else -> if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(muxerStarted)
                        val buffer = encoder.getOutputBuffer(output)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(output, false)
                }
            }
        }
    } finally {
        runCatching { encoder.stop() }
        encoder.release()
        try { if (muxerStarted) muxer.stop() } finally { muxer.release() }
    }
    val metadata = MediaMetadataRetriever()
    try {
        metadata.setDataSource(file.path)
        val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
        check(duration >= 2_900) { "Video fixture is too short: ${duration}ms" }
    } finally { metadata.release() }
    return file
}
