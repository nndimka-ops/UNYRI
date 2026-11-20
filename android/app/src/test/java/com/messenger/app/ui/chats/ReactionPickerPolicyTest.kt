package com.messenger.app.ui.chats

import org.junit.Assert.*
import org.junit.Test

class ReactionPickerPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun quickThenThirtyDayCountsThenDefaultsWithToneAggregation() {
        var history = ReactionPickerHistory().recordSend("🚀", now - ReactionPickerHistory.WINDOW_MILLIS - 1)
        history = history.recordSend("🚀", now + 1) // Future events do not rank at this clock.
        history = history.recordSend("👍🏻", now - 30).recordSend("👍🏽", now - 20)
            .recordSend("🔥", now - 10).recordSend("👍🏿", now)
        val ranked = history.ranked("❤️", now)
        assertEquals(listOf("❤️", "👍🏿", "🔥", "😂", "😮", "😢", "🙏"), ranked)
        assertEquals(7, ranked.map { ReactionSearch.baseKey(ReactionTones.base(it)) }.toSet().size)
        assertFalse("🚀" in ranked)
    }

    @Test fun equalCountsUseLatestSendAndQuickIsNeverDuplicated() {
        val history = ReactionPickerHistory().recordSend("🚀", now - 20).recordSend("🥳", now - 10)
        assertEquals(listOf("🔥", "🥳", "🚀"), history.ranked("🔥", now).take(3))
        assertEquals(1, history.ranked("🔥", now).count { it == "🔥" })
        assertEquals(ReactionPickerHistory.DEFAULTS, ReactionPickerHistory().ranked("", now))
    }

    @Test fun recentsKeepSixteenUniqueExactSentVariantsInReverseOrder() {
        val choices = listOf("😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃", "🫠", "😉", "😊", "😇", "🥰", "😍", "👍🏿")
        var history = ReactionPickerHistory()
        choices.forEachIndexed { index, emoji -> history = history.recordSend(emoji, now + index) }
        assertEquals(choices.takeLast(16).reversed(), history.recent)
        history = history.recordSend("😍", now + 30)
        assertEquals("😍", history.recent.first())
        assertEquals(16, history.recent.size)
        assertEquals(1, history.recent.count { it == "😍" })
        assertTrue("👍🏿" in history.recent)
    }

    @Test fun toneChoiceIsPerBaseAndDefaultSendResetsIt() {
        val history = ReactionPickerHistory().setTone("👍", "👍🏽").setTone("🙏", "🙏🏿")
        assertEquals("👍🏽", history.preferred("👍"))
        assertEquals("🙏🏿", history.preferred("🙏"))
        assertEquals("👍", history.recordSend("👍", now).preferred("👍"))
        assertEquals("☝️", ReactionTones.base("☝🏾"))
        assertEquals("❤️", ReactionTones.base("❤"))
        assertEquals(6, ReactionTones.variants("👩‍💻").size)
        assertEquals(emptyList<String>(), ReactionTones.variants("👩‍🤝‍👩"))
        assertEquals(emptyList<String>(), ReactionTones.variants("👨‍👩‍👦"))
        assertEquals(emptyList<String>(), ReactionTones.variants("👪"))
        assertEquals(emptyList<String>(), ReactionTones.variants("👯"))
        assertEquals(emptyList<String>(), ReactionTones.variants("🔥"))
        assertEquals(history, history.setTone("🔥", "🔥🏽"))
    }

    @Test fun presentationKeepsJoinersAndKeycapsValid() {
        assertEquals("❤️‍🔥", ReactionTones.canonical("❤‍🔥"))
        assertEquals("1️⃣", ReactionTones.canonical("1\u20E3"))
        assertEquals("🏳️‍🌈", ReactionTones.canonical("🏳‍🌈"))
        assertEquals("✍🏿", ReactionTones.variants("✍️").last())
        assertEquals("👩🏽‍💻", ReactionTones.variants("👩‍💻")[3])
    }

    @Test fun storageIsIsolatedByServerAndAccountAndErasesOnlyOne() {
        class MemoryStorage : ReactionHistoryStorage {
            val values = mutableMapOf<String, String>()
            override fun read(key: String) = values[key]
            override fun write(key: String, value: String) { values[key] = value }
            override fun remove(key: String) { values.remove(key) }
        }
        val storage = MemoryStorage()
        val alice = ReactionPickerStore(storage, "https://chat.example", "alice")
        val bob = ReactionPickerStore(storage, "https://chat.example", "bob")
        val otherServer = ReactionPickerStore(storage, "https://other.example", "alice")
        alice.recordSend("🚀", now)
        alice.setTone("👍", "👍🏽")
        bob.recordSend("🙏", now)
        assertEquals(listOf("🚀"), alice.snapshot().recent)
        assertEquals("👍🏽", alice.preferred("👍"))
        assertEquals("👍", bob.preferred("👍"))
        assertTrue(otherServer.snapshot().uses.isEmpty())
        assertEquals(ReactionPickerStore.storageKey("HTTPS://CHAT.EXAMPLE:443/", "alice"), ReactionPickerStore.storageKey("https://chat.example", "alice"))
        assertNotEquals(ReactionPickerStore.storageKey("https://a.example/a", "bc"), ReactionPickerStore.storageKey("https://a.example/ab", "c"))
        alice.erase()
        assertTrue(alice.snapshot().recent.isEmpty())
        assertEquals(listOf("🙏"), bob.snapshot().recent)
    }

    @Test fun searchRanksWholeNameThenNameWordThenKeywordThenPrefixes() {
        val query = listOf("cat")
        assertEquals(0, ReactionSearch.rank(query, listOf("cat"), setOf("cat")))
        assertEquals(1, ReactionSearch.rank(query, listOf("black cat"), setOf("black", "cat")))
        assertEquals(2, ReactionSearch.rank(query, listOf("kitten"), setOf("cat", "kitten")))
        assertEquals(3, ReactionSearch.rank(query, listOf("caterpillar"), setOf("caterpillar")))
        assertEquals(4, ReactionSearch.rank(query, listOf("insect"), setOf("caterpillar")))
        assertTrue(ReactionSearch.matches(listOf("black", "ca"), listOf("black", "cat")))
        assertFalse(ReactionSearch.matches(listOf("black", "dog"), listOf("black", "cat")))
        assertEquals(ReactionSearch.words("ЁЛКА"), ReactionSearch.words("елка"))
    }
}
