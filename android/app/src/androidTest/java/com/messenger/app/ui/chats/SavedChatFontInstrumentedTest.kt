package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.ui.components.SelectionOverlayLayout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native family providers and the same measured text/file/selection components used by chat. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalTestApi::class)
class SavedChatFontInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val families = listOf("system", "serif", "monospace")
    private val cyrillic = "Привет! Як справи? Їжак, ґанок, Євген — 0123456789.\nДлинное сообщение остаётся целым."

    private fun style(id: String) = TextStyle(
        fontFamily = savedChatFontFamily(id), fontSize = 19.sp, lineHeight = 25.sp,
        fontWeight = FontWeight.Medium, platformStyle = PlatformTextStyle(includeFontPadding = false))

    @Test fun everySupportedFamilyWrapsCompleteCyrillicBeforeItsReceiptAtLargeFont() {
        val family = mutableStateOf("system")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                MaterialTheme {
                    Box(Modifier.width(220.dp).background(Color.Black)) {
                        CompactMessageText(AnnotatedString(cyrillic), style(family.value), Color.White, true,
                            modifier = Modifier.testTag("family-body")) {
                            Text("16:28", fontSize = 10.sp, modifier = Modifier.testTag("family-receipt"))
                        }
                    }
                }
            }
        }
        for (id in families) {
            compose.runOnIdle { family.value = id }
            val text = compose.onNodeWithText(cyrillic, useUnmergedTree = true).assertIsDisplayed()
            val layout = layout(text)
            assertEquals(savedChatFontFamily(id), layout.layoutInput.style.fontFamily)
            assertFalse("Complete Cyrillic text must not clip for $id", layout.hasVisualOverflow)
            assertTrue("Long Cyrillic must wrap for $id", layout.lineCount >= 2)
            val receipt = compose.onNodeWithTag("family-receipt").fetchSemanticsNode().boundsInRoot
            val textBounds = text.fetchSemanticsNode().boundsInRoot
            val body = compose.onNodeWithTag("family-body").fetchSemanticsNode().boundsInRoot
            assertTrue("Receipt must follow the whole message for $id", receipt.top >= textBounds.bottom)
            assertEquals(body.right, receipt.right, 1f)
        }
        // Missing/unknown values in old or malformed exports keep the original system provider.
        assertEquals(FontFamily.Default, savedChatFontFamily(""))
        assertEquals(FontFamily.Default, savedChatFontFamily("unsupported"))
    }

    @Test fun changingFamilyRemeasuresNarrowFileAndKeepsFullExtensionAndParentActions() {
        val family = mutableStateOf("system")
        // At 19sp × 1.6 system scaling the monospace ".pdf" itself needs ~73dp.
        // This is deliberately narrower than the production file-title slot,
        // while leaving room for the full extension in every supported family.
        val width = mutableStateOf(84.dp)
        val name = "20261026_очень-длинное-имя-документа.pdf"
        var clicks = 0
        var longClicks = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                MaterialTheme {
                    Box(Modifier.width(width.value).combinedClickable(
                        onClick = { clicks++ }, onLongClick = { longClicks++ }).testTag("family-file")) {
                        CompactFileName(name, style(family.value), Color.White)
                    }
                }
            }
        }
        for ((index, id) in families.withIndex()) {
            compose.runOnIdle { family.value = id; width.value = 84.dp }
            fun currentLayout(): TextLayoutResult {
                val node = compose.onNodeWithContentDescription(name, useUnmergedTree = true)
                    .assertIsDisplayed().assertContentDescriptionEquals(name)
                return layout(node).also {
                    assertEquals(savedChatFontFamily(id), it.layoutInput.style.fontFamily)
                    assertEquals(1, it.lineCount)
                    assertTrue("Complete PDF extension must remain for $id", it.layoutInput.text.text.endsWith(".pdf"))
                    assertFalse("File must fit its real narrow slot for $id", it.hasVisualOverflow)
                }
            }
            val narrow = currentLayout().layoutInput.text.text
            compose.onNodeWithTag("family-file").performClick()
            compose.onNodeWithTag("family-file").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
            compose.runOnIdle {
                assertEquals(index + 1, clicks); assertEquals(index + 1, longClicks)
                width.value = 224.dp
            }
            assertTrue("Wider cell must recover filename characters for $id",
                currentLayout().layoutInput.text.text.length > narrow.length)
        }
    }

    @Test fun selectingWrappedTextKeepsExactGeometryAndIndependent48dpMarkForEveryFamily() {
        val family = mutableStateOf("system")
        val selecting = mutableStateOf(false)
        val checked = mutableStateOf(false)
        var messageBounds = Rect.Zero
        var selections = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                MaterialTheme {
                    Box(Modifier.width(260.dp)) {
                        SelectionOverlayLayout(outgoing = true,
                            selectionMark = if (selecting.value) ({
                                Checkbox(checked.value, { checked.value = it; selections++ }, Modifier.requiredSize(48.dp))
                            }) else null) {
                            CompactMessageText(AnnotatedString(cyrillic), style(family.value), Color.White, true,
                                modifier = Modifier.width(220.dp).padding(8.dp)
                                    .onGloballyPositioned { messageBounds = it.boundsInRoot() }) {
                                Text("16:28", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
        for ((index, id) in families.withIndex()) {
            compose.runOnIdle { family.value = id; selecting.value = false; checked.value = false }
            compose.waitForIdle()
            val original = messageBounds
            compose.runOnIdle { selecting.value = true }
            compose.waitForIdle()
            assertEquals("Opening selection must not move $id text", original, messageBounds)
            val mark = compose.onNode(isToggleable()).assertIsDisplayed().assertIsOff()
            val target = mark.fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            assertTrue(target.width >= 48f * density - 1f)
            assertTrue(target.height >= 48f * density - 1f)
            mark.performTouchInput { click() }
            mark.assertIsOn()
            compose.runOnIdle { assertEquals(index + 1, selections) }
            assertEquals("Marking selection must not resize $id text", original, messageBounds)
            compose.runOnIdle { selecting.value = false }
            compose.waitForIdle()
            assertEquals("Closing selection must retain $id geometry", original, messageBounds)
        }
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
