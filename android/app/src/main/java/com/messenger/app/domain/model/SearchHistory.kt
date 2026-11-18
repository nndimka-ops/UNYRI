package com.messenger.app.domain.model

import kotlinx.coroutines.flow.MutableStateFlow

/** Repository-owned, bounded memory; bind clears it on account/session teardown. */
class SearchHistory {
    private var owner: String? = null
    val queries = MutableStateFlow<List<String>>(emptyList())
    val people = MutableStateFlow<List<PublicUser>>(emptyList())
    fun bind(key: String?) { if (key != owner) { clear(); owner = key } }
    fun remember(query: String, person: PublicUser? = null) {
        val trimmed = query.trim()
        val value = trimmed.substring(0, trimmed.offsetByCodePoints(0, trimmed.codePointCount(0, trimmed.length).coerceAtMost(256)))
        if (value.isNotEmpty()) queries.value = (listOf(value) + queries.value.filter { it != value }).take(8)
        if (person != null) people.value = (listOf(person) + people.value.filter { it.id != person.id }).take(10)
    }
    fun clear() { queries.value = emptyList(); people.value = emptyList() }
}

