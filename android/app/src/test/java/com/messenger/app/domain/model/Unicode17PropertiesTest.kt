package com.messenger.app.domain.model

import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class Unicode17PropertiesTest {
    /** Golden computed independently from official, SHA-256-pinned UCD inputs:
     * java android/tools/CheckDisplayNameUnicodeProperties.java
     * Each scalar contributes its UTF-32 value, property flags and full lowercase
     * sequence. This checks all properties for every possible server char value.
     */
    @Test fun everyUnicodeScalarMatchesThePinnedPropertyDigest() {
        val digest = MessageDigest.getInstance("SHA-256")
        val record = ByteArray(18)
        val buffer = ByteBuffer.wrap(record)
        var count = 0
        for (scalar in 0..0x10FFFF) {
            if (scalar in 0xD800..0xDFFF) continue
            buffer.putInt(0, scalar)
            record[4] = ((if (Unicode17.isMark(scalar)) 1 else 0) or
                (if (Unicode17.isAlphabetic(scalar)) 2 else 0) or
                (if (Unicode17.isWhitespace(scalar)) 4 else 0)).toByte()
            val lowercase = Unicode17.lowercase(scalar)
            record[5] = lowercase.size.toByte()
            lowercase.forEachIndexed { index, value -> buffer.putInt(6 + index * 4, value) }
            digest.update(record, 0, 6 + lowercase.size * 4)
            count++
        }
        assertEquals(1_112_064, count)
        assertEquals("579e6f0d5e7f7b8e8cf6562bccd8ad477aa0d8d37420412dd58f888e69c35a51",
            digest.digest().joinToString("") { "%02x".format(it) })
    }
}
