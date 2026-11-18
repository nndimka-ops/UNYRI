package com.messenger.app.data.directtransfer

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DirectTransferFormatTest {
    @Test fun binaryUnitsAndHalfUpMatchTheSharedIosContract() {
        val units = listOf("B", "KB", "MB", "GB")
        assertEquals("0 B", DirectTransferFormat.size(0, Locale.US, units))
        assertEquals("1023 B", DirectTransferFormat.size(1023, Locale.US, units))
        assertEquals("1.0 KB", DirectTransferFormat.size(1024, Locale.US, units))
        assertEquals("1.3 MB", DirectTransferFormat.size(1310720, Locale.US, units))
        assertEquals("64.0 GB", DirectTransferFormat.size(DirectFileWire.MAX_FILE_BYTES, Locale.US, units))
        assertEquals("1,3 МБ", DirectTransferFormat.size(1310720, Locale.forLanguageTag("ru"), listOf("Б", "КБ", "МБ", "ГБ")))
    }
    @Test fun AckSnapshotEtaUsesCeilingAndSharedPadding() {
        assertNull(DirectTransferFormat.secondsRemaining(0, 100, 0))
        assertEquals(4L, DirectTransferFormat.secondsRemaining(0, 10, 3))
        assertEquals("00:04", DirectTransferFormat.clock(4))
        assertEquals("02:05", DirectTransferFormat.clock(125))
        assertEquals("1:02:05", DirectTransferFormat.clock(3725))
        assertEquals("24:00:00", DirectTransferFormat.clock(86400))
        assertNull(DirectTransferFormat.clock(86401))
    }
}
