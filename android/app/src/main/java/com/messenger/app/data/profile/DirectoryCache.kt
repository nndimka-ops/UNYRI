package com.messenger.app.data.profile

import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.model.SearchResult

/** Bounded contiguous prefix of the unfiltered directory. No presence or chat history. */
@kotlinx.serialization.Serializable
internal data class DirectoryCache(val items: List<PublicUser> = emptyList()) {
    fun append(offset: Long, page: List<PublicUser>): DirectoryCache {
        if (offset != 0L && offset != items.size.toLong()) return this
        val prefix = if (offset == 0L) emptyList() else items
        return DirectoryCache((prefix + page).distinctBy { it.id }.take(100).map { it.copy(bio = null, avatarUrl = null) })
    }
    fun search(query: String, limit: Int, offset: Long): SearchResult {
        val term = query.trim().removePrefix("@")
        val matched = items.filter { it.displayName.contains(term, true) || it.username.contains(term, true) }
        val start = offset.coerceIn(0, matched.size.toLong()).toInt()
        val end = (start + limit.coerceIn(1, 30)).coerceAtMost(matched.size)
        return SearchResult(matched.subList(start, end), end.toLong().takeIf { end < matched.size }, cached = true)
    }
}

internal fun canUseDirectoryCache(error: Throwable): Boolean =
    error is com.messenger.app.data.api.AuthApiException.Network && error.message?.startsWith("HTTP ") != true
