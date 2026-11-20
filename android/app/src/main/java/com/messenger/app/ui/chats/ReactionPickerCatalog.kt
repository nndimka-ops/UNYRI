package com.messenger.app.ui.chats

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class ReactionCategory(val id: String, val title: String, val titleRU: String, val titleUK: String, val symbol: String) {
    fun localizedTitle(language: String): String = when (language) { "ru" -> titleRU; "uk" -> titleUK; else -> title }
    val aliases: List<String> get() = listOf(title, titleRU, titleUK) + when (id) {
        "people" -> listOf("Faces", "Лица", "Обличчя", "Gestures", "Жесты", "Жести")
        "nature" -> listOf("Cats and animals", "Животные", "Тварини", "Nature", "Природа")
        "food" -> listOf("Food", "Еда", "Їжа")
        "activity" -> listOf("Activities", "Занятия", "Заняття")
        "travel", "objects" -> listOf("Travel and objects", "Предметы", "Предмети")
        "symbols" -> listOf("Hearts", "Сердца", "Серця")
        else -> emptyList()
    }
}

internal data class ReactionEmoji(
    val emoji: String,
    val categoryID: String,
    val englishName: String,
    val names: Map<String, String>,
    val searchTokens: Set<String>,
) {
    fun localizedName(language: String): String = names[language] ?: names["en"] ?: englishName
}

/** The shared CLDR file is also the finite, Unicode <=15.0 catalog, never the device's newer UCD. */
internal class ReactionPickerCatalog private constructor(val entries: List<ReactionEmoji>, val categories: List<ReactionCategory>) {
    val entriesByCategory: Map<String, List<ReactionEmoji>> = entries.groupBy(ReactionEmoji::categoryID)
    private val byKey = entries.associateBy { ReactionSearch.baseKey(it.emoji) }

    fun entry(emoji: String): ReactionEmoji? = byKey[ReactionSearch.baseKey(ReactionTones.base(emoji))]

    suspend fun search(query: String): List<ReactionEmoji> = withContext(Dispatchers.Default) {
        val raw = query.trim()
        if (raw.isEmpty()) return@withContext emptyList()
        val queryWords = ReactionSearch.words(raw)
        val normalized = ReactionSearch.normalize(raw)
        val legacyGroups = categories.filter { category -> category.aliases.any { ReactionSearch.normalize(it) == normalized } }.map(ReactionCategory::id).toSet()
        val pastedKey = ReactionSearch.baseKey(raw)
        val untonedKey = ReactionSearch.baseKey(ReactionTones.base(raw))
        data class Match(val rank: Int, val defaultIndex: Int, val nameLength: Int, val index: Int, val entry: ReactionEmoji)
        val found = ArrayList<Match>()
        entries.forEachIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            val key = ReactionSearch.baseKey(entry.emoji)
            // The owner's contracted short-name query is "кот" -> 🐱, while
            // "кошка" remains 🐈. This search-only name never replaces CLDR labels.
            val names = entry.names.values + entry.englishName + if (key == "🐱") listOf("кот") else emptyList()
            val favourite = ReactionPickerHistory.DEFAULTS.indexOf(entry.emoji).let { if (it < 0) ReactionPickerHistory.DEFAULTS.size else it }
            val length = names.minOfOrNull { ReactionSearch.words(it).size } ?: 0
            when {
                pastedKey == key -> found += Match(-1, 0, 0, index, entry)
                untonedKey == key && ReactionTones.canonical(raw) in ReactionTones.variants(entry.emoji) ->
                    found += Match(-1, 0, 0, index, entry.copy(emoji = raw))
                ReactionSearch.matches(queryWords, entry.searchTokens) ->
                    found += Match(ReactionSearch.rank(queryWords, names, entry.searchTokens), favourite, length, index, entry)
                entry.categoryID in legacyGroups -> found += Match(5, favourite, length, index, entry)
            }
        }
        found.sortedWith(compareBy<Match> { it.rank }.thenBy { it.defaultIndex }.thenBy { it.nameLength }.thenBy { it.index }).map(Match::entry)
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var prepared: Deferred<ReactionPickerCatalog>? = null
        @Volatile private var cached: ReactionPickerCatalog? = null

        /** Names may be reused by a later pill without starting catalog work before panel open. */
        fun cached(): ReactionPickerCatalog? = cached

        /** Starts only when the full panel opens. Parsing and token preparation run once on IO. */
        suspend fun load(context: Context): ReactionPickerCatalog {
            val task = synchronized(this) {
                prepared ?: scope.async {
                    context.applicationContext.assets.open("ReactionEmojiKeywords.json").bufferedReader(Charsets.UTF_8)
                        .use { build(it.readText()) }.also { cached = it }
                }.also { prepared = it }
            }
            return task.await()
        }

