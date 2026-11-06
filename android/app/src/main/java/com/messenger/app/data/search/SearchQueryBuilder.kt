package com.messenger.app.data.search

/**
 * Safe FTS4 query builder (M.8). Raw user input is NEVER passed directly to
 * `MATCH`; each whitespace-separated term is sanitized to its Unicode
 * letters/digits (so quotes, `-`, `*`, parens, `:`, emoji, and any other
 * FTS metacharacter can never produce a malformed MATCH expression), then
 * wrapped as a quoted prefix query. Multiple terms are joined with spaces,
 * which FTS4 interprets as implicit AND (M.9).
 *
 * Returns null when no searchable terms remain (empty / all-symbols input),
 * which the caller treats as "no results" rather than a query.
 */
object SearchQueryBuilder {

    /** Builds a safe FTS4 MATCH expression, or null for an empty query. */
    fun build(raw: String): String? {
        // Split on runs of NON-alphanumeric characters - this must mirror
        // FTS4's own tokenization exactly, where `_`, `-`, `:`, quotes etc.
        // are token SEPARATORS (not simply "stripped"). Stripping them would
        // concatenate "M_SEARCH_ALPHA" into "MSEARCHALPHA", which matches
        // nothing because FTS4 indexed three separate tokens. Each resulting
        // alphanumeric run is safe to emit UNQUOTED as a `term*` prefix query
        // (quoted `"term"*` is a phrase-prefix query with different semantics).
        val terms = raw.split(nonAlphanumeric).mapNotNull { term ->
            if (term.isNotEmpty()) "$term*" else null
        }
        if (terms.isEmpty()) return null
        // Cap length so an absurdly long paste can't build a pathological query.
        // Space between terms is FTS4's implicit AND (the explicit "AND"
        // keyword is NOT supported by the FTS4 query parser - it is treated
        // as a literal token - so implicit AND is both simpler and correct).
        val joined = terms.joinToString(" ")
        return if (joined.length > MAX_QUERY_LENGTH) joined.take(MAX_QUERY_LENGTH) else joined
    }

    /** The cleaned tokens, for client-side highlighting of the matched text. */
    fun highlightTerms(raw: String): List<String> =
        raw.split(nonAlphanumeric).mapNotNull { term ->
            if (term.isNotEmpty()) term.lowercase() else null
        }.distinct()

    /** Bounded preview around the first hit, without cutting a UTF-16 surrogate pair. */
    fun snippet(text: String, rawQuery: String, maxCodePoints: Int = 160): String {
        val limit = maxCodePoints.coerceAtLeast(1)
        val match = highlightTerms(rawQuery).map { text.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
        val total = text.codePointCount(0, text.length)
        val startPoint = (text.codePointCount(0, match) - 36).coerceAtLeast(0)
        val endPoint = (startPoint + limit).coerceAtMost(total)
        val start = text.offsetByCodePoints(0, startPoint)
        val end = text.offsetByCodePoints(0, endPoint)
        return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
    }

    /** Runs of characters that FTS4's simple tokenizer treats as separators. */
    private val nonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")
    private const val MAX_QUERY_LENGTH = 500
}
