package com.messenger.app.data.identity

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.SearchResult
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All knowledge is scoped to this server/account/session. Transient errors never select legacy mode. */
class IdentityRepository(private val api: IdentityApi, private val auth: AuthRepository,
    private val server: () -> String = { ServerEndpoint.current() }) {
    private val mutex = Mutex()
    private var scope: String? = null
    private var known = false
    private var refreshedAt: Long? = null
    private val refreshPolicy = IdentityRefreshPolicy()
    private val mutable = MutableStateFlow<OwnerIdentity?>(null)
    val identity: StateFlow<OwnerIdentity?>
        get() { if (scope != owner()) invalidate(); return mutable }
    private val legacyMutable = MutableStateFlow(false)
    val legacy: StateFlow<Boolean>
        get() { if (scope != owner()) invalidate(); return legacyMutable }
    internal fun ownerKey() = owner()
    private fun owner() = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id?.let {
        "${server()}|$it|${auth.currentSessionEpoch()}"
    }
    fun invalidate() { scope = null; known = false; refreshedAt = null; mutable.value = null; legacyMutable.value = false }
    suspend fun supports(origin: String) = api.supports(origin)
    suspend fun refresh(force: Boolean = false): OwnerIdentity? {
        val captured = owner() ?: throw AuthApiException.Api("unauthorized", "Signed out")
        return mutex.withLock { refreshLocked(captured, force) }
    }
    internal suspend fun refreshOnForeground(expectedOwner: String): Unit = mutex.withLock {
        if (expectedOwner != owner()) return@withLock
        refreshLocked(expectedOwner, force = true, foreground = true)
        Unit
    }
    internal suspend fun refreshOnConnection(expectedOwner: String, reconnect: Boolean): Unit = mutex.withLock {
        if (expectedOwner != owner()) return@withLock
        refreshLocked(expectedOwner, force = reconnect)
        Unit
    }
    private suspend fun refreshLocked(captured: String, force: Boolean, foreground: Boolean = false): OwnerIdentity? {
        check(captured == owner()) { "Identity owner changed" }
        if (scope != captured) { invalidate(); scope = captured }
        // The session epoch fences responses, but does not reset an account's foreground budget.
        val account = captured.substringBeforeLast('|')
        if (foreground && !refreshPolicy.foregroundDue(account, legacyMutable.value, mutable.value == null, refreshedAt)) return mutable.value
        if (known && !force) return mutable.value
        refreshPolicy.attempted(account)
        val origin = server()
        val token = auth.currentAccessToken() ?: error("Signed out")
        check(captured == owner())
        val response = api.get(origin, token)
        check(captured == owner())
        check(response == null || response.userId == (auth.authState.value as AuthSessionState.LoggedIn).user.id)
        mutable.value = response; legacyMutable.value = response == null; known = true
        refreshedAt = refreshPolicy.now()
        return response
    }
    suspend fun update(current: OwnerIdentity, username: String?, policy: Discoverability): OwnerIdentity = mutex.withLock {
        val captured = owner(); check(captured != null && captured == scope)
        val origin = server()
        val token = auth.currentAccessToken() ?: error("Signed out")
        check(captured == owner())
        val result = api.update(origin, token, current, username, policy, username != current.username)
        check(captured == owner() && result.userId == current.userId)
        mutable.value = result
        refreshedAt = refreshPolicy.now()
        result
    }
    /** Null means an explicitly detected old server; empty means a new server found no exact match. */
    suspend fun search(raw: String, offset: Long): SearchResult? {
        refresh() ?: return null
        if (offset != 0L) return SearchResult(emptyList(), null)
        val captured = owner()
        val origin = server()
        val target = IdentityPolicy.lookup(raw, origin) ?: return SearchResult(emptyList(), null)
        val token = auth.currentAccessToken() ?: error("Signed out")
        check(captured == owner())
        val profile = try { api.lookup(origin, token, target) }
        catch (error: AuthApiException.Api) {
            check(captured == owner())
            if (IdentityPolicy.legacyLookup(error.httpStatus, error.code)) {
                mutable.value = null; legacyMutable.value = true; known = true
                return null
            }
            if (error.httpStatus == 404) null else throw error
        }
        check(captured == owner())
        if (profile != null) check(when (target.parameter) {
            "username" -> profile.username == target.value
            "number" -> target.value.length < 9 || profile.number == target.value
            else -> false
        }) { "Lookup target mismatch" }
        return SearchResult(listOfNotNull(profile?.domain()), null)
    }
    suspend fun recovery(password: String): String {
        val captured = owner(); val origin = server()
        check(captured != null)
        val token = auth.currentAccessToken() ?: error("Signed out")
        check(captured == owner())
        return api.recovery(origin, token, password).also { check(captured == owner()) }
    }
    suspend fun profile(id: String): IdentityProfile? {
        refresh() ?: return null
        val captured = owner(); val origin = server()
        val token = auth.currentAccessToken() ?: error("Signed out")
        check(captured == owner())
        return api.profile(origin, token, id).also { check(captured == owner() && it.id == id) }
    }
}
