package com.messenger.app.ui.admin

import java.net.URLEncoder

// ---------------------------------------------------------------------------
// Admin Product Pack 2.1 - cursor pagination.
//
// Pure Kotlin so the paging contract stays unit-testable without Compose or
// Android. The server owns ordering and the page boundary: the client only ever
// appends the page it was handed and must never derive a boundary itself.
// ---------------------------------------------------------------------------

/** One server page plus what the UI needs to render it. */
internal data class AdminPage<T>(
    val rows: List<T> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

/** What a list screen is doing, so every list reports the same states. */
internal enum class AdminLoadState { Idle, Loading, LoadingMore, Ready, Failed }

internal object AdminPaging {
    /** Page size for every administration list. */
    const val PageSize = 25

    /** Filters are bounded before they go on the wire. */
    private const val MaxFilterLength = 200

    /**
     * Query string built from whitelisted filters. Blank values are omitted
     * entirely rather than sent as empty parameters, so the server sees exactly
     * the filters the administrator chose.
     */
    fun query(vararg params: Pair<String, String?>): String = params.mapNotNull { (key, value) ->
        val trimmed = value?.trim()?.take(MaxFilterLength)
        if (trimmed.isNullOrEmpty()) null else "$key=${encode(trimmed)}"
    }.joinToString("&")

    /** The request path for one page of `path`. */
    fun path(path: String, query: String = "", cursor: String? = null): String {
        val parts = mutableListOf("limit=$PageSize")
        if (query.isNotBlank()) parts += query
        cursor?.takeIf { it.isNotBlank() }?.let { parts += "cursor=${encode(it)}" }
        return "$path?" + parts.joinToString("&")
    }

    /**
     * Appends a page, dropping rows that are already present. Ordering is the
     * server's contract, so a repeated identity can only mean a boundary
     * overlap - the UI must never show the same row twice.
     */
    fun <T> append(current: AdminPage<T>, next: AdminPage<T>, idOf: (T) -> String?): AdminPage<T> =
        AdminPage(
            rows = merge(current.rows, next.rows, idOf),
            nextCursor = next.nextCursor,
            hasMore = next.hasMore,
        )

    fun <T> merge(current: List<T>, next: List<T>, idOf: (T) -> String?): List<T> {
        val seen = current.mapNotNull(idOf).toMutableSet()
        val merged = current.toMutableList()
        for (row in next) {
            val id = idOf(row)
            if (id == null || seen.add(id)) merged.add(row)
        }
        return merged
    }

    /**
     * True when a response that has just arrived still belongs to the request
     * the screen is waiting for. A late answer from a previous filter, account
     * or panel generation must never be merged into the current list.
     */
    fun isCurrent(expected: Int, actual: Int, expectedFilter: String, currentFilter: String): Boolean =
        expected == actual && expectedFilter == currentFilter

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
