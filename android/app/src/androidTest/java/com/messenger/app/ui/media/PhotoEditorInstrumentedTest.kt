package com.messenger.app.ui.media

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.messenger.app.ui.chats.ZoomImagePage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.messenger.app.R
import com.messenger.app.data.local.MediaExportStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PhotoEditorInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun composerPhotoOpensEditorPreviewAndRemovalRemainsASeparateControl() {
        val file = File.createTempFile("editor-tray-", ".jpg", compose.activity.cacheDir)
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        val uri = android.net.Uri.fromFile(file)
        var opened: android.net.Uri? = null
        var removed: android.net.Uri? = null
        try {
            compose.setContent {
                com.messenger.app.ui.chats.ComposerAttachmentTray(listOf(uri), null, { removed = it }, {}, onOpenPhoto = { opened = it })
            }
            val label = compose.activity.getString(R.string.editor_title)
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(label).performClick()
            compose.runOnIdle { assertEquals(uri, opened); assertNull(removed) }
            compose.onNodeWithTag("composer-remove-0").performClick()
            compose.runOnIdle { assertEquals(uri, removed) }
        } finally { file.delete() }
    }

    @Test fun toolbarClearUndoRedoAndAttachmentCallbackAreReachable() {
        val initial = PhotoEditorState().label(EditorLabel(text = "UNYRI"))
        var state by mutableStateOf(initial)
        var attached = 0
        compose.setContent {
            EditorTopBar(state, false, true, {}, { state = state.undo() }, { state = state.redo() }, {}, {}, { state = state.clear() }, {}, { attached++ }, Modifier)
        }
        fun control(id: Int) = compose.onNodeWithContentDescription(compose.activity.getString(id))
        control(R.string.editor_clear).performClick()
        control(R.string.editor_clear).assertIsNotEnabled()
        control(R.string.editor_undo).performClick()
        compose.runOnIdle { assertEquals(initial.document, state.document) }
        control(R.string.editor_redo).performClick()
        compose.runOnIdle { assertFalse(state.document.changed) }
        control(R.string.editor_attach).performClick()
        compose.runOnIdle { assertEquals(1, attached) }
    }

    @Test fun textCancelAndDoneUseSeparateDraftAndColorSelection() {
        val original = EditorLabel(text = "original")
        var result: EditorLabel? = null
        var cancelled = false
        var editing by mutableStateOf<EditorLabel?>(original)
        compose.setContent { editing?.let { label -> EditorTextEntry(label, { cancelled = true; editing = null }) { result = it; editing = null } } }
        compose.onNodeWithTag("editor-text-input").performTextReplacement("UNYRI")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.editor_red)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertTrue(cancelled); assertNull(result); assertEquals("original", original.text) }
        compose.onNodeWithTag("editor-text-input").assertDoesNotExist()
        compose.runOnIdle { editing = original.copy(id = "second-entry") }
        compose.onNodeWithTag("editor-text-input").performTextReplacement("UNYRI")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.editor_red)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.done)).performClick()
        compose.runOnIdle { assertEquals("UNYRI", result?.text); assertEquals(0xFFFF453A.toInt(), result?.color) }
    }

    @Test fun exportPreservesSourceAndDrawsNormalizedStrokeAtSourceResolution() {
        val source = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val root = File(compose.activity.cacheDir, "editor-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val doc = EditorDocument(strokes = listOf(EditorStroke(listOf(EditorPoint(.25f, .5f), EditorPoint(.75f, .5f)), Color.WHITE, .02f)))
            val output = PhotoEditorExport.create(source, doc, MediaExportStore(root))
            val decoded = android.graphics.BitmapFactory.decodeFile(output.file.path)
            assertEquals(800, decoded.width); assertEquals(400, decoded.height)
            assertTrue(Color.red(decoded.getPixel(400, 200)) > 230)
            assertEquals(Color.BLACK, source.getPixel(400, 200))
            decoded.recycle(); output.close(); assertFalse(output.file.exists())
        } finally { source.recycle(); root.deleteRecursively() }
    }

    @Test fun previewAppliesExifOrientationAndRejectsCorruptInput() {
        val file = File(compose.activity.cacheDir, "editor-exif-${System.nanoTime()}.jpg")
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            android.media.ExifInterface(file.path).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val decoded = com.messenger.app.data.attachment.ImageProcessor.editorPreview(file)!!
            assertEquals(200, decoded.width); assertEquals(400, decoded.height); decoded.recycle()
            file.writeText("corrupt")
            assertNull(com.messenger.app.data.attachment.ImageProcessor.editorPreview(file))
        } finally { source.recycle(); file.delete() }
    }

    @Test fun overlappingTextAndStickerLabelsHaveSeparateAccessibleNodesAndStableActions() {
        lateinit var canvas: PhotoEditorCanvas
        val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val labels = (0 until 6).map { EditorLabel(text = if (it % 2 == 0) "Label $it" else "😂", emoji = it % 2 != 0) }
        var edited: EditorLabel? = null
        compose.setContent {
            AndroidView(factory = { context -> PhotoEditorCanvas(context).also {
                canvas = it; it.bitmap = source; it.state = PhotoEditorState(EditorDocument(labels = labels)); it.edit = { edited = it }
            } }, modifier = Modifier.fillMaxSize())
        }
        compose.runOnIdle {
            val provider = ViewCompat.getAccessibilityNodeProvider(canvas)!!
            assertEquals(6, provider.createAccessibilityNodeInfo(ExploreByTouchHelper.HOST_ID)!!.childCount)
            val customIds = setOf(R.id.editor_label_edit, R.id.editor_label_left, R.id.editor_label_right, R.id.editor_label_up,
                R.id.editor_label_down, R.id.editor_label_enlarge, R.id.editor_label_shrink)
            for (id in 1..6) {
                val node = provider.createAccessibilityNodeInfo(id)!!
                assertEquals(labels[id - 1].text, node.text.toString())
                assertEquals(customIds, node.actionList.map { it.id }.filter { it in customIds }.toSet())
                assertTrue(provider.performAction(id, R.id.editor_label_edit, null))
                assertEquals(labels[id - 1].id, edited?.id)
            }
            assertTrue(provider.performAction(1, R.id.editor_label_right, null))
            val movedId = labels.first().id
            val moved = canvas.state.document.labels.single { it.id == movedId }
            assertTrue(moved.center.x > labels.first().center.x)
            assertEquals(labels.drop(1), canvas.state.document.labels.filterNot { it.id == movedId })
            canvas.state = canvas.state.label(moved.copy(text = ""))
            assertEquals(5, provider.createAccessibilityNodeInfo(ExploreByTouchHelper.HOST_ID)!!.childCount)
            assertEquals(labels[1].text, provider.createAccessibilityNodeInfo(2)!!.text.toString())
        }
    }

    @Test fun cappedDrawingRejectsPreviewBeforeTouchAndUndoAllowsNextStroke() {
        compose.runOnIdle {
            val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            val canvas = PhotoEditorCanvas(compose.activity).apply { bitmap = source; layout(0, 0, 200, 200); widthFraction = .05f }
            var state = PhotoEditorState()
            repeat(128) { state = state.stroke(EditorStroke(listOf(EditorPoint(.1f, .1f)), Color.WHITE, .01f)) }
            canvas.state = state
            var commits = 0
            canvas.commit = { canvas.state = it; commits++ }
            fun touch(action: Int, x: Float, y: Float) {
                val event = android.view.MotionEvent.obtain(0, 0, action, x, y, 0)
                try { canvas.onTouchEvent(event) } finally { event.recycle() }
            }
            fun previewPixel(): Int {
                val frame = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
                return try { canvas.draw(android.graphics.Canvas(frame)); frame.getPixel(150, 150) } finally { frame.recycle() }
            }
            touch(android.view.MotionEvent.ACTION_DOWN, 140f, 140f)
            touch(android.view.MotionEvent.ACTION_MOVE, 160f, 160f)
            assertEquals(Color.BLACK, previewPixel())
            touch(android.view.MotionEvent.ACTION_UP, 160f, 160f)
            assertEquals(0, commits)
            canvas.state = state.undo()
            touch(android.view.MotionEvent.ACTION_DOWN, 140f, 140f)
            touch(android.view.MotionEvent.ACTION_MOVE, 160f, 160f)
            assertNotEquals(Color.BLACK, previewPixel())
            touch(android.view.MotionEvent.ACTION_UP, 160f, 160f)
            assertEquals(1, commits)
            source.recycle()
        }
    }

    @Test fun suspendingViewerForEditorPreservesZoomAndPagingLockAfterCancel() {
        val file = File(compose.activity.cacheDir, "editor-return-${System.nanoTime()}.png")
        val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }; source.recycle()
        var active by mutableStateOf(true)
        val zoomed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            compose.setContent { ZoomImagePage(file, { zoomed.set(it) }, {}, {}, active = active) }
            compose.waitUntil(10000) { compose.onAllNodesWithText("…").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("photo-gestures").performTouchInput { doubleClick() }
            compose.waitUntil(5000) { zoomed.get() }
            compose.runOnIdle { active = false }
            compose.waitForIdle(); assertTrue(zoomed.get())
            compose.runOnIdle { active = true }
            compose.waitForIdle(); assertTrue(zoomed.get())
            // A single double tap must reset the retained zoom, proving it was
            // still zoomed while its callback kept horizontal paging locked.
            compose.onNodeWithTag("photo-gestures").performTouchInput { doubleClick() }
            compose.waitUntil(5000) { !zoomed.get() }
        } finally { file.delete() }
    }

    @Test fun fullLabelCapacityDisablesNewEntryButUndoRestoresIt() {
        var state by mutableStateOf(PhotoEditorState())
        repeat(64) { state = state.label(EditorLabel(text = "Label $it")) }
        compose.setContent {
            EditorTopBar(state, false, true, {}, { state = state.undo() }, {}, {}, {}, {}, {}, {}, Modifier)
        }
        fun control(id: Int) = compose.onNodeWithContentDescription(compose.activity.getString(id))
        control(R.string.editor_text).assertIsNotEnabled()
        control(R.string.editor_sticker).assertIsNotEnabled()
        control(R.string.editor_undo).performClick()
        control(R.string.editor_text).assertIsEnabled()
        control(R.string.editor_sticker).assertIsEnabled()
    }

    @Test fun rejectedLabelDraftStaysOpenAndCanBeSavedAfterCapacityRecovers() {
        val session = PhotoEditorSession()
        repeat(64) { session.trySaveLabel(EditorLabel(text = "Label $it")) }
        val draft = EditorLabel(text = "")
        var saved by mutableStateOf(false)
        compose.setContent { if (!saved) EditorLabelEntry(session, draft, {}, { saved = true }) }
        compose.onNodeWithTag("editor-text-input").performTextReplacement("Keep this text")
        compose.onNodeWithText(compose.activity.getString(R.string.done)).performClick()
        compose.onNodeWithTag("editor-text-input").assertTextContains("Keep this text")
        compose.onNodeWithText(compose.activity.getString(R.string.editor_label_limit)).assertIsDisplayed()
        compose.runOnIdle { assertFalse(saved); session.state = session.state.undo() }
        compose.onNodeWithText(compose.activity.getString(R.string.done)).performClick()
        compose.onNodeWithTag("editor-text-input").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(saved)
            assertEquals("Keep this text", session.state.document.labels.single { it.id == draft.id }.text)
        }
    }
}
