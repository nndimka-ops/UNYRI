package com.messenger.app.domain.model

enum class AttachmentMediaType {
    IMAGE, FILE, VOICE;
    companion object {
        /** Local database values are an explicit contract, independent of enum/R8 names. */
        fun fromStored(value: String): AttachmentMediaType = when (value.lowercase(java.util.Locale.ROOT)) {
            "image" -> IMAGE
            "voice" -> VOICE
            else -> FILE
        }
    }
}

enum class AttachmentUploadState {
    PENDING, ENCRYPTING, UPLOADING, UPLOADED, SENDING, SENT, FAILED, CANCELLED;
    companion object {
        /** Unknown/future values must never become a new upload or a false success. */
        fun fromStored(value: String): AttachmentUploadState = when (value.lowercase(java.util.Locale.ROOT)) {
            "pending" -> PENDING
            "encrypting" -> ENCRYPTING
            "uploading" -> UPLOADING
            "uploaded" -> UPLOADED
            "sending" -> SENDING
            "sent" -> SENT
            "cancelled" -> CANCELLED
            else -> FAILED
        }
    }
}

enum class AttachmentDownloadState {
    NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, FAILED, CORRUPT;
    companion object {
        fun fromStored(value: String): AttachmentDownloadState = when (value.lowercase(java.util.Locale.ROOT)) {
            "not_downloaded" -> NOT_DOWNLOADED
            "downloading" -> DOWNLOADING
            "downloaded" -> DOWNLOADED
            "corrupt" -> CORRUPT
            else -> FAILED
        }
    }
}

data class Attachment(
    val attachmentId: String,
    val clientMessageId: String,
    val mediaType: AttachmentMediaType,
    val mimeType: String,
    val filename: String?,
    val size: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val thumbnailBase64: String?,
    val localPath: String?,
    val downloadState: AttachmentDownloadState,
    val uploadState: AttachmentUploadState,
    val progress: Int,
)
