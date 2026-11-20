package com.messenger.app.ui.chats

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReactionPickerCatalogTest {
    private val catalog by lazy {
        val source = requireNotNull(javaClass.getResourceAsStream("/ReactionEmojiKeywords.json"))
        ReactionPickerCatalog.build(source.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    @Test fun sharedCldrCatalogHasNamesAndNoUncertainUntonedHoldingHands() {
        assertEquals(1870, catalog.entries.size)
        assertEquals(1870, catalog.entries.map { ReactionSearch.baseKey(it.emoji) }.toSet().size)
        assertEquals(8, catalog.categories.size)
        assertTrue(catalog.entries.all { !it.names["ru"].isNullOrEmpty() && !it.names["uk"].isNullOrEmpty() && !it.names["en"].isNullOrEmpty() })
        val cells = catalog.entries.map(ReactionEmoji::emoji)
        listOf("👩‍🤝‍👩", "👨‍🤝‍👨", "👩‍🤝‍👨", "🫩").forEach { assertFalse(it in cells) }
        listOf("❤️", "🚀", "🙏", "🇺🇦", "1️⃣", "👩‍💻").forEach { assertTrue(it in cells) }
    }

    @Test fun russianUkrainianAndEnglishQueriesMatchQualifiedOrder() = runBlocking {
        assertEquals("🐱", catalog.search("кот").first().emoji)
        assertEquals("🐈", catalog.search("кошка").first().emoji)
        assertEquals("🚀", catalog.search("ракета").first().emoji)
        assertEquals("🙏", catalog.search("дякую").first().emoji)
        assertEquals("🚀", catalog.search("rocket").first().emoji)
        assertEquals("🔥", catalog.search("огонь").first().emoji)
        assertEquals("❤️", catalog.search("сердце").first().emoji)
        assertEquals(catalog.search("Елка"), catalog.search("ЁЛКА"))
        assertTrue(catalog.search("no_such_emoji_qzx").isEmpty())
        assertTrue(catalog.search("").isEmpty())
    }

    @Test fun pastedTonedEmojiFindsItselfAndPreservesItsExactTone() = runBlocking {
        listOf("👍🏿", "☝🏽", "👩🏾‍💻", "🙏🏻").forEach { pasted ->
            assertEquals(pasted, catalog.search(pasted).first().emoji)
        }
        assertEquals("❤️", catalog.search("❤").first().emoji)
    }
}
