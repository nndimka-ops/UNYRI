package com.messenger.app.ui.chats

import org.junit.Assert.*
import org.junit.Test

class EmojiSearchTest {
    @Test fun legacyEnglishRussianAndUkrainianAliasesStillFindTheirEmoji() {
        for ((query, expected) in listOf("heart" to "❤️", "сердце" to "❤️", "серце" to "❤️",
            "dog" to "🐶", "thanks" to "🙏", "спасибо" to "🙏", "дякую" to "🙏",
            "laugh" to "😂", "кофе" to "☕", "sun" to "☀️", "самолет" to "✈️")) {
            assertTrue("Missing $expected for $query", expected in searchEmoji(query))
        }
    }
    @Test fun legacySubgroupsDoNotBecomeWholeIosCategories() {
        assertFalse("😀" in searchEmoji("heart"))
        assertFalse("❤️" in searchEmoji("thanks"))
        assertFalse("🐶" in searchEmoji("sun"))
    }
    @Test fun searchKeepsCompoundSequencesUniqueAndHandlesWhitespaceAndCase() {
        assertEquals(listOf("❤️‍🔥"), searchEmoji(" ❤️‍🔥 "))
        assertEquals(searchEmoji("heart"), searchEmoji("  HEART  "))
        val result = searchEmoji("yes")
        assertEquals(result.distinct(), result)
        assertTrue(searchEmoji("no-such-query-12345").isEmpty())
        assertTrue("🇺🇦" in searchEmoji("Флаги"))
    }
}
