package com.messenger.app.ui.chats

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.messenger.app.R
import com.messenger.app.ui.media.PhotoEditorCanvas
import com.messenger.app.ui.theme.CocoTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compile without phones here; execute with the lead's phase-2 fixtures. */
class MediaViewerEditorInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun b06PencilOpensOneB14EditorAndAttachesOneResultWithTheTrayIdentity() {
        val file = photo()
        val shown = mutableStateOf(true)
        val source = "content://composer/original"
        var attachments = 0
        var attachedSource: String? = null
        compose.setContent { if (shown.value) CocoTheme {
            MediaViewerDialog(listOf(MediaViewerItem(file, "image/png", "Photo", sender = "Peer", editorSource = source)), file,
                onEditedPhoto = { result ->
                    attachments++; attachedSource = result.source
                    assertTrue(result.file.isFile)
                    result.close()
                    true
                }, onDismiss = { shown.value = false })
        } }
        try {
            val pencil = compose.activity.getString(R.string.media_markup)
            compose.onAllNodesWithContentDescription(pencil).assertCountEquals(1)
            compose.onNodeWithContentDescription(pencil).performClick()
            compose.onAllNodesWithTag("photo-editor").assertCountEquals(1)
            val attach = compose.activity.getString(R.string.editor_attach)
            // Wait for the native canvas decoder, independently of the toolbar's
            // subcomposed semantics (which can lag its AndroidView update).
            fun decoded(view: android.view.View): Boolean = when (view) {
                is PhotoEditorCanvas -> view.bitmap != null
                is android.view.ViewGroup -> (0 until view.childCount).any { decoded(view.getChildAt(it)) }
                else -> false
            }
            compose.waitUntil(20_000) {
                var ready = false
                compose.runOnIdle {
                    ready = android.view.inspector.WindowInspector.getGlobalWindowViews().any(::decoded)
                }
                ready
            }
            // QA2 D6 must export a genuinely edited image, then return it with
            // the same composer identity rather than dispatching a message.
            compose.onNodeWithTag("editor-canvas").performTouchInput {
                swipe(centerLeft + androidx.compose.ui.geometry.Offset(12f, 0f),
                    centerRight - androidx.compose.ui.geometry.Offset(12f, 0f), 500)
            }
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.editor_undo)).assertIsEnabled()
            compose.onNodeWithContentDescription(attach).performClick()
            compose.waitUntil(10_000) { attachments == 1 }
            compose.waitForIdle()
            compose.onNodeWithTag("photo-editor").assertDoesNotExist()
            compose.onNodeWithTag("media-metadata").assertTextContains("Peer")
            compose.runOnIdle { assertEquals(source, attachedSource); assertEquals(1, attachments); assertTrue(file.isFile) }
        } finally { compose.runOnIdle { shown.value = false }; compose.waitForIdle(); file.delete() }
    }

    @Test fun optionalEditorOverrideDoesNotAlsoLaunchTheFallback() {
        val file = photo()
        val shown = mutableStateOf(true)
        var opened: File? = null
        compose.setContent { if (shown.value) CocoTheme {
            MediaViewerDialog(listOf(MediaViewerItem(file, "image/png", "Photo")), file,
                onEdit = { opened = it }, onDismiss = { shown.value = false })
        } }
        try {
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_markup)).performClick()
            compose.runOnIdle { assertEquals(file, opened) }
            compose.onNodeWithTag("photo-editor").assertDoesNotExist()
        } finally { compose.runOnIdle { shown.value = false }; compose.waitForIdle(); file.delete() }
    }

    private fun photo(): File = File.createTempFile("viewer-editor-", ".png", compose.activity.cacheDir).also { file ->
        Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.DKGRAY)
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
    }
}
