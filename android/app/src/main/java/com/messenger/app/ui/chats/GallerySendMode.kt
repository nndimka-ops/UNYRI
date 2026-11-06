package com.messenger.app.ui.chats

internal const val GALLERY_SEND_MODE_PREFERENCE = "gallery-send-mode-v1"

/** Only a choice made in the labelled picker may persist originals as a default. */
internal fun galleryPreferredSendQuality(saved: String?, legacy: String?): String = when (saved) {
    "image", "image_high", "file" -> saved
    else -> gallerySendQuality(legacy, false)
}

/** A legacy global "file" preference must not silently turn a gallery album
 * into documents. Originals remain an explicit choice on the current batch. */
internal fun gallerySendQuality(preferred: String?, originals: Boolean): String = when {
    originals -> "file"
    preferred == "image_high" -> "image_high"
    else -> "image"
}

internal fun galleryQueueKind(quality: String, video: Boolean): String = when {
    quality == "file" -> "file"
    video && quality == "image_high" -> "video_high"
    video -> "video"
    quality == "image_high" -> "image_high"
    else -> "image"
}
