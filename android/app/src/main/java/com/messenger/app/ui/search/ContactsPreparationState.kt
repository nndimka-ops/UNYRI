package com.messenger.app.ui.search

import androidx.lifecycle.ViewModel
import com.messenger.app.domain.model.PublicUser

internal data class ContactsPreparedSnapshot(
    val query: String,
    val items: List<ContactListItem>,
    val directory: List<PublicUser>,
    val online: Set<String> = emptySet(),
    val search: SearchUiState = SearchUiState.Idle,
    val entries: List<com.messenger.app.data.profile.ContactEntry> = emptyList(),
    val localeTag: String = "",
    val legacyLogins: Boolean = true,
)

/** Retain the prepared identities while a chat covers this navigation entry. No subscriptions. */
internal class ContactsPreparationState : ViewModel() {
    var snapshot: ContactsPreparedSnapshot? = null
}
