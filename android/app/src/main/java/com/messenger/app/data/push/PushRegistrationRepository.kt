package com.messenger.app.data.push

import com.messenger.app.data.api.PushApi
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.*

/** One registration attempt at a time; account/server ownership is checked after every suspension. */
class PushRegistrationRepository internal constructor(
    private val scope: CoroutineScope,
    private val owner: () -> String?,
    private val acquireToken: suspend () -> String?,
    private val accessToken: suspend () -> String?,
    private val register: suspend (String, String) -> Unit,
    private val savePending: (String) -> Unit,
    private val isUploaded: (String, String) -> Boolean,
    private val markUploaded: (String, String) -> Unit,
    private val clearUploaded: () -> Unit,
) {
    constructor(api: PushApi, tokenStore: PushTokenStore, authRepository: AuthRepository,
                scope: CoroutineScope, acquireToken: suspend () -> String?) : this(
        scope,
        { (authRepository.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.let {
            "${com.messenger.app.data.api.ServerEndpoint.current()}|${it.user.id}|${authRepository.currentSessionEpoch()}"
        } }, acquireToken, { authRepository.currentAccessToken() },
        { access, token -> api.registerPushToken(access, "fcm", token) },
        tokenStore::savePendingToken, tokenStore::isUploadedFor, tokenStore::markUploadedFor, tokenStore::clearUploadedFlag,
    )
    private var job: Job? = null
    private var revision = 0L
    fun onNewToken(token: String) { savePending(token); ensureRegistered() }
    @Synchronized fun ensureRegistered() {
        val expected = owner() ?: return
        val ticket = ++revision
        job?.cancel()
        job = scope.launch {
            fun current() = synchronized(this@PushRegistrationRepository) { if (ticket != revision || owner() != expected) throw CancellationException("Push owner changed") }
            try {
                // Explicit acquisition repairs a missed onNewToken callback; null means unconfigured.
                val token = withTimeoutOrNull(15_000) { acquireToken() } ?: return@launch
                ensureActive(); current()
                if (token.isBlank()) return@launch
                savePending(token)
                if (isUploaded(token, expected)) return@launch
                val access = accessToken() ?: return@launch
                ensureActive(); current()
                register(access, token)
                ensureActive()
                synchronized(this@PushRegistrationRepository) { current(); markUploaded(token, expected) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retried on login, foreground or socket reconnect. */ }
        }
    }
    @Synchronized fun onLogout() { ++revision; job?.cancel(); job = null; clearUploaded() }
}
