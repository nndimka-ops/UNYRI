package com.messenger.app.data.attachment

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class BoundedFileCopyTest {
    @Test fun `accepts empty and exact limit without changing bytes`() {
        for (size in listOf(0, 7, 131072)) {
            val data = ByteArray(size) { (it % 251).toByte() }
            val output = ByteArrayOutputStream()
            copyBoundedFile(ByteArrayInputStream(data), output, size.toLong())
            assertContentEquals(data, output.toByteArray())
        }
    }
    @Test fun `unknown source size cannot fill disk beyond limit`() {
        val output = ByteArrayOutputStream()
        assertFailsWith<AttachmentTooLargeException> {
            copyBoundedFile(ByteArrayInputStream(ByteArray(200000)), output, 70000)
        }
        assertTrue(output.size() <= 70000)
    }
}
