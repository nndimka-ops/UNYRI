package com.messenger.app.data.auth

import android.os.Build
import com.messenger.app.BuildConfig
import com.messenger.app.data.api.AuthApi
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.AuthResponseDto
import com.messenger.app.data.api.LoginRequestDto
import com.messenger.app.data.api.RefreshRequestDto
import com.messenger.app.data.api.RegisterRequestDto
import com.messenger.app.data.secure.SecureTokenStore
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.AuthUser
import com.messenger.app.domain.repository.AuthRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Access tokens are refreshed proactively (before they expire) rather than
 *  reactively on a 401, since auth is currently the only authenticated
 *  traffic; this is revisited once other authenticated endpoints exist. */
class AuthRepositoryImpl(
    private val api: AuthApi,
    private val tokenStore: SecureTokenStore,
    private val deferLogout: suspend (String) -> Unit = {},
    private val clearAccountData: suspend () -> Unit = {},
    private val installationCredentials: InstallationCredentialCoordinator? = null,
    private val signupIdentity: suspend (String, String) -> Boolean = { _, _ -> false },
    private val deviceName: () -> String = { DeviceNamePolicy.fallback(Build.MANUFACTURER, Build.MODEL) },
    private val refreshSavedAccounts: () -> Unit = {},
) : AuthRepository {

    private val _signupRecoveryPending = MutableStateFlow(tokenStore.signupRecoveryPending())
    override val signupRecoveryPending: StateFlow<Boolean> = _signupRecoveryPending
    override suspend fun confirmSignupRecovery() {
        val epoch = sessionEpoch.get()
        refreshMutex.withLock {
            check(epoch == sessionEpoch.get() && _authState.value is AuthSessionState.LoggedIn)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tokenStore.setSignupRecoveryPending(false) }
            _signupRecoveryPending.value = false
        }
    }

    private val _authState = MutableStateFlow<AuthSessionState>(AuthSessionState.Unknown)
    private val _terminalReason = MutableStateFlow<String?>(null)
    val terminalReason: StateFlow<String?> = _terminalReason
    override val authState: StateFlow<AuthSessionState> = _authState
    private val _cacheCleanupFailed = MutableStateFlow(false)
    override val cacheCleanupFailed: StateFlow<Boolean> = _cacheCleanupFailed
    private val _needsHistoryChoice = MutableStateFlow(false)
    override val needsHistoryChoice: StateFlow<Boolean> = _needsHistoryChoice
    private val _historyContext = MutableStateFlow<com.messenger.app.domain.repository.HistoryContext?>(null)
    override val historyContext: StateFlow<com.messenger.app.domain.repository.HistoryContext?> = _historyContext
    private val historyMutex = Mutex()
    override suspend fun reconcileHistoryContext() {
        if (!historyMutex.tryLock()) return
        try {
            if (_historyContext.value != null || _authState.value !is AuthSessionState.LoggedIn) return
            val epoch = sessionEpoch.get()
            val token = currentAccessToken() ?: return
            val context = api.historyContext(token)
            Instant.parse(context.beforeDate) // reject malformed metadata without touching storage
            require(context.beforeSequence >= 0)
            refreshMutex.withLock {
                if (sessionEpoch.get() != epoch) return@withLock
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    tokenStore.saveHistoryContext(context.beforeSequence, context.beforeDate)
                }
                _historyContext.value = tokenStore.historyContext()
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
          catch (_: Exception) { /* Optional metadata: retry on a later chat open, keep offline use. */ }
        finally { historyMutex.unlock() }
    }
    override suspend fun startWithoutRestoredHistory(): Result<Unit> = runCatching {
        refreshMutex.withLock {
            check(_authState.value is AuthSessionState.LoggedIn)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tokenStore.completeHistoryChoice() }
            _needsHistoryChoice.value = false
        }
    }

    private val refreshMutex = tokenStore.refreshMutex
    // Starts at 1 so a freshly-restored (or never-logged-in) process is a
    // real, distinguishable epoch too - 0 is never a valid "current" epoch.
    private val sessionEpoch = AtomicInteger(1)

    init {
        restoreSession()
    }

    private fun restoreSession() {
        tokenStore.installationId()
        _cacheCleanupFailed.value = tokenStore.cacheCleanupPending()
        _signupRecoveryPending.value = tokenStore.signupRecoveryPending()
        _needsHistoryChoice.value = tokenStore.needsHistoryChoice()
        _historyContext.value = tokenStore.historyContext()
        val userId = tokenStore.userId()
        val username = tokenStore.username()
        val displayName = tokenStore.displayName()
        _authState.value = if (userId != null && username != null && displayName != null && tokenStore.refreshToken() != null) {
            AuthSessionState.LoggedIn(AuthUser(userId, username, displayName))
        } else {
            AuthSessionState.LoggedOut
        }
    }

    private fun installationId(): String =
        tokenStore.installationId() ?: UUID.randomUUID().toString().also(tokenStore::saveInstallationId)

    private suspend fun prepareInstallationCredential(origin: String): String? {
        val coordinator = installationCredentials ?: return null
        if (!installationProofRequired(origin, BuildConfig.ALLOW_LOCAL_HTTP)) return null
        return coordinator.prepare(origin) { origin == com.messenger.app.data.api.ServerEndpoint.current().trimEnd('/') }
    }

    override suspend fun register(username: String, password: String, displayName: String): Result<Unit> =
        registerWithInvite(username, password, displayName, null)

    override suspend fun registerWithInvite(username: String, password: String, displayName: String, inviteCode: String?): Result<Unit> = runCatching {
      refreshMutex.withLock {
        val origin = com.messenger.app.data.api.ServerEndpoint.current().trimEnd('/')
        val credential = prepareInstallationCredential(origin)
        val binding = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tokenStore.createSignInBinding() }
        try {
        val response = api.register(
            RegisterRequestDto(
                username = username,
                password = password,
                displayName = displayName,
                inviteCode = inviteCode,
                installationId = installationId(),
                deviceName = deviceName(),
                osVersion = Build.VERSION.RELEASE,
                appVersion = BuildConfig.VERSION_NAME,
                installationCredential = credential,
            ), binding,
        )
        val pending = try { signupIdentity(origin, response.accessToken) } catch (_: Exception) { true }
        persistSession(response, pending, binding)
        } catch (error: Exception) {
            val failure = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                val failure = deviceBindingFailure(error, tokenStore.signInHardwareUnavailable())
                tokenStore.discardSignInBinding(binding)
                failure
            }
            throw failure
        }
      }
    }

    override suspend fun login(username: String, password: String): Result<Unit> = runCatching {
      refreshMutex.withLock {
        val origin = com.messenger.app.data.api.ServerEndpoint.current().trimEnd('/')
        val credential = prepareInstallationCredential(origin)
        val binding = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tokenStore.createSignInBinding() }
        try {
        val response = api.login(
            LoginRequestDto(
                username = username,
                password = password,
                installationId = installationId(),
                deviceName = deviceName(),
                osVersion = Build.VERSION.RELEASE,
                appVersion = BuildConfig.VERSION_NAME,
                installationCredential = credential,
            ), binding,
        )
        persistSession(response, deviceBinding = binding)
        } catch (error: Exception) {
            val failure = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                val failure = deviceBindingFailure(error, tokenStore.signInHardwareUnavailable())
                tokenStore.discardSignInBinding(binding)
                failure
            }
            throw failure
        }
      }
    }

    suspend fun changeServer(expectedEpoch: Int? = null, apply: () -> Unit) {
        refreshMutex.withLock {
            check(expectedEpoch == null || expectedEpoch == sessionEpoch.get())
            check(_authState.value is AuthSessionState.LoggedOut && !_cacheCleanupFailed.value)
            sessionEpoch.incrementAndGet()
            apply()
        }
    }

    override suspend fun logout() = logoutAndChangeServer {}

    /** Complete even when the initiating screen disappears. Never carry an old
     * server token into a new endpoint, or pop the freshly-created login graph. */
    suspend fun logoutAndChangeServer(expectedEpoch: Int? = null, apply: () -> Unit) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            refreshMutex.withLock {
                check(expectedEpoch == null || expectedEpoch == sessionEpoch.get())
                tokenStore.refreshToken()?.let { deferLogout(it) }
                val accessToken = tokenStore.accessToken()
                if (accessToken != null) {
                    kotlinx.coroutines.withTimeoutOrNull(5_000) {
                        runCatching { api.logout(accessToken) }
                    }
                }
                try { apply() } finally { endLocalSession() }
            }
        }

    override suspend fun deleteAccount(password: String): Result<Unit> {
        val epoch = sessionEpoch.get()
        return try {
            require(password.isNotEmpty() && password.toByteArray(Charsets.UTF_8).size <= 1024)
            val token = currentAccessToken() ?: throw AuthApiException.Api("unauthorized", "Not authenticated.")
            refreshMutex.withLock {
                check(epoch == sessionEpoch.get())
                // Serialize against login/logout: a delayed deletion must never clear a new account.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    api.deleteAccount(token, password)
                    val securityOwner = com.messenger.app.data.secure.LocalSecurityDecisions.owner(
                        com.messenger.app.data.api.ServerEndpoint.storageOrigin(), checkNotNull(tokenStore.userId()))
                    tokenStore.requestAccountSecurityErase(securityOwner)
                    endLocalSession()
                }
            }
            Result.success(Unit)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun handleRevokedAccessToken(token: String, epoch: Int, reason: String = "session_revoked") {
        refreshMutex.withLock {
            if (sessionEpoch.get() == epoch && tokenStore.accessToken() == token) endTerminalSession(reason)
        }
    }

    suspend fun handleRevokedSession(epoch: Int, reason: String) {
        if (!isTerminalAuthCode(reason)) return
        refreshMutex.withLock { if (sessionEpoch.get() == epoch) endTerminalSession(reason) }
    }

    /** A wake is merely a hint. Only this authenticated server response may erase data. */
    suspend fun checkSessionNow() {
        val epoch = sessionEpoch.get()
        val token = currentAccessToken() ?: return
        try { api.historyContext(token) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure: Exception) {
            if (isTerminalAuthFailure(failure)) handleRevokedAccessToken(token, epoch, (failure as AuthApiException.Api).code)
        }
    }

    private suspend fun endTerminalSession(reason: String) {
        if (reason == "account_deleted") tokenStore.userId()?.let {
            tokenStore.requestAccountSecurityErase(com.messenger.app.data.secure.LocalSecurityDecisions.owner(
                com.messenger.app.data.api.ServerEndpoint.storageOrigin(), it))
        }
        _terminalReason.value = deviceBindingFailureCode(reason, tokenStore.deviceSessionHardwareUnavailable())
        endLocalSession()
    }

    private suspend fun endLocalSession() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        sessionEpoch.incrementAndGet()
        installationCredentials?.clearSessionState()
        com.messenger.app.data.local.ChatImageCache.clear()
        _signupRecoveryPending.value = false
        _authState.value = AuthSessionState.LoggedOut
        _needsHistoryChoice.value = false
        _historyContext.value = null
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tokenStore.clearSession() }
            clearRoom { clearAccountData() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tokenStore.completeCacheCleanup() }
            _cacheCleanupFailed.value = false
        } catch (_: Exception) {
            _cacheCleanupFailed.value = true
        }
    }

    override fun currentDeviceId(): String? = tokenStore.deviceId()
    override fun historyBeforeSequence(): Long = tokenStore.historyBeforeSequence()

    override fun currentSessionEpoch(): Int = sessionEpoch.get()

    /** Migrate a restored signed-in device without replacing its account/session. */
    suspend fun reconcileInstallationCredential(): Result<Unit> {
        val coordinator = installationCredentials ?: return Result.success(Unit)
        val epoch = sessionEpoch.get()
        val userId = (_authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: return Result.success(Unit)
        val origin = com.messenger.app.data.api.ServerEndpoint.current().trimEnd('/')
        fun current() = epoch == sessionEpoch.get() && origin == com.messenger.app.data.api.ServerEndpoint.current().trimEnd('/') &&
            (_authState.value as? AuthSessionState.LoggedIn)?.user?.id == userId
        return try {
            if (!installationProofRequired(origin, BuildConfig.ALLOW_LOCAL_HTTP)) return Result.success(Unit)
            val token = currentAccessToken() ?: if (current()) throw AuthApiException.Network("Installation binding needs a valid session") else error("Session changed")
            check(current()) { "Session changed" }
            val deviceId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tokenStore.deviceId() } ?: error("No current device")
            // No refresh lock is held during network/bootstrap/storage work.
            coordinator.bindCurrent(origin, token, "$userId|$deviceId|$epoch", ::current)
            Result.success(Unit)
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (error: Exception) { Result.failure(error) }
    }

    // P0 hardening: every durable Room write in a long-running account job is
    // gated through this so a stale old-account write is rejected before the
    // DAO write (deterministically, sharing the mutex with clearRoom).
    private val writeGate = SessionWriteGate { sessionEpoch.get() }

    override suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T? =
        writeGate.write(epochAtStart, block)

    private val workspaceGate = SessionWriteGate { sessionEpoch.get() }
    override suspend fun <T> withWorkspaceWrite(epochAtStart: Int, block: suspend () -> T): T? = workspaceGate.write(epochAtStart, block)
    private val mediaGate = SessionWriteGate { sessionEpoch.get() }
    override suspend fun <T> withMediaWrite(epochAtStart: Int, block: suspend () -> T): T? = mediaGate.write(epochAtStart, block)
    override suspend fun clearRoom(block: suspend () -> Unit) = writeGate.clear { workspaceGate.clear { mediaGate.clear(block) } }

    override suspend fun currentAccessToken(): String? = refreshMutex.withLock {
        val epoch = sessionEpoch.get()
        // These getters decrypt SharedPreferences even when the token is valid.
        // Keep one snapshot under the refresh lock, away from the caller's UI thread.
        val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Triple(tokenStore.accessToken(), tokenStore.accessTokenExpiresAt(), tokenStore.refreshToken())
        }
        val accessToken = snapshot.first
        val expiresAt = snapshot.second
        val refreshToken = snapshot.third ?: return@withLock null

        val stillValid = accessToken != null && expiresAt != null &&
            Instant.now().isBefore(expiresAt.minusSeconds(EXPIRY_SAFETY_MARGIN_SECONDS))
        try {
            val (binding, hardwareUnavailable) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                tokenStore.deviceSessionBinding() to tokenStore.deviceSessionHardwareUnavailable()
            }
            // Force the one-time enrollment on an upgraded bearer session before WebSocket use.
            if (stillValid && canReuseDeviceAccessToken(binding != null, hardwareUnavailable)) return@withLock accessToken
            refreshSavedAccounts()
            val requestId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tokenStore.refreshRequestId() }
            val signingKey = binding ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tokenStore.deviceSessionBinding(createForLegacy = true) }
            val refreshed = api.refresh(RefreshRequestDto(refreshToken, requestId,
                appVersion = BuildConfig.VERSION_NAME, osVersion = Build.VERSION.RELEASE), signingKey)
            if (sessionEpoch.get() != epoch) return@withLock null
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                tokenStore.saveTokens(refreshed.accessToken, Instant.parse(refreshed.accessTokenExpiresAt), refreshed.refreshToken)
            }
            refreshed.accessToken
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // Sparse failure-only diagnostic: never log credentials or bodies.
            val failure = when (e) {
                is AuthApiException.Api -> "api_${e.code.take(40)}_http_${e.httpStatus ?: 0}"
                is AuthApiException.Network -> {
                    val message = e.message.orEmpty().lowercase()
                    val kind = when {
                        "resolve" in message || "dns" in message -> "dns"
                        "certificate" in message || "ssl" in message || "tls" in message -> "tls"
                        "timeout" in message || "timed out" in message -> "timeout"
                        "connect" in message -> "connection"
                        "cancel" in message -> "cancelled"
                        else -> "other"
                    }
                    "network_${kind}_http_${e.httpStatus ?: 0}"
                }
                is kotlinx.serialization.SerializationException -> "response_format"
                is java.time.format.DateTimeParseException -> "expiry_format"
                is ClassCastException -> "storage_type"
                else -> e.javaClass.simpleName
            }
            android.util.Log.i("AuthRefresh", "failure=$failure")
            // Only a definitive terminal auth rejection clears the session;
            // transient network/server failures keep it for a later retry.
            if (sessionEpoch.get() == epoch && isTerminalAuthFailure(e)) {
                endTerminalSession((e as AuthApiException.Api).code)
            }
            null
        }
    }

    suspend fun websocketDeviceProof(token: String, serverTime: Long? = null): String? = refreshMutex.withLock {
        val epoch = sessionEpoch.get()
        if (tokenStore.accessToken() != token) throw kotlinx.coroutines.CancellationException("Session changed")
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                tokenStore.deviceSessionBinding()?.proof("GET", "ws", accessSessionID(token, com.messenger.app.data.api.ApiJson), token, serverTime)
            }
        } catch (error: AuthApiException.Api) {
            if (sessionEpoch.get() == epoch && isTerminalAuthFailure(error)) endTerminalSession(error.code)
            throw error
        }
    }

    private suspend fun persistSession(response: AuthResponseDto, signupPending: Boolean = false, deviceBinding: DeviceSessionBinding? = null) {
        _terminalReason.value = null
        sessionEpoch.incrementAndGet()
        installationCredentials?.clearSessionState()
        // Unknown or different ownership must be erased before new credentials
        // become visible, including a retry after an interrupted cleanup.
        clearRoom {
            if (_cacheCleanupFailed.value || tokenStore.cacheOwnerId() != response.user.id) {
                try {
                    clearAccountData()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tokenStore.completeCacheCleanup() }
                    _cacheCleanupFailed.value = false
                }
                catch (error: Exception) { _cacheCleanupFailed.value = true; throw error }
            }
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            tokenStore.saveSession(response.accessToken, Instant.parse(response.accessTokenExpiresAt), response.refreshToken,
                response.user.id, response.user.username, response.user.displayName, response.deviceId, response.historyBeforeSequence, signupPending, deviceBinding)
        }
        _signupRecoveryPending.value = tokenStore.signupRecoveryPending()
        _needsHistoryChoice.value = tokenStore.needsHistoryChoice()
        _historyContext.value = tokenStore.historyContext()
        _authState.value = AuthSessionState.LoggedIn(AuthUser(response.user.id, response.user.username, response.user.displayName))
    }

    private companion object {
        const val EXPIRY_SAFETY_MARGIN_SECONDS = 5L
    }
}
