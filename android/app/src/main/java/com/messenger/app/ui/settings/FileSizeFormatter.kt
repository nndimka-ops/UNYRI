package com.messenger.app.ui.settings

import java.util.Locale

/**
 * Human-readable byte formatting shared by the Data & Storage screen.
 * Locale.ROOT keeps the decimal separator a '.' on every locale
 * (Phase 7 lesson: `"%.1f".format` produced "3,5" on the ru locale).
 */
object FileSizeFormatter {

    fun format(bytes: Long): String {
        if (bytes < 0) return "0 B"
        val kb = 1024L
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> "${oneDecimal(bytes.toDouble() / gb)} GB"
            bytes >= mb -> "${oneDecimal(bytes.toDouble() / mb)} MB"
            bytes >= kb -> "${bytes / kb} KB"
            else -> "$bytes B"
        }
    }

    private fun oneDecimal(value: Double): String {
        // Trim a trailing ".0" so "12.0 MB" renders as "12 MB", while
        // "12.4 MB" keeps its single decimal.
        return String.format(Locale.ROOT, "%.1f", value)
            .removeSuffix(".0")
    }
}
