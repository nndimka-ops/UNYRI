package com.messenger.app.data.profile

import java.text.CollationKey
import java.text.Collator
import java.util.Locale

/** Finder-style localized ordering: compare digit runs numerically, without an integer size limit.
 * Build every collation key once per sort, on the store's worker dispatcher. */
internal object ContactsOrdering {
    private data class Part(val localized: CollationKey, val number: String?)
    private data class Identity(val entry: ContactEntry, val name: List<Part>, val username: List<Part>)

    fun sorted(entries: List<ContactEntry>, locale: Locale = Locale.getDefault()): List<ContactEntry> {
        val collator = Collator.getInstance(locale).apply {
            strength = Collator.PRIMARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }
        val identities = entries.map { entry ->
            Identity(entry, parts(entry.user.visibleName, collator), parts(entry.user.username, collator))
        }
        return identities.sortedWith { left, right ->
            compare(left.name, right.name).takeIf { it != 0 }
                ?: compare(left.username, right.username).takeIf { it != 0 }
                ?: left.entry.id.compareTo(right.entry.id)
        }.map { it.entry }
    }

    private fun parts(text: String, collator: Collator): List<Part> {
        val result = mutableListOf<Part>()
        var index = 0
        while (index < text.length) {
            val start = index
            val numeric = Character.digit(text.codePointAt(index), 10) >= 0
            val digits = if (numeric) StringBuilder() else null
            while (index < text.length) {
                val scalar = text.codePointAt(index)
                val digit = Character.digit(scalar, 10)
                if ((digit >= 0) != numeric) break
                digits?.append(('0'.code + digit).toChar())
                index += Character.charCount(scalar)
            }
            val number = digits?.toString()?.trimStart('0')?.ifEmpty { "0" }
            result += Part(collator.getCollationKey(text.substring(start, index)), number)
        }
        return result
    }

    private fun compare(left: List<Part>, right: List<Part>): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val a = left[index]
            val b = right[index]
            val compared = if (a.number != null && b.number != null) {
                a.number.length.compareTo(b.number.length).takeIf { it != 0 } ?: a.number.compareTo(b.number)
            } else a.localized.compareTo(b.localized)
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }
}
