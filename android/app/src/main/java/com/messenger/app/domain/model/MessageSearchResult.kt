package com.messenger.app.domain.model

/** A single search hit from local (on-device) FTS. */
data class MessageSearchResult(
    val messageId: String,
    val chatId: String,
    val senderId: String,
    val sequence: Long?,
    val createdAt: String,
    /** Effective decrypted text (snippet computed by the caller). */
    val text: String,
)
