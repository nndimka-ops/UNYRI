package com.messenger.app.domain.model

import org.junit.Assert.*
import org.junit.Test

/** Shared server/src/display_name.rs and ios/Tests/DisplayNameContracts vectors. */
class DisplayNamePolicyTest {
    @Test fun sanitizationVectors() {
        val cases = listOf(
            "  Bob   Smith  " to "Bob Smith",
            "Bob\u00A0\u2003Smith" to "Bob Smith",
            "\u202EnhoJ" to "nhoJ",
            "Al\u200Bice" to "Alice",
            "\u2066Eve\u2069" to "Eve",
            "A\u200DB" to "AB",
            "a\u200Cb" to "ab",
            "A${scalar(0xE0041)}" to "A",
            "\uFE0FBob" to "Bob",
            "e\u0301" to "\u00E9",
            "\uF8FFApple" to "Apple",
            "a \u0301 b" to "a b",
            "Tab\tand\nline" to "Tab and line",
        )
        for ((raw, expected) in cases) assertEquals(raw, expected, DisplayNamePolicy.sanitize(raw))
        for ((raw, expected) in listOf(
            "👨\u200D👩\u200D👧" to "👨👩👧",
            "🏳\uFE0F\u200D🌈" to "🏳🌈",
            "می\u200Cخواهم" to "میخواهم",
            "ශ්\u200Dරී" to "ශ්රී",
        )) assertEquals(raw, expected, DisplayNamePolicy.sanitize(raw))
        val taggedFlag = "🏴" + listOf(0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F).joinToString("") { scalar(it) }
        assertEquals(taggedFlag, DisplayNamePolicy.sanitize(taggedFlag))
        assertEquals(1, DisplayNamePolicy.scalarCount(DisplayNamePolicy.sanitize("e\u0301")))
        val stacked = DisplayNamePolicy.sanitize("Z" + "\u0301".repeat(10))
        assertEquals(4, DisplayNamePolicy.scalarCount(stacked))
        assertTrue(stacked.startsWith("\u0179"))
    }

    @Test fun validityCountsScalarsAndRequiresVisibleContent() {
        for (raw in listOf("\u2800\u2800", "\u3164", "\u0301", "   ", "", "a".repeat(33))) {
            assertFalse(raw, DisplayNamePolicy.isValid(DisplayNamePolicy.sanitize(raw)))
        }
        assertTrue(DisplayNamePolicy.isValid(DisplayNamePolicy.sanitize("a".repeat(32) + "\u200B")))
        assertFalse(DisplayNamePolicy.isValid(DisplayNamePolicy.sanitize("👨‍👩‍👧‍👦".repeat(10))))
        assertTrue(DisplayNamePolicy.isValid(DisplayNamePolicy.sanitize("  Bob  ")))
        assertTrue(DisplayNamePolicy.isValid(DisplayNamePolicy.sanitize("🏴" + scalar(0xE0067) + scalar(0xE007F))))
    }

    @Test fun reservedVectorsAndOfficialException() {
        for (name in listOf(
            "UNYRI", "unyri", "Unyri Support", "Поддержка UNYRI", "UNYRI Поддержка", "U N Y R I", "U.N.Y.R.I",
            "u_n_y_r_i", "ＵＮＹＲＩ", "𝐔𝐍𝐘𝐑𝐈", "ᴜɴʏʀɪ", "UNYRİ", "Ünyrí", "UNУRI", "UИYRI", "UNYR1", "UNYRl",
            "unyri.app", "Юнири", "Support", "Поддержка", "Служба поддержки", "Пoддepжкa", "Admin", "official",
        )) assertTrue(name, DisplayNamePolicy.isReserved(name))
        for (name in listOf("Unity", "Yuri", "Sunny Rick", "Bunny Rich", "Support Team", "Dmytro", "Мария", "Администратор Петя")) {
            assertFalse(name, DisplayNamePolicy.isReserved(name))
        }
        assertEquals("unyri", DisplayNamePolicy.key("UNYRI"))
        assertEquals("unyri", DisplayNamePolicy.key("UИYRI"))
        assertEquals("officiai", DisplayNamePolicy.key("official"))
        assertEquals(DisplayNamePolicy.key("Поддержка"), DisplayNamePolicy.key("Пoддepжкa"))
        assertEquals(DisplayNamePolicy.reservedError, DisplayNamePolicy.error("UNYRI Support", official = false))
        assertNull(DisplayNamePolicy.error("UNYRI Support", official = true))
        assertEquals(DisplayNamePolicy.lengthError, DisplayNamePolicy.error("\u2800", official = true))
    }