        /** Pure production parser, also used by JVM tests with the same resource. */
        fun build(keywordData: String): ReactionPickerCatalog {
            val annotations = Json.parseToJsonElement(keywordData).jsonObject
            val categories = categoryDefinitions
            val entries = annotations.map { (raw, value) ->
                val labels = value.jsonObject.mapValues { (_, values) -> values.jsonArray.map { it.jsonPrimitive.content } }
                val category = categoryID(raw)
                val english = raw.codePoints().filter { it != 0x200D && it != 0x20E3 && it !in 0xE0020..0xE007F }
                    .mapToObj { runCatching { Character.getName(it)?.lowercase(java.util.Locale.ROOT) }.getOrNull().orEmpty() }
                    .toArray().joinToString(" ").ifEmpty { labels["en"]?.firstOrNull().orEmpty() }
                val group = categories.first { it.id == category }
                ReactionEmoji(ReactionTones.canonical(raw), category, english,
                    labels.mapValues { (_, values) -> values.firstOrNull().orEmpty() },
                    (listOf(english, group.title, group.titleRU, group.titleUK) + labels.values.flatten()).flatMap(ReactionSearch::words).toSet())
            }
            val order = categories.mapIndexed { index, category -> category.id to index }.toMap()
            val sorted = entries.sortedWith { a, b ->
                val category = order.getValue(a.categoryID).compareTo(order.getValue(b.categoryID))
                if (category != 0) category else compareScalars(a.emoji, b.emoji)
            }
            return ReactionPickerCatalog(sorted, categories)
        }

        private fun compareScalars(a: String, b: String): Int {
            val left = a.codePoints().toArray()
            val right = b.codePoints().toArray()
            for (index in 0 until minOf(left.size, right.size)) {
                val comparison = left[index].compareTo(right[index])
                if (comparison != 0) return comparison
            }
            return left.size.compareTo(right.size)
        }

        private fun categoryID(emoji: String): String {
            if (emoji.codePoints().anyMatch { it in 0x1F1E6..0x1F1FF } || emoji.startsWith("🏳") || emoji.startsWith("🏴")) return "flags"
            if (emoji.startsWith("🐻") || emoji.startsWith("🐈") || emoji.startsWith("🐕")) return "nature"
            if (emoji.startsWith("❤") || emoji.startsWith("👁")) return "symbols"
            return when (emoji.codePointAt(0)) {
                0x1F3C1, 0x1F6A9 -> "flags"
                in 0x1F600..0x1F64F, in 0x1F466..0x1F487, in 0x1F440..0x1F465, 0x1F48B,
                in 0x1F48F..0x1F491, 0x1F4AA, in 0x1F590..0x1F596, in 0x1F90C..0x1F93A,
                in 0x1F970..0x1F97F, in 0x1F9B0..0x1F9BF, in 0x1F9D0..0x1F9DF,
                in 0x1FAE0..0x1FAEF, in 0x1FAF0..0x1FAFF, 0x261D, in 0x270A..0x270D -> "people"
                in 0x1F300..0x1F302, in 0x1F30C..0x1F321, in 0x1F330..0x1F343, in 0x1F400..0x1F43F,
                in 0x1F980..0x1F9AF, in 0x1FAB0..0x1FABF, 0x1F54A, 0x1F525, in 0x1F4A7..0x1F4A8,
                in 0x2600..0x2604, 0x2614, 0x2744, 0x26C4 -> "nature"
                in 0x1F344..0x1F37F, in 0x1F950..0x1F96F, 0x1F9C0, in 0x1FAD0..0x1FADF, 0x2615 -> "food"
                in 0x1F380..0x1F3AF, in 0x1F3B1..0x1F3D3, in 0x1F3F8..0x1F3FF,
                in 0x1F93C..0x1F94F, 0x1F9E9, in 0x1FA70..0x1FA74, in 0x26BD..0x26BE, 0x26F3, 0x26F8 -> "activity"
                in 0x1F680..0x1F6FF, in 0x1F3D4..0x1F3F0, in 0x1F5FA..0x1F5FF,
                0x1F30B, in 0x1F303..0x1F309, 0x26EA, in 0x26F0..0x26F2, in 0x26F4..0x26F5, 0x26FA, 0x26FD, 0x2708 -> "travel"
                in 0x1F48C..0x1F48E, in 0x1F4B0..0x1F4FD, in 0x1F56F..0x1F579, in 0x1F5A5..0x1F5F3,
                in 0x1F9E0..0x1F9FF, in 0x1FA78..0x1FA7C, in 0x1FA80..0x1FAAF, in 0x1FAC0..0x1FACF,
                in 0x231A..0x231B, 0x2328, in 0x23F0..0x23F3, 0x260E, in 0x2692..0x2697,
                0x2699, in 0x26B0..0x26B1, 0x2702, 0x2709, in 0x270F..0x2712 -> "objects"
                else -> "symbols"
            }
        }

        val categoryDefinitions = listOf(
            ReactionCategory("people", "Smileys & People", "Смайлики и люди", "Смайлики та люди", "😀"),
            ReactionCategory("nature", "Animals & Nature", "Животные и природа", "Тварини та природа", "🐾"),
            ReactionCategory("food", "Food & Drink", "Еда и напитки", "Їжа та напої", "🍴"),
            ReactionCategory("activity", "Activity", "Активность", "Активність", "⚽"),
            ReactionCategory("travel", "Travel & Places", "Путешествия", "Подорожі", "🚗"),
            ReactionCategory("objects", "Objects", "Предметы", "Предмети", "💡"),
            ReactionCategory("symbols", "Symbols", "Символы", "Символи", "❤️"),
            ReactionCategory("flags", "Flags", "Флаги", "Прапори", "🏳️"),
        )
    }
}
