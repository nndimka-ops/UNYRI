package org.unyri.admin.core

import org.junit.Test
import kotlin.test.*

class HostPortConflictsTest {
    @Test fun readsGroupedContainerIdentityWithoutRenderingRawToolOutput() {
        val text = """
            command failed: private configuration must never be shown
            UNYRI_PORT_BUSY:TCP|3478|container|messenger-coturn
            UNYRI_PORT_BUSY:UDP|3478,49152-49200|container|messenger-coturn
            UNYRI_PORT_BUSY:TCP|80|process|WebServer
            UNYRI_PORT_BUSY:TCP|443|unknown|unknown
        """.trimIndent()
        assertEquals(listOf(
            HostPortConflict("TCP", "3478", "container", "messenger-coturn"),
            HostPortConflict("UDP", "3478,49152-49200", "container", "messenger-coturn"),
            HostPortConflict("TCP", "80", "process", "WebServer"),
            HostPortConflict("TCP", "443", "unknown", "unknown")
        ), HostPortConflicts.parse(text))
    }

    @Test fun rejectsMalformedOrUnboundedFieldsAndDeduplicates() {
        val invalid = listOf(
            "UDP|0|process|app", "UDP|65536|process|app", "UDP|9-1|process|app",
            "UDP|3478,|process|app", "UDP|3478|other|app", "HTTP|80|process|app",
            "UDP|3478|process|a|b", "UDP|3478|process|a\tb", "UDP|3478|process|a\u202eb",
            "UDP|3478|process|${"a".repeat(121)}"
        ).joinToString("\n") { "UNYRI_PORT_BUSY:$it" }
        assertTrue(HostPortConflicts.parse(invalid).isEmpty())
        val row = "UNYRI_PORT_BUSY:UDP|3478|container|messenger-coturn"
        assertEquals(1, HostPortConflicts.parse("$row\n$row").size)
    }
}
