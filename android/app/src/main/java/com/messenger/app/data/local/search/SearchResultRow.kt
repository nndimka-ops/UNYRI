package com.messenger.app.data.local.search

/** One row returned by an FTS search - never ciphertext, only the
 *  locally-decrypted searchable projection already in Room. */
data class SearchResultRow(
    val clientMessageId: String,
    val chatId: String,
    val senderId: String,
    val sequence: Long?,
    val createdAt: String,
    val text: String,
    val caption: String? = null,
    val filename: String? = null,
    val mediaType: String? = null,
    val mimeType: String? = null,
    val thumbnailBase64: String? = null,
)
