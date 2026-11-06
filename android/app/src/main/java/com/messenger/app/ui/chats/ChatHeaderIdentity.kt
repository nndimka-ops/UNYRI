package com.messenger.app.ui.chats

import com.messenger.app.domain.model.ChatSummary

/** Saved Messages has no peer: use its localized identity, never the generic
 * direct-chat fallback or the owner's name. Does not fetch hidden history. */
internal fun ChatSummary.headerTitle(savedTitle: String, fallback: String): String =
    if (type == "saved") savedTitle else otherUser?.displayName ?: otherUser?.username ?: fallback
