@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.messenger.app.ui.chats

import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PdfZoom(val scale: Float = 1f, val pan: Float = 0f)
/** PDFKit maxScaleFactor = max(4, fit * 6), expressed relative to width fit. */
internal fun pdfZoomLimit(pageWidth: Int, viewportWidthDp: Float): Float =
    if (pageWidth > 0 && viewportWidthDp.isFinite() && viewportWidthDp > 0) maxOf(6f, 4f * pageWidth / viewportWidthDp) else 6f

internal fun pdfZoom(old: PdfZoom, requested: Float, focusX: Float, deltaX: Float, width: Float, limit: Float = 6f): PdfZoom {
    if (!listOf(requested, focusX, deltaX, width, limit).all { it.isFinite() } || width <= 0 || limit < 1f) return old
    val scale = requested.coerceIn(1f, limit)
    val panLimit = width * (scale - 1f) / 2f
    val focus = focusX - width / 2f
    return PdfZoom(scale, ((old.pan - focus) * scale / old.scale + focus + deltaX).coerceIn(-panLimit, panLimit))
}
internal fun pdfZoomScrollOffset(offset: Int, focus: Float, ratio: Float, panY: Float): Int {
    if (!listOf(focus, ratio, panY).all { it.isFinite() } || ratio <= 0) return offset
    // LazyColumn normalizes signed offsets across previous pages and clamps at
    // the document ends. Clamping here would move the focal point on later pages.
    return ((offset + focus) * ratio - focus - panY).roundToInt()
}

internal fun Modifier.pdfPageAccessibility(pageLabel: String, documentText: String, unavailableLabel: String,
    explainUnavailable: Boolean, actions: List<CustomAccessibilityAction>) = semantics {
    pdfPageContent(pageLabel, documentText, unavailableLabel, explainUnavailable)
    customActions = actions
}

internal fun SemanticsPropertyReceiver.pdfPageContent(pageLabel: String, documentText: String,
    unavailableLabel: String, explainUnavailable: Boolean) {
    if (documentText.isNotBlank()) {
        // TalkBack prioritizes contentDescription over text. Put both the page
        // number and readable content on the text path, with no overriding label.
        text = AnnotatedString("$pageLabel\n$documentText")
    } else contentDescription = if (explainUnavailable) "$pageLabel. $unavailableLabel" else pageLabel
}

internal data class PdfVisiblePage(val index: Int, val top: Int, val height: Int)
internal fun pdfCurrentPage(pages: List<PdfVisiblePage>, start: Int, end: Int): Int =
    (pages.maxByOrNull { (minOf(end.toLong(), it.top.toLong() + it.height) - maxOf(start, it.top)).coerceAtLeast(0) }?.index ?: 0) + 1

internal fun pdfEllipsizedTitle(title: String, fits: (String) -> Boolean): String {
    if (fits(title)) return title
    val count = title.codePointCount(0, title.length)
    fun middle(keep: Int): String {
        val prefix = title.offsetByCodePoints(0, (keep + 1) / 2)
        val suffix = title.offsetByCodePoints(title.length, -(keep / 2))
        return title.substring(0, prefix) + "…" + title.substring(suffix)
    }
    var low = 0; var high = (count - 1).coerceAtLeast(0)
    while (low < high) { val mid = (low + high + 1) / 2; if (fits(middle(mid))) low = mid else high = mid - 1 }
    return middle(low)
}

private sealed interface PdfOpenState {
    data object Loading : PdfOpenState
    data class Ready(val cache: PdfPageCache) : PdfOpenState
    data class Failed(val reason: PdfFailure) : PdfOpenState
}

/** Lives inside the existing viewer window; closing never navigates/rebuilds the chat. */
@Composable internal fun PdfReader(file: File, title: String, active: Boolean, chrome: Boolean,
    onChrome: (Boolean) -> Unit, onZoom: (Boolean) -> Unit, onClose: () -> Unit, onShare: () -> Unit,
    actionFailed: Boolean = false) {
    key(file.path) { PdfReaderContent(file, title, active, chrome, onChrome, onZoom, onClose, onShare, actionFailed) }
}

