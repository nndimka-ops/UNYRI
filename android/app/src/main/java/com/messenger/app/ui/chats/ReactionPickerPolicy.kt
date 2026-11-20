package com.messenger.app.ui.chats

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

/** Window-local, density-independent coordinates. Insets include the IME when visible. */
internal data class ReactionRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal object ReactionPanelGeometry {
    fun frame(viewportWidth: Float, viewportHeight: Float, bubble: ReactionRect,
              top: Float, bottom: Float, left: Float, right: Float, outgoing: Boolean,
              screenHeight: Float = viewportHeight): ReactionRect {
        val safeTop = (top + 8f).coerceAtMost(viewportHeight)
        val safeBottom = (viewportHeight - bottom - 8f).coerceAtLeast(safeTop)
        val safeLeft = (left + 16f).coerceAtMost(viewportWidth)
        val safeRight = (viewportWidth - right - 16f).coerceAtLeast(safeLeft)
        val width = minOf(420f, safeRight - safeLeft)
        val above = (minOf(safeBottom, bubble.top - 8f) - safeTop).coerceAtLeast(0f)
        val below = (safeBottom - maxOf(safeTop, bubble.bottom + 8f)).coerceAtLeast(0f)
        val useAbove = above >= 240f || above >= below
        val height = minOf(if (useAbove) above else below, screenHeight * .6f, safeBottom - safeTop).coerceAtLeast(0f)
        val x = (if (outgoing) bubble.right - width else bubble.left).coerceIn(safeLeft, safeRight - width)
        val y = (if (useAbove) bubble.top - 8f - height else bubble.bottom + 8f).coerceIn(safeTop, safeBottom - height)
        return ReactionRect(x, y, x + width, y + height)
    }
}

