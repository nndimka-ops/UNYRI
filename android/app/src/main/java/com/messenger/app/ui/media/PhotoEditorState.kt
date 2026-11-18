package com.messenger.app.ui.media

import java.io.Serializable
import java.util.UUID
import kotlin.math.min
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*

/** Image-relative vectors, never screen pixels. A resize changes only the fitted rectangle. */
internal data class EditorPoint(val x: Float, val y: Float) : Serializable {
    fun clamped() = EditorPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}
internal data class EditorStroke(val points: List<EditorPoint>, val color: Int, val width: Float) : Serializable
internal data class EditorLabel(
    val id: String = UUID.randomUUID().toString(), val text: String = "", val color: Int = -1,
    val center: EditorPoint = EditorPoint(.5f, .4f), val size: Float = 30f / 402f,
    val scale: Float = 1f, val emoji: Boolean = false,
) : Serializable {
    fun transformed(dx: Float, dy: Float, zoom: Float) = copy(
        center = EditorPoint(center.x + dx, center.y + dy).clamped(),
        scale = (scale * zoom).coerceIn(.5f, 6f),
    )
}
internal data class EditorDocument(val strokes: List<EditorStroke> = emptyList(), val labels: List<EditorLabel> = emptyList()) : Serializable {
    val changed get() = strokes.isNotEmpty() || labels.isNotEmpty()
}
internal enum class EditorTool { Pen, Marker, Pencil, Eraser }

/** Every completed gesture is one transaction, including label edits/moves and clear. */
internal data class PhotoEditorState(
    val document: EditorDocument = EditorDocument(),
    val past: List<EditorDocument> = emptyList(), val future: List<EditorDocument> = emptyList(),
) : Serializable {
    val canDraw get() = document.strokes.size < 128
    fun canBeginStroke(tool: EditorTool) = tool == EditorTool.Eraser || canDraw
    val canAddLabel get() = document.labels.size < 64
    fun canSaveLabel(label: EditorLabel) = label.text.isBlank() || canAddLabel || document.labels.any { it.id == label.id }
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun commit(next: EditorDocument): PhotoEditorState = if (next == document) this else
        PhotoEditorState(next, (past + document).takeLast(64), emptyList())
    fun stroke(stroke: EditorStroke) = if (stroke.points.isEmpty() || !canDraw) this else
        commit(document.copy(strokes = document.strokes + stroke.copy(points = stroke.points.take(4096).map { it.clamped() })))
    fun label(label: EditorLabel): PhotoEditorState {
        if (!canSaveLabel(label)) return this
        val remaining = document.labels.filterNot { it.id == label.id }
        val next = if (label.text.isBlank()) remaining else {
            remaining + label.copy(text = label.text.take(512), center = label.center.clamped(), scale = label.scale.coerceIn(.5f, 6f))
        }
        return commit(document.copy(labels = next))
    }
    fun clear() = commit(EditorDocument())
    fun eraseAt(point: EditorPoint, aspect: Float = 1f) = commit(eraseEditorGesture(document, listOf(point), aspect))
    fun undo() = if (!canUndo) this else PhotoEditorState(past.last(), past.dropLast(1), future + document)
    fun redo() = if (!canRedo) this else PhotoEditorState(future.last(), past + document, future.dropLast(1))
}

/** Whole-stroke eraser. Bounds reject misses; no per-sample documents/history or bitmaps.
 * The production caller runs this on its worker dispatcher, never in a touch callback. */
