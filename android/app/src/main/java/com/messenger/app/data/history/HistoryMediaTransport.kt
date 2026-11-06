package com.messenger.app.data.history

import kotlinx.serialization.Serializable
import java.io.File

@Serializable data class HistoryMediaStart(val expected_revision: Long, val blob_id: String, val bytes: Long, val sha256: String)
@Serializable data class HistoryMediaRevision(val expected_revision: Long)
@Serializable data class HistoryMediaLink(val blob_id: String, val bytes: Long, val sha256: String, val upload_url: String? = null, val download_url: String? = null, val expires_at: String)
@Serializable data class HistoryRestoreCheck(val message_ids: List<String>, val call_ids: List<String> = emptyList())
@Serializable data class HistoryRestoreDeletion(val id: String, val sequence: Long)
@Serializable data class HistoryRestoreChecked(val deleted: List<HistoryRestoreDeletion>, val deleted_call_ids: List<String>? = null)
@Serializable data class HistorySourceChecked(val complete: Boolean)
interface HistoryMediaTransport {
    suspend fun mediaStart(request: HistoryMediaStart): HistoryMediaLink
    suspend fun mediaSeal(id: String, revision: Long): HistoryMediaLink
    suspend fun mediaGet(id: String): HistoryMediaLink
    suspend fun liveAttachment(id: String): String
    suspend fun receiveMedia(url: String, entry: HistoryMediaEntry, directory: File): File
    suspend fun putMedia(url: String, file: File)
    suspend fun restoreCheck(ids: List<String>): Map<String, Long>
    suspend fun restoreCallCheck(ids: List<String>): Set<String> {
        check(ids.isEmpty()) { "Call restore validation unavailable" }
        return emptySet()
    }
    suspend fun sourceComplete(ids: List<String>): Boolean
    suspend fun sourceComplete(ids: List<String>, callIds: List<String>): Boolean {
        check(callIds.isEmpty()) { "Call source validation unavailable" }
        return sourceComplete(ids)
    }
}
