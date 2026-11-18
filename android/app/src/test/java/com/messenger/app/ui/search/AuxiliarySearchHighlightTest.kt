package com.messenger.app.ui.search

import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuxiliarySearchHighlightTest {
    @Test fun highlightsCyrillicCaseInsensitivelyAndKeepsEmoji() {
        val result = auxiliarySearchHighlight("Привет! 👋 Ещё привет", "привет")
        assertEquals("Привет! 👋 Ещё привет", result.text)
        assertEquals(listOf("Привет", "привет"), result.spanStyles.map { result.text.substring(it.start, it.end) })
        assertTrue(result.spanStyles.all { it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun queryPunctuationIsSafeAndSourceMarkupRemainsPlainText() {
        val result = auxiliarySearchHighlight("<b>hello</b> world", "\"hello\"*")
        assertEquals("<b>hello</b> world", result.text)
        assertEquals(listOf("hello"), result.spanStyles.map { result.text.substring(it.start, it.end) })
        assertTrue(auxiliarySearchHighlight("hello 👋", "👋").spanStyles.isEmpty())
    }
}
