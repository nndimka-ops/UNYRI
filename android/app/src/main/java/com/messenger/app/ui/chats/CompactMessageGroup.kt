package com.messenger.app.ui.chats

/** Presentation only: no grouping of messages, keys, batches or server events. */
internal fun compactMessageGroup(
    previousSender: String?,
    currentSender: String?,
    sameDay: Boolean,
    previousIsMessage: Boolean = true,
    currentIsMessage: Boolean = true,
): Boolean = sameDay && previousIsMessage && currentIsMessage &&
    !previousSender.isNullOrBlank() && previousSender == currentSender
