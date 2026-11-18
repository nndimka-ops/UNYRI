package com.messenger.app.data.profile

import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.ProfileRepository
import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** App-scoped, bounded refresh work; UI observers never open their own polling loops. */
internal class SelfProfileRefreshCoordinator(
    private val repository: ProfileRepository,
    private val auth: AuthRepository,
    connection: Flow<SocketConnectionState>,
    foreground: Flow<Boolean>,
    scope: CoroutineScope,
    private val origin: () -> String = { com.messenger.app.data.api.ServerEndpoint.current() },
) {
    private data class Request(val owner: SelfProfileOwner, val version: Long?)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val pendingLock = Any()
    private var pending: Request? = null

    private fun owner(): SelfProfileOwner? = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id
        ?.let { SelfProfileOwner(it, auth.currentSessionEpoch(), origin()) }

    init {
        scope.launch {
            auth.authState.map { owner() }.distinctUntilChanged().collect {
                // A login may follow a conflated logout. Clear even when the new user ID is the same.
                repository.clearSessionProfile()
            }
        }
        scope.launch {
            connection.distinctUntilChanged().collect { if (it == SocketConnectionState.CONNECTED) request() }
        }
        scope.launch {
            foreground.distinctUntilChanged().collect { if (it) request() }
        }
        scope.launch {
            for (signal in requests) {
                delay(100) // Coalesce foreground + reconnect + a burst of mutation hints.
                val request = synchronized(pendingLock) { pending.also { pending = null } } ?: continue
                if (owner() != request.owner) continue
                // A hint can overtake an already-running GET. One bounded follow-up fetch handles that race.
                for (attempt in 0..1) {
                    if (owner() != request.owner) break
                    val profile = repository.getMe().getOrNull()
                    if (profile == null || request.version == null || profile.profileVersion >= request.version!!) break
                    if (attempt == 0) delay(200)
                }
            }
        }
    }

    fun changed(userId: String, version: Long) {
        val owner = owner() ?: return
        if (userId == owner.userId && version >= 0) enqueue(Request(owner, version))
    }

    /** Conflate wakeups, not the highest version: a foreground wake or a delayed
     * older hint must never erase a newer committed mutation's target. */
    private fun enqueue(request: Request) {
        synchronized(pendingLock) {
            if (owner() != request.owner) return
            val previous = pending?.takeIf { it.owner == request.owner }
            val version = when {
                previous?.version == null -> request.version
                request.version == null -> previous.version
                else -> maxOf(previous.version, request.version)
            }
            pending = request.copy(version = version)
        }
        requests.trySend(Unit)
    }

    private fun request() { owner()?.let { enqueue(Request(it, null)) } }
}
