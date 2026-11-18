package com.messenger.app.data.profile

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccountAutoDeleteSnapshot(
    @SerialName("inactive_days") val inactiveDays: Int?,
    @SerialName("last_active_at") val lastActiveAt: String,
    @SerialName("next_deletion_at") val nextDeletionAt: String?,
    val revision: Long,
)

data class AccountAutoDeleteUpdate(val inactiveDays: Int?, val expectedRevision: Long, val password: String?)
data class AccountAutoDeleteOwner(val origin: String, val userId: String, val epoch: Int)

interface AccountAutoDeleteRemote {
    suspend fun getAutoDelete(token: String, origin: String): AccountAutoDeleteSnapshot
    suspend fun updateAutoDelete(token: String, origin: String, update: AccountAutoDeleteUpdate): AccountAutoDeleteSnapshot
    suspend fun recordAccountActivity(token: String, origin: String)
}

/** Only server snapshots are accepted. No timer, password or policy is persisted locally. */
class AccountAutoDeleteRepository(
    private val api: AccountAutoDeleteRemote,
    private val auth: AuthRepository,
    private val origin: () -> String = { ServerEndpoint.current() },
) {
    private val mutex = Mutex()
    @Volatile private var snapshotOwner: AccountAutoDeleteOwner? = null
    private val mutable = MutableStateFlow<AccountAutoDeleteSnapshot?>(null)
    val state: StateFlow<AccountAutoDeleteSnapshot?>
        get() { if (snapshotOwner != ownerKey()) invalidate(); return mutable }

    fun ownerKey(): AccountAutoDeleteOwner? = (auth.authState.value as? AuthSessionState.LoggedIn)?.let {
        AccountAutoDeleteOwner(origin().trimEnd('/'), it.user.id, auth.currentSessionEpoch())
    }
    fun invalidate() { snapshotOwner = null; mutable.value = null }

    suspend fun refresh(owner: AccountAutoDeleteOwner? = ownerKey()): Result<AccountAutoDeleteSnapshot> = mutex.withLock {
        try {
            val captured = checkNotNull(owner)
            val token = tokenFor(captured)
            accept(captured, api.getAutoDelete(token, captured.origin))
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { if (snapshotOwner != ownerKey()) invalidate(); Result.failure(error) }
    }

    /** Uses the revision the user reviewed. A conflict is refreshed but is never retried as a write. */
    suspend fun update(owner: AccountAutoDeleteOwner, inactiveDays: Int?, expectedRevision: Long,
        password: String?): Result<AccountAutoDeleteSnapshot> = mutex.withLock {
        try {
            require(inactiveDays == null || inactiveDays in 1..1095)
            require(inactiveDays == null || !password.isNullOrEmpty())
            check(snapshotOwner == owner && mutable.value?.revision == expectedRevision) { "Reload the server policy" }
            val token = tokenFor(owner)
            val update = AccountAutoDeleteUpdate(inactiveDays, expectedRevision, if (inactiveDays == null) null else password)
            accept(owner, api.updateAutoDelete(token, owner.origin, update))
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            if (owner == ownerKey() && error.isAutoDeleteConflict()) {
                // Invalidate first: even a failed refetch must make the stale revision unusable.
                invalidate()
                try { accept(owner, api.getAutoDelete(tokenFor(owner), owner.origin)) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* The UI must explicitly reload before another save. */ }
            }
            if (snapshotOwner != ownerKey()) invalidate()
            Result.failure(error)
        }
    }

    suspend fun recordActivity(owner: AccountAutoDeleteOwner, foreground: () -> Boolean) {
        check(foreground())
        val token = tokenFor(owner)
        currentCoroutineContext().ensureActive()
        check(foreground() && owner == ownerKey())
        api.recordAccountActivity(token, owner.origin)
        // A late response must never update a different session or a local deadline.
    }

    private suspend fun tokenFor(owner: AccountAutoDeleteOwner): String {
        check(owner == ownerKey()) { "Session changed" }
        val token = checkNotNull(auth.currentAccessToken())
        currentCoroutineContext().ensureActive()
        check(owner == ownerKey()) { "Session changed" }
        return token
    }
    private fun accept(owner: AccountAutoDeleteOwner, value: AccountAutoDeleteSnapshot): Result<AccountAutoDeleteSnapshot> {
        check(owner == ownerKey()) { "Session changed" }
        require(value.inactiveDays == null || value.inactiveDays in 1..1095)
        require(value.revision >= 0)
        snapshotOwner = owner; mutable.value = value
        return Result.success(value)
    }
}

fun Throwable.isAutoDeleteConflict(): Boolean = when (this) {
    is AuthApiException.Api -> httpStatus == 409
    is AuthApiException.Network -> httpStatus == 409
    else -> false
}
