package com.messenger.app.domain.model

import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/** Unicode 17.0.0 properties and normalization for the server's display-name contract.
 * Android and the JVM ship different Unicode versions. None of these decisions
 * use their Character, locale casing or Normalizer tables.
 *
 * Data: official UCD 17.0.0, Unicode License V3 (resources/unicode/LICENSE.txt).
 * Reproduce with: java android/tools/GenerateDisplayNameUnicode.java
 */
internal object Unicode17 {
    const val version = "17.0.0"

    private class Sequences(val keys: IntArray, val values: Array<IntArray>) {
        operator fun get(scalar: Int): IntArray? = keys.binarySearch(scalar).takeIf { it >= 0 }?.let { values[it] }
    }

    private class Tables(input: DataInputStream) {
        init { check(input.readInt() == 0x554E3137) { "Invalid display-name Unicode data" } }
        val marks = IntArray(input.readInt() * 2) { input.readInt() }
        val alphabetic = IntArray(input.readInt() * 2) { input.readInt() }
        val whitespace = IntArray(input.readInt() * 2) { input.readInt() }
        private val classCount = input.readInt()
        val classKeys = IntArray(classCount)
        val classValues = IntArray(classCount)
        init { repeat(classCount) { classKeys[it] = input.readInt(); classValues[it] = input.readInt() } }
        val lowercase = sequences(input)
        private val decompositionCount = input.readInt()
        val decompositionKeys = IntArray(decompositionCount)
        val compatibility = BooleanArray(decompositionCount)
        val decompositionValues = Array(decompositionCount) { index ->
            decompositionKeys[index] = input.readInt()
            compatibility[index] = input.readBoolean()
            IntArray(input.readInt()) { input.readInt() }
        }
        private val compositionCount = input.readInt()
        val compositionKeys = LongArray(compositionCount)
        val compositionValues = IntArray(compositionCount)
        init {
            repeat(compositionCount) { compositionKeys[it] = input.readLong(); compositionValues[it] = input.readInt() }
            check(input.read() == -1) { "Unexpected display-name Unicode data" }
        }
        private fun sequences(input: DataInputStream): Sequences {
            val keys = IntArray(input.readInt())
            val values = Array(keys.size) { index -> keys[index] = input.readInt(); IntArray(input.readInt()) { input.readInt() } }
            return Sequences(keys, values)
        }
    }

    private val tables by lazy {
        val resource = checkNotNull(Unicode17::class.java.getResourceAsStream("/unicode/display-name-17.bin.gz")) {
            "Missing display-name Unicode data"
        }
        DataInputStream(GZIPInputStream(java.io.BufferedInputStream(resource))).use(::Tables)
    }

    private val numeric by lazy {
        checkNotNull(Unicode17::class.java.getResourceAsStream("/unicode/numeric-17.txt"))
            .bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotEmpty() && !it.startsWith("#") }.flatMap { it.split("..").asSequence().map { number -> number.toInt(16) } }.toList().toIntArray()
            }
    }
    fun isNumeric(scalar: Int): Boolean = contains(numeric, scalar)
    fun preload() { tables; numeric.size }
    fun isMark(scalar: Int): Boolean = contains(tables.marks, scalar)
    fun isAlphabetic(scalar: Int): Boolean = contains(tables.alphabetic, scalar)
    fun isWhitespace(scalar: Int): Boolean = contains(tables.whitespace, scalar)
    fun lowercase(scalar: Int): IntArray = tables.lowercase[scalar] ?: intArrayOf(scalar)
    fun nfc(value: String): String = normalize(value, compatibility = false, composeResult = true)
    fun nfd(value: String): String = normalize(value, compatibility = false, composeResult = false)
    fun nfkc(value: String): String = normalize(value, compatibility = true, composeResult = true)
    fun nfkd(value: String): String = normalize(value, compatibility = true, composeResult = false)

    private fun normalize(value: String, compatibility: Boolean, composeResult: Boolean): String {
        val decomposed = ArrayList<Int>(value.length)
        value.codePoints().forEach { decompose(it, compatibility, decomposed) }
        val scalars = if (composeResult) compose(decomposed) else decomposed
        return StringBuilder(value.length).also { output -> scalars.forEach(output::appendCodePoint) }.toString()
    }

    /** Recursive canonical/compatibility decomposition, followed by stable CCC ordering. */
    private fun decompose(scalar: Int, compatibility: Boolean, output: MutableList<Int>) {
        val syllable = scalar - S_BASE
        if (syllable in 0 until S_COUNT) {
            appendOrdered(L_BASE + syllable / N_COUNT, output)
            appendOrdered(V_BASE + syllable % N_COUNT / T_COUNT, output)
            val trailing = syllable % T_COUNT
            if (trailing != 0) appendOrdered(T_BASE + trailing, output)
            return
        }
        val index = tables.decompositionKeys.binarySearch(scalar)
        if (index >= 0 && (compatibility || !tables.compatibility[index])) {
            for (part in tables.decompositionValues[index]) decompose(part, compatibility, output)
        } else appendOrdered(scalar, output)
    }

    private fun appendOrdered(scalar: Int, output: MutableList<Int>) {
        val currentClass = combiningClass(scalar)
        var index = output.size
        if (currentClass != 0) {
            while (index > 0 && combiningClass(output[index - 1]) > currentClass) index--
        }
        output.add(index, scalar)
    }

    /** UAX #15 canonical composition, including blocking and algorithmic Hangul. */
    private fun compose(input: List<Int>): List<Int> {
        val output = ArrayList<Int>(input.size)
        var starterIndex = -1
        var starter = 0
        var previousClass = 0
        for (scalar in input) {
            val currentClass = combiningClass(scalar)
            val composite = if (starterIndex >= 0 && (previousClass == 0 || previousClass < currentClass)) {
                composition(starter, scalar)
            } else null
            if (composite != null) {
                output[starterIndex] = composite
                starter = composite
            } else {
                if (currentClass == 0) { starterIndex = output.size; starter = scalar }
                output.add(scalar)
                previousClass = currentClass
            }
        }
        return output
    }

    private fun composition(first: Int, second: Int): Int? {
        val leading = first - L_BASE
        val vowel = second - V_BASE
        if (leading in 0 until L_COUNT && vowel in 0 until V_COUNT) {
            return S_BASE + (leading * V_COUNT + vowel) * T_COUNT
        }
        val syllable = first - S_BASE
        val trailing = second - T_BASE
        if (syllable in 0 until S_COUNT && syllable % T_COUNT == 0 && trailing in 1 until T_COUNT) return first + trailing
        val pair = (first.toLong() shl 21) or second.toLong()
        return tables.compositionKeys.binarySearch(pair).takeIf { it >= 0 }?.let { tables.compositionValues[it] }
    }

    private fun combiningClass(scalar: Int): Int =
        tables.classKeys.binarySearch(scalar).takeIf { it >= 0 }?.let { tables.classValues[it] } ?: 0

    private fun contains(ranges: IntArray, scalar: Int): Boolean {
        var low = 0
        var high = ranges.size / 2 - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                scalar < ranges[middle * 2] -> high = middle - 1
                scalar > ranges[middle * 2 + 1] -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    private const val S_BASE = 0xAC00
    private const val L_BASE = 0x1100
    private const val V_BASE = 0x1161
    private const val T_BASE = 0x11A7
    private const val L_COUNT = 19
    private const val V_COUNT = 21
    private const val T_COUNT = 28
    private const val N_COUNT = V_COUNT * T_COUNT
    private const val S_COUNT = L_COUNT * N_COUNT
}
