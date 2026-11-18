package com.messenger.app.ui.chats

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated display/semantics: parent actions are counters, never file or repository operations. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalTestApi::class)
class CompactFileNameInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun narrowTransferSlotKeepsCompletePdfExtensionAt19spAndLargeSystemFont() {
        val name = "20261021_163706_очень-длинное-имя-файла.pdf"
        compose.setContent {
            val realDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(realDensity.density, 1.6f)) {
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    CompactFileName(name, style = TextStyle(fontSize = 19.sp, lineHeight = 23.75.sp), color = Color.White,
                        modifier = Modifier.width(66.dp))
                }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithContentDescription(name, useUnmergedTree = true)
            .assertContentDescriptionEquals(name).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertEquals(19.sp, layout.layoutInput.style.fontSize)
        // The system typeface may also fit an ellipsis. The full extension is the invariant.
        assertTrue(layout.layoutInput.text.text.endsWith(".pdf"))
        assertFalse("Extension must fit the narrow transfer slot", layout.hasVisualOverflow)
    }

    @Test fun keeps17spExtensionAccessibleFullNameAndParentActionsAtLargeFont() {
        val name = "20261021_163706_очень-длинное-имя-файла.pdf"
        val width = mutableStateOf(148.dp)
        var clicks = 0
        var longClicks = 0
        compose.setContent {
            val realDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(realDensity.density, 1.6f)) {
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("compact-file-test-canvas").padding(24.dp)) {
                    Box(Modifier.width(width.value).combinedClickable(role = Role.Button,
                        onClick = { clicks++ }, onLongClick = { longClicks++ })) {
                        CompactFileName(name, style = TextStyle(fontSize = 17.sp, lineHeight = 21.25.sp), color = Color.White)
                    }
                }
            }
        }

        fun assertFits(): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithContentDescription(name, useUnmergedTree = true)
                .assertIsDisplayed().assertContentDescriptionEquals(name)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            assertEquals(17.sp, layout.layoutInput.style.fontSize)
            assertEquals(1, layout.lineCount)
            assertFalse("Name must not overflow: ${layout.size}, ${layout.layoutInput.constraints}", layout.hasVisualOverflow)
            assertTrue(layout.layoutInput.text.text.endsWith(".pdf"))
            assertTrue(layout.layoutInput.text.text.contains('…'))
            assertNotEquals(name, layout.layoutInput.text.text)
            return layout
        }

        val narrow = assertFits().layoutInput.text.text
        compose.onNodeWithContentDescription(name).performClick()
        compose.onNodeWithContentDescription(name).performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.runOnIdle { assertEquals(1, clicks); assertEquals(1, longClicks); width.value = 224.dp }
        val wider = assertFits().layoutInput.text.text
        assertTrue("A wider cell should keep more filename content", wider.length > narrow.length)

        val image = compose.onNodeWithTag("compact-file-test-canvas").captureToImage().asAndroidBitmap()
        try {
            val folder = compose.activity.getExternalFilesDir("capsules-uiqa")!!
            folder.mkdirs()
            File(folder, "compact-file-name-large-font.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
}
