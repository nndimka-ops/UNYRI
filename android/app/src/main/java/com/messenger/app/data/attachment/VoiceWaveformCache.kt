package com.messenger.app.data.attachment

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Bounded, serialized streaming analysis. Never opens audio on the UI thread. */
object VoiceWaveformCache {
    private val lock = Mutex()
    private val cache = object : LinkedHashMap<String, List<Float>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Float>>?) = size > 64
    }
    suspend fun load(path: String): List<Float> = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = File(path)
            val key = "$path:${file.length()}:${file.lastModified()}"
            cache[key]?.let { return@withLock it }
            try {
                decode(path).also { if (it.isNotEmpty()) cache[key] = it }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        }
    }
    private suspend fun decode(path: String): List<Float> {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return emptyList()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val duration = format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder = codec
            codec.configure(format, null, null, 0); codec.start()
            val peaks = FloatArray(48)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var idle = 0
            while (idle < 200) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val count = extractor.readSampleData(requireNotNull(codec.getInputBuffer(index)), 0)
                        if (count < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { codec.queueInputBuffer(index, 0, count, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = codec.outputFormat
                    sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING) && output.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) return emptyList()
                } else if (index >= 0) {
                    idle = 0
                    val buffer = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                    // Decimate within each decoded block; time bins remain tied to actual audio timestamps.
                    for (offset in 0 until info.size / 2 step 8) {
                        val time = info.presentationTimeUs + offset.toLong() * 1_000_000 / (sampleRate.toLong() * channels).coerceAtLeast(1)
                        val bin = (time * peaks.size / duration).toInt().coerceIn(peaks.indices)
                        peaks[bin] = max(peaks[bin], abs(buffer.getShort(info.offset + offset * 2).toInt()) / 32768f)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        val peak = (peaks.maxOrNull() ?: 0f).coerceAtLeast(.01f)
                        return peaks.map { sqrt(it / peak).coerceIn(0f, 1f) }
                    }
                } else idle++
            }
            return emptyList()
        } finally {
            runCatching { decoder?.stop() }; decoder?.release(); extractor.release()
        }
    }
}
