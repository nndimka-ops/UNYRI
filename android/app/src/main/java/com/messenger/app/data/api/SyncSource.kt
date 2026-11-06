package com.messenger.app.data.api

/**
 * The subset of [SyncApi] that [com.messenger.app.data.sync.GlobalSyncManager]
 * depends on - kept narrow so the sync manager is unit-testable with a fake
 * instead of a real network client.
 */
interface SyncSource {
    suspend fun sync(accessToken: String, afterSequence: Long, limit: Int = 100): SyncResponseDto
}