    @Test fun linksAndLeadingAtMatchServerVectors() {
        for (name in listOf("http", "HTTPS://x", "www.example", "example.com",
            "visit foo-bar.dev!", "example․com", "example。com", "example｡com", "ｅｘａｍｐｌｅ．ｃｏｍ",
            "ex\u200Bample.com", "example.c\u070Fom", "h\u2065ttp", "еxample.соm", "Alice (example.org)",
            "пример.рф", "приклад.укр", "example.xn--p1ai")) {
            assertTrue(name, DisplayNamePolicy.containsLink(name))
            assertEquals(name, DisplayNamePolicy.linkError, DisplayNamePolicy.error(name, official = true))
        }
        for (name in listOf("Alice", "Support Team", "A. B.", "Dr. Smith", "a.b", "a.abcdefghijklmnopqrstuvwxyz", "v1.23", "a.com2")) {
            assertFalse(name, DisplayNamePolicy.containsLink(name))
        }
        for (name in listOf("@alice", "  @alice", "\u202E@alice", "＠alice")) {
            assertEquals(name, DisplayNamePolicy.leadingAtError, DisplayNamePolicy.error(name, official = true))
        }
        assertNull(DisplayNamePolicy.error("Alice @home", official = false))
        assertNull(DisplayNamePolicy.error("😀".repeat(32), official = false))
        assertEquals(DisplayNamePolicy.lengthError, DisplayNamePolicy.error("😀".repeat(33), official = false))
    }

    @Test fun extendedStaffScamWordsUseExactSkeletons() {
        for (name in listOf("служба безопасности", "безопасность", "security", "system", "система", "системное сообщение",
            "уведомление", "notification", "bank", "банк", "оплата", "payment", "verification", "верификация", "unyri team",
            "служба безпеки", "безпека", "системне повідомлення", "сповіщення", "платіж", "верифікація",
            "ＳＥＣＵＲＩＴＹ", "Sуstem", "Служба\u200B безопасности")) {
            assertTrue(name, DisplayNamePolicy.isReserved(name))
            assertTrue(name, DisplayNamePolicy.isReserved(name.uppercase(java.util.Locale.ROOT)))
        }
        for (name in listOf("Support Team", "Security Expert", "Bank Holiday")) assertFalse(name, DisplayNamePolicy.isReserved(name))
    }

    @Test fun editorRequiresShortEnoughNamesButPreservesUneditedStoredText() {
        for (legacy in listOf("UNYRI", "\u3164", "a".repeat(64))) {
            assertEquals(legacy, DisplayNamePolicy.nameToSend(legacy, legacy))
        }
        assertTrue(DisplayNamePolicy.isValidEdit("UNYRI", "UNYRI"))
        assertTrue(DisplayNamePolicy.isValidEdit("a".repeat(40), "a".repeat(40)))
        assertFalse(DisplayNamePolicy.isValidEdit("a".repeat(33), "Bob"))
        assertTrue(DisplayNamePolicy.isValidEdit("\u2800", "\u2800"))
        assertTrue(DisplayNamePolicy.isValidEdit("example.com", "example.com"))
        assertTrue(DisplayNamePolicy.isValidEdit("@Bob", "@Bob"))
        assertTrue(DisplayNamePolicy.isValidEdit("UNYRI Support", "Bob"))
        assertFalse(DisplayNamePolicy.isValidEdit("example.com", "Bob"))
        assertFalse(DisplayNamePolicy.isValidEdit("@Bob", "Bob"))
        assertEquals("Eve", DisplayNamePolicy.nameToSend("\u202EEve\u200B", "Bob"))
        assertEquals("Bob Smith", DisplayNamePolicy.nameToSend("  Bob \u2066  Smith ", "Bob"))
        assertTrue(DisplayNamePolicy.removesHiddenCharacters("Al\u200Bice"))
        assertFalse(DisplayNamePolicy.removesHiddenCharacters("  Bob   Smith "))
        assertFalse(DisplayNamePolicy.removesHiddenCharacters(" e\u0301 "))
    }

