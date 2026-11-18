package com.messenger.app.domain.model

import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every official UCD 17 normalization vector, with all UAX #15 invariants. */
class Unicode17NormalizationTest {
    @Test fun officialUnicode17NormalizationConformance() {
        val resource = checkNotNull(javaClass.getResourceAsStream("/unicode/NormalizationTest-17.0.0.txt.gz"))
        val bytes = GZIPInputStream(resource).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("5019ffd530751a741900c849c0e010332f142a3612234639bd200b82138a87db", digest)
        var vectors = 0
        bytes.toString(Charsets.UTF_8).lineSequence().forEachIndexed { lineNumber, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isNotEmpty() && !line.startsWith('@')) {
                val columns = line.split(';').take(5).map { fromCodePoints(it.trim()) }
                for (index in columns.indices) {
                    val label = "Unicode 17 line ${lineNumber + 1}, column ${index + 1}"
                    assertEquals("$label NFC", if (index < 3) columns[1] else columns[3], Unicode17.nfc(columns[index]))
                    assertEquals("$label NFD", if (index < 3) columns[2] else columns[4], Unicode17.nfd(columns[index]))
                    assertEquals("$label NFKC", columns[3], Unicode17.nfkc(columns[index]))
                    assertEquals("$label NFKD", columns[4], Unicode17.nfkd(columns[index]))
                }
                vectors++
            }
        }
        assertEquals(20034, vectors)
    }

    private fun fromCodePoints(value: String): String = StringBuilder().also { output ->
        value.split(' ').filter(String::isNotEmpty).forEach { output.appendCodePoint(it.toInt(16)) }
    }.toString()
}
