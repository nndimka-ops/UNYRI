package com.messenger.app.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.Locale

class FileSizeFormatterTest {

    @Test
    fun formatsBytesBelowOneKilobyte() {
        assertEquals("0 B", FileSizeFormatter.format(0))
        assertEquals("1 B", FileSizeFormatter.format(1))
        assertEquals("1023 B", FileSizeFormatter.format(1023))
    }

    @Test
    fun formatsKilobytesAsWholeNumbers() {
        assertEquals("1 KB", FileSizeFormatter.format(1024))
        assertEquals("824 KB", FileSizeFormatter.format(843_776))
    }

    @Test
    fun formatsMegabytesWithOneDecimal() {
        assertEquals("12.4 MB", FileSizeFormatter.format(13_002_342))
        assertEquals("12 MB", FileSizeFormatter.format(12L * 1024 * 1024))
    }

    @Test
    fun formatsGigabytesWithOneDecimal() {
        assertEquals("1.2 GB", FileSizeFormatter.format(1_288_490_189))
    }

    @Test
    fun usesDotDecimalSeparatorOnEveryLocale() {
        // Calls without an app locale retain the existing deterministic contract.
        assertEquals("12.4 MB", FileSizeFormatter.format(13_002_342))
    }

    @Test fun localizesRussianUnitsAndDecimalSeparator() {
        val ru = Locale.forLanguageTag("ru")
        assertEquals("50 МБ", FileSizeFormatter.format(50L * 1024 * 1024, ru))
        assertEquals("16,6 МБ", FileSizeFormatter.format(17_406_362, ru))
        assertEquals("5 ГБ", FileSizeFormatter.format(5L * 1024 * 1024 * 1024, ru))
    }

    @Test fun localizesUkrainianAndFrench() {
        assertEquals("100 МБ", FileSizeFormatter.format(100L * 1024 * 1024, Locale.forLanguageTag("uk")))
        assertEquals("1,2 Go", FileSizeFormatter.format(1_288_490_189, Locale.FRENCH))
    }

    @Test fun clampsInvalidCountsAndHandlesLargeQuotas() {
        assertEquals("0 Б", FileSizeFormatter.format(-1, Locale.forLanguageTag("ru")))
        assertEquals("2 TB", FileSizeFormatter.format(2L * 1024 * 1024 * 1024 * 1024, Locale.ENGLISH))
    }
}
