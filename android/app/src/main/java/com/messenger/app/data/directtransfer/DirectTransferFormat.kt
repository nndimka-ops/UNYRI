package com.messenger.app.data.directtransfer

import java.util.Locale

/** Direct-file display only; existing attachment formatting remains independent. */
internal object DirectTransferFormat {
    fun size(bytes: Long, locale: Locale, units: List<String>): String {
        require(bytes in 0..DirectFileWire.MAX_FILE_BYTES && units.size == 4)
        var divisor = 1L
        var unit = 0
        while (unit < 3 && bytes >= divisor * 1024) { divisor *= 1024; unit++ }
        val number = if (unit == 0) bytes.toString() else {
            val tenths = (bytes * 10 + divisor / 2) / divisor
            String.format(locale, "%.1f", tenths / 10.0)
        }
        return "$number ${units[unit]}"
    }
    fun secondsRemaining(offset: Long, total: Long, speed: Long): Long? {
        require(offset in 0..total && total <= DirectFileWire.MAX_FILE_BYTES && speed in 0..DirectFileWire.MAX_FILE_BYTES)
        return if (speed == 0L) null else (total - offset + speed - 1) / speed
    }
    fun clock(seconds: Long): String? {
        require(seconds >= 0)
        if (seconds > 86400) return null
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }
}
