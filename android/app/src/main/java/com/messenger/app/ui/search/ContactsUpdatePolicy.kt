package com.messenger.app.ui.search

import com.messenger.app.domain.model.PublicUser

/** No delay: drag and fling share the same scroll-in-progress gate. Commit only after layout preparation. */
internal class ContactsClassificationPolicy(initial: Set<String> = emptySet()) {
    var rendered: Set<String> = initial
        private set
    fun target(latest: Set<String>, scrolling: Boolean): Set<String> = if (scrolling) rendered else latest
    fun changed(next: Set<String>): Set<String> = (rendered - next) + (next - rendered)
    fun commit(next: Set<String>) { rendered = next }
}

internal data class ContactsVisiblePerson(val key: String, val offset: Int)

internal object ContactsAnchorPolicy {
    fun capture(visible: List<ContactsVisiblePerson>, changed: Set<String>, nextKeys: Set<String>): ContactsVisiblePerson? =
        visible.firstOrNull { it.key.startsWith("person:") && it.key.removePrefix("person:") !in changed && it.key in nextKeys }
}

/** A blank state still occupies the same feedback slot as loading, failure and offline. */
internal enum class ContactsServerFeedback { BLANK, LOADING, ERROR, CONNECT, EMPTY }

/** Exact discovery also supports short number aliases; legacy directory keeps its three-character gate. */
internal fun contactsCanSearchDirectory(query: String, legacyLogins: Boolean): Boolean =
    query.trim().isNotEmpty() && (!legacyLogins || ContactsPresentation.normalize(query).length >= 3)

/** Rejected invitation credentials and arbitrary URLs never become a directory query. */
internal fun contactsScannedQuery(raw: String): String? = raw.takeIf {
    com.messenger.app.data.identity.IdentityPolicy.parse(it) != null ||
        (!it.contains("invite_code", ignoreCase = true) && '#' !in it && '?' !in it &&
            !it.startsWith("https:", true) && !it.startsWith("http:", true))
}?.take(4096)

/** Replace a completed page atomically; debounce/cache misses do not erase previous identities. */
internal fun contactsDirectoryPage(state: SearchUiState, query: String, previous: List<PublicUser>): List<PublicUser> = when (state) {
    is SearchUiState.Loaded -> if (state.query == query.trim()) state.items else previous
    is SearchUiState.Error -> if (state.clearResults) emptyList() else previous
    SearchUiState.Idle -> if (ContactsPresentation.normalize(query).length >= 3) previous else emptyList()
    SearchUiState.Loading -> previous
}

internal fun contactsServerFeedback(state: SearchUiState, connected: Boolean, empty: Boolean, query: String): ContactsServerFeedback = when {
    state is SearchUiState.Loaded && state.query != query -> if (connected) ContactsServerFeedback.LOADING else ContactsServerFeedback.CONNECT
    state is SearchUiState.Error && state.query != query -> if (connected) ContactsServerFeedback.LOADING else ContactsServerFeedback.CONNECT
    state == SearchUiState.Loading || (state == SearchUiState.Idle && connected) ||
        (state is SearchUiState.Loaded && (state.refreshing || state.loadingMore)) -> ContactsServerFeedback.LOADING
    state is SearchUiState.Error || (state is SearchUiState.Loaded && state.pageError) -> ContactsServerFeedback.ERROR
    !connected -> ContactsServerFeedback.CONNECT
    state is SearchUiState.Loaded && empty -> ContactsServerFeedback.EMPTY
    else -> ContactsServerFeedback.BLANK
}
