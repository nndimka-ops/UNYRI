package com.messenger.app.ui.chats

import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

internal data class ReceiptVisibility(val active: Boolean, val epoch: Long, val resumedAtMillis: Long)

/** Updated synchronously by the screen lifecycle observer, independently of Compose frames. */
internal class ReceiptVisibilityState(active: Boolean, private val now: () -> Long) {
    var current = ReceiptVisibility(active, 0, now())
        private set

    fun update(active: Boolean): ReceiptVisibility {
        if (active != current.active) current = ReceiptVisibility(active, current.epoch + 1,
            if (active) now() else current.resumedAtMillis)
        return current
    }
}

internal data class ReceiptRowVisibility(val enabled: Boolean = false, val ids: Set<String> = emptySet())

internal data class ReceiptStatusStamp(
    val status: MessageStatus,
    val observedAtMillis: Long,
    val visibility: ReceiptVisibility,
    val rowVisible: Boolean,
    val changed: Boolean,
) {
    fun isLive(current: ReceiptVisibility): Boolean = changed && rowVisible && visibility.active && current.active &&
        visibility.epoch == current.epoch && observedAtMillis >= current.resumedAtMillis + ReceiptInkPolicy.resumeSettleMillis
}

/** Status arrival is recorded before the timeline can hold an older display snapshot. Main-thread owned. */
internal class ReceiptStatusFeed(val visibility: ReceiptVisibilityState, private val now: () -> Long) {
    private val stamps = mutableMapOf<String, ReceiptStatusStamp>()

    fun stamp(id: String?, status: MessageStatus): ReceiptStatusStamp? = stamps[id]?.takeIf { it.status == status }

    fun record(messages: List<Message>?, rows: ReceiptRowVisibility) {
        if (messages == null) return
        val statuses = messages.associateTo(mutableMapOf()) { MessageReceiptPolicy.messageLiveId(it) to it.status }
        messages.filter { it.albumId != null }.groupBy(MessageReceiptPolicy::albumLiveId).forEach { (id, members) ->
            statuses[id] = MessageReceiptPolicy.albumStatus(members)
        }
        val observedAt = now()
        val visible = visibility.current.let { it.copy(active = it.active && rows.enabled) }
        statuses.forEach { (id, status) ->
            val previous = stamps[id]
            if (previous == null || previous.status != status) {
                stamps[id] = ReceiptStatusStamp(status, observedAt, visible, id in rows.ids, previous != null)
            }
        }
        stamps.keys.retainAll(statuses.keys)
    }
}

/** The same collection supplies rendering and arrival stamps; no extra subscription or timer. */
internal fun Flow<List<Message>?>.withReceiptStatusStamps(feed: ReceiptStatusFeed, rows: () -> ReceiptRowVisibility): Flow<List<Message>?> =
    onEach { feed.record(it, rows()) }
