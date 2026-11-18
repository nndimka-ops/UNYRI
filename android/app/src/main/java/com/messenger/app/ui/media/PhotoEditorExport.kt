package com.messenger.app.ui.media

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.messenger.app.data.local.MediaExportStore
import com.messenger.app.data.local.OwnedMediaExport
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import com.messenger.app.data.attachment.MediaPreparationCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import com.messenger.app.data.attachment.ComposerDrafts
import com.messenger.app.data.attachment.ComposerTraySnapshot
import com.messenger.app.MessengerApp
import android.net.Uri
import java.util.UUID

/** Temporary content-URI preview; streaming IO, capped disk use, original URI retained as identity. */
internal suspend fun copyEditorPreview(context: android.content.Context, source: Uri): OwnedMediaExport {
    val worker = currentCoroutineContext()
    return MediaExportStore(context.cacheDir).create { file ->
        context.contentResolver.openInputStream(source)?.use { input -> file.outputStream().use { output ->
            com.messenger.app.data.attachment.copyBoundedFile(input, output, MediaExportStore.MAX_BYTES) { worker.ensureActive() }
        } } ?: throw java.io.IOException("Photo unavailable")
    }
}

/** Admission transfers file ownership; only durable completion permits editor dismissal. */
internal class PhotoEditorResult(private val owned: OwnedMediaExport, val source: String? = null,
    val operationId: String = UUID.randomUUID().toString()) : AutoCloseable {
    val file: File get() = owned.file
    private val ownership = AtomicInteger(0) // 0 editor, 1 application importer, 2 released
    override fun close() { if (ownership.compareAndSet(0, 2)) owned.close() }
    private fun releaseImport() { if (ownership.getAndSet(2) != 2) owned.close() }

    suspend fun importToComposer(coordinator: MediaPreparationCoordinator, chat: String, source: String,
        epoch: Int, maximumItems: Int, onFinished: () -> Unit = {}, currentEpoch: () -> Int): Boolean {
        if (!ownership.compareAndSet(0, 1)) { onFinished(); return false }
        val completed = CompletableDeferred<Boolean>()
        fun durable() = currentEpoch() == epoch && coordinator.state.value[chat]?.items?.any {
            it.source == source && it.staged != null
        } == true
        var admitted = false
        try {
            admitted = coordinator.start(chat, listOf(source), expectedEpoch = epoch,
                append = true, maximumItems = maximumItems,
                cleanup = {
                    releaseImport()
                    onFinished()
                    // Invalidated operations suppress their normal completion callbacks.
                    if (currentEpoch() != epoch) completed.complete(false)
                }, onComplete = { completed.complete(durable()) }, onFailure = {
                    // A marker-clear failure after a successful Room commit must not make
                    // Retry add the same edit again. The committed slot is authoritative.
                    completed.complete(durable())
                })
            if (!admitted) { releaseImport(); return false }
            return completed.await()
        } catch (error: Throwable) {
            // UI cancellation cannot delete an application-owned copy source mid-import.
            if (!admitted) releaseImport()
            throw error
        }
    }
}

internal data class PhotoEditorPlacement(val photos: List<String>, val order: List<String>, val replaced: String?)

/** A pure journal edit: replace one visible photo slot, or append; never consume text. */
internal fun placeEditedPhoto(stored: ComposerDrafts, tray: ComposerTraySnapshot, original: String?, edited: String): PhotoEditorPlacement {
    val replacing = original?.takeIf { it in tray.photos }
    check(replacing != null || tray.ordered.size < 10) { "Composer is full" }
    val photos = if (replacing in stored.photos) stored.photos.map { if (it == replacing) edited else it }
        else stored.photos + edited
    val order = if (replacing != null) tray.ordered.map { if (it == replacing) edited else it } else tray.ordered + edited
    return PhotoEditorPlacement(photos.distinct(), order.distinct(), replacing)
}

