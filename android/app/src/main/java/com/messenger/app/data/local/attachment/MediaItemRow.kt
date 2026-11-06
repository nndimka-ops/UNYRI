package com.messenger.app.data.local.attachment

/** Projection row for the media gallery - already filtered to content
 *  messages that are neither tombstoned nor locally hidden (M.24). */
data class MediaItemRow(
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
