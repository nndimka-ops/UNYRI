package com.messenger.app.data.history

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class HistoryMediaTest {
    @Test fun `shared manifest uses exact bytes and production SHA256`() = spool { spool, _ ->
        val record = Json.decodeFromString<HistoryMediaEntry>(java.io.File("../../shared/test-vectors/history-media-v1.json").readText())
        val file = spool.receive(record, { ByteArrayInputStream("abc".toByteArray()) }, {})
        assertEquals("abc", file.readText())
    }
    private val content = ByteArray(160_001) { (it % 251).toByte() }
    private fun entry(bytes: ByteArray = content) = HistoryMediaEntry(UUID.randomUUID().toString(), UUID.randomUUID().toString(), bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private fun spool(test: (HistoryMediaSpool, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("coco-history-test-").toFile().canonicalFile
        try { test(HistoryMediaSpool(root), root) } finally { root.deleteRecursively() }
    }
    @Test fun `streams bounded blocks and retry does not reopen source`() = spool { spool, root ->
        val record = entry(); var largest = 0; var closed = false
        val result = spool.receive(record, { object : ByteArrayInputStream(content) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { largest = maxOf(largest, len); return super.read(b, off, len) }
            override fun close() { closed = true; super.close() }
        } }, {})
        assertArrayEquals(content, result.readBytes()); assertTrue(closed); assertTrue(largest <= 65536)
        assertEquals(result, spool.receive(record, { error("must use verified cache") }, {}))
        assertEquals(1, root.listFiles()!!.size)
    }
    @Test fun `truncation oversize hash mismatch and stalled source leave no files`() = spool { spool, root ->
        for (bad in listOf(content.copyOf(content.size - 1), content + byteArrayOf(1), content.copyOf().also { it[0] = 99 })) {
            assertThrows(Exception::class.java) { spool.receive(entry(), { ByteArrayInputStream(bad) }, {}) }
            assertTrue(root.listFiles()!!.isEmpty())
        }
        assertThrows(Exception::class.java) { spool.receive(entry(), { object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        } }, {}) }
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun `cancel account switch and network failure close stream and remove partial`() = spool { spool, root ->
        var reads = 0; var closed = false
        assertThrows(CancellationException::class.java) { spool.receive(entry(), { object : ByteArrayInputStream(content) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return super.read(b, off, len) }
            override fun close() { closed = true }
        } }, { if (reads == 1) throw CancellationException() }) }
        assertTrue(closed); assertTrue(root.listFiles()!!.isEmpty())
        assertThrows(IllegalStateException::class.java) { spool.receive(entry(), { error("network failed") }, {}) }
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun `corrupt existing cache cannot report completion or be overwritten`() = spool { spool, _ ->
        val record = entry(); val file = spool.receive(record, { ByteArrayInputStream(content) }, {})
        val damaged = content.copyOf().also { it[9] = 77 }; file.writeBytes(damaged)
        assertThrows(Exception::class.java) { spool.receive(record, { error("must reject corrupt cache") }, {}) }
        assertArrayEquals(damaged, file.readBytes())
    }
    @Test fun `manifest enforces exact coverage uniqueness safe paths and cumulative quota`() {
        val a = entry(); val b = entry()
        assertEquals(a.bytes + b.bytes, validateHistoryMedia(listOf(a, b), setOf(a.attachment, b.attachment), a.bytes + b.bytes))
        for ((records, required, quota) in listOf(
            Triple(listOf(a), setOf(a.attachment, b.attachment), Long.MAX_VALUE),
            Triple(listOf(a, b), setOf(a.attachment), Long.MAX_VALUE),
            Triple(listOf(a, a), setOf(a.attachment), Long.MAX_VALUE),
            Triple(listOf(a, b.copy(blob = a.blob)), setOf(a.attachment, b.attachment), Long.MAX_VALUE),
            Triple(listOf(a, b), setOf(a.attachment, b.attachment), a.bytes + b.bytes - 1),
            Triple(listOf(a.copy(blob = "../escape")), setOf(a.attachment), Long.MAX_VALUE),
            Triple(listOf(a.copy(bytes = Long.MAX_VALUE)), setOf(a.attachment), Long.MAX_VALUE),
            Triple(listOf(a.copy(sha256 = "0".repeat(63) + "/")), setOf(a.attachment), Long.MAX_VALUE)
        )) assertThrows(Exception::class.java) { validateHistoryMedia(records, required, quota) }
    }
}
