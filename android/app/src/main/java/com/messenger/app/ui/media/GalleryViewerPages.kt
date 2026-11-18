package com.messenger.app.ui.media

import com.messenger.app.domain.model.MediaItem
import com.messenger.app.ui.chats.MediaViewerItem
import java.io.File

/** Keep gallery order and stable attachment IDs, including pages with only an encrypted thumbnail. */
internal fun galleryViewerPages(items: List<MediaItem>, opened: MediaViewerItem): List<MediaViewerItem> {
    if (!opened.mime.startsWith("image/") && !opened.mime.startsWith("video/")) return listOf(opened)
    val pages = items.asSequence()
        .filter { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") }
        .distinctBy { it.attachmentId }
        .map { item ->
            MediaViewerItem(if (item.attachmentId == opened.id) opened.file else item.localPath?.let(::File),
                item.mimeType, item.filename ?: "UNYRI", item.attachmentId, item.thumbnailBase64)
        }.toList()
    // If a concurrent refresh removed the selected item, keep it in isolation.
    // Appending it would invent a new position at the end of an unrelated page set.
    return if (pages.any { it.id == opened.id }) pages else listOf(opened)
}
