package com.messenger.app.ui.chats

import org.junit.Assert.*
import org.junit.Test

class CompactFileNameTest {
    private fun columns(value: String) = fileNameGraphemes(value).size

    @Test fun fittingNamesStayUnchanged() {
        for (name in listOf("report.pdf", "README", ".env", ".config.json", "archive.tar.gz", "name.with.many.dots.txt")) {
            assertEquals(name, compactFileName(name, columns(name), ::columns))
        }
    }

    @Test fun ordinaryAndCompoundExtensionsStayComplete() {
        assertEquals(FileNameParts("report", ".pdf"), splitCompactFileName("report.pdf"))
        assertEquals(FileNameParts("name.with.many.dots", ".txt"), splitCompactFileName("name.with.many.dots.txt"))
        assertEquals(FileNameParts("archive", ".tar.gz"), splitCompactFileName("archive.tar.gz"))
        assertEquals(FileNameParts("Archive", ".TAR.GZ"), splitCompactFileName("Archive.TAR.GZ"))
        assertEquals(FileNameParts(".tar", ".gz"), splitCompactFileName(".tar.gz"))
    }

    @Test fun dotfilesMissingAndTrailingExtensionsAreNotMisclassified() {
        for (name in listOf("README", ".env", ".profile", "file.", ".", "..", "")) {
            assertEquals(FileNameParts(name, ""), splitCompactFileName(name))
        }
        assertEquals(FileNameParts(".config", ".json"), splitCompactFileName(".config.json"))
    }

    @Test fun everyWidthFitsAndKeepsExtensionWheneverPossible() {
        val names = listOf("very-long-report-final-version.pdf", "long.versioned.backup.tar.gz", "README-with-a-long-name", ".configuration.json")
        for (name in names) {
            val extension = splitCompactFileName(name).extension
            for (width in 0..columns(name)) {
                val compact = compactFileName(name, width, ::columns)
                assertTrue("$name at $width produced $compact", columns(compact) <= width)
                if (extension.isNotEmpty() && columns(extension) <= width) {
                    assertTrue("Extension must remain complete: $compact", compact.endsWith(extension))
                }
                if (width > 0 && width < columns(name) && compact != extension) assertTrue(compact.contains("…"))
            }
        }
    }

    @Test fun veryNarrowCellKeepsCompleteExtensionBeforePartialWholeName() {
        assertEquals(".pdf", compactFileName("long-document-name.pdf", 4, ::columns))
        assertEquals("….pdf", compactFileName("long-document-name.pdf", 5, ::columns))
        assertEquals(".tar.gz", compactFileName("long-archive-name.tar.gz", 7, ::columns))
    }

    @Test fun impossibleWidthsDoNotOverflowOrReduceFont() {
        assertEquals("", compactFileName("report.pdf", 0) { 10 })
        assertEquals("", compactFileName("report.pdf", 2) { it.length * 3 })
        assertEquals("…", compactFileName("report.pdf", 1, ::columns))
        assertEquals("", compactFileName("", 100, ::columns))
    }

    @Test fun graphemeBoundariesKeepAccentsSurrogatesAndEmojiSequencesWhole() {
        val clusters = listOf("A\u0301", "😀", "👍🏽", "👨‍👩‍👧‍👦", "🇷🇴", "✈️")
        assertEquals(clusters, fileNameGraphemes(clusters.joinToString("")))
        val name = clusters.joinToString("") + "-e\u0301-document.pdf"
        val originalClusters = fileNameGraphemes(name)
        for (width in 1 until columns(name)) {
            val compact = compactFileName(name, width, ::columns)
            assertTrue(fileNameGraphemes(compact).all { it == "…" || it in originalClusters })
            compact.forEachIndexed { index, character ->
                if (character.isHighSurrogate()) assertTrue(index + 1 < compact.length && compact[index + 1].isLowSurrogate())
                if (character.isLowSurrogate()) assertTrue(index > 0 && compact[index - 1].isHighSurrogate())
            }
        }
    }

    @Test fun fileNameRemainsOneDisplayLine() {
        assertEquals("report draft final.pdf", compactFileName("report\ndraft\rfinal.pdf", 100, ::columns))
    }

    @Test fun measuredVariableWidthNamesKeepBothEndsAndFullExtension() {
        fun measure(value: String): Int = fileNameGraphemes(value).fold(0) { total, cluster -> total + if (cluster == "W") 3 else 1 }
        val compact = compactFileName("WWWW-wide-version-final.pdf", 15, ::measure)
        assertTrue(measure(compact) <= 15)
        assertTrue(compact.startsWith("W"))
        assertTrue(compact.endsWith(".pdf"))
        assertTrue(compact.indexOf('…') in 1 until compact.lastIndex)
    }

    @Test fun longNamesUseBoundedLogarithmicMeasurements() {
        val name = "a".repeat(5000) + ".bin"
        var measured = 0
        val compact = compactFileName(name, 24) { measured++; it.length }
        assertTrue(compact.length <= 24)
        assertTrue(compact.endsWith(".bin"))
        assertTrue("Unexpected repeated text measurement: $measured", measured <= 20)
    }
}
