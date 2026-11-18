package com.messenger.app.ui.chats

import com.messenger.app.domain.model.MessageStatus

/** The renderer's live history belongs to one uninterrupted visible interval. */
internal class ReceiptInkObservation(
    status: MessageStatus,
    visibility: ReceiptVisibility,
    private val now: () -> Long,
) {
    data class Change(val from: MessageStatus, val animate: Boolean)

    private var shown = status
    private var active = visibility.active
    private var visibilityEpoch = visibility.epoch
    private var bornAt = now()
    private var watchedSending = false

    fun isCurrent(active: Boolean, visibilityEpoch: Long): Boolean =
        this.active == active && this.visibilityEpoch == visibilityEpoch

    fun observe(status: MessageStatus, visibility: ReceiptVisibility, calm: Boolean, stamp: ReceiptStatusStamp?): Change {
        val active = visibility.active
        val visibilityEpoch = visibility.epoch
        val from = shown
        shown = status
        if (!isCurrent(active, visibilityEpoch)) {
            this.active = active
            this.visibilityEpoch = visibilityEpoch
            bornAt = now()
            watchedSending = false
            // This first snapshot may contain changes made while the timeline was frozen.
            return Change(from, false)
        }
        if (!active) {
            watchedSending = false
            return Change(from, false)
        }
        if (from == status) return Change(from, false)
        // A later composition cannot turn a background/unfreeze arrival into a live send.
        if (stamp == null || stamp.status != status || !stamp.isLive(visibility) ||
            stamp.observedAtMillis < bornAt || stamp.observedAtMillis > now()) {
            watchedSending = false
            return Change(from, false)
        }
        if (from == MessageStatus.PENDING) watchedSending = true
        return Change(from, ReceiptInkPolicy.animates(from, status,
            stamp.observedAtMillis - bornAt, active, calm, watchedSending))
    }
}