internal fun eraseEditorGesture(document: EditorDocument, points: List<EditorPoint>, aspect: Float,
    checkpoint: () -> Unit = {}): EditorDocument {
    require(aspect.isFinite() && aspect > 0f)
    if (points.isEmpty() || document.strokes.isEmpty()) return document
    val bounds = FloatArray(document.strokes.size * 4)
    val removed = BooleanArray(document.strokes.size)
    val radius = .03f; val radiusY = radius / aspect
    var work = 0
    fun checkWork() { if (work++ and 1023 == 0) checkpoint() }
    for ((i, stroke) in document.strokes.withIndex()) {
        var left = Float.POSITIVE_INFINITY; var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
        for (point in stroke.points) {
            checkWork()
            left = minOf(left, point.x); right = maxOf(right, point.x)
            top = minOf(top, point.y); bottom = maxOf(bottom, point.y)
        }
        bounds[i * 4] = left - radius; bounds[i * 4 + 1] = top - radiusY
        bounds[i * 4 + 2] = right + radius; bounds[i * 4 + 3] = bottom + radiusY
    }
    for (point in points.take(4096)) {
        checkpoint()
        for (i in document.strokes.indices.reversed()) {
            checkWork()
            if (removed[i] || point.x < bounds[i * 4] || point.x > bounds[i * 4 + 2] ||
                point.y < bounds[i * 4 + 1] || point.y > bounds[i * 4 + 3]) continue
            val stroke = document.strokes[i]
            var hit = false
            for (j in stroke.points.indices) {
                checkWork()
                val a = stroke.points[if (j == 0) 0 else j - 1]; val b = stroke.points[j]
                if (point.x < minOf(a.x, b.x) - radius || point.x > maxOf(a.x, b.x) + radius ||
                    point.y < minOf(a.y, b.y) - radiusY || point.y > maxOf(a.y, b.y) + radiusY) continue
                val dx = b.x - a.x; val dy = (b.y - a.y) * aspect
                val px = point.x - a.x; val py = (point.y - a.y) * aspect
                val length = dx * dx + dy * dy
                val t = if (length == 0f) 0f else ((px * dx + py * dy) / length).coerceIn(0f, 1f)
                if ((px - dx * t) * (px - dx * t) + (py - dy * t) * (py - dy * t) <= radius * radius) {
                    hit = true; break
                }
            }
            if (hit) { removed[i] = true; break } // Preserve the topmost-stroke-per-sample behavior.
        }
    }
    checkpoint()
    return if (removed.none { it }) document else document.copy(strokes = document.strokes.filterIndexed { i, _ -> !removed[i] })
}

/** One cancellable worker, immutable vectors shared with history, no image-sized allocations. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PhotoEditorSession(
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val scopeOverride: CoroutineScope? = null,
    private val eraseDocument: (EditorDocument, List<EditorPoint>, Float, () -> Unit) -> EditorDocument = ::eraseEditorGesture,
) : ViewModel() {
    private var currentState by mutableStateOf(PhotoEditorState())
    var exportToken by mutableStateOf(UUID.randomUUID().toString())
        private set
    var state: PhotoEditorState
        get() = currentState
        set(value) { if (value !== currentState) {
            cancelErasing()
            if (value.document != currentState.document) exportToken = UUID.randomUUID().toString()
            currentState = value
        } }
    var erasing by mutableStateOf(false)
        private set
    var eraseFailed by mutableStateOf(false)
        private set
    private var eraseJob: Job? = null
    private var eraseToken: Any? = null

    fun trySaveLabel(label: EditorLabel): Boolean {
        if (!state.canSaveLabel(label)) return false
        state = state.label(label)
        return true
    }
    fun erase(points: List<EditorPoint>, aspect: Float): Job? {
        cancelErasing()
        if (points.isEmpty()) return null
        val snapshot = state
        // Keep the release location even when a long gesture fills the move-sample cap.
        val samples = ArrayList(points.take(4096))
        if (points.size > 4096) samples[samples.lastIndex] = points.last()
        val token = Any(); eraseToken = token; erasing = true
        val job = (scopeOverride ?: viewModelScope).launch {
            try {
                val result = withContext(workerDispatcher) {
                    val context = currentCoroutineContext()
                    eraseDocument(snapshot.document, samples, aspect) { context.ensureActive() }
                }
                if (eraseToken === token && currentState === snapshot) {
                    if (result != snapshot.document) exportToken = UUID.randomUUID().toString()
                    currentState = snapshot.commit(result) // One gesture, one undo step.
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (eraseToken === token) eraseFailed = true }
            finally {
                if (eraseToken === token) { eraseToken = null; eraseJob = null; erasing = false }
            }
        }
        if (eraseToken === token) eraseJob = job
        return job
    }
    fun cancelErasing() {
        eraseToken = null; eraseJob?.cancel(); eraseJob = null
        erasing = false; eraseFailed = false
    }
    fun release() { cancelErasing(); currentState = PhotoEditorState(); exportToken = UUID.randomUUID().toString() }
}

internal data class EditorRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun point(x: Float, y: Float) = EditorPoint((x - left) / width, (y - top) / height).clamped()
    fun contains(x: Float, y: Float) = x >= left && x <= left + width && y >= top && y <= top + height
}
internal fun editorFit(width: Int, height: Int, areaWidth: Float, areaHeight: Float): EditorRect {
    if (width <= 0 || height <= 0 || areaWidth <= 0 || areaHeight <= 0) return EditorRect(0f, 0f, 0f, 0f)
    val scale = min(areaWidth / width, areaHeight / height)
    val w = width * scale; val h = height * scale
    return EditorRect((areaWidth - w) / 2, (areaHeight - h) / 2, w, h)
}
