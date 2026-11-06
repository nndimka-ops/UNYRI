package com.messenger.app.domain.model

/** One gallery item (photo/file/voice), derived from the local Room
 *  projection - never backend media search (M.24). */
data class MediaItem(
    val attachmentId: String,
    val clientMessageId: String,
    val chatId: String,
    val mediaType: String,
    val mimeType: String,
    val filename: String?,
    val size: Long,
    val durationMs: Long?,
    val thumbnailBase64: String?,
    val downloadState: String,
    val localPath: String?,
    val senderId: String,
    val sequence: Long,
    val createdAt: String,
)
