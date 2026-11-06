package com.messenger.app.ui.chats

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
import androidx.core.content.FileProvider
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalCocoColors

internal data class MediaViewerItem(val file: File?, val mime: String, val title: String,
    val id: String = file?.path.orEmpty(), val thumbnailBase64: String? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaViewerDialog(items: List<MediaViewerItem>, initialFile: File, download: suspend (String) -> File? = { null }, onForward: ((String) -> Unit)? = null, onDismiss: () -> Unit) {
    if (items.isEmpty()) return
    val colors=LocalCocoColors.current
    val context = LocalContext.current
    val app = context.applicationContext as com.messenger.app.MessengerApp
    val call by app.callController.state.collectAsState()
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
        if (activity != null) MediaOrientationLease.acquire(activity, token)
        onDispose { if (activity != null) MediaOrientationLease.release(activity, token) }
    }
    val scope = rememberCoroutineScope()
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
    val currentFile = currentItem.file?.takeIf { it.isFile } ?: downloaded[currentItem.id]?.takeIf { it.isFile }
    LaunchedEffect(currentItem.id, retry) {
        if (currentFile == null) {
            loadFailed = false
            try {
                val file = downloadCurrent(currentItem.id)?.takeIf { it.isFile }
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
    LaunchedEffect(activePage) { controls = true; moreActions = false; error = false; zoomed = false }
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        val dialogView = LocalView.current
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        // Dialog windows otherwise retain a floating/portrait measured size on
        // devices that handle rotation without recreating the Activity.
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            dialogWindow?.apply {
                setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
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
        MediaViewerSystemBars(dialogView, navigationVisible = controls || moreActions || saveItem != null)
    val actionButton: @Composable () -> Unit = {
        Box {
            IconButton(enabled = currentFile != null, onClick = { CocoHaptics.tap(dialogView); saveActions = false; moreActions = !moreActions }, modifier = Modifier.size(48.dp)) {
                Icon(MediaViewerIcons.Forward, stringResource(R.string.media_actions), tint = Color.White, modifier = Modifier.size(22.dp))
            }
                    DropdownMenu(expanded = moreActions, onDismissRequest = { moreActions = false }, containerColor = colors.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                        if (!saveActions) DropdownMenuItem(leadingIcon = { Icon(MediaViewerIcons.Forward, null, tint = Color.White) }, text = { Text(stringResource(if (onForward != null) R.string.media_forward_chat else R.string.media_share), color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal) }, onClick = {
                            moreActions = false
                            if (onForward != null) { CocoHaptics.tap(dialogView); onForward(currentItem.id) }
                            else {
                                val file = currentFile ?: return@DropdownMenuItem
                                CocoHaptics.tap(dialogView)
                                try {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(currentItem.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                                } catch (_: Exception) { error = true }
                            }
                        })
                        if (!saveActions) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color.White.copy(alpha = .1f))
                        if (!saveActions) DropdownMenuItem(leadingIcon = { Icon(MediaViewerIcons.Download, null, tint = Color.White) }, text = { Text(stringResource(R.string.media_save_device), color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal) }, onClick = { CocoHaptics.tap(dialogView); saveActions = true })
                        if (saveActions) DropdownMenuItem(leadingIcon = { Icon(MediaViewerIcons.Download, null, tint = Color.White) }, text = { Text(stringResource(R.string.media_save_device), color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal) }, onClick = {
                            moreActions = false
                            CocoHaptics.tap(dialogView)
                            val item = currentItem.copy(file = currentFile); saveItem = item
                            save.launch(item.title.takeUnless { it == "UNYRI" } ?: ("UNYRI." + (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mime) ?: "bin")))
                        })
                        if (saveActions) DropdownMenuItem(text = { Text(stringResource(R.string.media_open_with), color = Color.White) }, onClick = {
                            moreActions = false
                            val item = currentItem
                            val file = currentFile ?: return@DropdownMenuItem
                            CocoHaptics.tap(dialogView)
                            try { val uri = FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file); context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            catch (_: Exception) { error = true }
                        })
                    }
                    }
    }
        Box(viewport.background(Color.Black)) {
            HorizontalPager(state = pager, key = { items[it].id }, beyondViewportPageCount = 1,
                userScrollEnabled = !zoomed && !scrubbing, modifier = Modifier.fillMaxSize().testTag("media-pager")) { index ->
                val item = items.getOrNull(index) ?: return@HorizontalPager
                // A held swipe can cross the midpoint and return. Adjoining pages
                // stay passive; do not prepare/release a decoder during that gesture.
                val pageFile = if (index == activePage) currentFile else item.file ?: downloaded[item.id]
                val preview = if (kotlin.math.abs(index - pager.currentPage) <= 1)
                    rememberMediaViewerPreview(pageFile, item.mime, item.thumbnailBase64) else null
                if (index == activePage) {
                    val file = currentFile
                    if (file == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        preview?.let { androidx.compose.foundation.Image(it.asImageBitmap(), contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                        if (loadFailed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.gallery_open_failed), color = Color.White, modifier = Modifier.padding(24.dp))
                            TextButton(onClick = { CocoHaptics.tap(dialogView); retry++ }) { Text(stringResource(R.string.retry)) }
                        } else CircularProgressIndicator()
                    }
                    else if (item.mime.startsWith("video/")) VideoPage(file, audioAllowed, { controls = it }, { scrubbing = it }, moreActions, actionButton)
                    else if (item.mime.startsWith("image/")) ZoomImagePage(file, { zoomed = it }, { controls = !controls }, onDismiss, preview)
                    else if (item.mime == "application/pdf") PdfPage(file)
                    else Text(item.title, color = Color.White, modifier = Modifier.align(Alignment.Center))
                } else if (preview != null) {
                    androidx.compose.foundation.Image(preview.asImageBitmap(), contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            if ((controls || moreActions) && !currentItem.mime.startsWith("video/")) {
                Box(Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout)).padding(16.dp).background(colors.surface.copy(alpha = .75f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))) { actionButton() }
            }
            if (controls || moreActions) Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().displayCutoutPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    com.messenger.app.ui.components.CocoBackButton(onClick = { CocoHaptics.tap(dialogView); onDismiss() }, label = stringResource(R.string.dismiss))
                    Text("${pager.currentPage + 1} / ${items.size}", color = Color.White, fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp), modifier = Modifier.padding(horizontal = 12.dp))
                    Spacer(Modifier.weight(1f))

                }
                if (error) Text(stringResource(R.string.media_action_failed), color = Color.White, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

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
    preview: android.graphics.Bitmap? = null) {
    val imageContext = LocalContext.current
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var x by remember(file) { mutableFloatStateOf(0f) }; var y by remember(file) { mutableFloatStateOf(0f) }
    var loading by remember(file) { mutableStateOf(true) }
    var dismissDrag by remember(file) { mutableFloatStateOf(0f) }
    val settle = remember(file) { androidx.compose.animation.core.Animatable(0f) }
    val gestureScope = rememberCoroutineScope()
    val image by produceState<Drawable?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching {
            if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height); if (longest > 2048) decoder.setTargetSampleSize((longest + 2047) / 2048)
            } else {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.let { android.graphics.drawable.BitmapDrawable(imageContext.resources, it) }
            }
        }.getOrNull() }
        loading = false
    }
    val previewDrawable = remember(preview, imageContext.resources) {
        preview?.let { android.graphics.drawable.BitmapDrawable(imageContext.resources, it) }
    }
    val displayImage = image ?: previewDrawable
    val imageLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(image, imageLifecycle) {
        fun updateAnimation() { if (Build.VERSION.SDK_INT >= 28) {
            val animated = image as? AnimatedImageDrawable
            if(imageLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) animated?.start() else animated?.stop()
        } }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> updateAnimation() }
        imageLifecycle.addObserver(observer); updateAnimation()
        onDispose { imageLifecycle.removeObserver(observer); if (Build.VERSION.SDK_INT >= 28) (image as? AnimatedImageDrawable)?.stop(); onZoom(false) }
    }
    LaunchedEffect(scale) { onZoom(scale > 1f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxX = constraints.maxWidth.toFloat(); val maxY = constraints.maxHeight.toFloat()
        fun bounds(zoom: Float) = mediaPanBounds(maxX, maxY, displayImage?.intrinsicWidth?.toFloat() ?: maxX, displayImage?.intrinsicHeight?.toFloat() ?: maxY, zoom)
        if (displayImage == null) Text(if (loading) "…" else stringResource(R.string.media_preview_unavailable), color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        LaunchedEffect(maxX, maxY) {
            scale = 1f; x = 0f; y = 0f; dismissDrag = 0f; settle.snapTo(0f)
        }
        // Gesture coordinates stay in the viewport; only the image is transformed.
        Box(Modifier.fillMaxSize().testTag("photo-gestures")
            .pointerInput(file, maxX, maxY, image) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { point ->
                if (image == null) return@detectTapGestures
                scale = if (scale > 1.01f) 1f else 2.5f
                val (limitX, limitY) = bounds(scale)
                x = ((maxX / 2 - point.x) * (scale - 1)).coerceIn(-limitX, limitX)
                y = ((maxY / 2 - point.y) * (scale - 1)).coerceIn(-limitY, limitY)
            }) }
            .pointerInput(file, maxX, maxY, image) { awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var dragX = 0f; var dragY = 0f
                var vertical = false; var transformed = false; var interrupted = false
                val slop = viewConfiguration.touchSlop
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) { interrupted = true; break }
                    if (event.changes.count { it.pressed } > 1 || scale > 1.01f) {
                        transformed = true; dismissDrag = 0f
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
                        if (vertical) { dismissDrag = dragY; event.changes.forEach { it.consume() } }
                    }
                } while (event.changes.any { it.pressed })
                if (vertical && !transformed && !interrupted && kotlin.math.abs(dismissDrag) > maxY * .15f) onDismiss()
                else if (dismissDrag != 0f) {
                    val start = dismissDrag
                    // One short return animation; decoding and the chat list are unaffected.
                    gestureScope.launch { settle.snapTo(start); dismissDrag = 0f; settle.animateTo(0f, androidx.compose.animation.core.tween(180)) }
                }
            } }) {
            AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { if (it.drawable !== displayImage) it.setImageDrawable(displayImage) },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = x
                    translationY = y + dismissDrag + settle.value
                })
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun VideoPage(file: File, audioAllowed: Boolean, onControlsChanged: (Boolean) -> Unit = {}, onScrubbing: (Boolean) -> Unit = {}, controlsPinned: Boolean = false, actions: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val hapticView = LocalView.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val controlsChanged by rememberUpdatedState(onControlsChanged)
    val scrubChanged by rememberUpdatedState(onScrubbing)
    var userMuted by remember(file) { mutableStateOf(false) }
    var failed by remember(file) { mutableStateOf(false) }
    var isPlaying by remember(file) { mutableStateOf(false) }
    var position by androidx.compose.runtime.saveable.rememberSaveable(file.path) { mutableLongStateOf(0L) }
    var started by androidx.compose.runtime.saveable.rememberSaveable(file.path) { mutableStateOf(false) }
    val player = remember(file) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build().apply {
            setHandleAudioBecomingNoisy(true)
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
            seekTo(position)
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
    var appliedAudio by remember(player) { mutableStateOf(audioAllowed) }
    DisposableEffect(player, lifecycle) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing; if (playing) started = true; playerView.keepScreenOn = playing }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true; controlsChanged(true) }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                position = player.currentPosition; player.pause()
            }
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !started) player.play()
        }
        player.addListener(listener); lifecycle.addObserver(observer)
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, audioAllowed)
        player.volume = if (audioAllowed && !userMuted) 1f else 0f
        if (audioAllowed) (context.applicationContext as? com.messenger.app.MessengerApp)?.voicePlayerController?.stop()
        player.prepare()
        player.playWhenReady = !started && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        onDispose {
            scrubChanged(false)
            position = player.currentPosition
            lifecycle.removeObserver(observer); player.removeListener(listener)
            playerView.keepScreenOn = false; playerView.player = null; player.release()
        }
    }
    LaunchedEffect(audioAllowed) {
        if (appliedAudio == audioAllowed) return@LaunchedEffect
        appliedAudio = audioAllowed
        // Calls own the route. Switching audio ownership must never resume a paused video.
        player.pause()
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, audioAllowed)
        player.volume = if (audioAllowed && !userMuted) 1f else 0f
    }
    LaunchedEffect(isPlaying) {
        while (isPlaying) { position = player.currentPosition; kotlinx.coroutines.delay(500) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(player) { AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize()) }
        VideoPlaybackControls(player, { controlsChanged(it) }, { scrubChanged(it) }, audioAllowed, controlsPinned, { userMuted = it }, actions)
        if (!audioAllowed) Text(stringResource(R.string.media_muted_call), color = Color.White,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
                .background(Color.Black.copy(alpha = .7f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(8.dp))
        if (failed) Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .8f)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.media_preview_unavailable), color = Color.White)
            TextButton(onClick = { CocoHaptics.tap(hapticView); failed = false; player.prepare(); player.play() }) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable private fun PdfPage(file: File) {
    val hapticView = LocalView.current
    var pageIndex by remember(file) { mutableIntStateOf(0) }
    var loading by remember(file) { mutableStateOf(true) }
    val rendered by produceState<Pair<android.graphics.Bitmap?, Int>>(null to 0, file, pageIndex) {
        loading = true
        value = withContext(Dispatchers.IO) { runCatching {
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))).use { page ->
                        val ratio = minOf(1440f / page.width.coerceAtLeast(1), 1920f / page.height.coerceAtLeast(1))
                        val bitmap = android.graphics.Bitmap.createBitmap((page.width * ratio).toInt().coerceAtLeast(1), (page.height * ratio).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap to renderer.pageCount
                    }
                }
            }
        }.getOrDefault(null to 0) }
        loading = false
    }
    Column(Modifier.fillMaxSize().padding(top = 110.dp, bottom = 24.dp)) {
        if (rendered.first == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator()
            else Text(stringResource(R.string.media_preview_unavailable), color = Color.White, modifier = Modifier.padding(24.dp))
        }
        rendered.first?.let { bitmap -> androidx.compose.foundation.Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.weight(1f).fillMaxWidth()) }
        if (rendered.second > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(enabled = pageIndex > 0, onClick = { CocoHaptics.tap(hapticView); pageIndex-- }) { Text("‹") }
            Text("${pageIndex + 1} / ${rendered.second}", color = Color.White)
            TextButton(enabled = pageIndex + 1 < rendered.second, onClick = { CocoHaptics.tap(hapticView); pageIndex++ }) { Text("›") }
        }
    }
}

// All calls run from Compose effects on the main thread. Nested viewers release independently.
private object MediaOrientationLease {
    private data class Lease(val previous: Int, val owners: MutableSet<Any> = mutableSetOf())
    private val leases = java.util.WeakHashMap<android.app.Activity, Lease>()
    fun acquire(activity: android.app.Activity, owner: Any) {
        val lease = leases.getOrPut(activity) { Lease(activity.requestedOrientation) }
        lease.owners.add(owner)
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
    }
    fun release(activity: android.app.Activity, owner: Any) {
        val lease = leases[activity] ?: return
        lease.owners.remove(owner)
        if (lease.owners.isEmpty()) {
            leases.remove(activity)
            activity.requestedOrientation = lease.previous
        }
    }
}
