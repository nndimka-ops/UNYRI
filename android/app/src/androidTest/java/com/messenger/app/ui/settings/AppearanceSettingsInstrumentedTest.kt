package com.messenger.app.ui.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.ui.theme.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Draft settings and real chat rendering, with isolated preferences and no real account/network. */
@OptIn(ExperimentalTestApi::class)
class AppearanceSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun controlsChangeTheRealPreviewButWriteOnlyOnApply() {
        val context = compose.activity
        val preferenceName = "appearance-editor-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val original = defaultChatAppearanceSettings()
        var applied: ChatAppearanceSettings? = null
        try {
            compose.setContent { CocoTheme {
                Box(Modifier.requiredSize(320.dp, 660.dp)) {
                    ChatAppearanceEditor(original, onApply = {
                        saveChatAppearanceSettings(preferences, it); applied = it
                    }, onCancel = {}, showConnectionSetting = false)
                }
            } }
            val originalPreview = compose.onNodeWithTag("appearance-preview").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("appearance-family-monospace").performScrollTo().performClick()
            compose.onNodeWithTag("appearance-font-size").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(19f) }
            compose.onNodeWithTag("appearance-medium").performScrollTo().performClick()
            compose.onNodeWithTag("appearance-line-height").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(1.45f) }
            compose.onNodeWithTag("appearance-density-relaxed").performScrollTo().performClick()
            compose.onNodeWithTag("appearance-radius").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
            compose.onNodeWithTag("appearance-color-blue").performScrollTo().performClick()
            compose.onNodeWithTag("appearance-transparency").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
            compose.onNodeWithTag("appearance-pattern").performScrollTo().performClick()
            val sample = compose.activity.getString(R.string.appearance_preview_outgoing)
            compose.onNodeWithText(sample, useUnmergedTree = true).performScrollTo()
            val rendered = textLayout(sample)
            assertEquals(19.sp, rendered.layoutInput.style.fontSize)
            assertEquals(FontFamily.Monospace, rendered.layoutInput.style.fontFamily)
            assertEquals(19f * 1.45f, rendered.layoutInput.style.lineHeight.value, .01f)
            assertFalse("Preview uses a complete real text layout", rendered.hasVisualOverflow)
            assertEquals("Changing the font must not move the pinned example", originalPreview,
                compose.onNodeWithTag("appearance-preview").fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle {
                assertNull(applied)
                assertTrue("No slider, radio or preview click may persist a draft", preferences.all.isEmpty())
            }
            compose.onNodeWithTag("appearance-apply").assertIsDisplayed().performClick()
            compose.runOnIdle {
                val saved = readChatAppearanceSettings(preferences)
                assertEquals("monospace", saved.fontFamily)
                assertEquals(19, saved.fontSize)
                assertEquals(!original.mediumText, saved.mediumText)
                assertEquals(1.45f, saved.lineHeight, .001f)
                assertEquals("relaxed", saved.density)
                assertEquals(32f, saved.cornerRadius)
                assertEquals("blue", saved.outgoingColor)
                assertEquals(40, saved.transparency)
                assertEquals(!original.patterned, saved.patterned)
                assertEquals(saved, applied)
            }
            saveScreenshot("appearance-editor-live-font.png")
        } finally { context.deleteSharedPreferences(preferenceName) }
    }

    @Test fun cancellingChangedColorAndWallpaperKeepsAllSavedPreferences() {
        val context = compose.activity
        val preferenceName = "appearance-editor-cancel-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val original = defaultChatAppearanceSettings().copy(fontFamily = "serif", outgoingColor = "violet", patterned = true)
        saveChatAppearanceSettings(preferences, original)
        val before = preferences.all.toMap()
        var cancelled = 0
        var applied = 0
        try {
            compose.setContent { CocoTheme {
                Box(Modifier.requiredSize(320.dp, 660.dp)) {
                    ChatAppearanceEditor(original, onApply = { applied++ }, onCancel = { cancelled++ },
                        showConnectionSetting = false)
                }
            } }
            compose.onNodeWithTag("appearance-color-blue").performScrollTo().performClick()
            compose.onNodeWithTag("appearance-pattern").performScrollTo().performClick()
            compose.onNodeWithTag("appearance-cancel").assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertEquals(1, cancelled)
                assertEquals(0, applied)
                assertEquals("Cancel leaves every persisted value untouched", before, preferences.all)
            }
        } finally { context.deleteSharedPreferences(preferenceName) }
    }

    @Test fun resetIsADraftAndAttachmentsRemainReadOnlyUntilDefaultIsApplied() {
        val context = compose.activity
        val preferenceName = "appearance-editor-reset-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val original = defaultChatAppearanceSettings().copy(fontSize = 20, fontFamily = "serif", density = "compact",
            cornerRadius = 16f, outgoingColor = "violet", transparency = 50, patterned = false)
        saveChatAppearanceSettings(preferences, original)
        preferences.edit().putString("unrelated", "keep").commit()
        val before = preferences.all.toMap()
        try {
            compose.setContent { CocoTheme {
                Box(Modifier.requiredSize(320.dp, 660.dp)) {
                    ChatAppearanceEditor(original, onApply = { saveChatAppearanceSettings(preferences, it) },
                        onCancel = {}, showConnectionSetting = false)
                }
            } }
            compose.onNodeWithTag("appearance-preview-attachments").performClick()
            val filename = context.getString(R.string.appearance_preview_filename)
            compose.onNodeWithContentDescription(filename, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.waitUntil(5000) { File(context.cacheDir, "appearance-preview-v1/sample.jpg").length() > 0L }
            compose.onNodeWithContentDescription(filename, useUnmergedTree = true).performTouchInput { click() }
            val photoLabel = context.getString(R.string.photo_preview)
            compose.waitUntil(5000) {
                compose.onAllNodesWithContentDescription(photoLabel, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(photoLabel, useUnmergedTree = true)
                .performScrollTo().performTouchInput { click() }
            compose.onNodeWithContentDescription(context.getString(R.string.play), useUnmergedTree = true)
                .performScrollTo().performTouchInput { click() }
            compose.onNodeWithTag("appearance-editor").assertIsDisplayed()
            compose.runOnIdle { assertEquals("Preview media taps cannot save or leave the editor", before, preferences.all) }
            compose.onNodeWithTag("appearance-reset").performScrollTo().performClick()
            compose.runOnIdle { assertEquals("Reset alone does not save", before, preferences.all) }
            compose.onNodeWithTag("appearance-apply").performClick()
            compose.runOnIdle {
                assertEquals(defaultChatAppearanceSettings(), readChatAppearanceSettings(preferences))
                assertFalse("A true default has no stale overrides", preferences.contains(CHAT_APPEARANCE_OVERRIDES_KEY))
                assertEquals("keep", preferences.getString("unrelated", null))
            }
            saveScreenshot("appearance-editor-attachments.png")
        } finally { context.deleteSharedPreferences(preferenceName) }
    }

    @Test fun portraitLargeTextAndShortLandscapeKeepApplyCancelAndControlsInBounds() {
        data class Window(val width: Int, val height: Int, val fontScale: Float, val densityFactor: Float)
        val windows = listOf(Window(320, 568, 1.8f, 1f), Window(500, 280, 1f, .6f), Window(320, 300, 1f, 1f))
        val shown = mutableStateOf(windows.first())
        var cancellations = 0
        compose.setContent { CocoTheme {
            val systemDensity = LocalDensity.current.density
            val window = shown.value
            CompositionLocalProvider(LocalDensity provides Density(systemDensity * window.densityFactor, window.fontScale)) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.requiredSize(window.width.dp, window.height.dp)) {
                        ChatAppearanceEditor(defaultChatAppearanceSettings(), onApply = {}, onCancel = { cancellations++ },
                            showConnectionSetting = false)
                    }
                }
            }
        } }
        for ((index, window) in windows.withIndex()) {
            compose.runOnIdle { shown.value = window }
            compose.waitForIdle()
            val frame = compose.onNodeWithTag("appearance-editor").fetchSemanticsNode().boundsInRoot
            val apply = compose.onNodeWithTag("appearance-apply").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val cancel = compose.onNodeWithTag("appearance-cancel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertInside("Apply", frame, apply)
            assertInside("Cancel", frame, cancel)
            assertFalse("Footer actions must not overlap", apply.overlaps(cancel))
            if (window.width >= 480) {
                val preview = compose.onNodeWithTag("appearance-preview").fetchSemanticsNode().boundsInRoot
                val controls = compose.onNodeWithTag("appearance-controls").fetchSemanticsNode().boundsInRoot
                assertTrue("Landscape shares the row rather than squeezing out controls", preview.right < controls.left)
            }
            if (window.height < 340 && window.width < 480) {
                compose.onNodeWithTag("appearance-compact-preview").performClick()
                compose.onNodeWithTag("appearance-preview").assertIsDisplayed()
                compose.onNodeWithTag("appearance-compact-preview").performClick()
            }
            compose.onNodeWithTag("appearance-reset").performScrollTo().assertIsDisplayed()
            saveScreenshot("appearance-editor-${window.width}x${window.height}-font${window.fontScale}.png")
            val applyLayout = textLayout(contextString(R.string.appearance_apply))
            val metrics = "size=${applyLayout.size}, constraints=${applyLayout.layoutInput.constraints}, " +
                "overflowW=${applyLayout.didOverflowWidth}, overflowH=${applyLayout.didOverflowHeight}, " +
                "paragraph=${applyLayout.multiParagraph.width}x${applyLayout.multiParagraph.height}, " +
                "intrinsic=${applyLayout.multiParagraph.intrinsics.maxIntrinsicWidth}, " +
                "line=${applyLayout.getLineLeft(0)}..${applyLayout.getLineRight(0)}, " +
                "lineY=${applyLayout.getLineTop(0)}..${applyLayout.getLineBottom(0)}, " +
                "style=${applyLayout.layoutInput.style}"
            android.util.Log.i("AppearanceEditorUIQA", "$window apply-label $metrics")
            assertFalse("Apply label remains complete for $window ($metrics)",
                applyLayout.hasVisualOverflow)
            assertFalse("Cancel label remains complete for $window",
                textLayout(contextString(R.string.cancel)).hasVisualOverflow)
            compose.onNodeWithTag("appearance-cancel").performClick()
            compose.runOnIdle { assertEquals(index + 1, cancellations) }
        }
    }

    private fun contextString(id: Int) = compose.activity.getString(id)

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertInside(label: String, frame: Rect, bounds: Rect) {
        assertTrue("$label left", bounds.left >= frame.left - 1)
        assertTrue("$label right", bounds.right <= frame.right + 1)
        assertTrue("$label top", bounds.top >= frame.top - 1)
        assertTrue("$label bottom", bounds.bottom <= frame.bottom + 1)
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onNodeWithTag("appearance-editor").captureToImage().asAndroidBitmap()
        try {
            val directory = compose.activity.getExternalFilesDir("appearance-uiqa")!!.apply { mkdirs() }
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
