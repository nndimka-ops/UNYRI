package com.messenger.app.data.auth

import android.content.Context
import com.messenger.app.BuildConfig
import com.messenger.app.data.api.*
import com.messenger.app.data.secure.SecureTokenStore
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** Called by startup, the existing active refresh cadence and wake events; owns no timer. */
internal class SavedAccountSessions(private val context: Context, private val accounts: AccountWorkspaces) {
    private val storage = AccountWorkspaceContext.installationContext(context)
    private val checkMutex = Mutex()
    private var lastCheckNanos = 0L
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .dns(AndroidSystemDns.create(context)).callTimeout(8, TimeUnit.SECONDS).build()

    suspend fun check(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!checkMutex.tryLock()) return@withContext
        try {
            accounts.resumePendingErasure()
            val now = System.nanoTime()
            if (!force && lastCheckNanos != 0L && now - lastCheckNanos < TimeUnit.SECONDS.toNanos(60)) return@withContext
            lastCheckNanos = now
            for (account in accounts.accounts().filter { it.key != accounts.activeKey }) {
                try {
                    require(ServerEndpoint.normalize(account.origin, BuildConfig.ALLOW_LOCAL_HTTP) == account.origin)
                    val store = SecureTokenStore(AccountWorkspaceContext(storage, account.key))
                    checkSavedAccountSession(refreshAndPersist = { store.refreshMutex.withLock {
                        if (accounts.accounts().none { it.key == account.key && it.origin == account.origin }) return@withLock
                        val token = store.refreshToken() ?: return@withLock
                            val next = refreshDeviceSession(client, ApiJson, account.origin,
                                store.deviceSessionBinding(createForLegacy = true), token, store.refreshRequestId())
                            store.saveTokens(next.accessToken, Instant.parse(next.accessTokenExpiresAt), next.refreshToken)
                    } }, erase = { accounts.removeInactive(account.key, it) })
                } catch (cancel: CancellationException) { throw cancel }
                  catch (_: Exception) { /* Offline and temporary failures preserve this workspace. */ }
            }
        } finally { checkMutex.unlock() }
    }
}

/** Erasure runs after refresh ownership is released, so it can safely drain the same workspace. */
internal suspend fun checkSavedAccountSession(refreshAndPersist: suspend () -> Unit, erase: suspend (String) -> Unit) {
    try { refreshAndPersist() }
    catch (failure: AuthApiException.Api) {
        if (isTerminalAuthFailure(failure)) erase(failure.code) else throw failure
    }
}
