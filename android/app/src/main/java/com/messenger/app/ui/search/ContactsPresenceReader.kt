package com.messenger.app.ui.search

import com.messenger.app.data.websocket.PeerPresence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class ContactPresence(val online: Boolean = false, val lastSeen: String? = null, val hidden: Boolean = false)

/** A single screen collector fans out semantic changes. Lease refreshes never invalidate rows. */
internal class ContactsPresenceReader {
    private val rows = mutableMapOf<String, MutableStateFlow<ContactPresence>>()
    private val _online = MutableStateFlow<Set<String>>(emptySet())
    val online: StateFlow<Set<String>> = _online
    fun row(id: String): StateFlow<ContactPresence> = rows.getOrPut(id) { MutableStateFlow(ContactPresence()) }

    fun update(peers: Map<String, PeerPresence>, history: Map<String, String>, visible: Set<String>, now: Long) {
        val expired = rows.filter { (id, state) -> state.value.online && peers[id]?.let { it.online && it.expiresAt > now } != true }.keys
        for (id in visible + expired) {
            val value = peers[id]?.takeIf { it.expiresAt > now }
            val state = rows.getOrPut(id) { MutableStateFlow(ContactPresence()) }
            state.value = ContactPresence(value?.online == true,
                if (value?.online == true) null else value?.lastSeenAt ?: history[id],
                value != null && !value.online && value.lastSeenAt == null)
        }
        // Offscreen classification is retained until the shared presence lease expires.
        // Unknown gaps do not invent an authoritative offline section.
        _online.value = peers.filterValues { it.online && it.expiresAt > now }.keys
    }
}
