package com.messenger.app.ui.chats

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.messenger.app.data.auth.AccountFileProvider
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors

internal data class MediaViewerItem(val file: File?, val mime: String, val title: String,
    val id: String = file?.path.orEmpty(), val thumbnailBase64: String? = null,
    val sender: String? = null, val createdAt: String? = null, val editorSource: String? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
/** Supply onTextInPhoto only when a recognition implementation is available. */
internal fun MediaViewerDialog(items: List<MediaViewerItem>, initialFile: File, download: suspend (String) -> File? = { null }, onForward: ((String) -> Unit)? = null,
    onEditedPhoto: (suspend (com.messenger.app.ui.media.PhotoEditorResult) -> Boolean)? = null,
    onEdit: ((File) -> Unit)? = null, onTextInPhoto: ((File) -> Unit)? = null, onDismiss: () -> Unit) {
    if (items.isEmpty()) return
    val foreground = rememberViewerForeground()
    val colors=LocalCocoColors.current
    val context = LocalContext.current
    val app = context.applicationContext as com.messenger.app.MessengerApp
    val call by app.callController.state.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    val audioAllowed = when (call) {
        is com.messenger.app.domain.call.CallUiState.OutgoingRinging,
        is com.messenger.app.domain.call.CallUiState.IncomingRinging,
        is com.messenger.app.domain.call.CallUiState.Connecting,
        is com.messenger.app.domain.call.CallUiState.Connected,
        is com.messenger.app.domain.call.CallUiState.Reconnecting -> false
        else -> true
    }
    val activity = remember(context) {
        var current: android.content.Context = context
        while (current is android.content.ContextWrapper && current !is android.app.Activity) current = current.baseContext
        current as? android.app.Activity
    }
    DisposableEffect(activity) {
        val token = Any()
        if (activity != null) com.messenger.app.OrientationLease.acquire(activity, token)
        onDispose { if (activity != null) com.messenger.app.OrientationLease.release(activity, token) }
    }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    // One request pins the resolved file and optional composer identity together.
    var editorRequest by remember { mutableStateOf<ViewerEditorRequest?>(null) }
    val viewerWorkAllowed = foreground && editorRequest == null && !closing
    val transitionSource = remember(initialFile) { MediaSources.get(initialFile.path) }
    val reduceMotion = LocalChatMotionSettings.current?.reduceMotion == true
    fun requestClose() { closing = true }
    val flight by rememberViewerFlight(closing, reduceMotion, onDismiss)
    var photoRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.file == initialFile }.coerceAtLeast(0), pageCount = { items.size })
    // currentPage changes halfway through a held swipe. Keep downloads, gestures
    // and the native decoder on the settled page until that gesture finishes.
    val activePage = pager.settledPage
    val currentItem = items.getOrNull(activePage) ?: items.last()
    val downloaded = remember { mutableStateMapOf<String, File>() }
    val downloadCurrent by rememberUpdatedState(download)
    DisposableEffect(items, downloaded.values.toList()) {
        val files = items.mapNotNull { it.file } + downloaded.values
        val lease = com.messenger.app.data.local.MediaCacheUse.acquire(files)
        files.forEach { com.messenger.app.data.local.MediaCacheUse.touchCache(it) }
        onDispose { lease.close() }
    }
    var loadFailed by remember(currentItem.id) { mutableStateOf(false) }
    var retry by remember(currentItem.id) { mutableIntStateOf(0) }
    val unavailable = remember { mutableStateMapOf<String, Boolean>() }
    val currentFile = downloaded[currentItem.id] ?: currentItem.file?.takeUnless { unavailable[currentItem.id] == true }
    val currentPreview = rememberViewerItemPreview(currentItem.id) {
        rememberMediaViewerPreview(currentFile, currentItem.mime, currentItem.thumbnailBase64, viewerWorkAllowed)
    }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val dateLine = remember(currentItem.createdAt, locale, foreground) {
        viewerDateLine(currentItem.createdAt, java.time.Instant.now(), java.time.ZoneId.systemDefault(), locale,
            { context.getString(R.string.viewer_today_at, it) }, { context.getString(R.string.viewer_yesterday_at, it) })
    }
    LaunchedEffect(currentItem.id, retry, viewerWorkAllowed) {
        if (!viewerWorkAllowed) return@LaunchedEffect
        if (currentFile == null || !withContext(Dispatchers.IO) { currentFile.isFile }) {
            unavailable[currentItem.id] = true; downloaded.remove(currentItem.id)
            loadFailed = false
            try {
                val candidate = downloadCurrent(currentItem.id)
                val file = withContext(Dispatchers.IO) { candidate?.takeIf { it.isFile } }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (file == null) loadFailed = true else downloaded[currentItem.id] = file
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { loadFailed = true }
        }
    }
    var zoomed by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var saveItem by remember { mutableStateOf<MediaViewerItem?>(null) }
    var moreActions by remember { mutableStateOf(false) }
    var saveActions by remember { mutableStateOf(false) }
    fun editCurrent() {
        val request = currentItem.editorRequest(currentFile) ?: return
        moreActions = false; saveActions = false
        if (onEdit != null) onEdit(request.file) else editorRequest = request
    }
    val galleryGate = remember { GalleryPermissionGate() }
    val galleryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> galleryGate.result(granted) }
    suspend fun exportGallery(item: MediaViewerItem) {
        galleryGate.save(galleryNeedsPermission(Build.VERSION.SDK_INT,
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED),
            request = { galleryPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) }) {
            saveMediaToGallery(context, item.file ?: throw java.io.IOException("File unavailable"), item.mime, item.title)
        }
    }
    fun requestGallery(item: MediaViewerItem) { scope.launch {
        try { exportGallery(item) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    } }
    LaunchedEffect(activePage) { controls = true; moreActions = false; error = false; zoomed = false; photoRect = null; dragFraction = 0f }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val item = saveItem; saveItem = null
        if (uri != null && item != null) scope.launch {
            try { withContext(Dispatchers.IO) { val source = item.file ?: throw java.io.IOException("File unavailable"); context.contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } } ?: throw java.io.IOException("Cannot save") } }
            catch (_: Exception) { error = true }
        }
    }
    val hostConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val hostBounds = remember(hostConfiguration.screenWidthDp, hostConfiguration.screenHeightDp, activity) {
        if (Build.VERSION.SDK_INT >= 30) activity?.windowManager?.currentWindowMetrics?.bounds else null
    }
    val viewport = with(density) {
        if (hostBounds != null) Modifier.requiredSize(hostBounds.width().toDp(), hostBounds.height().toDp())
        else Modifier.requiredSize(hostConfiguration.screenWidthDp.dp, hostConfiguration.screenHeightDp.dp)
    }
    // The explicit MATCH_PARENT window below owns the full-screen bounds.
    // The false branch in Compose 1.7 remeasures from resource screen dp and
    // overwrites the window size on layout, leaving a floating, clipped dialog.
    Dialog(onDismissRequest = ::requestClose, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        val dialogView = LocalView.current
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        // Dialog windows otherwise retain a floating/portrait measured size on
        // devices that handle rotation without recreating the Activity.
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            dialogWindow?.apply {
                setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { windowAnimations = 0 }
                addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                if (Build.VERSION.SDK_INT >= 30) attributes = attributes.apply {
                    setFitInsetsTypes(0)
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
            onDispose { }
        }
        LaunchedEffect(dialogWindow, configuration.screenWidthDp, configuration.screenHeightDp) {
            dialogView.requestLayout()
        }
        if (currentItem.mime == "application/pdf") PdfReaderSystemBars(dialogView, controls)
        else MediaViewerSystemBars(dialogView, navigationVisible = controls || moreActions || saveItem != null)
    fun shareCurrent(openWith: Boolean = false) {
        val file = currentFile ?: return
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { AccountFileProvider.uriForFile(context, "${context.packageName}.fileprovider", file) }
                val intent = if (openWith) Intent(Intent.ACTION_VIEW).setDataAndType(uri, currentItem.mime)
                    else Intent(Intent.ACTION_SEND).setType(currentItem.mime).putExtra(Intent.EXTRA_STREAM, uri)
                context.startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
        }
    }
    fun forwardCurrent() {
        CocoHaptics.tap(dialogView)
        if (onForward != null) onForward(currentItem.id) else shareCurrent()
    }
    val forwardButton: @Composable () -> Unit = {
        Box {
            ViewerGlyphButton(MediaViewerIcons.Forward,
                stringResource(if (onForward != null) R.string.viewer_forward_chat else R.string.viewer_share),
                enabled = currentFile != null, onClick = { saveActions = false; moreActions = !moreActions })
            DropdownMenu(expanded = moreActions, onDismissRequest = { moreActions = false },
                containerColor = colors.glassFloating, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                if (!saveActions) DropdownMenuItem(leadingIcon = { Icon(MediaViewerIcons.Forward, null) },
                    text = { Text(stringResource(if (onForward != null) R.string.viewer_forward_chat else R.string.viewer_share)) },
                    onClick = { moreActions = false; forwardCurrent() })
                if (!saveActions) DropdownMenuItem(text = { Text(stringResource(R.string.viewer_save_device)) }, onClick = { saveActions = true })
                if (saveActions) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.media_save_files)) }, onClick = {
                        moreActions = false
                        val item = currentItem.copy(file = currentFile); saveItem = item
                        save.launch(viewerExportName(item))
                    })
                    if (mediaCanSaveToGallery(currentItem.mime)) DropdownMenuItem(text = { Text(stringResource(R.string.media_save_gallery)) },
                        onClick = { moreActions = false; requestGallery(currentItem.copy(file = currentFile)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.viewer_open_with)) },
                        onClick = { moreActions = false; shareCurrent(openWith = true) })
                }
            }
        }
    }
    val photoMore: @Composable () -> Unit = {
        Box {
            ViewerGlyphButton(MediaViewerIcons.More, stringResource(R.string.viewer_more), currentFile != null, glass = true,
                onClick = { CocoHaptics.tap(dialogView); moreActions = !moreActions })
            DropdownMenu(expanded = moreActions, onDismissRequest = { moreActions = false },
                containerColor = colors.glassFloating, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                DropdownMenuItem(leadingIcon = { Icon(MediaViewerIcons.Share, null) }, text = { Text(stringResource(R.string.viewer_share)) },
                    onClick = { moreActions = false; shareCurrent() })
                if (mediaCanSaveToGallery(currentItem.mime)) DropdownMenuItem(text = { Text(stringResource(R.string.media_save_gallery)) },
                    onClick = { moreActions = false; requestGallery(currentItem.copy(file = currentFile)) })
                DropdownMenuItem(text = { Text(stringResource(R.string.media_save_files)) }, onClick = {
                    moreActions = false
                    val item = currentItem.copy(file = currentFile); saveItem = item; save.launch(viewerExportName(item))
                })
                DropdownMenuItem(leadingIcon = { Icon(CocoIcons.Copy, null) }, text = { Text(stringResource(R.string.copy)) }, onClick = {
                    moreActions = false
                    val file = currentFile ?: return@DropdownMenuItem
                    scope.launch {
                        try {
                            val uri = withContext(Dispatchers.IO) { AccountFileProvider.uriForFile(context, "${context.packageName}.fileprovider", file) }
                            com.messenger.app.data.secure.SensitiveClipboard.copyUri(context, uri, currentItem.mime)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                    }
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.viewer_open_with)) },
                    onClick = { moreActions = false; shareCurrent(openWith = true) })
            }
        }
    }
        val material = com.messenger.app.ui.theme.rememberGlassBackdrop(blurEnabled = Build.VERSION.SDK_INT >= 31)
        CompositionLocalProvider(com.messenger.app.ui.theme.LocalGlassBackdrop provides material) {
        BoxWithConstraints(viewport) {
            val width = constraints.maxWidth.toFloat(); val height = constraints.maxHeight.toFloat()
            val source = if (closing) currentFile?.let { MediaSources.get(it.path) } else transitionSource
            val sourceBounds = source?.bounds?.takeIf { viewerUsableSource(it, width, height) }
            val flightBitmap = if (closing) currentPreview ?: source?.bitmap?.get() else source?.bitmap?.get()
            val hasFlight = sourceBounds != null && flightBitmap != null && !reduceMotion
            val closingRect = remember(closing) { photoRect }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = flight * (1f - dragFraction * .88f) }
                .background(Color.Black)) { MediaViewerBackdrop(currentPreview, viewerWorkAllowed, reduceMotion) }
            MediaViewerMaterial(currentPreview, photoRect, material)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (hasFlight && (closing || flight < 1f)) 0f else flight }) {
            HorizontalPager(state = pager, key = { items[it].id }, beyondViewportPageCount = 1,
                userScrollEnabled = editorRequest == null && !closing && !zoomed && !scrubbing, modifier = Modifier.fillMaxSize().testTag("media-pager")) { index ->
                val item = items.getOrNull(index) ?: return@HorizontalPager
                // A held swipe can cross the midpoint and return. Adjoining pages
                // stay passive; do not prepare/release a decoder during that gesture.
                val pageFile = if (index == activePage) currentFile else item.file ?: downloaded[item.id]
                val preview = if (kotlin.math.abs(index - pager.currentPage) <= 1)
                    rememberMediaViewerPreview(pageFile, item.mime, item.thumbnailBase64, workAllowed = viewerWorkAllowed && (index == pager.currentPage ||
                        (pager.currentPageOffsetFraction > 0f && index == pager.currentPage + 1) ||
                        (pager.currentPageOffsetFraction < 0f && index == pager.currentPage - 1))) else null
                if (index == activePage) {
                    val file = currentFile
                    if (file == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        preview?.let { androidx.compose.foundation.Image(it.asImageBitmap(), contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                        if (loadFailed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.gallery_open_failed), color = Color.White, modifier = Modifier.padding(24.dp))
                            TextButton(onClick = { CocoHaptics.tap(dialogView); retry++ }) { Text(stringResource(R.string.retry)) }
                        } else if (foreground) {
                            if (item.mime == "application/pdf") Text(stringResource(R.string.pdf_loading), color = Color.White)
                            else CircularProgressIndicator()
                        }
                    }
                    else if (item.mime.startsWith("video/")) VideoPage(file, audioAllowed, { controls = it }, { scrubbing = it }, moreActions, forwardButton, onDismiss = ::requestClose,
                        onDrag = { dragFraction = it }, onImageRect = { photoRect = it })
                    else if (item.mime.startsWith("image/")) ZoomImagePage(file, { zoomed = it }, { controls = !controls }, ::requestClose, preview,
                        onDrag = { dragFraction = it }, onImageRect = { photoRect = it }, active = viewerWorkAllowed)
                    else if (item.mime == "application/pdf") PdfReader(file, item.title, viewerWorkAllowed, controls,
                        onChrome = { controls = it }, onZoom = { zoomed = it }, onClose = ::requestClose,
                        onShare = { error = false; shareCurrent() }, actionFailed = error)
                    else Text(item.title, color = Color.White, modifier = Modifier.align(Alignment.Center))
                } else if (preview != null) {
                    androidx.compose.foundation.Image(preview.asImageBitmap(), contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            }
            MediaViewerFlight(flightBitmap, sourceBounds, closingRect, closing, flight, reduceMotion)
            if (currentItem.mime == "application/pdf" && currentFile == null && !closing && foreground) {
                PdfReaderChrome(currentItem.title, 1, 0, controls,
                    onClose = ::requestClose, onShare = {}, shareEnabled = false)
            }
            if ((controls || moreActions) && !closing && !currentItem.mime.startsWith("video/") && currentItem.mime != "application/pdf") {
                if (currentItem.mime.startsWith("image/")) MediaViewerPhotoToolbar(currentFile != null,
                    stringResource(if (onForward != null) R.string.viewer_forward_chat else R.string.viewer_share), ::forwardCurrent,
                    onEdit = { CocoHaptics.tap(dialogView); editCurrent() },
                    onText = onTextInPhoto?.let { recognize -> { currentFile?.let { CocoHaptics.tap(dialogView); recognize(it) } } },
                    more = photoMore, modifier = Modifier.align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout)).padding(16.dp)
                        .graphicsLayer { alpha = flight })
                else Box(Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout))
                    .padding(16.dp).viewerGlass()) { forwardButton() }
            }
            if ((controls || moreActions) && !closing && currentItem.mime != "application/pdf") Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout)).padding(16.dp)
                .graphicsLayer { alpha = flight }) {
                MediaViewerHeader(currentItem.sender, dateLine, pager.currentPage, items.size,
                    onClose = { CocoHaptics.tap(dialogView); requestClose() })
                if (error) Text(stringResource(R.string.viewer_action_failed), color = Color.White, modifier = Modifier.padding(12.dp))
            }
        }
        }
    }

    editorRequest?.let { request -> com.messenger.app.ui.media.PhotoEditorScreen(request.file, onEditedPhoto, export = { owned, share ->
        val file = owned.file
        if (share) {
            val uri = withContext(Dispatchers.IO) { AccountFileProvider.uriForFile(context, "${context.packageName}.fileprovider", file) }
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
            owned.shared()
        } else exportGallery(MediaViewerItem(file, "image/jpeg", file.name))
    }, onDismiss = { editorRequest = null }, source = request.source) }

}

