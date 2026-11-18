package com.messenger.app.data.push

import com.messenger.app.data.api.PushApi
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

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
        { pushRegistrationOwner(authRepository) }, acquireToken, { authRepository.currentAccessToken() },
        { access, token ->
            val expected = pushRegistrationOwner(authRepository)
            com.messenger.app.push.PushBindingWrites.mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (expected == null || pushRegistrationOwner(authRepository) != expected) throw CancellationException("Push owner changed")
                withContext(NonCancellable) { api.registerPushToken(access, "fcm", token) }
            }
        },
        tokenStore::savePendingToken, tokenStore::isUploadedFor, tokenStore::markUploadedFor, tokenStore::clearUploadedFlag,
    )
    private var job: Job? = null
    private var revision = 0L
    // A durable flag is only a last-known acknowledgement. Another workspace/process or an
    // FCM UNREGISTERED response may have invalidated it; revalidate once in every new graph.
    private var acknowledged: Pair<String, String>? = null
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
                if (isUploaded(token, expected) && synchronized(this@PushRegistrationRepository) {
                        acknowledged == (token to expected)
                    }) return@launch
                val access = accessToken() ?: return@launch
                ensureActive(); current()
                register(access, token)
                ensureActive()
                synchronized(this@PushRegistrationRepository) {
                    current(); markUploaded(token, expected); acknowledged = token to expected
                }
                android.util.Log.i("PushRegistration", "uploaded current binding")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.w("PushRegistration", "registration pending: ${error.javaClass.simpleName}")
                // Retried on login, foreground or socket reconnect.
            }
        }
    }
    @Synchronized fun onLogout() { ++revision; job?.cancel(); job = null; acknowledged = null; clearUploaded() }
}

private fun pushRegistrationOwner(auth: AuthRepository): String? =
    (auth.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.let {
        "${com.messenger.app.data.api.ServerEndpoint.current()}|${it.user.id}|${auth.currentDeviceId()}|${auth.currentSessionEpoch()}"
    }
