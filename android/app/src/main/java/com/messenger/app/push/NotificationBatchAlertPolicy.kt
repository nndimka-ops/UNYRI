package com.messenger.app.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Local presentation metadata only. It is never added to FCM or the wire DTO. */
data class NotificationBatchSession(val server: String, val owner: String, val epoch: Int)

data class NotificationMessageMetadata(
    val serverId: String?, val chatId: String, val senderId: String, val sequence: Long?,
    val albumId: String?, val kind: String, val status: String,
    val hidden: Boolean, val deleted: Boolean,
) {
    internal fun isReadableFor(event: PushEvent.NewMessage, session: NotificationBatchSession): Boolean =
        serverId == event.messageId && chatId == event.chatId && sequence == event.sequence.toLongOrNull() &&
            sequence != null && sequence > 0 && senderId.isNotBlank() && senderId != session.owner &&
            kind == "content" && status in setOf("sent", "delivered", "read") && !hidden && !deleted

    internal fun batch(): NotificationBatchKey? = albumId?.takeIf { it.isNotBlank() && it.length <= 256 }
        ?.let { NotificationBatchKey(chatId, senderId, it) }
}

internal data class NotificationBatchKey(val chatId: String, val senderId: String, val albumId: String)

/** Bounded, session-scoped first-alert state. No time-window guessing: two
 * independent messages or two distinct batches always retain their own alert.
 * Like the existing sequence ledger this lasts for the current process only. */
internal class NotificationBatchAlertLedger(private val capacity: Int = 512) {
    init { require(capacity > 0) }
    private var session: NotificationBatchSession? = null
    private val batches = LinkedHashSet<NotificationBatchKey>()
    private val posted = LinkedHashSet<String>()

    private fun prepare(value: NotificationBatchSession) {
        if (session != value) { clear(); session = value }
    }
    private fun <T> remember(set: LinkedHashSet<T>, value: T) {
        set.remove(value); set.add(value)
        while (set.size > capacity) set.remove(set.first())
    }
    fun learnResolved(messageId: String, key: NotificationBatchKey?, value: NotificationBatchSession) {
        prepare(value)
        if (messageId in posted && key != null) remember(batches, key)
    }
    fun shouldAlert(messageId: String, key: NotificationBatchKey?, value: NotificationBatchSession): Boolean {
        prepare(value)
        return messageId !in posted && (key == null || key !in batches)
    }
    fun recordPosted(messageId: String, key: NotificationBatchKey?, value: NotificationBatchSession) {
        prepare(value); remember(posted, messageId)
        if (key != null) remember(batches, key)
    }
    fun clear() { session = null; batches.clear(); posted.clear() }
}

internal sealed interface NotificationMetadataResolution {
    data class Readable(val metadata: NotificationMessageMetadata) : NotificationMetadataResolution
    data object Suppress : NotificationMetadataResolution
    data object Unresolved : NotificationMetadataResolution
}

/** Waits only for the exact persisted server message. The caller supplies the
 * existing shared sync trigger; this class never decrypts or changes cursors. */
internal class NotificationMetadataResolver(
    private val current: () -> NotificationBatchSession?,
    private val load: suspend (String) -> NotificationMessageMetadata?,
    private val requestCatchUp: () -> Unit,
    private val timeoutMillis: Long = 2500,
    private val intervalMillis: Long = 100,
) {
    init { require(timeoutMillis in 1..3000 && intervalMillis > 0) }
    suspend fun resolve(event: PushEvent.NewMessage, expected: NotificationBatchSession): NotificationMetadataResolution {
        fun valid() = current() == expected && (event.userId == null || event.userId == expected.owner)
        if (!valid()) return NotificationMetadataResolution.Suppress
        fun classify(row: NotificationMessageMetadata): NotificationMetadataResolution =
            if (valid() && row.isReadableFor(event, expected)) NotificationMetadataResolution.Readable(row)
            else NotificationMetadataResolution.Suppress
        suspend fun lookup(): NotificationMessageMetadata? = try { load(event.messageId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        val resolved = withTimeoutOrNull(timeoutMillis) {
            lookup()?.let { return@withTimeoutOrNull classify(it) }
            if (!valid()) return@withTimeoutOrNull NotificationMetadataResolution.Suppress
            requestCatchUp()
            while (valid()) {
                lookup()?.let { return@withTimeoutOrNull classify(it) }
                delay(intervalMillis)
            }
            NotificationMetadataResolution.Suppress
        }
        return resolved ?: if (valid()) NotificationMetadataResolution.Unresolved else NotificationMetadataResolution.Suppress
    }
}