/** A value snapshot; read at picker open/send, never observed by every message row. */
@Serializable
internal data class ReactionPickerHistory(
    val uses: List<Use> = emptyList(),
    val recent: List<String> = emptyList(),
    val tones: Map<String, String> = emptyMap(),
) {
    @Serializable data class Use(val emoji: String, val timeMillis: Long)

    fun recordSend(emoji: String, nowMillis: Long = System.currentTimeMillis()): ReactionPickerHistory {
        if (emoji.isEmpty()) return this
        val base = ReactionTones.base(emoji)
        val canonical = ReactionTones.canonical(emoji)
        val chosenTones = if (canonical in ReactionTones.variants(base)) tones + (ReactionSearch.baseKey(base) to canonical) else tones
        return copy(
            uses = uses.filter { it.timeMillis in (nowMillis - WINDOW_MILLIS)..nowMillis } + Use(emoji, nowMillis),
            recent = (listOf(emoji) + recent.filterNot { it == emoji }).take(16),
            tones = chosenTones,
        )
    }

    fun ranked(quick: String, nowMillis: Long = System.currentTimeMillis()): List<String> {
        val valid = uses.filter { it.timeMillis in (nowMillis - WINDOW_MILLIS)..nowMillis }
        val groups = valid.groupBy { ReactionSearch.baseKey(ReactionTones.base(it.emoji)) }
        val personal = groups.keys.sortedWith(compareByDescending<String> { groups.getValue(it).size }
            .thenByDescending { groups.getValue(it).maxOf(Use::timeMillis) }.thenBy { it })
        return (listOf(quick) + personal + DEFAULTS).filter(String::isNotEmpty)
            .distinctBy { ReactionSearch.baseKey(ReactionTones.base(it)) }.take(7).map(::preferred)
    }

    fun preferred(emoji: String): String = tones[ReactionSearch.baseKey(ReactionTones.base(emoji))] ?: ReactionTones.canonical(emoji)

    fun setTone(base: String, variant: String): ReactionPickerHistory =
        if (variant in ReactionTones.variants(base)) copy(tones = tones + (ReactionSearch.baseKey(ReactionTones.base(base)) to variant)) else this

    companion object {
        val DEFAULTS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥")
        const val WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

internal object ReactionTones {
    private fun modifier(cp: Int) = cp in 0x1F3FB..0x1F3FF

    /** Unicode 15.0 Emoji_Modifier_Base; multi-person ZWJ sequences are rejected below. */
    private fun modifierBase(cp: Int): Boolean = when (cp) {
        0x261D, 0x26F9, in 0x270A..0x270D, 0x1F385, in 0x1F3C2..0x1F3C4, 0x1F3C7,
        in 0x1F3CA..0x1F3CC, in 0x1F442..0x1F443, in 0x1F446..0x1F450, in 0x1F466..0x1F469,
        in 0x1F46B..0x1F46E, in 0x1F470..0x1F478, 0x1F47C, in 0x1F481..0x1F483,
        in 0x1F485..0x1F487, 0x1F48F, 0x1F491, 0x1F4AA, in 0x1F574..0x1F575,
        0x1F57A, 0x1F590, in 0x1F595..0x1F596, in 0x1F645..0x1F647, in 0x1F64B..0x1F64F,
        0x1F6A3, in 0x1F6B4..0x1F6B6, 0x1F6C0, 0x1F6CC, 0x1F90C, 0x1F90F,
        in 0x1F918..0x1F91F, 0x1F926, in 0x1F930..0x1F939, in 0x1F93D..0x1F93E,
        0x1F977, in 0x1F9B5..0x1F9B6, in 0x1F9B8..0x1F9B9, 0x1F9BB,
        in 0x1F9CD..0x1F9CF, in 0x1F9D1..0x1F9DD, in 0x1FAC3..0x1FAC5,
        in 0x1FAF0..0x1FAF8 -> true
        else -> false
    }

    fun base(emoji: String): String = canonical(buildString { emoji.codePoints().filter { !modifier(it) }.forEach { appendCodePoint(it) } })

    fun variants(emoji: String): List<String> {
        val plain = base(emoji)
        val scalars = plain.codePoints().toArray()
        val positions = scalars.indices.filter { modifierBase(scalars[it]) }
        if (positions.size != 1) return emptyList()
        val position = positions.single()
        // Holding hands needs CLDR's mixed-tone sequence table, never guessed variants.
        if (0x1F91D in scalars && 0x200D in scalars) return emptyList()
        return listOf(plain) + (0x1F3FB..0x1F3FF).map { tone ->
            buildString {
                scalars.forEachIndexed { index, cp ->
                    if (!(index == position + 1 && cp == 0xFE0F)) appendCodePoint(cp)
                    if (index == position) appendCodePoint(tone)
                }
            }
        }
    }

    /** CLDR keys omit VS16. Restore emoji presentation without relying on the phone's UCD age. */
    fun canonical(emoji: String): String {
        val scalars = emoji.codePoints().filter { it != 0xFE0F }.toArray()
        return buildString {
            scalars.forEachIndexed { index, cp ->
                appendCodePoint(cp)
                if (needsSelector(cp) && !modifier(scalars.getOrElse(index + 1) { 0 })) appendCodePoint(0xFE0F)
            }
        }
    }

    private fun needsSelector(cp: Int): Boolean {
        if (cp == 0x200D || cp == 0x20E3) return false
        if (cp == 0x23 || cp == 0x2A || cp in 0x30..0x39) return true
        if (cp in 0x2000..0x2BFF || cp == 0xA9 || cp == 0xAE || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299) {
            return cp !in defaultEmojiBMP
        }
        return when (cp) {
            in 0x1F170..0x1F171, in 0x1F17E..0x1F17F, 0x1F202, 0x1F21A, 0x1F22F, 0x1F237,
            0x1F321, in 0x1F324..0x1F32C, 0x1F336, 0x1F37D, in 0x1F396..0x1F397,
            in 0x1F399..0x1F39B, in 0x1F39E..0x1F39F, in 0x1F3CB..0x1F3CE,
            in 0x1F3D4..0x1F3DF, 0x1F3F3, 0x1F3F5, 0x1F3F7, 0x1F43F, 0x1F441, 0x1F4FD,
            in 0x1F549..0x1F54A, in 0x1F56F..0x1F570, in 0x1F573..0x1F579, 0x1F587,
            in 0x1F58A..0x1F58D, 0x1F590, 0x1F5A5, 0x1F5A8, in 0x1F5B1..0x1F5B2, 0x1F5BC,
            in 0x1F5C2..0x1F5C4, in 0x1F5D1..0x1F5D3, in 0x1F5DC..0x1F5DE, 0x1F5E1,
            0x1F5E3, 0x1F5E8, 0x1F5EF, 0x1F5F3, 0x1F5FA, in 0x1F6CB..0x1F6CF,
            in 0x1F6E0..0x1F6E5, 0x1F6E9, 0x1F6F0, 0x1F6F3 -> true
            else -> false
        }
    }

    private val defaultEmojiBMP = setOf(0x231A,0x231B,0x23E9,0x23EA,0x23EB,0x23EC,0x23F0,0x23F3,
        0x2614,0x2615,0x2648,0x2649,0x264A,0x264B,0x264C,0x264D,0x264E,0x264F,0x2650,0x2651,0x2652,0x2653,
        0x267F,0x2693,0x26A1,0x26AA,0x26AB,0x26BD,0x26BE,0x26C4,0x26C5,0x26CE,0x26D4,0x26EA,
        0x26F2,0x26F3,0x26F5,0x26FA,0x26FD,0x2705,0x270A,0x270B,0x2728,0x274C,0x274E,0x2753,0x2754,
        0x2755,0x2757,0x2795,0x2796,0x2797,0x27B0,0x27BF,0x2B1B,0x2B1C,0x2B50,0x2B55)
}

internal object ReactionSearch {
    fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT).replace('ё', 'е'), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
    fun words(value: String): List<String> = normalize(value).split(Regex("[^\\p{L}\\p{N}]+" )).filter(String::isNotEmpty)
    fun baseKey(emoji: String): String = emoji.replace("\uFE0F", "")
    fun matches(queryWords: List<String>, tokens: Collection<String>): Boolean =
        queryWords.isNotEmpty() && queryWords.all { query -> tokens.any { it.startsWith(query) } }
    fun rank(queryWords: List<String>, names: Collection<String>, tokens: Collection<String>): Int {
        val nameWords = names.map(::words)
        val inNames = nameWords.flatten().toSet()
        return when {
            nameWords.any { it == queryWords } -> 0
            queryWords.all { it in inNames } -> 1
            queryWords.all { it in tokens } -> 2
            queryWords.all { query -> inNames.any { it.startsWith(query) } } -> 3
            else -> 4
        }
    }
}
