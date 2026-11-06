package com.messenger.app.domain.repository

import com.messenger.app.domain.model.ConnectionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the transport (WebSocket) so the UI layer never talks to
 * OkHttp directly.
 */
interface ConnectionRepository {
    val connectionState: StateFlow<ConnectionState>
    val log: StateFlow<List<String>>

    fun connect()
    fun sendPing()
    fun disconnect()
}
