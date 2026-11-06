package com.messenger.app.domain.repository

import com.messenger.app.domain.model.AuthSessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

private val noCacheCleanupFailure = MutableStateFlow(false)
data class HistoryContext(val beforeSequence: Long, val beforeDate: String)
private val noHistoryContext = MutableStateFlow<HistoryContext?>(null)

/**
 * Abstraction over authentication so the UI layer never talks to the HTTP
 * client, token storage, or refresh logic directly.
 */
interface AuthRepository {
    val authState: StateFlow<AuthSessionState>
    val cacheCleanupFailed: StateFlow<Boolean> get() = noCacheCleanupFailure
    val needsHistoryChoice: StateFlow<Boolean> get() = noCacheCleanupFailure
    suspend fun startWithoutRestoredHistory(): Result<Unit> = Result.success(Unit)

    suspend fun register(username: String, password: String, displayName: String): Result<Unit>
    suspend fun login(username: String, password: String): Result<Unit>
    suspend fun logout()
    suspend fun deleteAccount(password: String): Result<Unit> = Result.failure(UnsupportedOperationException())

    /** Returns a currently-valid access token, transparently refreshing it if needed. */
    suspend fun currentAccessToken(): String?

    /** The stable, app-generated id of *this* device (matches `devices.id`
     *  server-side) - needed to exclude the sending device itself when
     *  building multi-device echo envelopes. Not a secret; safe to expose
     *  outside the token-storage abstraction. */
    fun currentDeviceId(): String?
    fun historyBeforeSequence(): Long = 0L
    val historyContext: StateFlow<HistoryContext?> get() = noHistoryContext
    suspend fun reconcileHistoryContext() {}

    /** Monotonically increases on every successful login/register AND on
     *  every logout - never on token refresh (same session, same epoch).
     *  Long-running coroutines (global sync, message ingestion) capture
     *  this at the start of an operation and MUST re-check it before any
     *  durable write; a mismatch means the account/session changed since
     *  the operation started (e.g. a stale response arriving after logout)
     *  and the write must be silently discarded, never applied. */
    fun currentSessionEpoch(): Int

    /** P0 hardening: runs [block] only if the session epoch still equals
     *  [epochAtStart], atomically under a mutex shared with [clearRoom].
     *  Returns null (and never runs [block]) if the session changed. Every
     *  durable Room write in a long-running account job MUST go through this
     *  so a stale old-account write is rejected before the DAO write. */
    suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T?

    /** P0 hardening: runs [block] (the Room clear after logout) under the
     *  same mutex as [withSessionWrite]. Call it only AFTER the epoch has
     *  been bumped, so any in-flight write finishes first and every later
     *  write is rejected - zero stale old-account rows can survive. */
    /** Local workspace writes never wait for an outbox network operation. */
    suspend fun <T> withWorkspaceWrite(epochAtStart: Int, block: suspend () -> T): T? = withSessionWrite(epochAtStart, block)

    /** Media work must not hold the message write gate; logout drains both. */
    suspend fun <T> withMediaWrite(epochAtStart: Int, block: suspend () -> T): T? = withSessionWrite(epochAtStart, block)

    suspend fun clearRoom(block: suspend () -> Unit)
}
