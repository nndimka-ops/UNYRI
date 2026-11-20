package com.messenger.app.ui.settings

import java.util.Locale
import java.text.NumberFormat

/**
 * Binary byte counts with the app locale's decimal separator and unit symbols.
 * The default preserves the non-UI wire/debug contract; UI callers supply their locale.
 */
object FileSizeFormatter {

    fun format(bytes: Long, locale: Locale = Locale.ROOT): String {
        val safeBytes = bytes.coerceAtLeast(0)
        val kb = 1024L
        val mb = kb * 1024
        val gb = mb * 1024
        val tb = gb * 1024
        val (value, unit) = when {
            safeBytes >= tb -> safeBytes.toDouble() / tb to 4
            safeBytes >= gb -> safeBytes.toDouble() / gb to 3
            safeBytes >= mb -> safeBytes.toDouble() / mb to 2
            safeBytes >= kb -> (safeBytes / kb).toDouble() to 1
            else -> safeBytes.toDouble() to 0
        }
        val number = NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = 0
            maximumFractionDigits = if (unit >= 2) 1 else 0
        }.format(value)
        return "$number ${units(locale)[unit]}"
    }

    private fun units(locale: Locale): List<String> = when (locale.language) {
        "ru", "bg" -> listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        "uk" -> listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        "ar" -> listOf("بايت", "ك.ب", "م.ب", "غ.ب", "ت.ب")
        "el" -> listOf("B", "KB", "MB", "GB", "TB")
        "fr" -> listOf("o", "Ko", "Mo", "Go", "To")
        "de", "it", "nl", "ro", "tr", "hu", "en", "ja", "ko", "zh" -> listOf("B", "KB", "MB", "GB", "TB")
        "cs", "es", "pl", "pt" -> listOf("B", "kB", "MB", "GB", "TB")
        else -> listOf("B", "KB", "MB", "GB", "TB")
    }
}
