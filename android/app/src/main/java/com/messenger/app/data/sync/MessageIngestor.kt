package com.messenger.app.data.sync

import com.messenger.app.data.api.MessageDto

/**
 * The subset of [MessageIngestionPipeline] that [GlobalSyncManager] depends
 * on - kept narrow so the sync manager's pagination/mutex/session-abort
 * logic is unit-testable with a fake ingestor instead of real Signal
 * Protocol decryption.
 */
interface MessageIngestor {
    suspend fun recoverRetainedAfterSync() {}
    suspend fun deleteChats(chatIds: List<String>): Boolean = chatIds.isEmpty()
    suspend fun ingest(dtos: List<MessageDto>, advanceCursor: Boolean = true): IngestResult

    data class IngestResult(
        /** False if a session switch was detected mid-batch - the caller
         *  aborted early; the durable cursor only reflects what was
         *  processed before the switch. */
        val advancedFully: Boolean,
        val highestSequence: Long,
    )
}
