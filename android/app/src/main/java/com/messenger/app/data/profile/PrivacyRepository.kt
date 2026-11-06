package com.messenger.app.data.profile

import com.messenger.app.data.api.UsersApi
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PrivacySnapshot(
    @SerialName("online_visibility") val onlineVisibility: String = "everyone",
    @SerialName("last_seen_visibility") val lastSeenVisibility: String = "everyone",
    @SerialName("always_allow") val alwaysAllow: List<String> = emptyList(),
    @SerialName("never_allow") val neverAllow: List<String> = emptyList(),
    @SerialName("send_read_receipts") val sendReadReceipts: Boolean = true,
    val revision: Long = 0,
)
@Serializable
data class PrivacyUpdate(
    @SerialName("online_visibility") val onlineVisibility: String,
    @SerialName("last_seen_visibility") val lastSeenVisibility: String,
    @SerialName("always_allow") val alwaysAllow: List<String>,
    @SerialName("never_allow") val neverAllow: List<String>,
    @SerialName("send_read_receipts") val sendReadReceipts: Boolean,
    @SerialName("expected_revision") val expectedRevision: Long,
)

/** Snapshots are memory-only, scoped to origin + account + epoch. The server owns enforcement. */
class PrivacyRepository(private val api: UsersApi, private val auth: AuthRepository, private val origin: () -> String = { ServerEndpoint.current() }) {
    private val mutex = Mutex()
    @Volatile private var snapshotOwner: String? = null
    private val mutable = MutableStateFlow<PrivacySnapshot?>(null)
    val state: StateFlow<PrivacySnapshot?>
        get() { if (snapshotOwner != ownerKey()) invalidate(); return mutable }
    fun ownerKey(): String? = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.let {
        origin().trimEnd('/') + "|" + it.id + "|" + auth.currentSessionEpoch()
    }
    fun invalidate() { snapshotOwner = null; mutable.value = null }
    suspend fun refresh(): Result<PrivacySnapshot> = request { token, server -> api.privacy(token, origin = server) }
    suspend fun update(change: (PrivacySnapshot) -> PrivacySnapshot): Result<PrivacySnapshot> = request { token, server ->
        // Always fetch before editing: no default or previous-account snapshot may be written.
        val editingOwner = ownerKey()
        val current = api.privacy(token, origin = server)
        check(editingOwner == ownerKey()) { "Session changed" }
        val changed = change(current)
        require(changed.onlineVisibility in setOf("everyone", "nobody") && changed.lastSeenVisibility in setOf("everyone", "nobody"))
        api.privacy(token, PrivacyUpdate(changed.onlineVisibility, changed.lastSeenVisibility,
            changed.alwaysAllow.distinct(), changed.neverAllow.distinct(), changed.sendReadReceipts, current.revision), origin = server)
    }
    suspend fun people(query: String): List<com.messenger.app.data.api.PublicUserDto> {
        val owner = checkNotNull(ownerKey())
        val server = owner.substringBefore('|')
        val token = checkNotNull(auth.currentAccessToken())
        check(owner == ownerKey())
        val result = api.privacyDirectory(token, query.take(128), server).items
        check(owner == ownerKey())
        val self = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id
        return result.filter { it.id != self }
    }
    private suspend fun request(action: suspend (String, String) -> PrivacySnapshot): Result<PrivacySnapshot> = mutex.withLock {
        val owner = ownerKey()
        if (owner == null) { invalidate(); return@withLock Result.failure(IllegalStateException("Sign in required")) }
        val server = owner.substringBefore('|')
        if (snapshotOwner != owner) invalidate()
        try {
            val token = checkNotNull(auth.currentAccessToken())
            check(owner == ownerKey())
            val snapshot = action(token, server)
            check(owner == ownerKey())
            snapshotOwner = owner; mutable.value = snapshot
            Result.success(snapshot)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            // A conflict cannot remain the source for the next write.
            if (owner != ownerKey()) invalidate()
            // A failed request never replaces the last accepted server snapshot.
            Result.failure(error)
        }
    }
}
