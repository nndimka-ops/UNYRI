package com.messenger.app.data.auth

import com.messenger.app.data.api.AuthApiException
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface InstallationCredentialStorage {
    fun read(origin: String): String?
    fun write(origin: String, credential: String)
}

/** The deliberate LAN-only development mode carries no installation proof. */
internal fun installationProofRequired(origin: String, allowLocalHttp: Boolean): Boolean {
    val normalized = try { com.messenger.app.data.api.ServerEndpoint.normalize(origin, allowLocalHttp) }
        catch (_: Exception) { throw AuthApiException.Api("secure_connection_required", "Installation protection requires HTTPS") }
    if (URI(normalized).scheme == "https") return true
    // normalize permits HTTP only for an explicitly allowed local address.
    check(allowLocalHttp)
    return false
}

/** A server-issued installation proof is not an account token or a device id. */
class InstallationCredentialCoordinator(
    private val storage: InstallationCredentialStorage,
    private val bootstrap: suspend (origin: String) -> String,
    private val bind: suspend (origin: String, accessToken: String, credential: String) -> Unit,
) {
    private val mutex = Mutex()
    private val unsupported = mutableSetOf<String>()
    private val sessionLock = Any()
    private var sessionGeneration = 0L
    private var boundOwner: Pair<String, String>? = null

    suspend fun prepare(origin: String, current: () -> Boolean): String? = withContext(Dispatchers.IO) {
        mutex.withLock { prepareLocked(origin, current) }
    }

    suspend fun bindCurrent(origin: String, accessToken: String, owner: String, current: () -> Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireCurrent(current)
            val generation = synchronized(sessionLock) {
                if (boundOwner == (origin to owner)) return@withLock
                sessionGeneration
            }
            val credential = prepareLocked(origin, current) ?: return@withLock
            requireCurrent(current)
            // Once bootstrap succeeded (or a proof is already saved), a missing
            // bind route is a real server error; never reinterpret it as legacy.
            bind(origin, accessToken, credential)
            coroutineContext.ensureActive()
            requireCurrent(current)
            synchronized(sessionLock) {
                check(sessionGeneration == generation) { "Session changed" }
                requireCurrent(current)
                boundOwner = origin to owner
            }
        }
    }

    /** Non-blocking logout cleanup: never wait for the old account's network call. */
    fun clearSessionState() = synchronized(sessionLock) { sessionGeneration++; boundOwner = null }

    private suspend fun prepareLocked(origin: String, current: () -> Boolean): String? {
        requireCurrent(current)
        if (URI(origin).scheme != "https") throw AuthApiException.Api("secure_connection_required", "Installation protection requires HTTPS")
        if (origin in unsupported) return null
        storage.read(origin)?.let { value -> validate(value); return value }
        val value = try { bootstrap(origin) } catch (error: Exception) {
            if (isUnsupported(error)) { unsupported += origin; return null }
            // A network failure/invalid proof is never a legacy capability signal.
            throw error
        }
        validate(value)
        requireCurrent(current)
        // Persist before login/bind. An unsaved proof must not acquire accounts.
        storage.write(origin, value)
        requireCurrent(current)
        return value
    }

    private fun requireCurrent(current: () -> Boolean) {
        check(current()) { "Session changed" }
    }

    companion object {
        internal fun validate(value: String) {
            require(value.length == 43 && value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' } &&
                runCatching { Base64.getUrlDecoder().decode(value).size == 32 }.getOrDefault(false)) { "Invalid installation protection credential" }
        }
        private fun isUnsupported(error: Exception): Boolean = when (error) {
            is AuthApiException.Api -> error.httpStatus in listOf(404, 405)
            is AuthApiException.Network -> error.httpStatus in listOf(404, 405)
            else -> false
        }
    }
}