@Composable private fun PdfReaderContent(file: File, title: String, active: Boolean, chrome: Boolean,
    onChrome: (Boolean) -> Unit, onZoom: (Boolean) -> Unit, onClose: () -> Unit, onShare: () -> Unit, actionFailed: Boolean) {
    val list = rememberLazyListState()
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var pan by rememberSaveable { mutableFloatStateOf(0f) }
    var rasterScale by remember { mutableFloatStateOf(scale) }
    var previousWidth by rememberSaveable { mutableFloatStateOf(0f) }
    var retry by remember { mutableIntStateOf(0) }
    val open by produceState<PdfOpenState>(PdfOpenState.Loading, file, retry, active) {
        value = PdfOpenState.Loading
        if (!active) return@produceState
        // The owner/finally stays on IO, including across prompt cancellation of
        // the result handoff to Main; a newly opened descriptor cannot be lost.
        withContext(Dispatchers.IO) {
            var cache: PdfPageCache? = null
            try {
                cache = PdfPageCache.open(file)
                value = PdfOpenState.Ready(cache)
                awaitCancellation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { value = PdfOpenState.Failed(pdfFailure(error)) }
            catch (error: OutOfMemoryError) { value = PdfOpenState.Failed(pdfFailure(error)) }
            finally { withContext(NonCancellable) { cache?.close() } }
        }
    }
    val cache = (open as? PdfOpenState.Ready)?.cache
    val pageSizes = remember(cache) { mutableStateMapOf<Int, PdfPageSize>().apply { cache?.let { put(0, it.firstSize) } } }
    val sizeLoaded: (Int, PdfPageSize) -> Unit = remember(cache) { { index, size ->
        pageSizes[index] = size
        if (pageSizes.size > 256) pageSizes.keys.maxByOrNull { abs(it - index) }?.let { pageSizes.remove(it) }
    } }
    val tileRevision = remember(cache) { mutableIntStateOf(0) }
    var loadedTiles by remember(cache) { mutableStateOf(emptySet<PdfTileKey>()) }
    var tileAttempt by remember(cache) { mutableIntStateOf(0) }
    val page by remember { derivedStateOf {
        val layout = list.layoutInfo
        pdfCurrentPage(layout.visibleItemsInfo.map { PdfVisiblePage(it.index, it.offset, it.size) },
            layout.viewportStartOffset, layout.viewportEndOffset)
    } }
    val zoomChanged by rememberUpdatedState(onZoom)
    LaunchedEffect(scale > 1f) { zoomChanged(scale > 1f) }
    DisposableEffect(Unit) { onDispose { zoomChanged(false) } }
    val talkBack = rememberPdfTouchExploration()
    val scope = rememberCoroutineScope()
    val toggle by rememberUpdatedState(onChrome)
    val latestChrome by rememberUpdatedState(chrome)
    val closeLabel = stringResource(R.string.pdf_close)
    val shareLabel = stringResource(R.string.pdf_share)
    val toggleLabel = stringResource(if (chrome) R.string.pdf_hide_controls else R.string.pdf_show_controls)
    val zoomLabel = stringResource(R.string.pdf_zoom_in)
    val resetLabel = stringResource(R.string.pdf_zoom_reset)
    val nextLabel = stringResource(R.string.pdf_next_page)
    val previousLabel = stringResource(R.string.pdf_previous_page)
    val density = LocalDensity.current
    val topPadding = WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout).asPaddingValues().calculateTopPadding() + 62.dp

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).clipToBounds().testTag("pdf-reader")) {
        val width = constraints.maxWidth.toFloat()
        val zoomLimit = pdfZoomLimit(cache?.firstSize?.width ?: 0, maxWidth.value)
        val tilePlan by remember(cache, width, scale, rasterScale, pan, active) { derivedStateOf {
            val layout = list.layoutInfo
            if (cache == null || !active) PdfTilePlan(1, emptyList()) else pdfTilePlan(
                layout.visibleItemsInfo.mapNotNull { item -> pageSizes[item.index]?.let { PdfPageViewport(item.index, it, item.offset) } },
                width.toInt(), layout.viewportStartOffset, layout.viewportEndOffset, scale, rasterScale, pan)
        } }
        var failedTiles by remember(cache, tilePlan, tileAttempt) { mutableStateOf(emptySet<PdfTileKey>()) }
        LaunchedEffect(cache, tilePlan, active, tileAttempt) {
            cache?.setVisibleTiles(tilePlan.keys)
            loadedTiles = cache?.residentKeys().orEmpty()
            tileRevision.intValue++
            if (cache == null || !active) return@LaunchedEffect
            for (key in tilePlan.keys) {
                try {
                    val rendered = cache.render(key)
                    ensureActive()
                    if (!rendered) failedTiles = failedTiles + key
                    loadedTiles = cache.residentKeys()
                    tileRevision.intValue++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failedTiles = failedTiles + key }
                catch (_: OutOfMemoryError) { failedTiles = failedTiles + key }
            }
        }
        val before = with(density) { topPadding.toPx() }
        LaunchedEffect(width) {
            if (previousWidth > 0 && previousWidth != width) {
                list.requestScrollToItem(list.firstVisibleItemIndex, (list.firstVisibleItemScrollOffset * width / previousWidth).roundToInt())
                pan = (pan * width / previousWidth).coerceIn(-width * (scale - 1) / 2, width * (scale - 1) / 2)
            }
            previousWidth = width
        }
        fun applyZoom(requested: Float, focus: Offset, delta: Offset = Offset.Zero) {
            if (!listOf(focus.x, focus.y, delta.x, delta.y).all { it.isFinite() }) return
            val updated = pdfZoom(PdfZoom(scale, pan), requested, focus.x, delta.x, width, zoomLimit)
            val ratio = updated.scale / scale
            val offset = pdfZoomScrollOffset(list.firstVisibleItemScrollOffset, focus.y - before, ratio, delta.y)
            scale = updated.scale; pan = updated.pan
            if (ratio != 1f) list.requestScrollToItem(list.firstVisibleItemIndex, offset)
            else if (delta.y != 0f) list.dispatchRawDelta(-delta.y)
        }
        val actions = buildList {
            add(CustomAccessibilityAction(toggleLabel) { toggle(!latestChrome); true })
            add(CustomAccessibilityAction(closeLabel) { onClose(); true })
            add(CustomAccessibilityAction(shareLabel) { onShare(); true })
            if (cache != null) {
                if (scale < zoomLimit) add(CustomAccessibilityAction(zoomLabel) {
                    applyZoom(scale * 2, Offset(width / 2, constraints.maxHeight / 2f)); rasterScale = scale; true
                })
                if (scale > 1f) add(CustomAccessibilityAction(resetLabel) {
                    applyZoom(1f, Offset(width / 2, constraints.maxHeight / 2f)); rasterScale = scale; true
                })
                if (page < cache.pageCount) add(CustomAccessibilityAction(nextLabel) { scope.launch { list.scrollToItem(page) }; true })
                if (page > 1) add(CustomAccessibilityAction(previousLabel) { scope.launch { list.scrollToItem(page - 2) }; true })
            }
        }
        Box(Modifier.fillMaxSize().semantics { contentDescription = title; customActions = actions }
            .pointerInput(cache, active, width, zoomLimit) {
                if (!active || cache == null) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var transforming = false; var horizontal = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        val change = event.changes.firstOrNull()
                        if (fingers > 1) transforming = true
                        if (!transforming && scale > 1 && change != null) {
                            val travel = change.position - down.position
                            if (abs(travel.x) > viewConfiguration.touchSlop && abs(travel.x) > abs(travel.y)) horizontal = true
                        }
                        if ((transforming || horizontal) && event.changes.any { it.pressed }) {
                            val delta = event.calculatePan()
                            applyZoom(scale * event.calculateZoom(), event.calculateCentroid(useCurrent = false),
                                if (transforming) delta else Offset(delta.x, 0f))
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    rasterScale = scale
                }
            }
            .pointerInput(cache, active, width, zoomLimit) {
                if (!active) return@pointerInput
                detectTapGestures(onTap = { toggle(!latestChrome) }, onDoubleTap = { focus ->
                    if (cache != null) { applyZoom(if (scale > 1f) 1f else 2f, focus); rasterScale = scale }
                })
            }) {
            if (cache != null) LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("pdf-pages"),
                contentPadding = PaddingValues(top = topPadding, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cache.pageCount, key = { it }) { index ->
                    PdfRasterPage(cache, index, width.toInt(), scale, pan, list, active, talkBack, actions,
                        tilePlan, loadedTiles, failedTiles, tileRevision, sizeLoaded, onRetry = { tileAttempt++ })
                }
            } else if (active) Column(Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val failed = open as? PdfOpenState.Failed
                Text(stringResource(if (failed == null) R.string.pdf_loading else when (failed.reason) {
                    PdfFailure.Locked -> R.string.pdf_locked
                    PdfFailure.Memory -> R.string.pdf_memory_error
                    PdfFailure.Invalid -> R.string.pdf_open_error
                }), color = Color.White.copy(alpha = .8f), style = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.testTag("pdf-load-state"))
                if (failed != null) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.pdf_retry), color = Color.White) }
            }
        }
        if (active) PdfReaderChrome(title, page, cache?.pageCount ?: 0, chrome, onClose, onShare)
        if (active && actionFailed && chrome) Text(stringResource(R.string.pdf_share_error), color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = topPadding).background(Color.Black.copy(alpha = .8f)).padding(12.dp))
    }
}