/** This adapter deliberately has no message/outbox/send operation. */
internal interface PhotoEditorComposerStore {
    val chat: String
    val epoch: Int
    fun currentEpoch(): Int
    suspend fun stage(file: File): String
    suspend fun place(original: String?, edited: String): PhotoEditorPlacement
    suspend fun contains(edited: String): Boolean
    suspend fun discard(staged: String)
    fun retire(original: String?)
}

/** Application-owned one-photo imports; order metadata is event-driven and bounded. */
internal class PhotoEditorComposer(private val scope: CoroutineScope) {
    data class Key(val chat: String, val epoch: Int)
    private val mutableOrders = MutableStateFlow<Map<Key, List<String>>>(emptyMap())
    val orders = mutableOrders.asStateFlow()
    private val mutablePending = MutableStateFlow<Set<String>>(emptySet())
    val pending = mutablePending.asStateFlow()
    private val active = mutableSetOf<Key>()
    private val completed = linkedMapOf<Pair<Key, String>, String>()

    fun project(tray: ComposerTraySnapshot, key: Key): ComposerTraySnapshot = project(tray, mutableOrders.value[key].orEmpty())
    suspend fun accept(result: PhotoEditorResult, store: PhotoEditorComposerStore): Boolean {
        val key = Key(store.chat, store.epoch)
        if (store.currentEpoch() != store.epoch || !active.add(key)) { result.close(); return false }
        mutablePending.value += result.operationId
        var handedOff = false
        fun finished() { active.remove(key); mutablePending.value -= result.operationId }
        try {
            completed[key to result.operationId]?.let { staged ->
                if (store.contains(staged)) { result.close(); return true }
            }
            // A private preparation owner stages and commits exactly one replacement.
            // It never appends a transient second pick to the shared composer journal.
            val importer = MediaPreparationCoordinator(scope, store::currentEpoch,
                stage = { _, _ -> store.stage(result.file) },
                commit = { _, _, staged, _ ->
                    val placement = store.place(result.source, staged)
                    if (store.currentEpoch() != store.epoch) throw CancellationException("Account changed")
                    store.retire(placement.replaced)
                    mutableOrders.value = (mutableOrders.value.filterKeys { it.epoch == store.epoch } + (key to placement.order))
                        .entries.toList().takeLast(64).associate { it.toPair() }
                    completed[key to result.operationId] = staged
                    while (completed.size > 128) completed.remove(completed.keys.first())
                }, marker = { _, _, _ -> }, discard = { _, staged -> store.discard(staged) })
            handedOff = true
            return result.importToComposer(importer, store.chat, result.file.path, store.epoch, 1,
                onFinished = ::finished, currentEpoch = store::currentEpoch)
        } finally { if (!handedOff) finished() }
    }

