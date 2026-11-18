package com.messenger.app.ui.chats

import com.messenger.app.ui.security.rejectObscuredTouches

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class GalleryEntry(val uri: Uri, val video: Boolean, val duration: Long = 0)

// Camera is an independent "+" action. This picker never claims a
// camera lease or observes live call/capture state. Existing capture arguments
// remain compatible with the current caller and component fixtures.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentGallery(onCamera: () -> Unit, onVideo: () -> Unit, onGallery: () -> Unit, onFile: () -> Unit, onSelected: (List<Uri>) -> Unit, selected: List<Uri>, onSelection: (List<Uri>) -> Unit, cameraReady: Boolean, selectionLimit: Int = 10,
    caption: String = "", onCaptionChange: (String) -> Unit = {}, onClose: () -> Unit = {},
    onSendNow: (List<Uri>) -> Unit = onSelected, onFileSendNow: () -> Unit = onFile,
    initialFilesTab: Boolean = false, onFilesTabChange: (Boolean) -> Unit = {},
    initialFileSelection: List<String> = emptyList(), onFileSelection: (List<String>) -> Unit = {},
    onFilesSelected: (List<RecentFile>, Boolean) -> Boolean = { _, _ -> false }, owner: String? = null, epoch: Int = 0,
    recentFilesLoader: (suspend () -> List<RecentFile>)? = null,
    onPickedMedia: ((List<Uri>, String, Boolean) -> Boolean)? = null,
    onGalleryMode: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val colors = LocalCocoColors.current
    val pickerInk = Color(AttachmentPickerStyle.foreground)
    val pickerAccent = Color(AttachmentPickerStyle.accent)
    val pickerSurface = Color(AttachmentPickerStyle.background)
    val tileShape = RoundedCornerShape(AttachmentPickerStyle.tileRadius.dp)
    var revision by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<GalleryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<GalleryEntry?>(null) }
    var limitReached by remember { mutableStateOf(false) }
    var limit by remember { mutableIntStateOf(300) }
    var album by remember { mutableStateOf<Pair<String, String>?>(null) }
    var albums by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var albumMenu by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    var sendNowMenu by remember { mutableStateOf(false) }
    var filesTab by rememberSaveable(owner, epoch) { mutableStateOf(initialFilesTab) }
    var fileSelection by rememberSaveable(owner, epoch) { mutableStateOf(initialFileSelection) }
    var recentFiles by remember(owner, epoch) { mutableStateOf<List<RecentFile>>(emptyList()) }
    var filesLoading by remember { mutableStateOf(false) }
    var filesFailed by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val qualityPreferences = remember(context) { context.getSharedPreferences("media-quality", Context.MODE_PRIVATE) }
    var preferredQuality by rememberSaveable { mutableStateOf(galleryPreferredSendQuality(
        qualityPreferences.getString(GALLERY_SEND_MODE_PREFERENCE, null), qualityPreferences.getString("send", "image"))) }
    var mediaAsFiles by rememberSaveable { mutableStateOf(false) }
    val pickerMode = GalleryPickerMode(preferredQuality, mediaAsFiles)
    val quality = pickerMode.quality
    fun openSystemGallery() { if (onGalleryMode != null) onGalleryMode(quality) else onGallery() }
    fun switchTab(files: Boolean) { filesTab = files; onFilesTabChange(files) }
    LaunchedEffect(filesTab, revision, owner, epoch) {
        if (filesTab) {
            filesLoading = true; filesFailed = false
            try { recentFiles = recentFilesLoader?.invoke() ?: loadAccountRecentFiles(context, owner, epoch) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { recentFiles = emptyList(); filesFailed = true }
            finally { filesLoading = false }
        }
    }
    val activeCount = if (filesTab) fileSelection.size else selected.size
    fun commit(sendNow: Boolean) {
        if (committing || activeCount == 0) return
        if (activeCount > selectionLimit.coerceIn(0, 10)) { limitReached = true; return }
        if (!filesTab) {
            committing = true
            if (onPickedMedia != null) { if (!onPickedMedia(selected, quality, sendNow)) committing = false }
            else if (sendNow) onSendNow(selected) else onSelected(selected)
            return
        }
        committing = true
        scope.launch {
            var accepted = false
            try {
                val chosen = fileSelection.mapNotNull { id -> recentFiles.find { it.id == id } }
                val available = withContext(Dispatchers.IO) { chosen.filter { java.io.File(it.path).isFile } }
                if (available.size != fileSelection.size) {
                    fileSelection = available.map { it.id }; onFileSelection(fileSelection); revision++
                    android.widget.Toast.makeText(context, R.string.composer_source_unavailable, android.widget.Toast.LENGTH_LONG).show()
                } else accepted = onFilesSelected(chosen, sendNow)
            } finally { if (!accepted) committing = false }
        }
    }
    val toggle: (Uri) -> Unit = { uri ->
        val next = toggleAttachmentSelection(selected, uri, selectionLimit)
        if (next == selected) limitReached = true
        else { CocoHaptics.select(hapticView); onSelection(next) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    val access = remember(revision) { permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
    val fullAccess = remember(revision) { permissions.take(if (Build.VERSION.SDK_INT >= 33) 2 else 1).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
    LaunchedEffect(filesTab, revision, access, album, limit) {
        if (filesTab) return@LaunchedEffect
        loading = true
        entries = if (access) withContext(Dispatchers.IO) { loadGallery(context, album?.first, limit) } else emptyList()
        loading = false
    }
    LaunchedEffect(filesTab, revision, access) { if (!filesTab) albums = if (access) withContext(Dispatchers.IO) { loadAlbums(context) } else emptyList() }
    val grid = rememberLazyGridState()
    if (limitReached) AlertDialog(onDismissRequest = { limitReached = false },
        text = { Text(stringResource(R.string.gallery_limit)) },
        confirmButton = { TextButton(onClick = { CocoHaptics.tap(hapticView); limitReached = false }) { Text(stringResource(android.R.string.ok)) } })
    preview?.let { entry -> GalleryItemPreview(entry, entry.uri in selected, { toggle(entry.uri) }, { preview = null }) }
    Column(Modifier.fillMaxWidth().fillMaxHeight().testTag("attachment-picker")) {
        Box(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(30.dp, 4.dp).background(pickerInk.copy(alpha = .28f), CircleShape))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { if (!filesTab) { CocoHaptics.tap(hapticView); albumMenu = true } }, enabled = !filesTab, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (filesTab) stringResource(R.string.media_files) else album?.second ?: stringResource(R.string.gallery_media), color = pickerInk, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)); if (!filesTab) { Spacer(Modifier.width(6.dp)); Text("⌄", color = pickerInk.copy(alpha = .7f)) } }
            DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }, containerColor = pickerSurface, shape = RoundedCornerShape(18.dp)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.gallery_recent)) }, onClick = { CocoHaptics.select(hapticView); album = null; limit = 300; albumMenu = false })
                if (selected.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.gallery_system)) }, onClick = { CocoHaptics.tap(hapticView); albumMenu = false; openSystemGallery() })
                albums.forEach { item -> DropdownMenuItem(text = { Text(item.second) }, onClick = { CocoHaptics.select(hapticView); album = item; limit = 300; albumMenu = false }) }
            }
        }
        if (activeCount > 0) Text("$activeCount/${selectionLimit.coerceIn(0, 10)}", color = pickerInk.copy(alpha = .7f), style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = { CocoHaptics.tap(hapticView); onClose() }, modifier = Modifier.size(48.dp).testTag("attachment-picker-close")) { Icon(CocoIcons.Close, stringResource(R.string.cancel), tint = pickerInk, modifier = Modifier.size(20.dp)) }
        }
        if (!filesTab) BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 7.dp).testTag("attachment-picker-send-mode")) {
            val modeLabel = stringResource(when (quality) {
                "file" -> R.string.gallery_send_files
                "image_high" -> R.string.gallery_send_hd
                else -> R.string.gallery_send_media
            })
            val modeMenu: @Composable () -> Unit = {
                Box {
                    TextButton(onClick = { CocoHaptics.tap(hapticView); qualityMenu = true },
                        modifier = Modifier.heightIn(min = 44.dp).testTag("attachment-picker-send-options")
                            .semantics { stateDescription = modeLabel },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = pickerInk.copy(alpha = .05f), contentColor = pickerAccent)) {
                        Icon(if (quality == "file") CocoIcons.File else CocoIcons.Image, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(modeLabel, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp)); Text("⌄", color = pickerInk.copy(alpha = .7f))
                    }
                    DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false },
                        containerColor = pickerSurface, shape = RoundedCornerShape(18.dp)) {
                        listOf("image", "image_high", "file").forEach { key ->
                            val title = stringResource(when (key) {
                                "file" -> R.string.gallery_send_files
                                "image_high" -> R.string.gallery_send_hd
                                else -> R.string.gallery_send_media
                            })
                            DropdownMenuItem(text = { Column {
                                Text(title, color = if (key == quality) pickerAccent else pickerInk)
                                Text(stringResource(when (key) {
                                    "file" -> R.string.gallery_send_files_detail
                                    "image_high" -> R.string.gallery_send_hd_detail
                                    else -> R.string.gallery_send_media_detail
                                }), style = MaterialTheme.typography.bodySmall, color = pickerInk.copy(alpha = .7f))
                            } }, trailingIcon = { if (quality == key) Icon(CocoIcons.Check, null, tint = pickerAccent) },
                                modifier = Modifier.testTag("attachment-picker-mode-$key").semantics { this.selected = quality == key },
                                onClick = {
                                    CocoHaptics.select(hapticView); qualityMenu = false
                                    val choice = pickerMode.choose(key) { saved ->
                                        qualityPreferences.edit().putString("send", saved).putString(GALLERY_SEND_MODE_PREFERENCE, saved).apply()
                                    }
                                    preferredQuality = choice.preferred; mediaAsFiles = choice.mediaAsFiles
                                })
                        }
                        if (selected.isNotEmpty()) {
                            HorizontalDivider(color = pickerInk.copy(alpha = .1f))
                            DropdownMenuItem(text = { Text(stringResource(R.string.gallery_clear), color = pickerInk) }, onClick = {
                                CocoHaptics.select(hapticView); onSelection(emptyList()); qualityMenu = false
                            })
                        }
                    }
                }
            }
            if (maxWidth < 310.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.gallery_send_as), style = MaterialTheme.typography.labelMedium, color = pickerInk.copy(alpha = .65f))
                    modeMenu()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.gallery_send_as), style = MaterialTheme.typography.labelMedium, color = pickerInk.copy(alpha = .65f))
                    modeMenu()
                }
            }
        }
        if (filesTab) RecentFilesTab(recentFiles, fileSelection, filesLoading, filesFailed,
            onToggle = { id ->
                val next = toggleAttachmentSelection(fileSelection, id, selectionLimit)
                if (next == fileSelection) limitReached = true
                else { CocoHaptics.select(hapticView); fileSelection = next; onFileSelection(next) }
            }, onChooseFiles = onFile, onMediaAsFiles = {
                CocoHaptics.select(hapticView); mediaAsFiles = pickerMode.asFiles().mediaAsFiles
                switchTab(false)
            }, onRetry = { revision++ }, modifier = Modifier.weight(1f))
        else {
        if (!fullAccess) TextButton(onClick = {
            CocoHaptics.tap(hapticView)
            if (!access && permissionRequested) {
                val settings = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(settings) }.onFailure { openSystemGallery() }
            } else { permissionRequested = true; request.launch(permissions) }
        }, modifier = Modifier.rejectObscuredTouches().fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.gallery_access), color = pickerAccent, style = MaterialTheme.typography.labelMedium) }
        LazyVerticalGrid(columns = GridCells.Fixed(AttachmentPickerStyle.columns), state = grid, modifier = Modifier.weight(1f).testTag("attachment-picker-grid"), contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(AttachmentPickerStyle.gap.dp), verticalArrangement = Arrangement.spacedBy(AttachmentPickerStyle.gap.dp)) {
            itemsIndexed(selected.filter { uri -> uri.authority != "media" && entries.none { it.uri == uri } }.map { GalleryEntry(it, false) } + entries, key = { _, entry -> entry.uri.toString() }) { index, entry ->
                val number = selected.indexOf(entry.uri) + 1
                val bitmap by produceState<Bitmap?>(null, entry.uri) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(entry.uri, Size(240, 240), null)
                        else if (entry.video) MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, ContentUris.parseId(entry.uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
                        else MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, ContentUris.parseId(entry.uri), MediaStore.Images.Thumbnails.MINI_KIND, null) }.getOrNull() ?: if (!entry.video) runCatching {
                            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            context.contentResolver.openInputStream(entry.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                            options.inSampleSize = 1
                            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 480) options.inSampleSize *= 2
                            options.inJustDecodeBounds = false
                            context.contentResolver.openInputStream(entry.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                        }.getOrNull() else null
                    }
                }
                val previewLabel = stringResource(if (entry.video) R.string.gallery_video_description else R.string.gallery_photo_description, index + 1)
                val selectionLabel = stringResource(if (number > 0) R.string.gallery_deselect else R.string.gallery_select)
                Box(Modifier.aspectRatio(1f).clip(tileShape).background(pickerInk.copy(alpha = .08f)).semantics { contentDescription = previewLabel }
                    .clickable(onClickLabel = previewLabel) { CocoHaptics.tap(hapticView); preview = entry }) {
                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (entry.video) Text(com.messenger.app.data.attachment.FilePresentation.duration(entry.duration), color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .6f)).padding(6.dp))
                    Box(Modifier.align(Alignment.TopEnd).size(48.dp).semantics { contentDescription = selectionLabel; this.selected = number > 0; role = Role.Checkbox }.clickable(onClickLabel = selectionLabel) { toggle(entry.uri) }, contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, border = BorderStroke(1.dp, if (number > 0) pickerAccent else pickerInk.copy(alpha = .85f)), color = if (number > 0) pickerAccent else Color.Black.copy(alpha = .35f), modifier = Modifier.size(26.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(if (number > 0) number.toString() else "", color = pickerInk) }
                    }
                    }
                }
            }
            if (entries.size >= limit) item(span = { GridItemSpan(AttachmentPickerStyle.columns) }) { TextButton(onClick = { CocoHaptics.tap(hapticView); limit += 300 }) { Text(stringResource(R.string.gallery_load_more), color = pickerAccent) } }
            if (entries.isEmpty()) item(span = { GridItemSpan(AttachmentPickerStyle.columns) }) {
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(color = pickerAccent) else Text(stringResource(if (access) R.string.search_no_results else R.string.gallery_access_hint), Modifier.padding(12.dp), color = pickerInk.copy(alpha = .7f))
                }
            }
        }
        }
        val footerShape = RoundedCornerShape(23.dp)
        val glass = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF343434).copy(alpha = .92f), Color(0xFF252525).copy(alpha = .92f)))
        Row(Modifier.fillMaxWidth().testTag("attachment-picker-caption").padding(start = 12.dp, end = 12.dp, top = 8.dp)
            .clip(footerShape).background(glass).border(.5.dp, pickerInk.copy(alpha = .10f), footerShape)
            .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val captionLabel = stringResource(R.string.picker_caption_optional)
            androidx.compose.foundation.text.BasicTextField(value = caption, onValueChange = onCaptionChange,
                modifier = Modifier.weight(1f).heightIn(min = 46.dp).padding(vertical = 12.dp).testTag("attachment-picker-caption-input")
                    .semantics { contentDescription = captionLabel }, maxLines = 4,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = pickerInk, fontSize = 16.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(pickerAccent),
                decorationBox = { field -> Box(contentAlignment = Alignment.CenterStart) { if (caption.isEmpty()) Text(captionLabel, color = pickerInk.copy(alpha = .45f), fontSize = 16.sp); field() } })
            Box {
                val sendNowLabel = stringResource(R.string.gallery_send_now)
                val canCommit = activeCount > 0 && !committing && (!filesTab || !filesLoading && !filesFailed)
                val addLabel = if (activeCount == 0) stringResource(R.string.gallery_add) else stringResource(R.string.gallery_add_count, activeCount)
                Box(Modifier.heightIn(min = 48.dp).testTag("attachment-picker-add")
                    .semantics { customActions = listOf(CustomAccessibilityAction(sendNowLabel) { if (canCommit) commit(true); canCommit }) }
                    .combinedClickable(enabled = canCommit, role = Role.Button, onClick = { CocoHaptics.tap(hapticView); commit(false) },
                        onLongClick = { CocoHaptics.hold(hapticView); sendNowMenu = true }, onLongClickLabel = sendNowLabel)
                    .padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
                    Text(addLabel, color = if (canCommit) Color.Black else pickerInk.copy(alpha = .45f), style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.heightIn(min = 36.dp).clip(CircleShape)
                            .background(if (canCommit) Color.White else pickerInk.copy(alpha = .10f))
                            .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 14.dp))
                }
                DropdownMenu(expanded = sendNowMenu, onDismissRequest = { sendNowMenu = false }, containerColor = pickerSurface) {
                    DropdownMenuItem(text = { Text(sendNowLabel) }, enabled = canCommit, modifier = Modifier.testTag("attachment-picker-send-now"),
                        onClick = { sendNowMenu = false; commit(true) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().testTag("attachment-picker-tabs").padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(CircleShape).background(glass).border(.5.dp, pickerInk.copy(alpha = .10f), CircleShape).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            listOf(false, true).forEach { files ->
                val label = stringResource(if (files) R.string.media_files else R.string.gallery_media)
                Row(Modifier.weight(1f).heightIn(min = 40.dp).clip(CircleShape)
                    .background(if (filesTab == files) pickerInk.copy(alpha = .14f) else Color.Transparent)
                    .testTag(if (files) "attach-tab-files" else "attach-tab-gallery")
                    .selectable(selected = filesTab == files, role = Role.Tab, onClick = { CocoHaptics.select(hapticView); switchTab(files) })
                    .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    val tint = pickerInk.copy(alpha = if (filesTab == files) 1f else .55f)
                    Icon(if (files) CocoIcons.File else CocoIcons.StackedPhotos, null, Modifier.size(17.dp), tint = tint)
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = tint, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun loadGallery(context: Context, album: String?, limit: Int): List<GalleryEntry> = runCatching {
    val result = ArrayList<GalleryEntry>()
    val collection = MediaStore.Files.getContentUri("external")
    context.contentResolver.query(collection, arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Video.Media.DURATION),
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)" + if (album == null) "" else " AND bucket_id = ?", if (album == null) arrayOf("1", "3") else arrayOf("1", "3", album), "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { cursor ->
        while (cursor.moveToNext() && result.size < limit) {
            val video = cursor.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            result += GalleryEntry(ContentUris.withAppendedId(base, cursor.getLong(0)), video, cursor.getLong(2))
        }
    }
    result
}.getOrDefault(emptyList())

private fun loadAlbums(context: Context): List<Pair<String, String>> = runCatching {
    val result = linkedMapOf<String, String>()
    context.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf("bucket_id", "bucket_display_name"),
        "media_type IN (1,3)", null, "date_added DESC")?.use { cursor ->
        while (cursor.moveToNext()) { val id = cursor.getString(0); val name = cursor.getString(1); if (id != null && !name.isNullOrBlank()) result[id] = name }
    }
    result.map { it.key to it.value }
}.getOrDefault(emptyList())

@Composable
private fun GalleryItemPreview(entry: GalleryEntry, selected: Boolean, toggle: () -> Unit, close: () -> Unit) {
    val colors=LocalCocoColors.current
    val context = LocalContext.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    var failed by remember(entry.uri) { mutableStateOf(false) }
    var video by remember { mutableStateOf<android.widget.VideoView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) video?.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); video?.stopPlayback() }
    }
    val bitmap by produceState<Bitmap?>(null, entry.uri) {
        if (!entry.video) value = withContext(Dispatchers.IO) { runCatching {
            if (Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, entry.uri)) { decoder, info, _ ->
                val scale = minOf(1f, 1600f / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            } else {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(entry.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                val decoded = context.contentResolver.openInputStream(entry.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                val orientation = runCatching { context.contentResolver.openInputStream(entry.uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } }.getOrNull()
                val matrix = android.graphics.Matrix()
                when (orientation) {
                    2 -> matrix.setScale(-1f, 1f)
                    3 -> matrix.setRotate(180f)
                    4 -> matrix.setScale(1f, -1f)
                    5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                    6 -> matrix.setRotate(90f)
                    7 -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
                    8 -> matrix.setRotate(270f)
                }
                if (decoded != null && !matrix.isIdentity) android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() } else decoded
            }
        }.getOrNull() }.also { failed = it == null }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxWidth().height((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 96).coerceAtLeast(240).dp), color = Color.Black) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                AttachmentPanelHeader(stringResource(if (entry.video) R.string.media_video else R.string.photo_preview), { CocoHaptics.tap(hapticView); close() })
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (entry.video) androidx.compose.ui.viewinterop.AndroidView(factory = { ctx -> android.widget.VideoView(ctx).apply {
                        video = this
                        setVideoURI(entry.uri)
                        setMediaController(android.widget.MediaController(ctx).also { it.setAnchorView(this) })
                        setOnPreparedListener { seekTo(1) }
                        setOnErrorListener { _, _, _ -> failed = true; true }
                    } }, modifier = Modifier.fillMaxSize(), onRelease = { it.stopPlayback(); video = null })
                    else bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    if (failed) Text(stringResource(R.string.gallery_preview_failed), color = Color.White)
                    else if (!entry.video && bitmap == null) CircularProgressIndicator()
                }
                AttachmentPanelButton(stringResource(if (selected) R.string.gallery_deselect else R.string.gallery_select), if (selected) CocoIcons.Close else CocoIcons.Image, if (selected) colors.categoryRose else colors.categoryBlue, toggle, modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
        }
    }
}