@Composable private fun PdfRasterPage(cache: PdfPageCache, index: Int, width: Int, scale: Float,
    pan: Float, list: LazyListState, active: Boolean, talkBack: Boolean, actions: List<CustomAccessibilityAction>,
    plan: PdfTilePlan, loadedTiles: Set<PdfTileKey>, failedTiles: Set<PdfTileKey>, revision: State<Int>,
    onSize: (Int, PdfPageSize) -> Unit, onRetry: () -> Unit) {
    var attempt by remember(cache, index) { mutableIntStateOf(0) }
    var metadataFailed by remember(cache, index, attempt) { mutableStateOf(false) }
    val measuredSize by produceState<PdfPageSize?>(if (index == 0) cache.firstSize else null, cache, index, active, attempt) {
        if (active) try { value = cache.size(index).also { onSize(index, it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { metadataFailed = true }
        catch (_: OutOfMemoryError) { metadataFailed = true }
    }
    val pageSize = measuredSize ?: cache.firstSize
    val geometry = pdfRasterGeometry(width, scale, plan.width, pageSize, pan)
    val keys = remember(plan, index) { plan.keys.filter { it.page == index } }
    val failed = plan.limited || keys.any { it in failedTiles }
    val visible by remember(index, active) { derivedStateOf {
        active && list.layoutInfo.visibleItemsInfo.any { it.index == index &&
            it.offset < list.layoutInfo.viewportEndOffset && it.offset.toLong() + it.size > list.layoutInfo.viewportStartOffset }
    } }
    val text by produceState("", cache, index, talkBack, active, visible) {
        if (active && talkBack && visible) try { value = cache.text(index) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A malformed text stream must not prevent rendering. */ }
        catch (_: OutOfMemoryError) { /* Retain the labeled page and share action. */ }
    }
    val pageLabel = stringResource(R.string.pdf_page, index + 1, cache.pageCount)
    val unavailableLabel = stringResource(R.string.pdf_text_unavailable)
    val height = with(LocalDensity.current) { geometry.height.toDp() }
    Box(Modifier.fillMaxWidth().height(height).clipToBounds().background(Color.White).testTag("pdf-page-$index")
        .pdfPageAccessibility(pageLabel, text, unavailableLabel, talkBack, actions)) {
        Canvas(Modifier.fillMaxSize()) {
            revision.value
            cache.drawTiles(keys) { key, bitmap ->
                    val tile = pdfTileBounds(key, pageSize)
                    val left = (tile.left * geometry.factor + geometry.x).roundToInt(); val top = (tile.top * geometry.factor).roundToInt()
                    val right = ((tile.left + tile.width) * geometry.factor + geometry.x).roundToInt()
                    val bottom = ((tile.top + tile.height) * geometry.factor).roundToInt()
                    drawImage(bitmap.asImageBitmap(), dstOffset = IntOffset(left, top), dstSize = IntSize(right - left, bottom - top))
            }
        }
        if (visible && (metadataFailed || failed || measuredSize == null || keys.any { it !in loadedTiles })) {
            // Static feedback: a slow native render must not start an idle animation.
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            val visibleTop = (list.layoutInfo.viewportStartOffset - (item?.offset ?: 0)).coerceAtLeast(0)
            Column(Modifier.offset { IntOffset(0, visibleTop) }.padding(12.dp).background(Color.White.copy(alpha = .92f))) {
                Text(stringResource(if (failed || metadataFailed) R.string.pdf_page_error else R.string.pdf_loading), color = Color.DarkGray, fontSize = 13.sp)
                if (failed || metadataFailed) TextButton(onClick = { attempt++; onRetry() }) { Text(stringResource(R.string.pdf_retry)) }
            }
        }
    }
}

@Composable internal fun PdfReaderChrome(title: String, page: Int, pages: Int, visible: Boolean,
    onClose: () -> Unit, onShare: () -> Unit, shareEnabled: Boolean = true) {
    val duration = if (LocalChatMotionSettings.current?.reduceMotion == true) 0 else 180
    AnimatedVisibility(visible, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration))) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent))))
            BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout))
                .padding(horizontal = 8.dp, vertical = 2.dp)) {
                val titleWidth = (maxWidth - 108.dp).coerceAtLeast(0.dp)
                val style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                val measurer = rememberTextMeasurer()
                val limit = with(LocalDensity.current) { (titleWidth - 28.dp).coerceAtLeast(0.dp).toPx() }
                val displayTitle = remember(title, limit, measurer) { pdfEllipsizedTitle(title) { measurer.measure(it, style).size.width <= limit } }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PdfChromeButton(CocoIcons.Close, stringResource(R.string.pdf_close), onClose)
                    Spacer(Modifier.width(6.dp))
                    Text(displayTitle, style = style, maxLines = 1, modifier = Modifier.widthIn(max = titleWidth).pdfCapsule()
                        .height(40.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 14.dp)
                        .semantics { contentDescription = title }.testTag("pdf-filename"))
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    PdfChromeButton(MediaViewerIcons.Share, stringResource(R.string.pdf_share), onShare, shareEnabled)
                }
            }
            if (pages > 1) Text(stringResource(R.string.pdf_page_count, page, pages), color = Color.White,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout))
                    .padding(bottom = 14.dp).pdfCapsule().height(32.dp).wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 14.dp).testTag("pdf-page-counter"))
        }
    }
}

