package com.messenger.app.ui.media

import android.content.Intent
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.messenger.app.ui.chats.MediaViewerDialog
import com.messenger.app.ui.chats.MediaViewerItem
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.messenger.app.R
import com.messenger.app.domain.model.MediaItem
import java.io.File

@Composable
fun MediaGalleryScreen(viewModel: MediaGalleryViewModel, onBack: () -> Unit, onViewInChat: (String) -> Unit) {
    val colors=LocalCocoColors.current
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var preview by remember { mutableStateOf<MediaViewerItem?>(null) }

    // Open the decrypted file via the SAME FileProvider as everywhere else,
    // with the REAL stored MIME type (BUG 4) - fall back to octet-stream only
    // when the MIME is absent/blank (shouldn't happen, but be safe).
    LaunchedEffect(uiState.openedFile) {
        uiState.openedFile?.let { file ->
            val mimeType = uiState.openedFileMimeType
                ?.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"
            val id = uiState.openedAttachmentId ?: file.path
            val item = uiState.items.firstOrNull { it.attachmentId == id }
            preview = MediaViewerItem(file, mimeType, item?.filename ?: file.name, id, item?.thumbnailBase64)
            viewModel.clearOpenedFile()
        }
    }

    preview?.let { initial ->
        val pages = remember(uiState.items, initial) { galleryViewerPages(uiState.items, initial) }
        initial.file?.let { MediaViewerDialog(pages, it, download = viewModel::downloadMediaForViewer) { preview = null } }
    }
    com.messenger.app.ui.theme.CocoScreenSurface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
            com.messenger.app.ui.components.CocoPageHeader(
                title = stringResource(R.string.media_title),
                start = { com.messenger.app.ui.components.CocoBackButton(onClick = onBack) },
            )
            TabRow(selectedTabIndex = viewModel.currentTab.ordinal,
                modifier=Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),containerColor=colors.surface,
                contentColor=colors.primaryText) {
                MediaTab.entries.forEach { tab ->
                    Tab(selected = viewModel.currentTab == tab, onClick = { viewModel.selectTab(tab) },
                        text = { Text(stringResource(tabLabel(tab))) })
                }
            }
            if (uiState.loading && uiState.items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.items.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(CocoIcons.Photo, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(if (uiState.error != null) R.string.gallery_load_failed else R.string.gallery_empty),
                        modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                    if (uiState.error != null) TextButton(onClick = viewModel::loadPage) { Text(stringResource(R.string.retry)) }
                }
            } else {
                when (viewModel.currentTab) {
                    MediaTab.Photos -> PhotoGrid(uiState.items, viewModel, onViewInChat, Modifier.weight(1f))
                    MediaTab.Files, MediaTab.Voice -> MediaList(viewModel.currentTab, uiState.items, viewModel, onViewInChat, Modifier.weight(1f))
                }
            }
            if (uiState.openingId != null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (uiState.error != null && uiState.items.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(20.dp), color = colors.surface) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(stringResource(if (uiState.error == "Download failed.") R.string.gallery_open_failed else R.string.gallery_load_failed),color=colors.danger)
                        TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.dismiss)) }
                    }
                }
            }
        }
    }
}

private fun tabLabel(tab: MediaTab): Int = when (tab) {
    MediaTab.Photos -> R.string.media_photos
    MediaTab.Files -> R.string.media_files
    MediaTab.Voice -> R.string.media_voice
}

@Composable
private fun PhotoGrid(items: List<MediaItem>, viewModel: MediaGalleryViewModel, onViewInChat: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 100.dp), modifier = modifier) {
        items(items, key = { it.attachmentId }) { item ->
            Column(modifier = Modifier.padding(4.dp)) {
                // remember() keyed by the thumbnail content so an unchanged
                // cell never re-decodes Base64 across recompositions (scroll
                // churn / tab switch). A changed thumbnail invalidates the
                // entry; null stays null. Per-composition only - no global cache.
                val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.thumbnailBase64) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { item.thumbnailBase64?.let { decodeThumb(it) } }
                }
                val decoded = bitmap
                if (decoded != null) {
                    Image(
                        bitmap = decoded,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).clickable { viewModel.open(item) },
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { viewModel.open(item) }) { Text(stringResource(R.string.photo_preview), modifier = Modifier.padding(8.dp)) }
                }
                TextButton(onClick = { onViewInChat(item.clientMessageId) }) { Text(stringResource(R.string.media_view_in_chat)) }
            }
        }
    }
}

private fun decodeThumb(base64: String): androidx.compose.ui.graphics.ImageBitmap? {
    if (base64.length > 128 * 1024) return null
    val bytes = runCatching { Base64.decode(base64, Base64.NO_WRAP) }.getOrNull() ?: return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return null
    return runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
}

@Composable
private fun MediaList(tab: MediaTab, items: List<MediaItem>, viewModel: MediaGalleryViewModel, onViewInChat: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors=LocalCocoColors.current
    LazyColumn(modifier = modifier) {
        items(items, key = { it.attachmentId }) { item ->
            Surface(Modifier.fillMaxWidth().padding(vertical=4.dp),shape=RoundedCornerShape(20.dp),color=colors.surface) {
            Column(modifier = Modifier.fillMaxWidth().clickable { viewModel.open(item) }.padding(14.dp)) {
                if (item.mimeType.startsWith("video/")) {
                    val thumbnail by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.thumbnailBase64) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { item.thumbnailBase64?.let { decodeThumb(it) } }
                    }
                    thumbnail?.let { Image(it, contentDescription = null, modifier = Modifier.size(100.dp)) }
                    Text("▶ " + com.messenger.app.data.attachment.FilePresentation.duration(item.durationMs), style = MaterialTheme.typography.labelMedium)
                } else {
                    com.messenger.app.ui.theme.FileTypeBadge(item.mimeType, item.filename)
                }
                val title = when (tab) {
                    MediaTab.Voice -> stringResource(R.string.media_voice) + " · " + com.messenger.app.data.attachment.FilePresentation.duration(item.durationMs)
                    else -> item.filename ?: stringResource(R.string.file_preview)
                }
                Text(title, style = MaterialTheme.typography.bodyLarge,color=colors.primaryText)
                val context = LocalContext.current
                val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                val metadata = remember(item.size, item.createdAt, locale) {
                    val size = android.text.format.Formatter.formatShortFileSize(context, item.size)
                    val date = runCatching {
                        java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                            .withLocale(locale).withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(item.createdAt))
                    }.getOrNull()
                    listOfNotNull(size, date).joinToString(" · ")
                }
                Text(metadata, style = MaterialTheme.typography.bodySmall,color=colors.secondaryText)
                TextButton(onClick = { onViewInChat(item.clientMessageId) }) { Text(stringResource(R.string.media_view_in_chat)) }
            }
            }
        }
    }
}

private fun openFile(context: android.content.Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