    @Test fun scalarContextAndRemovalBoundaries() {
        // Look ahead in the raw input, look behind in kept output; never reorder passes.
        assertEquals("AB", DisplayNamePolicy.sanitize("A\u200B\u200DB"))
        assertEquals("🌈🌈", DisplayNamePolicy.sanitize("🌈\u200D\u200B🌈"))
        assertEquals("A", DisplayNamePolicy.sanitize("A\uFE0F\uFE0E"))
        assertEquals("A B", DisplayNamePolicy.sanitize("A\u00A0\u0301\u2003B"))
        val spaces = listOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
            0x2028, 0x2029, 0x202F, 0x205F, 0x3000)
        for (c in spaces) assertEquals("space $c", "A B", DisplayNamePolicy.sanitize("A${scalar(c)}B"))
        for (c in listOf(0, 0x7F, 0x00AD, 0x034F, 0x600, 0x6DD, 0x061C, 0x70F, 0x890, 0x8E2, 0x2065, 0x110BD, 0x110CD, 0x13430,
            0x115F, 0x17B4, 0x180F, 0x200B, 0x202E,
            0x2064, 0x206F, 0x2800, 0x3164, 0xFE0D, 0xFEFF, 0xFFA0, 0xFFFC, 0x1BCA0, 0x1D17A,
            0xE0000, 0xE00FF, 0xE01EF, 0xE000, 0xF8FF, 0xF0000, 0xFFFFD, 0x100000, 0x10FFFD, 0xFDD0, 0xFFFF, 0x10FFFF)) {
            assertEquals("removed $c", "AB", DisplayNamePolicy.sanitize("A${scalar(c)}B"))
        }
    }

    @Test fun peerPresentationSanitizesWithoutChangingTheStoredName() {
        val raw = "\u202EAl\u200Bice"
        val person = PublicUser("peer", "alice", raw, null)
        assertEquals("Alice", person.visibleName)
        assertEquals(raw, person.displayName)
        assertEquals("@alice", person.copy(displayName = "\u3164").visibleName)
    }

    @Test fun unicode17MarksNeverBecomeVisibleBasesAndRunsAreCappedBeforeNfc() {
        val mark = "\u0898" // Unicode 14 Mn, still unassigned in Java 17's tables.
        for (raw in listOf(mark, mark.repeat(12), " $mark $mark ")) {
            val sanitized = DisplayNamePolicy.sanitize(raw)
            assertEquals("", sanitized)
            assertEquals(0, DisplayNamePolicy.scalarCount(sanitized))
            assertFalse(DisplayNamePolicy.isValid(sanitized))
            assertFalse(DisplayNamePolicy.isValid(raw))
        }
        val capped = DisplayNamePolicy.sanitize("A" + mark.repeat(12))
        assertEquals("A" + mark.repeat(4), capped)
        assertEquals(5, DisplayNamePolicy.scalarCount(capped))
        assertTrue(DisplayNamePolicy.isValid(capped))
        assertEquals("A B", DisplayNamePolicy.sanitize("A $mark B"))
        assertTrue(DisplayNamePolicy.isValid(capped + "B".repeat(27)))
        assertFalse(DisplayNamePolicy.isValid(capped + "B".repeat(28)))
    }

    @Test fun skeletonUsesPinnedAlphabeticLowercaseAndCompatibilityNormalization() {
        // These outlined letters were added after the platform's Unicode tables.
        val outlined = listOf(0x1CCEA, 0x1CCE3, 0x1CCEE, 0x1CCE7, 0x1CCDE).joinToString("") { scalar(it) }
        assertEquals("unyri", DisplayNamePolicy.key(outlined))
        assertTrue(DisplayNamePolicy.isReserved(outlined))
        assertEquals("\u1C8A", DisplayNamePolicy.key("\u1C89"))
        assertEquals("\uA7CF", DisplayNamePolicy.key("\uA7CE"))
        // Unicode 16 Kirat Rai composition must also work on Android 8 / Java 17.
        assertEquals(scalar(0x16D68), DisplayNamePolicy.sanitize(scalar(0x16D67).repeat(2)))
        assertEquals(1, DisplayNamePolicy.scalarCount(DisplayNamePolicy.sanitize(scalar(0x16D67).repeat(2))))
    }

    @Test fun sharedLeadDecisionVectors() {
        val text = checkNotNull(javaClass.getResourceAsStream("/names-contract-vectors.tsv")).bufferedReader().use { it.readText() }
        for (line in text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }) {
            val columns = line.split('\t', limit = 3)
            val actual = when (columns[0]) {
                "username" -> DisplayNamePolicy.isReservedUsername(columns[2])
                "link" -> DisplayNamePolicy.containsLink(columns[2])
                "reserved" -> DisplayNamePolicy.isReserved(columns[2])
                "ignorable" -> {
                    val range = columns[2].split("..").map { it.toInt(16) }
                    (range.first()..range.last()).all { cp ->
                        val hidden = scalar(cp)
                        DisplayNamePolicy.sanitize("A"+hidden+"B") == "AB" &&
                            DisplayNamePolicy.startsWithAt(hidden+"@Alice") &&
                            DisplayNamePolicy.containsLink("example.c"+hidden+"om")
                    }
                }
                else -> error("Unknown vector")
            }
            assertEquals(line, columns[1] == "block", actual)
        }
    }

    @Test fun fullDefaultIgnorableRangesAndNfdCap() {
        for (c in listOf(0xFFF0, 0xFFF8, 0xE0000, 0xE01F0, 0xE0FFF, 0x2065)) {
            val invisible = scalar(c)
            assertEquals("AB", DisplayNamePolicy.sanitize("A"+invisible+"B"))
            assertTrue(DisplayNamePolicy.startsWithAt(invisible+"@Alice"))
            assertTrue(DisplayNamePolicy.containsLink("example.c"+invisible+"om"))
        }
        val stored = DisplayNamePolicy.sanitize("é"+"\u0301".repeat(10))
        assertEquals(4, Unicode17.nfd(stored).codePoints().filter { Unicode17.isMark(it) }.count().toInt())
        assertEquals(stored, DisplayNameSanitizer.display(stored))
    }

    private fun scalar(value: Int): String = String(Character.toChars(value))
}
