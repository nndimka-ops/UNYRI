package com.messenger.app.ui.media

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.local.MediaExportStore
import com.messenger.app.data.local.OwnedMediaExport
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

private val editorColors = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFF453A, 0xFFFF9F0A, 0xFFFFD60A,
    0xFF32D74B, 0xFF64D2FF, 0xFF0A84FF, 0xFFBF5AF2).map { it.toInt() }
private val stickerEmoji = listOf("😂", "😍", "🔥", "👍", "❤️", "😎", "🤔", "😮", "😢", "🎉", "💯", "⭐️", "✅", "❌", "👀", "🙏", "💪", "😡", "🥳", "🤝", "📍", "➡️", "⬆️", "💬")

/** Event-driven canvas; no frame callbacks, polling, or idle animations. */
internal class PhotoEditorCanvas(context: android.content.Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) {
            if (field !== value) { field = value; labelAccessibility.invalidateRoot() }
        }
    var state = PhotoEditorState()
        set(value) {
            val labelsChanged = field.document.labels != value.document.labels
            field = value
            if (labelsChanged) labelAccessibility.invalidateRoot()
        }
    var tool = EditorTool.Pen
    var ink = -1
    var widthFraction = 2f / 402f
    var commit: (PhotoEditorState) -> Unit = {}
    var edit: (EditorLabel) -> Unit = {}
    var erase: (List<EditorPoint>, Float) -> Unit = { _, _ -> }
    var interactionAllowed: () -> Boolean = { true }
    private var points: MutableList<EditorPoint>? = null
    private var drag: EditorLabel? = null
    private var start: EditorPoint? = null
    private var original: EditorLabel? = null
    private var changed = false
    private var cancelledStroke = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // Native virtual children keep overlapping labels independently reachable and
    // avoid aggregating their actions on a single Compose semantics node.
    private val labelIds = mutableMapOf<String, Int>()
    private fun virtualId(label: EditorLabel) = labelIds.getOrPut(label.id) { labelIds.size + 1 }
    private fun labelBounds(label: EditorLabel): Rect {
        val f = fit()
        val layout = PhotoEditorExport.labelLayout(label, f.width)
        val textWidth = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f
        val halfWidth = maxOf(24 * resources.displayMetrics.density, textWidth / 2)
        val halfHeight = maxOf(24 * resources.displayMetrics.density, layout.height / 2f)
        val x = f.left + label.center.x * f.width; val y = f.top + label.center.y * f.height
        return Rect((x - halfWidth).toInt().coerceAtLeast(0), (y - halfHeight).toInt().coerceAtLeast(0),
            (x + halfWidth).toInt().coerceAtMost(width), (y + halfHeight).toInt().coerceAtMost(height))
    }
    private val actionIds = listOf(R.id.editor_label_edit, R.id.editor_label_left, R.id.editor_label_right,
        R.id.editor_label_up, R.id.editor_label_down, R.id.editor_label_enlarge, R.id.editor_label_shrink)
    private val actionStrings = listOf(R.string.editor_edit_label, R.string.editor_move_left, R.string.editor_move_right,
        R.string.editor_move_up, R.string.editor_move_down, R.string.editor_enlarge, R.string.editor_shrink)
    private val labelAccessibility = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = if (!isEnabled || bitmap == null) INVALID_ID else
            state.document.labels.lastOrNull { labelBounds(it).contains(x.toInt(), y.toInt()) }?.let(::virtualId) ?: INVALID_ID
        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            if (isEnabled && bitmap != null) state.document.labels.forEach { ids.add(virtualId(it)) }
        }
        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val label = state.document.labels.firstOrNull { virtualId(it) == id }
            node.text = label?.text.orEmpty()
            node.className = "android.widget.TextView"
            node.isEnabled = isEnabled && label != null
            node.setBoundsInParent(label?.let(::labelBounds) ?: Rect(0, 0, 1, 1))
            if (isEnabled && label != null) actionIds.forEachIndexed { i, action ->
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(action, context.getString(actionStrings[i], label.text)))
            }
        }
        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (!isEnabled || !interactionAllowed() || bitmap == null) return false
            val label = state.document.labels.firstOrNull { virtualId(it) == id } ?: return false
            val next = when (action) {
                R.id.editor_label_edit -> { edit(label); return true }
                R.id.editor_label_left -> label.transformed(-.05f, 0f, 1f)
                R.id.editor_label_right -> label.transformed(.05f, 0f, 1f)
                R.id.editor_label_up -> label.transformed(0f, -.05f, 1f)
                R.id.editor_label_down -> label.transformed(0f, .05f, 1f)
                R.id.editor_label_enlarge -> label.transformed(0f, 0f, 1.2f)
                R.id.editor_label_shrink -> label.transformed(0f, 0f, .8f)
                else -> return false
            }
            state = state.label(next); commit(state); invalidate()
            return true
        }
    }
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.editor_canvas)
        ViewCompat.setAccessibilityDelegate(this, labelAccessibility)
    }
    fun interactionEnabled(enabled: Boolean) {
        if (isEnabled != enabled) { isEnabled = enabled; labelAccessibility.invalidateRoot() }
    }
    override fun dispatchHoverEvent(event: MotionEvent) = labelAccessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    override fun dispatchKeyEvent(event: android.view.KeyEvent) = labelAccessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gainFocus, direction, previous)
        labelAccessibility.onFocusChanged(gainFocus, direction, previous)
    }
    private fun fit() = bitmap?.let { editorFit(it.width, it.height, width.toFloat(), height.toFloat()) }
        ?: EditorRect(0f, 0f, 0f, 0f)
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            drag = drag?.transformed(0f, 0f, detector.scaleFactor)
            changed = true; invalidate(); return true
        }
    })
    override fun onDraw(canvas: android.graphics.Canvas) {
        val image = bitmap ?: return
        val f = fit(); val rect = RectF(f.left, f.top, f.left + f.width, f.top + f.height)
        canvas.drawBitmap(image, null, rect, paint)
        var doc = state.document
        drag?.let { label -> doc = doc.copy(labels = doc.labels.map { if (it.id == label.id) label else it }) }
        if (tool != EditorTool.Eraser) points?.let { doc = doc.copy(strokes = doc.strokes + stroke(it)) }
        PhotoEditorExport.draw(canvas, rect, doc)
    }
    private fun stroke(p: List<EditorPoint>) = EditorStroke(p.toList(), when (tool) {
        EditorTool.Marker -> (ink and 0x00FFFFFF) or 0x60000000
        EditorTool.Pencil -> (ink and 0x00FFFFFF) or 0xB0000000.toInt()
        else -> ink
    }, widthFraction * if (tool == EditorTool.Marker) 4 else 1)
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) resetGesture()
        labelAccessibility.invalidateRoot()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val f = fit(); if (f.width <= 0 || f.height <= 0) return false
        val point = f.point(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled || !interactionAllowed() || !f.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                start = point; changed = false; cancelledStroke = false
                drag = state.document.labels.lastOrNull { label ->
                    val layout = PhotoEditorExport.labelLayout(label, f.width)
                    val textWidth = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f
                    abs(point.x - label.center.x) * f.width < maxOf(24 * resources.displayMetrics.density, textWidth / 2) && abs(point.y - label.center.y) * f.height < maxOf(24 * resources.displayMetrics.density, layout.height / 2f)
                }
                original = drag
                if (drag == null && !state.canBeginStroke(tool)) {
                    resetGesture()
                    announceForAccessibility(context.getString(R.string.editor_stroke_limit))
                    return true
                }
                if (drag == null) points = mutableListOf(point)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { points = null; cancelledStroke = true }
            MotionEvent.ACTION_MOVE -> {
                val from = start ?: return false
                if (drag != null && event.pointerCount == 1 && !scaler.isInProgress) {
                    val label = original ?: return false
                    drag = label.copy(center = EditorPoint(label.center.x + point.x - from.x, label.center.y + point.y - from.y).clamped(), scale = drag!!.scale)
                    if (abs(point.x - from.x) * f.width + abs(point.y - from.y) * f.height > 4 * resources.displayMetrics.density) changed = true
                } else if ((points?.size ?: 4096) < 4096) points?.add(point)
            }
            MotionEvent.ACTION_UP -> {
                when {
                    drag != null -> if (changed) commit(state.label(drag!!)) else if (!drag!!.emoji) edit(drag!!)
                    tool == EditorTool.Eraser && !cancelledStroke -> {
                        erase(points.orEmpty() + point, f.height / f.width)
                    }
                    points != null -> commit(state.stroke(stroke(points!!)))
                }
                resetGesture(); performClick()
            }
            MotionEvent.ACTION_CANCEL -> resetGesture()
        }
        scaler.onTouchEvent(event); invalidate(); return true
    }
    private fun resetGesture() { points = null; drag = null; original = null; start = null; parent?.requestDisallowInterceptTouchEvent(false) }
    override fun performClick(): Boolean { super.performClick(); return true }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotoEditorScreen(
    file: File, onEdited: (suspend (PhotoEditorResult) -> Boolean)?,
    export: suspend (OwnedMediaExport, Boolean) -> Unit, onDismiss: () -> Unit,
    source: String? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    val epoch = remember(file) { app.authRepository.currentSessionEpoch() }
    val store = remember(file) { MediaExportStore(context.cacheDir) }
    val sessionId = rememberSaveable(file.path) { java.util.UUID.randomUUID().toString() }
    val session = androidx.lifecycle.viewmodel.compose.viewModel<PhotoEditorSession>(key = "photo-editor:$sessionId")
    var state by session::state
    DisposableEffect(session) {
        var host: android.content.Context = context
        while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
        onDispose { if ((host as? android.app.Activity)?.isChangingConfigurations != true) session.release() }
    }
    var tool by rememberSaveable { mutableStateOf(EditorTool.Pen) }
    var ink by rememberSaveable { mutableIntStateOf(-1) }
    var width by rememberSaveable { mutableFloatStateOf(2f) }
    var editing by rememberSaveable(file.path) { mutableStateOf<EditorLabel?>(null) }
    var discard by remember { mutableStateOf(false) }
    var stickers by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val pendingExports by PhotoEditorComposer.Default.pending.collectAsState()
    val busy by remember(session) { derivedStateOf { exporting || session.exportToken in pendingExports } }
    var failure by remember { mutableStateOf(false) }
    var lastExportAction by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val image by produceState<Bitmap?>(null, file, retry) {
        loadFailed = false
        value = withContext(Dispatchers.IO) {
            try { com.messenger.app.data.attachment.ImageProcessor.editorPreview(file) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        loadFailed = value == null
    }
    // Editor-owned, outside the thumbnail cache. GC reclaims it after both UI and an
    // in-flight export release their references; disposal must not recycle under a worker.
    val scope = rememberCoroutineScope()
    fun close() { if (!busy) { session.cancelErasing(); if (state.document.changed) discard = true else onDismiss() } }
    fun finish(action: Int) {
        val bitmap = image ?: return
        if (busy || session.erasing) return
        val snapshot = state.document
        exporting = true; failure = false; more = false; lastExportAction = action
        scope.launch {
            var owned: OwnedMediaExport? = null
            try {
                owned = com.messenger.app.data.attachment.transferPreparedResource(Dispatchers.IO, create = {
                    app.authRepository.withWorkspaceWrite(epoch) { PhotoEditorExport.create(bitmap, snapshot, store) }
                        ?: throw CancellationException("Account changed")
                }, discard = { it.close() })
                if (app.authRepository.currentSessionEpoch() != epoch) throw CancellationException("Account changed")
                if (action == 0) {
                    val result = PhotoEditorResult(owned, source, session.exportToken)
                    owned = null
                    try { if (onEdited?.invoke(result) == true) onDismiss() else { result.close(); failure = true } }
                    catch (error: Throwable) { result.close(); throw error }
                } else export(owned, action == 2)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = true }
            finally { withContext(NonCancellable + Dispatchers.IO) { owned?.close() }; exporting = false }
        }
    }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    Dialog(onDismissRequest = { when { busy -> Unit; session.erasing -> session.cancelErasing(); editing != null -> editing = null; stickers -> stickers = false; tools -> tools = false; else -> close() } },
        properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            fun hideBars() { window?.let {
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            } }
            window?.apply {
                setLayout(-1, -1); setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
                setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                if (android.os.Build.VERSION.SDK_INT >= 30) attributes = attributes.apply {
                    setFitInsetsTypes(0)
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                WindowCompat.setDecorFitsSystemWindows(this, false)
                hideBars()
            }
            val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) hideBars() }
            window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(focus)
            onDispose { window?.decorView?.viewTreeObserver?.let { if (it.isAlive) it.removeOnWindowFocusChangeListener(focus) } }
        }
        LaunchedEffect(window, configuration.screenWidthDp, configuration.screenHeightDp) { view.requestLayout() }
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) window?.windowManager?.currentWindowMetrics?.bounds else null
        val viewport = with(density) { if (bounds != null) Modifier.requiredSize(bounds.width().toDp(), bounds.height().toDp())
            else Modifier.requiredSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp) }
        Box(viewport.background(Color.Black).testTag("photo-editor")) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout))) {
                Spacer(Modifier.height(64.dp))
                AndroidView(factory = { PhotoEditorCanvas(it) },
                    update = { canvas ->
                        if (canvas.bitmap !== image || canvas.state != state || canvas.ink != ink || canvas.tool != tool || canvas.widthFraction != width / 402f) {
                            canvas.bitmap = image; canvas.state = state; canvas.ink = ink; canvas.tool = tool; canvas.widthFraction = width / 402f; canvas.invalidate()
                        }
                        canvas.interactionEnabled(!busy && !session.erasing && editing == null && !stickers)
                        canvas.importantForAccessibility = if (editing != null || stickers) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        canvas.commit = { state = it }; canvas.edit = { session.cancelErasing(); editing = it }
                        canvas.erase = { points, aspect -> session.erase(points, aspect) }
                        canvas.interactionAllowed = { !busy && !session.erasing && editing == null && !stickers }
                    }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("editor-canvas"))
                Spacer(Modifier.height(96.dp).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility))
            }
            EditorTopBar(state, busy || image == null, onEdited != null, ::close,
                { state = state.undo() }, { state = state.redo() },
                { if (state.canAddLabel) { session.cancelErasing(); editing = EditorLabel() } },
                { if (state.canAddLabel) { session.cancelErasing(); stickers = true } },
                { state = state.clear() }, { more = true }, { finish(0) }, Modifier.align(Alignment.TopCenter).then(
                    if (editing != null || stickers) Modifier.clearAndSetSemantics { } else Modifier), canClose = !busy,
                exportEnabled = !session.erasing)
            Box(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(top = 60.dp, end = 12.dp)) {
                DropdownMenu(more, { more = false }, containerColor = Color(0xFF202020), shape = RoundedCornerShape(20.dp)) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_save), color = Color.White) }, enabled = !busy && !session.erasing && image != null, onClick = { finish(1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_share), color = Color.White) }, enabled = !busy && !session.erasing && image != null, onClick = { finish(2) })
                }
            }
            if (image != null && editing == null && !stickers) Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                if (tools) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xFF202020)).padding(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        EditorTool.entries.forEach { candidate ->
                            val label = stringResource(when (candidate) { EditorTool.Pen -> R.string.editor_pen; EditorTool.Marker -> R.string.editor_marker; EditorTool.Pencil -> R.string.editor_pencil; EditorTool.Eraser -> R.string.editor_eraser })
                            TextButton(enabled = !busy, onClick = { tool = candidate }, modifier = Modifier.semantics { selected = tool == candidate }) { Text(label, color = if (tool == candidate) Color.White else Color(0xFFB0B0B0)) }
                        }
                    }
                    EditorPalette(ink, !busy) { ink = it }
                    val widthLabel = stringResource(R.string.editor_width)
                    Slider(width, { width = it }, enabled = !busy, valueRange = 1f..16f, modifier = Modifier.semantics { contentDescription = widthLabel })
                }
                EditorButton(EditorGlyph.ToolNib, R.string.editor_tools, !busy, { tools = !tools }, Modifier.size(80.dp), surface = true, glyphSize = 64)
            }
            if (loadFailed || failure) Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .8f)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(if (loadFailed) R.string.editor_load_failed else R.string.editor_export_failed), color = Color.White)
                TextButton(enabled = !busy && !session.erasing, onClick = { if (loadFailed) retry++ else finish(lastExportAction) }) { Text(stringResource(R.string.retry)) }
            }
            if (busy) Text(stringResource(R.string.editor_exporting), color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .7f)).padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite })
            if (session.erasing || session.eraseFailed) Row(Modifier.align(Alignment.Center).background(Color(0xFF202020), RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (session.erasing) R.string.editor_erasing else R.string.editor_erase_failed), color = Color.White,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = session::cancelErasing) { Text(stringResource(if (session.erasing) R.string.cancel else R.string.dismiss)) }
            }
            if (!state.canDraw || !state.canAddLabel) Column(Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 112.dp)
                .background(Color(0xFF202020), RoundedCornerShape(12.dp)).padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                if (!state.canDraw) Text(stringResource(R.string.editor_stroke_limit), color = Color.White)
                if (!state.canAddLabel) Text(stringResource(R.string.editor_label_limit), color = Color.White)
            }
            if (editing != null) EditorLabelEntry(session, editing!!, { editing = null }, { editing = null })
            if (stickers) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)).clickable { stickers = false }) {
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF202020)).padding(16.dp)) {
                    if (!state.canAddLabel) Text(stringResource(R.string.editor_label_limit), color = Color.White)
                    LazyVerticalGrid(GridCells.Fixed(6)) {
                        items(stickerEmoji) { emoji -> TextButton(enabled = state.canAddLabel, onClick = {
                            if (session.trySaveLabel(EditorLabel(text = emoji, center = EditorPoint(.5f, .45f), scale = 1.6f, emoji = true))) stickers = false
                        }) { Text(emoji, fontSize = 34.sp) } }
                    }
                }
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.editor_discard_title)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_discard)) } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.editor_keep)) } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EditorTextEntry(label: EditorLabel, cancel: () -> Unit, error: String? = null, done: (EditorLabel) -> Unit) {
    var draft by rememberSaveable(label.id) { mutableStateOf(label) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val complete by rememberUpdatedState { done(draft) }
    LaunchedEffect(label.id) { focus.requestFocus() }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)).pointerInput(label.id) { detectTapGestures { complete() } }.imePadding().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = cancel) { Text(stringResource(R.string.cancel), color = Color.White.copy(alpha = .8f), fontSize = 17.sp) }
            TextButton(onClick = { done(draft) }) { Text(stringResource(R.string.done), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val description = stringResource(R.string.editor_text)
            BasicTextField(draft.text, { draft = draft.copy(text = it.take(512)) }, textStyle = TextStyle(color = Color(draft.color), fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White), modifier = Modifier.fillMaxWidth().padding(24.dp).focusRequester(focus).testTag("editor-text-input").semantics { contentDescription = description })
        }
        if (error != null) Text(error, color = Color.White, modifier = Modifier.padding(horizontal = 20.dp).semantics { liveRegion = LiveRegionMode.Polite })
        EditorPalette(draft.color, true) { draft = draft.copy(color = it) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable internal fun EditorLabelEntry(session: PhotoEditorSession, label: EditorLabel, cancel: () -> Unit, saved: () -> Unit) {
    var rejected by rememberSaveable(label.id) { mutableStateOf(false) }
    EditorTextEntry(label, cancel, error = if (rejected) stringResource(R.string.editor_label_limit) else null) { draft ->
        if (session.trySaveLabel(draft)) saved() else rejected = true
    }
}

@Composable private fun EditorPalette(selectedColor: Int, enabled: Boolean, select: (Int) -> Unit) {
    val names = listOf(R.string.editor_white, R.string.editor_black, R.string.editor_red, R.string.editor_orange, R.string.editor_yellow, R.string.editor_green, R.string.editor_cyan, R.string.editor_blue, R.string.editor_purple)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
        editorColors.forEachIndexed { index, color ->
            val label = stringResource(names[index])
            Box(Modifier.size(42.dp, 48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.RadioButton) { select(color) }.semantics { contentDescription = label; selected = color == selectedColor }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(30.dp).background(Color(color), CircleShape).border(if (selectedColor == color) 3.dp else 1.dp, Color.White, CircleShape))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EditorTopBar(state: PhotoEditorState, disabled: Boolean, canAttach: Boolean, close: () -> Unit, undo: () -> Unit, redo: () -> Unit,
    text: () -> Unit, sticker: () -> Unit, clear: () -> Unit, more: () -> Unit, attach: () -> Unit, modifier: Modifier, canClose: Boolean = !disabled,
    exportEnabled: Boolean = true) {
    BoxWithConstraints(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout)).padding(horizontal = 8.dp, vertical = 8.dp)) {
    val toolsWidth = (maxWidth - if (canAttach) 168.dp else 114.dp).coerceIn(100.dp, 208.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        EditorButton(EditorGlyph.Close, R.string.editor_close, canClose, close, surface = true)
        Row(Modifier.width(toolsWidth).clip(CircleShape).background(Color(0xFF202020)).border(.5.dp, Color.White.copy(alpha = .12f), CircleShape)) {
            EditorButton(EditorGlyph.Undo, R.string.editor_undo, !disabled && state.canUndo, undo, Modifier.weight(1f))
            EditorButton(EditorGlyph.Redo, R.string.editor_redo, !disabled && state.canRedo, redo, Modifier.weight(1f))
            EditorButton(EditorGlyph.Text, R.string.editor_text, !disabled && state.canAddLabel, text, Modifier.weight(1f))
            EditorButton(EditorGlyph.Sticker, R.string.editor_sticker, !disabled && state.canAddLabel, sticker, Modifier.weight(1f))
            EditorButton(EditorGlyph.Trash, R.string.editor_clear, !disabled && state.document.changed, clear, Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        EditorButton(EditorGlyph.More, R.string.editor_more, !disabled, more, surface = true)
        if (canAttach) EditorButton(EditorGlyph.Up, R.string.editor_attach, !disabled && exportEnabled, attach, surface = true, white = true)
    }
    }
}

internal enum class EditorGlyph { Close, Undo, Redo, Text, Sticker, Trash, More, Up, Pen, ToolNib }
@Composable internal fun EditorButton(glyph: EditorGlyph, label: Int, enabled: Boolean, action: () -> Unit, modifier: Modifier = Modifier, surface: Boolean = false, white: Boolean = false, glyphSize: Int = 20) {
    val description = stringResource(label)
    Box(modifier.defaultMinSize(minWidth = if (surface) 48.dp else 40.dp, minHeight = 48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = action).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (surface) Box(Modifier.matchParentSize().padding(2.dp).background(if (white) Color.White else Color(0xFF202020), CircleShape).border(.5.dp, Color.White.copy(alpha = .12f), CircleShape))
        val color = (if (white) Color.Black else Color.White).copy(alpha = if (enabled) 1f else .35f)
        if (glyph == EditorGlyph.Text) Text("Aa", color = color, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        else Canvas(Modifier.size(glyphSize.dp)) {
            fun p(x: Float, y: Float) = Offset(x * size.width / 24, y * size.height / 24)
            fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, p(x, y), p(xx, yy), size.width * .09f, StrokeCap.Round)
            when (glyph) {
                EditorGlyph.Close -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
                EditorGlyph.Up -> { line(12f, 21f, 12f, 3f); line(5f, 10f, 12f, 3f); line(12f, 3f, 19f, 10f) }
                EditorGlyph.More -> listOf(4f, 12f, 20f).forEach { drawCircle(color, size.width * .08f, p(it, 12f)) }
                EditorGlyph.Trash -> { line(5f, 6f, 19f, 6f); line(9f, 3f, 15f, 3f); line(7f, 6f, 8f, 21f); line(8f, 21f, 16f, 21f); line(17f, 6f, 16f, 21f); line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f) }
                EditorGlyph.Undo, EditorGlyph.Redo -> {
                    val mirror = glyph == EditorGlyph.Redo
                    fun x(v: Float) = if (mirror) 24 - v else v
                    line(x(9f), 3f, x(3f), 9f); line(x(3f), 9f, x(9f), 15f)
                    val path = androidx.compose.ui.graphics.Path().apply { moveTo(p(x(3f), 9f).x, p(0f, 9f).y); cubicTo(p(x(24f), 4f).x, p(0f, 4f).y, p(x(24f), 24f).x, p(0f, 24f).y, p(x(11f), 20f).x, p(0f, 20f).y) }
                    drawPath(path, color, style = Stroke(size.width * .09f, cap = StrokeCap.Round))
                }
                EditorGlyph.Sticker -> { drawCircle(color, size.width * .46f); drawCircle(Color.Black, size.width * .055f, p(8f, 9f)); drawCircle(Color.Black, size.width * .055f, p(16f, 9f)); drawArc(Color.Black, 15f, 150f, false, p(7f, 10f), androidx.compose.ui.geometry.Size(size.width * .42f, size.height * .3f), style = Stroke(size.width * .07f)) }
                EditorGlyph.Pen -> { line(9f, 20f, 12f, 2f); line(12f, 2f, 15f, 20f); line(9f, 20f, 15f, 20f); line(9f, 17f, 15f, 17f) }
                EditorGlyph.ToolNib -> {
                    val body = androidx.compose.ui.graphics.Path().apply { moveTo(p(9f, 24f).x, p(0f, 24f).y); lineTo(p(9f, 15f).x, p(0f, 15f).y); lineTo(p(12f, 2f).x, p(0f, 2f).y); lineTo(p(15f, 15f).x, p(0f, 15f).y); lineTo(p(15f, 24f).x, p(0f, 24f).y); close() }
                    drawPath(body, androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF242424), Color(0xFF555555), Color(0xFF242424))))
                    val nib = androidx.compose.ui.graphics.Path().apply { moveTo(p(11.2f, 5f).x, p(0f, 5f).y); lineTo(p(12f, 2f).x, p(0f, 2f).y); lineTo(p(12.8f, 5f).x, p(0f, 5f).y); close() }
                    drawPath(nib, color)
                    drawLine(color, p(9f, 17f), p(15f, 17f), size.width * .04f)
                }
                else -> Unit
            }
        }
    }
}