private fun Modifier.pdfCapsule() = background(Color.Black.copy(alpha = .62f), RoundedCornerShape(50))
    .border(.5.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(50))

@Composable private fun PdfChromeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Box(Modifier.size(40.dp).background(Color.Black.copy(alpha = .62f), CircleShape)
            .border(.5.dp, Color.White.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White.copy(alpha = if (enabled) 1f else .4f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable private fun rememberPdfTouchExploration(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/** PDF follows iOS chrome visibility, while the existing photo/video window policy stays independent. */
@Composable internal fun PdfReaderSystemBars(view: View, chrome: Boolean) {
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    val latestChrome by rememberUpdatedState(chrome)
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val insets = ViewCompat.getRootWindowInsets(window.decorView)
        val initialStatus = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val initialNav = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val initialBehavior = controller.systemBarsBehavior
        val initialLightStatus = controller.isAppearanceLightStatusBars
        val initialLightNav = controller.isAppearanceLightNavigationBars
        fun apply() {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.isAppearanceLightStatusBars = false; controller.isAppearanceLightNavigationBars = false
            if (latestChrome) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { if (it) apply() }
        window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        if (window.decorView.hasWindowFocus()) apply()
        onDispose {
            if (window.decorView.viewTreeObserver.isAlive) window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            controller.systemBarsBehavior = initialBehavior
            controller.isAppearanceLightStatusBars = initialLightStatus; controller.isAppearanceLightNavigationBars = initialLightNav
            if (initialStatus) controller.show(WindowInsetsCompat.Type.statusBars()) else controller.hide(WindowInsetsCompat.Type.statusBars())
            if (initialNav) controller.show(WindowInsetsCompat.Type.navigationBars()) else controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
    LaunchedEffect(window, chrome, configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        if (window.decorView.hasWindowFocus()) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (chrome) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
