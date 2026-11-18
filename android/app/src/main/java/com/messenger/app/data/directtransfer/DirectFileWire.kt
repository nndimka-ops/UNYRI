package com.messenger.app.data.directtransfer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exactly the cross-platform v1 14-byte network-order header; no serialization of entire files. */
internal data class DirectFileFrame(val type: Int, val offset: Long, val payload: ByteArray = byteArrayOf())

internal object DirectFileWire {
    const val CHUNK = 1
    const val ACK = 2
    const val FINISH = 3
    const val COMPLETE_ACK = 4
    const val CHUNK_BYTES = 16 * 1024
    const val MAX_FILE_BYTES = 64L * 1024 * 1024 * 1024
    const val HEADER_BYTES = 14

    fun encode(frame: DirectFileFrame, fileSize: Long): ByteArray {
        validate(frame.type, frame.offset, frame.payload.size, fileSize)
        if (frame.type == ACK && frame.payload.isNotEmpty()) DirectAckStats.decode(frame.payload)
        return ByteBuffer.allocate(HEADER_BYTES + frame.payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(1.toByte()).put(frame.type.toByte()).putLong(frame.offset).putInt(frame.payload.size).put(frame.payload).array()
    }

    fun decode(bytes: ByteArray, fileSize: Long, binary: Boolean = true): DirectFileFrame {
        require(binary) { "Unexpected transfer text frame" }
        require(bytes.size in HEADER_BYTES..HEADER_BYTES + CHUNK_BYTES) { "Invalid transfer frame size" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(input.get().toInt() == 1) { "Unsupported transfer frame version" }
        val type = input.get().toInt() and 255
        val offset = input.long
        val size = input.int
        require(size >= 0 && size == input.remaining()) { "Invalid transfer frame length" }
        validate(type, offset, size, fileSize)
        return DirectFileFrame(type, offset, ByteArray(size).also(input::get)).also {
            if (type == ACK && it.payload.isNotEmpty()) DirectAckStats.decode(it.payload)
        }
    }

    private fun validate(type: Int, offset: Long, size: Int, fileSize: Long) {
        require(fileSize in 1..MAX_FILE_BYTES && offset in 0..fileSize) { "Invalid transfer offset" }
        when (type) {
            CHUNK -> require(size in 1..CHUNK_BYTES && size.toLong() <= fileSize - offset) { "Invalid transfer chunk" }
            ACK -> require(size == 0 || size == 16) { "Invalid durable acknowledgment" }
            FINISH, COMPLETE_ACK -> require(size == 32 && offset == fileSize) { "Invalid transfer completion" }
            else -> error("Unknown transfer frame type")
        }
    }
}

internal data class DirectAckStats(val sampleId: Long, val speedBytesPerSecond: Long) {
    init { require(sampleId > 0 && speedBytesPerSecond in 0..DirectFileWire.MAX_FILE_BYTES) }
    fun encode(): ByteArray = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putLong(sampleId).putLong(speedBytesPerSecond).array()
    companion object {
        fun decode(bytes: ByteArray): DirectAckStats {
            require(bytes.size == 16)
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            return DirectAckStats(input.long, input.long)
        }
    }
}

/** Only the receiver owns rate measurement; every ACK publishes this exact durable snapshot to both peers. */
internal class DirectRateEstimator(offset: Long, nowMillis: Long) {
    private var baselineOffset = offset
    private var baselineAt = nowMillis
    private var lastProgressAt = nowMillis
    private var lastOffset = offset
    private var speed = 0L
    private var sequence = 0L
    fun observe(offset: Long, nowMillis: Long): DirectAckStats {
        require(offset >= lastOffset && nowMillis >= baselineAt)
        if (offset > lastOffset) lastProgressAt = nowMillis
        lastOffset = offset
        val elapsed = nowMillis - baselineAt
        if (elapsed >= 250) {
            val instant = ((offset - baselineOffset) * 1000 / elapsed).coerceIn(0, DirectFileWire.MAX_FILE_BYTES)
            speed = if (speed == 0L) instant else (speed * 3 + instant) / 4
            baselineAt = nowMillis; baselineOffset = offset
        }
        return DirectAckStats(++sequence, speed)
    }
    fun idle(offset: Long, nowMillis: Long): DirectAckStats? {
        require(offset == lastOffset && nowMillis >= baselineAt)
        if (speed == 0L || nowMillis - lastProgressAt < 1000) return null
        speed = 0; baselineAt = nowMillis; baselineOffset = offset
        return DirectAckStats(++sequence, 0)
    }
    fun verification(offset: Long, nowMillis: Long): DirectAckStats {
        require(offset == lastOffset && nowMillis >= baselineAt)
        speed = 0; baselineAt = nowMillis; baselineOffset = offset
        return DirectAckStats(++sequence, 0)
    }
}

internal class DirectAckSequence(initialOffset: Long) {
    private var lastOffset = initialOffset
    private var lastStats: DirectAckStats? = null
    fun accept(offset: Long, sentThrough: Long, stats: DirectAckStats?) {
        require(offset in lastOffset..sentThrough) { "Acknowledgment exceeds sent bytes" }
        if (stats != null) {
            val previous = lastStats
            require(previous == null || stats.sampleId > previous.sampleId || stats == previous && offset == lastOffset) { "Transfer statistics regressed" }
            lastStats = stats
        }
        lastOffset = offset
    }
}
