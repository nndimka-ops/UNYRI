package com.messenger.app.data.profile

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.ContactsRemote
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Session-fenced transport; an old server falls back only on an HTTP 404. */
class ContactsRepository(
    private val api: ContactsRemote,
    private val auth: AuthRepository,
    private val chats: ChatRepository,
    private val scope: CoroutineScope,
    private val origin: () -> String = { ServerEndpoint.current() },
) : ContactsService {
    private var activeOwner: String? = null
    private var activeStore: ContactsStore? = null
    private val hidden = mutableSetOf<String>()
    // A confirmed unblock can precede the local Room projection. Do not
    // reconstruct its previous block from that older cached row.
    private val unblocked = mutableSetOf<String>()
    private var blockRevision = 0L
    override fun ownerKey() = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.let {
        origin().trimEnd('/') + "|" + it.id + "|" + auth.currentSessionEpoch()
    }
    override val ownerId get() = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id.orEmpty()
    override fun isHidden(userId: String): Boolean = activeOwner == ownerKey() && userId in hidden

    /** Main-thread snapshot only. Never creates a store or loads the address book. */
    fun isKnownCallContact(userId: String): Boolean {
        val owner = ownerKey() ?: return false
        if (activeOwner != owner) return false
        val id = userId.lowercase(java.util.Locale.ROOT)
        return activeStore?.ids?.value?.contains(id) == true && id !in hidden && owner == ownerKey()
    }

    val store: ContactsStore
        get() {
            ensureOwner()
            if (activeStore == null) activeStore = ContactsStore(this, scope)
            return checkNotNull(activeStore)
        }

    private fun ensureOwner() {
        val owner = ownerKey()
        if (owner == activeOwner) return
        activeStore?.clear()
        activeStore = null
        activeOwner = owner
        hidden.clear()
        unblocked.clear()
        blockRevision++
    }

    override suspend fun page(cursor: String?): ContactsPage = authenticated { token, server ->
        try {
            val page = api.contacts(token, cursor, server)
            ContactsPage(page.items.filter { it.user.id !in hidden }.map { entry ->
                val user = entry.user
                ContactEntry(PublicUser(user.id, user.username, user.displayName, user.avatarUrl, user.bio,
                    user.profileVersion, user.avatarVersion, user.hasAvatar, user.publicNumber, user.publicUsername), entry.source, entry.addedAt)
            }, page.nextCursor)
        } catch (failure: AuthApiException) {
            val status = when (failure) {
                is AuthApiException.Api -> failure.httpStatus
                is AuthApiException.Network -> failure.httpStatus
            }
            if (status != 404) throw failure
            ContactsPage(chats.chats.first().filter { it.type == "direct" && !it.isBlocked }.mapNotNull { it.otherUser }
                .filter { it.id !in hidden }.distinctBy { it.id }.map { ContactEntry(it, "legacy_chat") },
                membershipAuthoritative = false)
        }
    }

    override suspend fun status(userId: String): Boolean = authenticated { token, server ->
        api.contactStatus(token, userId, server).also { check(it.userId.equals(userId, true)) }.isContact && userId !in hidden
    }

    override suspend fun set(userId: String, contact: Boolean) = authenticated { token, server ->
        check(userId != ownerId)
        val answer = api.setContact(token, userId, contact, server)
        check(answer.userId.equals(userId, true) && answer.isContact == contact)
    }

    fun changed(userId: String?, contact: Boolean?) {
        val owner = ownerKey()
        if (owner == null || activeOwner != owner || activeStore == null) return
        scope.launch {
            try { refreshKnownBlocks(owner) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (owner == ownerKey() && activeOwner == owner) activeStore?.markStale()
                return@launch
            }
            if (owner == ownerKey() && activeOwner == owner) activeStore?.receive(userId, contact)
        }
    }
    fun blocksChanged() {
        if (activeOwner != ownerKey()) return
        // An unnamed management hint does not say who was unblocked. The
        // confirmed chat preference projection (or explicit unblock) does.
        activeStore?.markStale()
    }
    fun blockChanged(userId: String, blocked: Boolean) {
        ensureOwner()
        if (activeOwner == null) return
        if (blocked) {
            val overrideRemoved = unblocked.remove(userId)
            val changed = hidden.add(userId)
            if (overrideRemoved || changed) blockRevision++
            if (changed) activeStore?.hide(userId)
        } else {
            val overrideAdded = unblocked.add(userId)
            val changed = hidden.remove(userId)
            if (overrideAdded || changed) blockRevision++
            if (changed) activeStore?.reveal(userId)
        }
    }

    /** Read already-cached metadata only; no chat or contact list is fetched.
     * False cache values never revoke a known block. A confirmed post-refresh
     * projection calls blockChanged(false), fenced by the chat repository. */
    private suspend fun refreshKnownBlocks(owner: String) {
        val revision = blockRevision
        val rows = chats.chats.first()
        if (owner != ownerKey() || activeOwner != owner || revision != blockRevision) return
        val states = rows.filter { it.type == "direct" }.groupBy { it.otherUser?.id ?: it.blockedUserId }
            .filterKeys { it != null }.mapValues { (_, chats) -> chats.any { it.isBlocked } }
        if (unblocked.removeAll { states[it] == false }) blockRevision++
        for ((id, blocked) in states) {
            if (id != null && blocked && id !in unblocked && hidden.add(id)) {
                blockRevision++
                activeStore?.hide(id)
            }
        }
    }

    private suspend fun <T> authenticated(block: suspend (String, String) -> T): T {
        val owner = checkNotNull(ownerKey())
        ensureOwner()
        refreshKnownBlocks(owner)
        val server = origin()
        val token = checkNotNull(auth.currentAccessToken())
        check(owner == ownerKey())
        val result = block(token, server)
        check(owner == ownerKey())
        refreshKnownBlocks(owner)
        return result
    }
}
