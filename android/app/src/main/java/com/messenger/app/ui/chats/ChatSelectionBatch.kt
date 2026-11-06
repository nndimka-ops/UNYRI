package com.messenger.app.ui.chats

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ChatSelectionBatchResult(val succeeded: Set<String>, val failed: Set<String>)

/** Partition only summaries that the current device already knows. */
internal object ChatArchiveVisibility {
    fun includes(chatId: String, type: String, archived: Set<String>, showingArchive: Boolean): Boolean {
        if (type == "saved") return false
        val isArchived = type == "direct" && chatId in archived
        return isArchived == showingArchive
    }
}

/** Finite, sequential snapshot: no parallel destructive writes and no retry loop.
 * Success means the existing repository durably accepted the local command.
 * A changed account/server aborts the remaining batch rather than acting as its new owner. */
internal suspend fun runChatSelectionBatch(
    ids: Set<String>,
    isCurrent: () -> Boolean,
    operation: suspend (String) -> Unit,
): ChatSelectionBatchResult {
    val succeeded = linkedSetOf<String>()
    val failed = linkedSetOf<String>()
    for (id in ids.toList()) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Chat selection owner changed")
        try {
            operation(id)
            if (!isCurrent()) throw CancellationException("Chat selection owner changed")
            succeeded += id
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (!isCurrent()) throw CancellationException("Chat selection owner changed")
            failed += id
        }
    }
    return ChatSelectionBatchResult(succeeded, failed)
}
