package com.messenger.app.ui.chats

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Measures the production picker; does not send media or start its live camera. */
class AttachmentPickerLayoutInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun measuredFooterAndCaptionStaySeparateAcrossWidthsAndLargeText() {
        val width = mutableStateOf(320)
        val height = mutableStateOf(640)
        val scale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                MaterialTheme { Box(Modifier.requiredSize(width.value.dp, height.value.dp)) {
                    AttachmentGallery({}, {}, {}, {}, {}, emptyList(), {}, cameraReady = false)
                } }
            }
        }
        for ((w, h) in listOf(320 to 640, 390 to 736, 736 to 320)) for (font in listOf(1f, 1.3f, 2f)) {
            compose.runOnIdle { width.value = w; height.value = h; scale.value = font }
            compose.waitForIdle()
            val root = compose.onNodeWithTag("attachment-picker").fetchSemanticsNode().boundsInRoot
            val grid = compose.onNodeWithTag("attachment-picker-grid").fetchSemanticsNode().boundsInRoot
            val caption = compose.onNodeWithTag("attachment-picker-caption").fetchSemanticsNode().boundsInRoot
            val tabs = compose.onNodeWithTag("attachment-picker-tabs").fetchSemanticsNode().boundsInRoot
            assertTrue("grid/caption overlap at $w/$h/$font", grid.bottom <= caption.top + 1)
            assertTrue("caption/tabs overlap at $w/$h/$font", caption.bottom <= tabs.top + 1)
            assertTrue("footer extends below picker at $w/$h/$font", tabs.bottom <= root.bottom + 1)
            assertTrue("caption escapes horizontally at $w/$h/$font", caption.left >= root.left - 1 && caption.right <= root.right + 1)
            if (w == 320 && font != 1.3f) {
                val folder = File(compose.activity.getExternalFilesDir(null), "picker-native-20261028").apply { mkdirs() }
                File(folder, "gallery-$w-font-$font.png").outputStream().use { output ->
                    compose.onNodeWithTag("attachment-picker").captureToImage().asAndroidBitmap()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                }
            }
        }
    }

    @Test fun captionUsesOwnerBindingAndAddKeepsSelectionOrder() {
        val caption = mutableStateOf("Existing composer text")
        val first = Uri.parse("content://media/external/images/media/9999901")
        val second = Uri.parse("content://media/external/images/media/9999902")
        var sent: List<Uri>? = null
        var sentCaption: String? = null
        var closed = 0
        var immediateSends = 0
        compose.setContent { MaterialTheme { Box(Modifier.requiredSize(320.dp, 640.dp)) {
            AttachmentGallery({}, {}, {}, {}, { sent = it; sentCaption = caption.value }, listOf(second, first), {}, false,
                caption = caption.value, onCaptionChange = { caption.value = it }, onClose = { closed++ }, onSendNow = { immediateSends++ })
        } } }
        compose.onNodeWithTag("attachment-picker-caption-input").performTextReplacement("Caption from gallery")
        compose.activity.runOnUiThread {
            (compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(3_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
        }
        compose.onNodeWithTag("attachment-picker-add").performClick()
        compose.runOnIdle {
            assertEquals("Caption from gallery", caption.value)
            assertEquals(caption.value, sentCaption)
            assertEquals(listOf(second, first), sent)
            assertEquals(0, immediateSends)
        }
        compose.onNodeWithTag("attachment-picker-close").performClick()
        compose.runOnIdle { assertEquals(1, closed); assertEquals("Caption from gallery", caption.value) }
    }

    @Test fun longPressAddOpensSendNowWithoutAddingAndKeepsCaptionAndPickOrder() {
        val first = Uri.parse("content://media/external/images/media/9999901")
        val second = Uri.parse("content://media/external/images/media/9999902")
        val caption = mutableStateOf("Existing caption")
        var adds = 0
        var sent: List<Uri>? = null
        var sentCaption: String? = null
        compose.setContent { MaterialTheme { Box(Modifier.requiredSize(320.dp, 640.dp)) {
            AttachmentGallery({}, {}, {}, {}, { adds++ }, listOf(second, first), {}, false,
                caption = caption.value, onCaptionChange = { caption.value = it },
                onSendNow = { sent = it; sentCaption = caption.value })
        } } }
        compose.onNodeWithTag("attachment-picker-add").performTouchInput { longClick() }
        compose.onNodeWithTag("attachment-picker-send-now").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, adds); assertNull(sent) }
        compose.onNodeWithTag("attachment-picker-send-now").performClick()
        compose.runOnIdle {
            assertEquals(0, adds)
            assertEquals(listOf(second, first), sent)
            assertEquals("Existing caption", sentCaption)
            assertEquals("Existing caption", caption.value)
        }
    }
}
