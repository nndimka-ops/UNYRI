package com.messenger.app.domain.model

import org.junit.Assert.*
import org.junit.Test

/** Same presentation vectors as ios/Tests/DisplayNameSanitizerContracts/main.swift. */
class DisplayNameSanitizerTest {
    @Test fun removesControlsInvisibleFillersAndBidi() {
        for (c in listOf(0, 0x7F, 0xAD, 0x34F, 0x61C, 0x70F, 0x890, 0x115F, 0x17B4, 0x180F, 0x200B,
            0x200E, 0x202E, 0x2065, 0x2066, 0x2069, 0x2800, 0x3164, 0xFEFF, 0xF8FF, 0xFFFC, 0x13430, 0xE0100, 0x10FFFF)) {
            assertEquals("removed $c", "AB", DisplayNameSanitizer.display("A${scalar(c)}B"))
        }
        assertEquals("Alice", DisplayNameSanitizer.display("\u202EAl\u200Bice"))
        assertEquals("A B C", DisplayNameSanitizer.display(" A\nB\tC "))
        assertEquals("AB", DisplayNameSanitizer.display("A\u200DB"))
    }

    @Test fun capsMarksPerDecomposedBaseAndKeepsUsefulJoiners() {
        assertEquals("", DisplayNameSanitizer.display("\u0301".repeat(10)))
        val marked = DisplayNameSanitizer.display("é" + "\u0301".repeat(10))
        assertEquals(4, Unicode17.nfd(marked).codePoints().filter { Unicode17.isMark(it) }.count().toInt())
        assertEquals("é", DisplayNameSanitizer.display("e\u0301"))
        for ((raw, expected) in listOf("👨‍👩‍👧" to "👨👩👧", "می‌خواهم" to "میخواهم", "🏳️‍🌈" to "🏳🌈", "Марія" to "Марія", "李明" to "李明")) {
            assertEquals(raw, expected, DisplayNameSanitizer.display(raw))
        }
    }

    @Test fun scalarLayoutLimitIncludesEllipsisAndFallbackIsAlsoSanitized() {
        for (base in listOf("a", "😀")) {
            assertEquals(base.repeat(64), DisplayNameSanitizer.display(base.repeat(64)))
            assertEquals(base.repeat(63) + "…", DisplayNameSanitizer.display(base.repeat(65)))
            assertEquals(64, DisplayNamePolicy.scalarCount(DisplayNameSanitizer.display(base.repeat(1000))))
        }
        assertEquals("Alice", DisplayNameSanitizer.display("\u3164", "\u202EAlice"))
        assertEquals("a".repeat(63) + "…", DisplayNameSanitizer.display("", "a".repeat(100)))
        val cutJoiner = DisplayNameSanitizer.display("a".repeat(61) + "👨‍👩" + "b".repeat(10))
        assertFalse(cutJoiner.contains("\u200D"))
        assertTrue(DisplayNamePolicy.scalarCount(cutJoiner) <= 64)
        val raw = "\u202E" + "a".repeat(100)
        val user = PublicUser("peer", "alice", raw, null)
        assertEquals("a".repeat(63) + "…", user.visibleName)
        assertEquals(raw, user.displayName)
        assertEquals(user.visibleName, DisplayNameSanitizer.display(user.visibleName))
    }

    @Test fun boundedInputAndMemoAvoidRepeatedSanitization() {
        val huge = "z".repeat(1024 * 1024)
        val started = System.nanoTime()
        assertEquals("z".repeat(63)+"…", DisplayNameSanitizer.display(huge))
        val after = DisplayNameSanitizer.computations
        repeat(1000) { assertEquals("z".repeat(63)+"…", DisplayNameSanitizer.display(huge)) }
        assertEquals(after, DisplayNameSanitizer.computations)
        assertTrue("Bounded cached calls should stay cheap", (System.nanoTime()-started)/1_000_000 < 5000)
        val boundary = "a".repeat(1023)+"😀"+"b".repeat(1000)
        assertEquals("a".repeat(63)+"…", DisplayNameSanitizer.display(boundary))
        assertEquals("@alice", DisplayNameSanitizer.peer("\uFFF0", "alice"))
        assertEquals("Unknown user", DisplayNameSanitizer.peer("\uFFF0"))
        for (cp in listOf(0xFFF0, 0xFFF8, 0xE01F0, 0xE0FFF)) assertEquals("AB", DisplayNameSanitizer.display("A"+scalar(cp)+"B"))
    }

    private fun scalar(value: Int) = String(Character.toChars(value))
}