    companion object {
        val Default by lazy { PhotoEditorComposer(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
        fun project(tray: ComposerTraySnapshot, preferred: List<String>): ComposerTraySnapshot {
            if (preferred.isEmpty()) return tray
            val present = tray.ordered.toSet()
            val order = preferred.filter { it in present } + tray.ordered.filterNot { it in preferred }
            val photos = tray.photos.toSet()
            return tray.copy(ordered = order, photos = order.filter { it in photos }, files = order.filterNot { it in photos })
        }
    }
}

internal fun photoEditorComposerStore(app: MessengerApp, chatId: String, expectedEpoch: Int): PhotoEditorComposerStore =
    object : PhotoEditorComposerStore {
        override val chat = chatId
        override val epoch = expectedEpoch
        override fun currentEpoch() = app.authRepository.currentSessionEpoch()
        private suspend fun drafts() = ComposerDrafts.decode(app.messageRepository.chatValue(chat, "mediaDrafts"),
            app.messageRepository.chatValue(chat, "fileDrafts"), app.messageRepository.chatValue(chat, "fileDraftUri"))
        override suspend fun stage(file: File): String = withContext(Dispatchers.IO) {
            app.authRepository.withWorkspaceWrite(epoch) { app.attachmentManager.stageDraft(Uri.fromFile(file)).toString() }
                ?: throw CancellationException("Account changed")
        }
        override suspend fun contains(edited: String): Boolean = withContext(Dispatchers.IO) {
            app.authRepository.withWorkspaceWrite(epoch) { edited in drafts().photos } == true
        }
        override suspend fun place(original: String?, edited: String): PhotoEditorPlacement = withContext(Dispatchers.IO) {
            app.authRepository.withWorkspaceWrite(epoch) {
                check(app.chatRepository.observeChat(chat).first() != null) { "Chat removed" }
                val preparation = app.mediaPreparation.state.value[chat] ?: MediaPreparationCoordinator.State()
                check(!preparation.sending) { "Composer is sending" }
                val stored = drafts()
                val tray = PhotoEditorComposer.Default.project(ComposerTraySnapshot.create(stored, preparation.items,
                    preparation.forgotten, preparation.unavailable), PhotoEditorComposer.Key(chat, epoch))
                val placement = placeEditedPhoto(stored, tray, original, edited)
                check(app.attachmentManager.isDurableComposerSource(edited))
                // The existing reentrant workspace gate serializes this read/replace with
                // all other composer writes. One Room row changes; captions/files do not.
                try { app.messageRepository.saveChatValue(chat, "mediaDrafts", ComposerDrafts.encode(placement.photos)) }
                catch (error: Exception) { if (edited !in drafts().photos) throw error }
                placement
            } ?: throw CancellationException("Account changed")
        }
        override suspend fun discard(staged: String) { withContext(Dispatchers.IO) {
            app.authRepository.withWorkspaceWrite(epoch) {
                app.messageRepository.discardMediaDrafts(setOfNotNull(Uri.parse(staged).path))
            }
        } }
        override fun retire(original: String?) { if (original != null && currentEpoch() == epoch) app.mediaPreparation.forget(chat, original) }
    }

/** The same renderer is used for preview and export, including wrapping and label baselines. */
internal object PhotoEditorExport {
    fun labelLayout(label: EditorLabel, width: Float): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = label.color; textSize = label.size * label.scale * width
            typeface = if (label.emoji) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            setShadowLayer(4 * width / 402, 0f, 0f, 0x73000000)
        }
        return StaticLayout.Builder.obtain(label.text, 0, label.text.length, paint, (width * .9f).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
    }
    fun draw(canvas: Canvas, rect: RectF, document: EditorDocument) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        canvas.save(); canvas.clipRect(rect)
        for (stroke in document.strokes) {
            paint.color = stroke.color; paint.strokeWidth = stroke.width * rect.width(); paint.style = Paint.Style.STROKE
            val path = Path()
            stroke.points.forEachIndexed { index, point ->
                val x = rect.left + point.x * rect.width(); val y = rect.top + point.y * rect.height()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (stroke.points.size == 1) {
                paint.style = Paint.Style.FILL
                val p = stroke.points.first(); canvas.drawCircle(rect.left + p.x * rect.width(), rect.top + p.y * rect.height(), paint.strokeWidth / 2, paint)
            } else canvas.drawPath(path, paint)
        }
        for (label in document.labels) {
            val layout = labelLayout(label, rect.width())
            canvas.save(); canvas.translate(rect.left + label.center.x * rect.width() - layout.width / 2,
                rect.top + label.center.y * rect.height() - layout.height / 2)
            layout.draw(canvas); canvas.restore()
        }
        canvas.restore()
    }
    /** editorPreview already applies EXIF and bounds the longest edge at 2560. One JPEG encode. */
    fun create(bitmap: Bitmap, document: EditorDocument, store: MediaExportStore): OwnedMediaExport {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output); canvas.drawColor(Color.BLACK); canvas.drawBitmap(bitmap, 0f, 0f, null)
            draw(canvas, RectF(0f, 0f, output.width.toFloat(), output.height.toFloat()), document)
            return store.create { file -> file.outputStream().use { check(output.compress(Bitmap.CompressFormat.JPEG, 94, it)) } }
        } finally { output.recycle() }
    }
}