private fun viewerExportName(item: MediaViewerItem): String = item.title.takeUnless { it == "UNYRI" } ?:
    ("UNYRI." + (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mime) ?: "bin"))

/** Own only this dialog window; never leave the underlying chat immersive. */
@Composable private fun MediaViewerSystemBars(dialogView: android.view.View, navigationVisible: Boolean) {
    val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, window.decorView) }
    val latestNavigationVisible by rememberUpdatedState(navigationVisible)
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    fun applyVisibility() {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        // Keep the established full-screen status-bar style. The bottom system
        // Back/Home/Recents (or gesture handle) follows the actual media controls.
        controller.hide(WindowInsetsCompat.Type.statusBars())
        if (latestNavigationVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
        else controller.hide(WindowInsetsCompat.Type.navigationBars())
    }
    DisposableEffect(window) {
        val decor = window.decorView
        val initialInsets = WindowCompat.getInsetsController(window, decor)
        val initialVisibility = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
        val statusWasVisible = initialVisibility?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationWasVisible = initialVisibility?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val previousBehavior = initialInsets.systemBarsBehavior
        val lightStatus = initialInsets.isAppearanceLightStatusBars
        val lightNavigation = initialInsets.isAppearanceLightNavigationBars
        val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) applyVisibility()
        }
        decor.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        if (decor.hasWindowFocus()) applyVisibility()
        onDispose {
            if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            controller.systemBarsBehavior = previousBehavior
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
            if (statusWasVisible) controller.show(WindowInsetsCompat.Type.statusBars())
            else controller.hide(WindowInsetsCompat.Type.statusBars())
            if (navigationWasVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
            else controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
    // Do not re-hide a transient edge-swipe reveal on every recomposition.
    // Apply only to a control transition, a real viewport change, or focus return.
    LaunchedEffect(window, navigationVisible, configuration.orientation,
        configuration.screenWidthDp, configuration.screenHeightDp) {
        if (window.decorView.hasWindowFocus()) applyVisibility()
    }
}

@Composable internal fun ZoomImagePage(file: File, onZoom: (Boolean) -> Unit, onTap: () -> Unit, onDismiss: () -> Unit,
    preview: android.graphics.Bitmap? = null, onDrag: (Float) -> Unit = {},
    onImageRect: (androidx.compose.ui.geometry.Rect?) -> Unit = {}, active: Boolean = true) {
    val imageContext = LocalContext.current
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var x by remember(file) { mutableFloatStateOf(0f) }; var y by remember(file) { mutableFloatStateOf(0f) }
    var loading by remember(file) { mutableStateOf(true) }
    var dismissDrag by remember(file) { mutableFloatStateOf(0f) }
    var dismissX by remember(file) { mutableFloatStateOf(0f) }
    val settle = remember(file) { androidx.compose.animation.core.Animatable(0f) }
    val settleX = remember(file) { androidx.compose.animation.core.Animatable(0f) }
    val reduceMotion = LocalChatMotionSettings.current?.reduceMotion == true
    val gestureScope = rememberCoroutineScope()
    val gestureDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val foreground = rememberViewerForeground() && active
    val image by produceState<Drawable?>(null, file, foreground) {
        if (!foreground || value != null) return@produceState
        value = withContext(Dispatchers.IO) { runCatching {
            if (!com.messenger.app.data.attachment.PeerImageDecoder.allowed(file)) return@runCatching null
            if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                require(info.size.width in 1..32768 && info.size.height in 1..32768 && info.size.width.toLong() * info.size.height <= 150_000_000L)
                val longest = maxOf(info.size.width, info.size.height); if (longest > 2048) decoder.setTargetSampleSize((longest + 2047) / 2048)
            } else {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                com.messenger.app.data.attachment.PeerImageDecoder.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                com.messenger.app.data.attachment.PeerImageDecoder.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.let { android.graphics.drawable.BitmapDrawable(imageContext.resources, it) }
            }
        }.getOrNull() }
        loading = false
    }
    val previewDrawable = remember(preview, imageContext.resources) {
        preview?.let { android.graphics.drawable.BitmapDrawable(imageContext.resources, it) }
    }
    val displayImage = image ?: previewDrawable
    val photoLabel = stringResource(R.string.viewer_photo)
    val zoomInLabel = stringResource(R.string.viewer_zoom_in)
    val resetZoomLabel = stringResource(R.string.viewer_zoom_reset)
    val toggleLabel = stringResource(R.string.viewer_toggle_controls)
    val closeLabel = stringResource(R.string.dismiss)
    val imageLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val zoomChanged by rememberUpdatedState(onZoom)
    val dragChanged by rememberUpdatedState(onDrag)
    val imageRectChanged by rememberUpdatedState(onImageRect)
    DisposableEffect(image, imageLifecycle, active) {
        fun updateAnimation() { if (Build.VERSION.SDK_INT >= 28) {
            val animated = image as? AnimatedImageDrawable
            if(active && imageLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) animated?.start() else animated?.stop()
        } }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> updateAnimation() }
        imageLifecycle.addObserver(observer); updateAnimation()
        onDispose { imageLifecycle.removeObserver(observer); if (Build.VERSION.SDK_INT >= 28) (image as? AnimatedImageDrawable)?.stop() }
    }
    LaunchedEffect(file) { observeViewerZoom({ scale }) { zoomChanged(it) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxX = constraints.maxWidth.toFloat(); val maxY = constraints.maxHeight.toFloat()
        fun bounds(zoom: Float) = mediaPanBounds(maxX, maxY, displayImage?.intrinsicWidth?.toFloat() ?: maxX, displayImage?.intrinsicHeight?.toFloat() ?: maxY, zoom)
        fun presentation(): ViewerImagePresentation {
            val drag = dismissDrag + settle.value
            val fraction = viewerDragFraction(drag, maxY)
            val frame = displayImage?.let { viewerFitRect(maxX, maxY, it.intrinsicWidth.toFloat(), it.intrinsicHeight.toFloat()) }
                ?.let { viewerTransformedRect(it, maxX, maxY, scale * (1f - fraction * .28f), x + dismissX + settleX.value, y + drag) }
            return ViewerImagePresentation(frame, fraction)
        }
        fun publishTransform(value: ViewerImagePresentation = presentation()) {
            dragChanged(value.dismissFraction); imageRectChanged(value.frame)
        }
        LaunchedEffect(file, maxX, maxY, displayImage) {
            observeViewerPresentation(::presentation) { publishTransform(it) }
        }
        if (displayImage == null) Text(if (loading) "…" else stringResource(R.string.media_preview_unavailable), color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        LaunchedEffect(maxX, maxY) {
            scale = 1f; x = 0f; y = 0f; dismissDrag = 0f; dismissX = 0f; settle.snapTo(0f); settleX.snapTo(0f)
        }
        // Gesture coordinates stay in the viewport; only the image is transformed.
        Box(Modifier.fillMaxSize().testTag("photo-gestures").semantics {
            contentDescription = photoLabel
            customActions = listOf(
                CustomAccessibilityAction(zoomInLabel) { scale = (scale * 2f).coerceAtMost(5f); true },
                CustomAccessibilityAction(resetZoomLabel) { scale = 1f; x = 0f; y = 0f; true },
                CustomAccessibilityAction(toggleLabel) { onTap(); true },
                CustomAccessibilityAction(closeLabel) { publishTransform(); onDismiss(); true })
        }
            .pointerInput(file, maxX, maxY, image) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { point ->
                if (image == null) return@detectTapGestures
                scale = if (scale > 1.01f) 1f else 2.5f
                val (limitX, limitY) = bounds(scale)
                x = ((maxX / 2 - point.x) * (scale - 1)).coerceIn(-limitX, limitX)
                y = ((maxY / 2 - point.y) * (scale - 1)).coerceIn(-limitY, limitY)
            }) }
            .pointerInput(file, maxX, maxY, image) { awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val velocity = androidx.compose.ui.input.pointer.util.VelocityTracker()
                velocity.addPosition(down.uptimeMillis, down.position)
                var dragX = 0f; var dragY = 0f
                var vertical = false; var transformed = false; var interrupted = false
                val slop = viewConfiguration.touchSlop
                do {
                    val event = awaitPointerEvent()
                    event.changes.firstOrNull()?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                    if (event.changes.any { it.isConsumed }) { interrupted = true; break }
                    if (event.changes.count { it.pressed } > 1 || scale > 1.01f) {
                        transformed = true; dismissDrag = 0f; dismissX = 0f
                        val factor = event.calculateZoom(); val pan = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = false)
                        val oldScale = scale
                        scale = (scale * factor).coerceIn(1f, 5f)
                        val ratio = scale / oldScale
                        val (limitX, limitY) = bounds(scale)
                        if (centroid.x.isFinite() && centroid.y.isFinite()) {
                            x = (x * ratio + (1 - ratio) * (centroid.x - maxX / 2) + pan.x).coerceIn(-limitX, limitX)
                            y = (y * ratio + (1 - ratio) * (centroid.y - maxY / 2) + pan.y).coerceIn(-limitY, limitY)
                        }
                        if (factor != 1f || kotlin.math.abs(pan.x) + kotlin.math.abs(pan.y) > .5f) event.changes.forEach { it.consume() }
                    } else if (!transformed) {
                        val pan = event.calculatePan(); dragX += pan.x; dragY += pan.y
                        if (!vertical && kotlin.math.abs(dragY) > slop && kotlin.math.abs(dragY) > kotlin.math.abs(dragX) * 1.5f) vertical = true
                        if (vertical) { dismissDrag = dragY; dismissX = dragX * .25f; event.changes.forEach { it.consume() } }
                    }
                    // Also publish synchronously: Back or release can arrive before
                    // the next snapshot observer dispatches the final pointer state.
                    publishTransform()
                } while (event.changes.any { it.pressed })
                if (vertical && !transformed && !interrupted && viewerDismisses(dismissDrag / gestureDensity, velocity.calculateVelocity().y / gestureDensity, maxY / gestureDensity)) {
                    publishTransform(); onDismiss()
                }
                else if (dismissDrag != 0f) {
                    val start = dismissDrag
                    val startX = dismissX
                    gestureScope.launch {
                        settle.snapTo(start); settleX.snapTo(startX); dismissDrag = 0f; dismissX = 0f
                        launch { settleX.animateTo(0f, if (reduceMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = .8f, stiffness = 500f)) }
                        settle.animateTo(0f, if (reduceMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = .8f, stiffness = 500f))
                    }
                }
            } }) {
            AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO } },
                update = { if (it.drawable !== displayImage) it.setImageDrawable(displayImage) },
                modifier = Modifier.fillMaxSize().testTag(if (image != null) "photo-loaded-image" else "photo-loading-image").graphicsLayer {
                    val dragScale = 1f - viewerDragFraction(dismissDrag + settle.value, maxY) * .28f
                    scaleX = scale * dragScale; scaleY = scale * dragScale; translationX = x + dismissX + settleX.value
                    translationY = y + dismissDrag + settle.value
                })
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun VideoPage(file: File, audioAllowed: Boolean, onControlsChanged: (Boolean) -> Unit = {}, onScrubbing: (Boolean) -> Unit = {}, controlsPinned: Boolean = false, actions: @Composable () -> Unit = {}, onDismiss: () -> Unit = {},
    onDrag: (Float) -> Unit = {}, onImageRect: (androidx.compose.ui.geometry.Rect?) -> Unit = {}) {
    val context = LocalContext.current
    val hapticView = LocalView.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val controlsChanged by rememberUpdatedState(onControlsChanged)
    val scrubChanged by rememberUpdatedState(onScrubbing)
    val dragChanged by rememberUpdatedState(onDrag)
    val imageRectChanged by rememberUpdatedState(onImageRect)
    var userMuted by remember(file) { mutableStateOf(VideoPlaybackMemory.muted) }
    var failed by remember(file) { mutableStateOf(false) }
    var isPlaying by remember(file) { mutableStateOf(false) }
    val savedState = androidx.compose.runtime.saveable.LocalSaveableStateRegistry.current
    val positionKey = remember(file.path) { "viewer-position:${file.path}" }
    var position by remember(file.path) { mutableLongStateOf((savedState?.consumeRestored(positionKey) as? Long) ?: VideoPlaybackMemory.position(file.path)) }
    var started by androidx.compose.runtime.saveable.rememberSaveable(file.path) { mutableStateOf(false) }
    val player = remember(file) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build().apply {
            setHandleAudioBecomingNoisy(true)
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
            seekVideoExact(position)
        }
    }
    val playerView = remember(player) {
        androidx.media3.ui.PlayerView(context).apply {
            this.player = player
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            useController = false
            setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
    }
    // Snapshot on state saving, rather than waking Compose every half-second while chrome is hidden.
    DisposableEffect(player, savedState) {
        val registration = savedState?.registerProvider(positionKey) { player.currentPosition }
        onDispose { registration?.unregister() }
    }
    var hasAudio by remember(player) { mutableStateOf<Boolean?>(null) }
    val currentAudioAllowed by rememberUpdatedState(audioAllowed)
    val audioOwnership = remember(player) { VideoAudioOwnership(
        volume = { player.volume = if (it) 1f else 0f },
        focus = { audible ->
            if (audible) (context.applicationContext as? com.messenger.app.MessengerApp)?.voicePlayerController?.stop()
            player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, audible)
        },
        tracks = { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, !it).build() }) }
    fun updateAudio() = audioOwnership.apply(currentAudioAllowed, userMuted, player.playWhenReady, hasAudio)
    var appliedAudio by remember(player) { mutableStateOf(audioAllowed) }
    var speedPicked by remember(player) { mutableStateOf(false) }
    val playbackScope = rememberCoroutineScope()
    LaunchedEffect(player) {
        val saved = withContext(Dispatchers.IO) { context.getSharedPreferences("media-player", android.content.Context.MODE_PRIVATE).getFloat("speed", 1f) }
        if (!speedPicked) { VideoPlaybackMemory.speed = saved; player.setPlaybackSpeed(VideoPolicy.speed(saved, player.duration)) }
    }

    DisposableEffect(player, lifecycle) {
        val listener = object : androidx.media3.common.Player.Listener {
            private var configured = false
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY && !configured) {
                    configured = true
                    player.seekVideoExact(VideoPolicy.restore(position, player.duration))
                    if (!speedPicked) player.setPlaybackSpeed(VideoPolicy.speed(VideoPlaybackMemory.speed, player.duration))
                    hasAudio = videoAudioAvailability(player.currentTracks, ready = true)
                    player.repeatMode = if (VideoPolicy.loops(player.duration, hasAudio == true)) androidx.media3.common.Player.REPEAT_MODE_ONE else androidx.media3.common.Player.REPEAT_MODE_OFF
                }
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                hasAudio = videoAudioAvailability(tracks, ready = player.playbackState == androidx.media3.common.Player.STATE_READY)
                updateAudio()
            }
            override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) { updateAudio() }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing; if (playing) started = true; playerView.keepScreenOn = playing }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true; controlsChanged(true) }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            com.messenger.app.data.local.VideoFrameFailures.lifecycle(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                position = player.currentPosition; player.pause()
            }
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !started) player.play()
        }
        player.addListener(listener); lifecycle.addObserver(observer)
        com.messenger.app.data.local.VideoFrameFailures.lifecycle(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        updateAudio()
        player.prepare()
        player.playWhenReady = !started && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        onDispose {
            scrubChanged(false)
            position = player.currentPosition
            VideoPlaybackMemory.save(file.path, position)
            lifecycle.removeObserver(observer); player.removeListener(listener)
            playerView.keepScreenOn = false; playerView.player = null; player.release()
        }
    }
    LaunchedEffect(audioAllowed) {
        if (appliedAudio == audioAllowed) return@LaunchedEffect
        appliedAudio = audioAllowed
        // Calls own the route. Switching audio ownership must never resume a paused video.
        player.pause()
        updateAudio()
    }
    val dragY = remember(file) { androidx.compose.animation.core.Animatable(0f) }
    val dragX = remember(file) { androidx.compose.animation.core.Animatable(0f) }
    val reduceMotion = LocalChatMotionSettings.current?.reduceMotion == true
    val videoDensity = androidx.compose.ui.platform.LocalDensity.current.density
    var videoWidth by remember(player) { mutableIntStateOf(1) }
    var videoHeight by remember(player) { mutableIntStateOf(1) }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                videoWidth = (size.width * size.pixelWidthHeightRatio).toInt().coerceAtLeast(1); videoHeight = size.height.coerceAtLeast(1)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        fun presentation(x: Float, y: Float): ViewerImagePresentation {
            val fraction = viewerVideoDragFraction(y / videoDensity)
            val frame = viewerFitRect(w, h, videoWidth.toFloat(), videoHeight.toFloat())?.let {
                viewerTransformedRect(it, w, h, 1f - fraction * .28f, x, y)
            }
            return ViewerImagePresentation(frame, fraction)
        }
        fun publishRect(value: ViewerImagePresentation) {
            dragChanged(value.dismissFraction); imageRectChanged(value.frame)
        }
        LaunchedEffect(player, w, h, videoDensity) {
            observeViewerPresentation({ presentation(dragX.value, dragY.value) }) { publishRect(it) }
        }
        key(player) { AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = 1f - viewerVideoDragFraction(dragY.value / videoDensity) * .28f; scaleY = scaleX
            translationX = dragX.value; translationY = dragY.value
        }) }
        VideoPlaybackControls(player, { controlsChanged(it) }, { scrubChanged(it) }, audioAllowed, controlsPinned, { userMuted = it; updateAudio() }, actions,
            onFill = { playerView.resizeMode = if (it) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT },
            onDismiss = onDismiss,
            onDrag = { x, y ->
                if (x != 0f || y != 0f) publishRect(presentation(x, y))
                playbackScope.launch {
                    if (x == 0f && y == 0f) {
                        launch { dragX.animateTo(0f, if (reduceMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
                        dragY.animateTo(0f, if (reduceMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                    } else { dragX.snapTo(x); dragY.snapTo(y) }
                }
            },
            onSpeed = { value -> speedPicked = true; playbackScope.launch(Dispatchers.IO) { context.getSharedPreferences("media-player", android.content.Context.MODE_PRIVATE).edit().putFloat("speed", value).apply() } })
        if (!audioAllowed) Text(stringResource(R.string.media_muted_call), color = Color.White,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
                .background(Color.Black.copy(alpha = .7f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(8.dp))
        if (failed) Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .8f)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.media_preview_unavailable), color = Color.White)
            TextButton(onClick = { CocoHaptics.tap(hapticView); failed = false; player.prepare(); player.play() }) { Text(stringResource(R.string.retry)) }
        }
    }
}
