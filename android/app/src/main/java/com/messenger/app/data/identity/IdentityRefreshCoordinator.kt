package com.messenger.app.data.identity

import com.messenger.app.domain.repository.SocketConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Existing lifecycle and connection transitions are the only wakeups; no polling or timer. */
internal class IdentityRefreshCoordinator(
    owners: Flow<String?>,
    foreground: Flow<Boolean>,
    connection: Flow<SocketConnectionState>,
    scope: CoroutineScope,
    refreshForeground: suspend (String) -> Unit,
    refreshConnection: suspend (String, Boolean) -> Unit,
) {
    init {
        scope.launch {
            combine(owners, foreground) { owner, active -> owner to active }.distinctUntilChanged()
                .collect { (owner, active) ->
                    if (owner != null && active) refreshSafely { refreshForeground(owner) }
                }
        }
        val connectionRequests = Channel<Pair<String, Boolean>>(Channel.CONFLATED)
        scope.launch {
            var connectedOwner: String? = null
            combine(owners, connection) { owner, state -> owner to state }.distinctUntilChanged()
                .collect { (owner, state) ->
                    if (owner != connectedOwner && state != SocketConnectionState.CONNECTED) connectedOwner = null
                    if (owner != null && state == SocketConnectionState.CONNECTED) {
                        val reconnect = connectedOwner == owner
                        connectedOwner = owner
                        connectionRequests.trySend(owner to reconnect)
                    }
                }
        }
        scope.launch {
            // Continue observing disconnects during HTTP and retain one bounded follow-up request.
            for ((owner, reconnect) in connectionRequests) refreshSafely { refreshConnection(owner, reconnect) }
        }
    }

    private suspend fun refreshSafely(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the last identity on transient failures; retry on a later event. */ }
    }
}
