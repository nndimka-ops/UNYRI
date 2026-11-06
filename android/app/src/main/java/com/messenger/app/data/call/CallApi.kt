package com.messenger.app.data.call

import com.messenger.app.data.api.CallDto
import com.messenger.app.data.api.IceServerDto

/** Narrow REST surface [com.messenger.app.domain.call.CallController] needs -
 *  kept separate from the concrete OkHttp client (see [CallApiImpl]) so the
 *  controller's state machine is unit-testable with an in-memory fake,
 *  matching the [com.messenger.app.data.sync.MessageIngestor]/
 *  [com.messenger.app.data.sync.SyncCursorStore] testability pattern used
 *  throughout this project. */
interface CallApi {
    suspend fun createCall(calleeUserId: String, mediaKind: String): CallDto
    suspend fun getActiveCall(): CallDto?
    suspend fun getCall(callId: String): CallDto
    suspend fun accept(callId: String): CallDto
    suspend fun reject(callId: String): CallDto
    suspend fun cancel(callId: String): CallDto
    suspend fun hangup(callId: String): CallDto
    suspend fun markConnected(callId: String): CallDto
    suspend fun markFailed(callId: String): CallDto
    /** Re-check after any credential refresh, before submitting old-call cleanup. */
    suspend fun markFailedIfCurrent(callId: String, isCurrent: () -> Boolean): CallDto? =
        if (isCurrent()) markFailed(callId) else null
    suspend fun iceServers(): List<IceServerDto>
}
