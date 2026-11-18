package com.messenger.app.ui.chats

import android.graphics.*
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.messenger.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Normalized vectors survive canvas resizing. No image is decoded by the view. */
internal class MediaMarkupView(context: android.content.Context) : View(context) {
    data class Mark(val points: List<PointF>, val color: Int, val width: Float, val text: String? = null)
    var image: Bitmap? = null
    var ink = Color.WHITE
    var marker = false
    var erasing = false
    private val lock = Any()
    private val marks = mutableListOf<Mark>()
    private val undone = mutableListOf<Mark>()
    private var current: MutableList<PointF>? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun fit(w: Int, h: Int, bitmap: Bitmap): RectF {
        val scale = minOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val iw = bitmap.width * scale; val ih = bitmap.height * scale
        return RectF((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2)
    }
    fun undo() { synchronized(lock) { if (marks.isNotEmpty()) undone.add(marks.removeAt(marks.lastIndex)) }; invalidate() }
    fun redo() { synchronized(lock) { if (undone.isNotEmpty()) marks.add(undone.removeAt(undone.lastIndex)) }; invalidate() }
    fun addLabel(text: String) { if (text.isNotBlank()) synchronized(lock) { add(Mark(listOf(PointF(.5f, .5f)), ink, .04f, text.take(256))) }; invalidate() }
    private fun add(mark: Mark) { marks.add(mark); while (marks.size > 128) marks.removeAt(0); undone.clear() }
    private fun drawMarks(canvas: android.graphics.Canvas, rect: RectF, snapshot: List<Mark>) {
        for (mark in snapshot) {
            paint.color = mark.color; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            if (mark.text != null) {
                paint.style = Paint.Style.FILL; paint.textSize = mark.width * rect.width(); paint.textAlign = Paint.Align.CENTER
                val point = mark.points.first(); canvas.drawText(mark.text, rect.left + point.x * rect.width(), rect.top + point.y * rect.height(), paint)
            } else {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = mark.width * rect.width()
                val path = android.graphics.Path()
                mark.points.forEachIndexed { i, point -> val x = rect.left + point.x * rect.width(); val y = rect.top + point.y * rect.height(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                canvas.drawPath(path, paint)
            }
        }
    }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val bitmap = image ?: return
        val rect = fit(width, height, bitmap)
        paint.style = Paint.Style.FILL; paint.alpha = 255; canvas.drawBitmap(bitmap, null, rect, paint)
        val snapshot = synchronized(lock) { marks.toList() + listOfNotNull(current?.let { Mark(it.toList(), ink, if (marker) .025f else .006f) }) }
        canvas.save(); canvas.clipRect(rect); drawMarks(canvas, rect, snapshot); canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = image ?: return false
        val rect = fit(width, height, bitmap)
        val point = PointF(((event.x - rect.left) / rect.width()).coerceIn(0f, 1f), ((event.y - rect.top) / rect.height()).coerceIn(0f, 1f))
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (!rect.contains(event.x, event.y)) return false; current = mutableListOf(point); parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> { if ((current?.size ?: 2048) < 2048) current?.add(point) }
            MotionEvent.ACTION_UP -> { synchronized(lock) {
                if (erasing) { val i = marks.indexOfLast { mark -> mark.points.any { kotlin.math.abs(it.x - point.x) + kotlin.math.abs(it.y - point.y) < .08f } }; if (i >= 0) undone.add(marks.removeAt(i)) }
                else current?.let { add(Mark(it.toList(), ink, if (marker) .025f else .006f)) }
            }; current = null; parent?.requestDisallowInterceptTouchEvent(false); performClick() }
            MotionEvent.ACTION_CANCEL -> current = null
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    /** Called on Dispatchers.Default, over a stable vector snapshot. */
    fun render(bitmap: Bitmap): Bitmap {
        val snapshot = synchronized(lock) { marks.toList() }
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(output)
        // Export uses its own paint/view-independent renderer to avoid UI races.
        val renderer = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        for (mark in snapshot) {
            renderer.color = mark.color
            if (mark.text != null) { renderer.style = Paint.Style.FILL; renderer.textAlign = Paint.Align.CENTER; renderer.textSize = mark.width * output.width; val p = mark.points.first(); canvas.drawText(mark.text, p.x * output.width, p.y * output.height, renderer) }
            else { renderer.style = Paint.Style.STROKE; renderer.strokeWidth = mark.width * output.width; val path = android.graphics.Path(); mark.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * output.width, p.y * output.height) else path.lineTo(p.x * output.width, p.y * output.height) }; canvas.drawPath(path, renderer) }
        }
        return output
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun MediaMarkupDialog(file: File, send: (suspend (File) -> Boolean)?, export: suspend (com.messenger.app.data.local.OwnedMediaExport, Boolean) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as com.messenger.app.MessengerApp
    val epoch = remember(file) { app.authRepository.currentSessionEpoch() }
    val exports = remember(file) { com.messenger.app.data.local.MediaExportStore(context.cacheDir) }
    val canvas = remember(file) { MediaMarkupView(context) }
    var text by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val image by produceState<Bitmap?>(null, file) {
        value = com.messenger.app.data.local.ChatImageCache.load("editor2560:" + file.path) { com.messenger.app.data.attachment.ImageProcessor.editorPreview(file) }
    }
    fun finish(action: Int) {
        val bitmap = image ?: return
        if (busy) return
        busy = true
        scope.launch {
            var output: com.messenger.app.data.local.OwnedMediaExport? = null
            try {
                val prepared = com.messenger.app.data.attachment.transferPreparedResource(Dispatchers.IO, create = {
                    app.authRepository.withWorkspaceWrite(epoch) {
                        val rendered = canvas.render(bitmap)
                        try { exports.create { target -> target.outputStream().use { check(rendered.compress(Bitmap.CompressFormat.JPEG, 94, it)) } } }
                        finally { rendered.recycle() }
                    } ?: throw kotlinx.coroutines.CancellationException("Account changed")
                }, discard = { it.close() })
                output = prepared
                if (action == 0) {
                    if (send?.invoke(prepared.file) == true) dismiss() else error = true
                } else export(prepared, action == 2)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { output?.close() }
                busy = false
            }
        }

    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = androidx.compose.ui.graphics.Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.dismiss)) }
                    TextButton(onClick = { canvas.undo() }) { Text(stringResource(R.string.media_markup_undo)) }
                    TextButton(onClick = { canvas.redo() }) { Text(stringResource(R.string.media_markup_redo)) }
                    if (send != null) TextButton(enabled = !busy && image != null, onClick = { finish(0) }) { Text(stringResource(R.string.send)) }
                }
                AndroidView(factory = { canvas }, update = { it.image = image; it.invalidate() }, modifier = Modifier.weight(1f).fillMaxWidth())
                if (error) Text(stringResource(R.string.media_action_failed))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { canvas.marker = false; canvas.erasing = false }) { Text(stringResource(R.string.media_markup_pen)) }
                    TextButton(onClick = { canvas.marker = true; canvas.erasing = false }) { Text(stringResource(R.string.media_markup_marker)) }
                    TextButton(onClick = { canvas.erasing = true }) { Text(stringResource(R.string.media_markup_eraser)) }
                    TextButton(onClick = { text = "" }) { Text(stringResource(R.string.media_markup_text)) }
                    listOf("😂", "❤️", "🔥", "👍").forEach { emoji -> TextButton(onClick = { canvas.addLabel(emoji) }) { Text(emoji) } }
                    TextButton(enabled = !busy, onClick = { finish(1) }) { Text(stringResource(R.string.media_save_gallery)) }
                    TextButton(enabled = !busy, onClick = { finish(2) }) { Text(stringResource(R.string.media_share)) }
                }
            }
        }
    }
    text?.let { value -> AlertDialog(onDismissRequest = { text = null }, title = { Text(stringResource(R.string.media_markup_text)) },
        text = { TextField(value, onValueChange = { text = it.take(256) }) },
        confirmButton = { TextButton(onClick = { canvas.addLabel(value); text = null }) { Text(stringResource(R.string.media_markup_add)) } }) }
}
