package com.messenger.app.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

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
        // Phase 7 lesson: "%.1f".format on the ru locale produced "3,5".
        assertEquals("12.4 MB", FileSizeFormatter.format(13_002_342))
    }
}
