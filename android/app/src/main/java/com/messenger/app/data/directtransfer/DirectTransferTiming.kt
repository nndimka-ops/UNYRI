package com.messenger.app.data.directtransfer

/** Fixed per-operation budgets. Peer traffic cannot extend a verification deadline. */
internal object DirectTransferTiming {
    const val MAX_REQUESTS = 6
    private const val MIN_HASH_BYTES_PER_SECOND = 4L * 1024 * 1024
    fun verificationMillis(size: Long): Long {
        require(size in 1..DirectFileWire.MAX_FILE_BYTES)
        val seconds = (size + MIN_HASH_BYTES_PER_SECOND - 1) / MIN_HASH_BYTES_PER_SECOND
        return maxOf(45_000L, seconds * 1000 + 30_000L)
    }
    fun requestMillis(size: Long, number: Int, acknowledged: Boolean): Long {
        require(number in 1..MAX_REQUESTS)
        val exponential = minOf(1_800_000L, 90_000L shl (number - 1))
        return if (acknowledged) maxOf(verificationMillis(size), 90_000L) else exponential
    }
}
