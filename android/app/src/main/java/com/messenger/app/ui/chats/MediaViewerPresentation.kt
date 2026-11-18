package com.messenger.app.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.collect
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A single route covers history, tray preview loading, and the editor above either viewer. */
internal sealed interface ChatMediaViewerTarget {
    data class History(val file: File, val mime: String) : ChatMediaViewerTarget
    data class ComposerPhoto(val source: String) : ChatMediaViewerTarget
}

internal data class ViewerEditorRequest(val file: File, val source: String?)

/** Use the resolved/downloaded photo, retaining the durable tray identity for replacement. */
internal fun MediaViewerItem.editorRequest(resolvedFile: File?): ViewerEditorRequest? =
    resolvedFile?.takeIf { mime.startsWith("image/") }?.let { ViewerEditorRequest(it, editorSource) }

internal data class ViewerImagePresentation(val frame: Rect?, val dismissFraction: Float)

/** Keep source upgrades within one item warm, but never carry a picture into another item. */
@Composable internal fun <T> rememberViewerItemPreview(itemId: String, preview: @Composable () -> T): T =
    key(itemId) { preview() }

/** Observe draw-layer state without depending on a recomposition or a frame-polling loop. */
internal suspend fun observeViewerPresentation(read: () -> ViewerImagePresentation,
    publish: (ViewerImagePresentation) -> Unit) {
    snapshotFlow(read).collect { publish(it) }
}

/** A cover such as Markup does not release the photo's paging lock; leaving the page does. */
internal suspend fun observeViewerZoom(scale: () -> Float, publish: (Boolean) -> Unit) {
    try { snapshotFlow { scale() > 1.01f }.collect { publish(it) } }
    finally { publish(false) }
}

internal fun viewerDateLine(createdAt: String?, now: Instant, zone: ZoneId, locale: Locale,
    today: (String) -> String, yesterday: (String) -> String): String? {
    val date = runCatching { Instant.parse(createdAt).atZone(zone) }.getOrNull() ?: return null
    val currentDay = now.atZone(zone).toLocalDate()
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(date)
    return when (date.toLocalDate()) {
        currentDay -> today(time)
        currentDay.minusDays(1) -> yesterday(time)
        else -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(date)
    }
}
