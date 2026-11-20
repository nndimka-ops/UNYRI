package com.messenger.app.data.profile

import com.messenger.app.domain.model.PublicUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class ContactEntry(val user: PublicUser, val source: String = "manual", val addedAt: String = "") {
    val id get() = user.id
}
data class ContactsPage(
    val items: List<ContactEntry>,
    val nextCursor: String? = null,
    /** Old-server chat partners are display rows, not evidence of membership. */
    val membershipAuthoritative: Boolean = true,
)

interface ContactsService {
    fun ownerKey(): String?
    val ownerId: String
    /** A stored contact is not necessarily visible (the server hides blocked peers). */
    fun isHidden(userId: String): Boolean = false
    suspend fun page(cursor: String?): ContactsPage
    suspend fun status(userId: String): Boolean
    suspend fun set(userId: String, contact: Boolean)
}

/** Address-book state is deliberately independent of profile publications. Main-thread confined. */
class ContactsStore(
    private val service: ContactsService,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Phase { IDLE, LOADING, LOADED, FAILED }
    private val owner = service.ownerKey()
    private val _entries = MutableStateFlow<List<ContactEntry>>(emptyList())
    val entries = _entries.asStateFlow()
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids = _ids.asStateFlow()
    private val _nonMembers = MutableStateFlow<Set<String>>(emptySet())
    val nonMembers = _nonMembers.asStateFlow()
    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase = _phase.asStateFlow()
    private val _changing = MutableStateFlow<Set<String>>(emptySet())
    val changing = _changing.asStateFlow()
    private val _checking = MutableStateFlow<Set<String>>(emptySet())
    val checking = _checking.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private var ticket = 0L
    private val touched = mutableMapOf<String, Long>()
    private val storedMembers = mutableSetOf<String>()
    private val hidden = mutableSetOf<String>()
    private var loadedAt: Long? = null
    private var loadedMembershipAuthoritative = false
    val hasLoadedMembership: Boolean get() = loadedAt != null && loadedMembershipAuthoritative
    private var stale = true
    private var viewers = 0
    private var retainedForSession = false
    private var keys = emptyMap<String, String>()
    private val current get() = owner != null && service.ownerKey() == owner
    private val fresh get() = loadedAt?.let { now() - it < FRESHNESS_MS } == true

    fun listAppeared() { viewers++ }
    fun listDisappeared() { viewers = (viewers - 1).coerceAtLeast(0) }
    fun retainForSession() { retainedForSession = true }

    suspend fun ensureLoaded() {
        if (!current) { clear(); return }
        if (_phase.value == Phase.LOADING || (!stale && fresh && _phase.value != Phase.FAILED)) return
        reload()
    }

    /** A real reconnect/foreground event invalidates a fresh book too. */
    suspend fun refreshForSession() {
        if (_phase.value == Phase.LOADING) return
        stale = true
        _nonMembers.value = emptySet()
        _revision.value++
        reload()
    }

    suspend fun reload() {
        if (!current) { clear(); return }
        val request = ++ticket
        stale = false
        _phase.value = Phase.LOADING
        try {
            val collected = mutableListOf<ContactEntry>()
            var membershipAuthoritative = true
            var cursor: String? = null
            val seen = mutableSetOf<String>()
            for (index in 0 until PAGE_LIMIT) {
                val page = service.page(cursor)
                if (ticket != request) return
                if (!current) { clear(); return }
                collected += page.items.map { it.copy(user = it.user.copy(username = "")) }
                membershipAuthoritative = membershipAuthoritative && page.membershipAuthoritative
                val next = page.nextCursor ?: break
                val normalized = canonicalUuid(next) ?: error("Invalid contacts cursor")
                check(seen.add(normalized)) { "Repeated contacts cursor" }
                check(index < PAGE_LIMIT - 1) { "Too many contacts pages" }
                cursor = normalized
            }
            val prepared = withContext(worker) {
                val unique = collected.filter { it.id != service.ownerId }.distinctBy { it.id }
                val folded = unique.associate { it.id to searchKey(it.user) }
                val rows = ContactsOrdering.sorted(unique)
                rows to folded
            }
            if (ticket != request) return
            if (!current) { clear(); return }
            val visible = prepared.first.filterNot { isHidden(it.id) }
            _entries.value = visible
            keys = prepared.second.filterKeys { id -> !isHidden(id) }
            if (membershipAuthoritative) {
                storedMembers.removeAll { !isHidden(it) }
                storedMembers.addAll(visible.map { it.id })
                _ids.value = storedMembers.filterNotTo(mutableSetOf(), ::isHidden)
            }
            _nonMembers.value -= _ids.value
            loadedAt = now()
            loadedMembershipAuthoritative = membershipAuthoritative
            _phase.value = Phase.LOADED
        } catch (cancel: CancellationException) {
            if (request == ticket) { stale = true; _phase.value = if (loadedAt == null) Phase.IDLE else Phase.LOADED }
            throw cancel
        } catch (_: Exception) {
            if (request != ticket) return
            if (!current) { clear(); return }
            stale = true
            _phase.value = Phase.FAILED
        }
    }

    suspend fun matching(query: String, legacyLogins: Boolean = true): List<ContactEntry> {
        val rows = _entries.value
        val searchKeys = keys
        return withContext(worker) {
            val term = fold(query.trim().removePrefix("@"))
            if (term.isEmpty()) rows else rows.filter {
                fold(com.messenger.app.domain.model.DisplayNameSanitizer.peer(it.user.displayName)).contains(term) ||
                    (com.messenger.app.data.identity.IdentityPolicy.input(query)?.takeIf { target -> target.parameter == "number" }
                        ?.let { target -> it.user.publicNumber == target.value } == true) ||
                    it.user.publicUsername?.let { name -> "@$name" == query.lowercase(java.util.Locale.ROOT) } == true
            }
        }
    }

    /** A profile asks for one membership, never for a page of contacts. */
    suspend fun check(user: PublicUser) {
        val id = user.id
        if (!current || id == service.ownerId) return
        if (_phase.value == Phase.LOADED && loadedMembershipAuthoritative && !stale && fresh) {
            if (isHidden(id)) publish(id)
            else if (id !in _ids.value) _nonMembers.value += id
            return
        }
        if (id in _changing.value || id in _checking.value) return
        _checking.value += id
        try {
            repeat(2) {
                val mark = touched[id] to _revision.value
                try {
                    val member = service.status(id)
                    if (!current) { clear(); return }
                    if (touched[id] != mark.first) return
                    if (_revision.value != mark.second) return@repeat
                    // A status answer reports visibility, not the underlying stored
                    // membership while a peer is blocked.
                    if (isHidden(id)) { touch(id); publish(id) }
                    else record(id, member)
                    return
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    if (!current) { clear(); return }
                    if (touched[id] != mark.first) return
                    if (_revision.value != mark.second) return@repeat
                    forget(id)
                    return
                }
            }
        }
        finally { _checking.value -= id }
    }

    fun markStale() {
        stale = true
        if (!current) return
        _nonMembers.value = emptySet()
        _revision.value++
        restartIfListed()
    }

    fun hide(id: String) {
        if (!current) return
        val interrupted = supersede()
        hidden += id
        touch(id)
        publish(id)
        _entries.value = _entries.value.filterNot { it.id == id }
        if (interrupted) restartIfListed()
    }

    fun reveal(id: String) {
        if (!current) return
        hidden -= id
        touch(id)
        // An unblock is not a fresh visible-membership answer. The next list or
        // profile check confirms visibility before any stored contact is shown.
        markStale()
    }

    fun receive(userId: String?, isContact: Boolean?) {
        if (!current) return
        if (userId == null || isContact == null) { markStale(); return }
        if (userId == service.ownerId) return
        val interrupted = supersede()
        record(userId, isContact)
        if (interrupted || (stale && loadedAt != null)) restartIfListed()
    }

    fun eraseDeletedPeer(id: String) {
        if (!current) return
        supersede()
        hidden += id
        storedMembers -= id
        _ids.value -= id
        _nonMembers.value -= id
        _entries.value = _entries.value.filterNot { it.id == id }
        keys -= id
        markStale()
    }

    suspend fun set(user: PublicUser, contact: Boolean): Boolean {
        val id = user.id
        if (!current || id == service.ownerId || id in _changing.value) return false
        _changing.value += id
        touch(id)
        var interrupted = supersede()
        try {
            service.set(id, contact)
            if (!current) { clear(); return false }
            interrupted = supersede() || interrupted
            val previouslyStale = stale
            record(id, contact)
            if (contact) stale = previouslyStale
            if (contact && !isHidden(id) && loadedAt != null && _entries.value.none { it.id == id }) {
                val userKey = withContext(worker) { searchKey(user) }
                while (id in _ids.value && !isHidden(id)) {
                    val snapshot = _entries.value
                    val rows = withContext(worker) {
                        ContactsOrdering.sorted((snapshot + ContactEntry(user.copy(username = ""))).distinctBy { it.id })
                    }
                    if (!current) { clear(); return false }
                    if (snapshot !== _entries.value) continue
                    if (id !in _ids.value || isHidden(id)) break
                    _entries.value = rows
                    keys = keys + (id to userKey)
                    break
                }
            }
            if (interrupted) restartIfListed()
            return true
        } catch (cancel: CancellationException) { if (interrupted) restartIfListed(); throw cancel }
        catch (_: Exception) { if (interrupted) restartIfListed(); return false }
        finally { _changing.value -= id }
    }

    private fun record(id: String, member: Boolean) {
        touch(id)
        if (member) storedMembers += id else storedMembers -= id
        publish(id)
    }

    private fun isHidden(id: String): Boolean = id in hidden || service.isHidden(id)
    private fun touch(id: String) { touched[id] = (touched[id] ?: 0) + 1 }

    private fun publish(id: String) {
        if (id in storedMembers && !isHidden(id)) {
            _nonMembers.value -= id
            _ids.value += id
            if (loadedAt != null && _entries.value.none { it.id == id }) stale = true
        } else {
            _nonMembers.value += id
            _ids.value -= id
            _entries.value = _entries.value.filterNot { it.id == id && (isHidden(id) || it.source != "legacy_chat") }
        }
    }

    private fun forget(id: String) {
        touch(id)
        val wasMember = storedMembers.remove(id) || id in _ids.value
        _ids.value -= id
        _nonMembers.value -= id
        _entries.value = _entries.value.filterNot { it.id == id && it.source != "legacy_chat" }
        if (_entries.value.none { it.id == id }) keys -= id
        if (wasMember) stale = true
    }

    private fun supersede(): Boolean {
        ticket++
        if (_phase.value != Phase.LOADING) return false
        _phase.value = if (loadedAt == null) Phase.IDLE else Phase.LOADED
        stale = true
        return true
    }

    private fun restartIfListed() { if (viewers > 0 || retainedForSession) scope.launch { reload() } }

    fun clear() {
        ticket++
        _entries.value = emptyList(); _ids.value = emptySet(); _nonMembers.value = emptySet()
        _phase.value = Phase.IDLE; _changing.value = emptySet(); _checking.value = emptySet()
        touched.clear(); storedMembers.clear(); hidden.clear()
        keys = emptyMap(); loadedAt = null; loadedMembershipAuthoritative = false; stale = true
        retainedForSession = false
    }

    companion object {
        const val FRESHNESS_MS = 300_000L
        const val PAGE_LIMIT = 25
        fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "").lowercase(Locale.ROOT)
        private fun searchKey(user: PublicUser) = fold(user.visibleName + "\n" + user.publicUsername.orEmpty() + "\n" + user.publicNumber.orEmpty())
        fun canonicalUuid(value: String): String? = runCatching { UUID.fromString(value).toString().takeIf { it.equals(value, true) } }.getOrNull()
    }
}
