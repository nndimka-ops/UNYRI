package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class CompactMessageTextInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val style = TextStyle(fontSize = 17.sp, lineHeight = 22.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false))

    @Test fun shortTextAndReceiptShareOneNaturalWidthRow() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(260.dp)) {
                    CompactMessageText(AnnotatedString("Привет"), style, Color.White, true,
                        modifier = Modifier.testTag("body")) {
                        Text("16:28 ✓✓", fontSize = 10.sp, modifier = Modifier.testTag("receipt"))
                    }
                }
            }
        }
        val message = textBounds("Привет")
        val receipt = bounds("receipt")
        val body = bounds("body")
        assertTrue(receipt.left > message.right)
        assertEquals(message.center.y, receipt.center.y, 1f)
        assertEquals(receipt.right, body.right, 1f)
        assertTrue(body.width < with(compose.activity.resources.displayMetrics) { 260f * density })
    }

    @Test fun wrappedMessagePlacesOwnReceiptBelowAtTheTrailingEdge() {
        val text = "Длинное сообщение сохраняет весь текст и переносится на несколько строк."
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(180.dp)) {
                    CompactMessageText(AnnotatedString(text), style, Color.White, true,
                        modifier = Modifier.testTag("body")) {
                        Text("16:28 ✓✓", fontSize = 10.sp, modifier = Modifier.testTag("receipt"))
                    }
                }
            }
        }
        val message = textBounds(text)
        val receipt = bounds("receipt")
        assertTrue(receipt.top >= message.bottom)
        assertEquals(bounds("body").right, receipt.right, 1f)
        assertNoTextOverflow(text)
    }

    @Test fun explicitNewlinePlacesIncomingReceiptBelowAtTheLeadingEdge() {
        val text = "Да\nХорошо"
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(260.dp)) {
                    CompactMessageText(AnnotatedString(text), style, Color.White, false,
                        modifier = Modifier.testTag("body")) {
                        Text("16:28", fontSize = 10.sp, modifier = Modifier.testTag("receipt"))
                    }
                }
            }
        }
        val receipt = bounds("receipt")
        assertTrue(receipt.top >= textBounds(text).bottom)
        assertEquals(bounds("body").left, receipt.left, 1f)
    }

    @Test fun oneLineTextStillUsesBelowWhenReceiptCannotFitBesideIt() {
        val text = "MMMMMMMM"
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(140.dp)) {
                    CompactMessageText(AnnotatedString(text), style, Color.White, true,
                        modifier = Modifier.testTag("body")) {
                        Text("16:28 ✓✓", fontSize = 10.sp, modifier = Modifier.testTag("receipt"))
                    }
                }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.single().lineCount)
        assertTrue(bounds("receipt").top >= textBounds(text).bottom)
        assertEquals(bounds("body").right, bounds("receipt").right, 1f)
    }

    @Test fun doubledSystemFontKeepsEmojiTextAndReceiptUnclipped() {
        val text = "Привет 👩🏽‍🚀\nЕщё строка 🤍"
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(190.dp).background(Color.Black)) {
                        CompactMessageText(AnnotatedString(text), style, Color.White, true,
                            modifier = Modifier.testTag("body")) {
                            Text("16:28 ✓✓", color = Color.White,
                                style = TextStyle(fontSize = 10.sp, lineHeight = 13.sp,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                maxLines = 1, softWrap = false,
                                modifier = Modifier.testTag("receipt"))
                        }
                    }
                }
            }
        }
        val message = textBounds(text)
        val receipt = bounds("receipt")
        val body = bounds("body")
        val report = "body=$body\nmessage=$message\nreceipt=$receipt\nroot=${compose.onRoot().fetchSemanticsNode().boundsInRoot}\n" +
            describeTextLayout(text, textLayout(text)) + "\n" + describeTextLayout("16:28 ✓✓", textLayout("16:28 ✓✓"))
        val output = File(compose.activity.getExternalFilesDir(null), "capsules-uiqa").apply { mkdirs() }
        File(output, "compact-text-font2-metrics.txt").writeText(report)
        File(output, "compact-text-font2.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        android.util.Log.i("CompactMessageTextQA", report)
        assertTrue(report, receipt.top >= message.bottom)
        assertTrue(report, body.bottom >= receipt.bottom)
        assertTrue(report, body.right >= receipt.right)
        assertNoTextOverflow(text)
        assertMetadataGlyphsFit("16:28 ✓✓")
    }

    @Test fun pendingToReadReceiptChangesWithoutADeferredHeightJump() {
        val status = mutableStateOf("◷")
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(260.dp)) {
                    CompactMessageText(AnnotatedString("Да"), style, Color.White, true,
                        modifier = Modifier.testTag("body")) {
                        Text("16:28 ${status.value}", fontSize = 10.sp,
                            modifier = Modifier.testTag("receipt"))
                    }
                }
            }
        }
        val beforeBody = bounds("body")
        val beforeMessage = textBounds("Да")
        compose.runOnIdle { status.value = "✓✓" }
        val afterMessage = textBounds("Да")
        val afterReceipt = bounds("receipt")
        val afterBody = bounds("body")
        assertEquals(beforeBody.height, afterBody.height, 1f)
        assertEquals(beforeMessage, afterMessage)
        assertTrue(afterReceipt.left > afterMessage.right)
        assertEquals(afterMessage.center.y, afterReceipt.center.y, 1f)
        compose.waitForIdle()
        assertEquals(afterBody, bounds("body"))
    }

    @Test fun widerPendingMetadataMovesBelowAndBackWithinEachLayoutPass() {
        val receipt = mutableStateOf("16:28 ✓✓")
        val text = "MMMMMMMM"
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(210.dp)) {
                    CompactMessageText(AnnotatedString(text), style, Color.White, true,
                        modifier = Modifier.testTag("body")) {
                        Text(receipt.value, fontSize = 10.sp, modifier = Modifier.testTag("receipt"))
                    }
                }
            }
        }
        assertTrue(bounds("receipt").left > textBounds(text).right)
        compose.runOnIdle { receipt.value = "16:28 Ожидание подключения" }
        assertTrue(bounds("receipt").top >= textBounds(text).bottom)
        val below = bounds("body")
        compose.waitForIdle()
        assertEquals(below, bounds("body"))
        compose.runOnIdle { receipt.value = "16:28 ✓✓" }
        assertTrue(bounds("receipt").left > textBounds(text).right)
        assertTrue(bounds("body").height < below.height)
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun textBounds(text: String): Rect = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun assertNoTextOverflow(text: String) {
        val result = textLayout(text)
        assertFalse(describeTextLayout(text, result), result.hasVisualOverflow)
    }

    private fun assertMetadataGlyphsFit(text: String) {
        val result = textLayout(text)
        val report = describeTextLayout(text, result)
        val node = textBounds(text)
        val width = result.size.width.toFloat()
        val height = result.size.height.toFloat()
        // Compose 1.7 retains the allocated paragraph width in this result
        // (570px), while Text measures the natural single-line width (266px).
        // didOverflowWidth therefore compares allocation rather than visible
        // glyph extents here. Check every actual line/glyph and the node bounds;
        // the message body above still uses the strict hasVisualOverflow check.
        assertFalse(report, result.didOverflowHeight)
        assertEquals(report, 1, result.lineCount)
        assertTrue("$report\nnode=$node", node.width >= width && node.height >= height)
        repeat(result.lineCount) { line ->
            assertFalse(report, result.isLineEllipsized(line))
            assertTrue(report, result.getLineLeft(line) >= 0f && result.getLineRight(line) <= width)
            assertTrue(report, result.getLineTop(line) >= 0f && result.getLineBottom(line) <= height)
        }
        assertEquals(report, text.length, result.getLineEnd(result.lineCount - 1))
        text.indices.filterNot { Character.isLowSurrogate(text[it]) }.forEach { offset ->
            val glyph = result.getBoundingBox(offset)
            assertTrue("$report\nglyph[$offset]=$glyph", glyph.left >= 0f && glyph.right <= width &&
                glyph.top >= 0f && glyph.bottom <= height)
        }
    }

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.size)
        return results.single()
    }

    private fun describeTextLayout(text: String, result: TextLayoutResult): String = buildString {
        appendLine("text=${text.replace("\n", "\\n")}")
        appendLine("size=${result.size}, constraints=${result.layoutInput.constraints}, " +
            "density=${result.layoutInput.density.density}, fontScale=${result.layoutInput.density.fontScale}")
        appendLine("style=${result.layoutInput.style}, paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}, " +
            "lines=${result.lineCount}, overflowWidth=${result.didOverflowWidth}, " +
            "overflowHeight=${result.didOverflowHeight}, visualOverflow=${result.hasVisualOverflow}")
        repeat(result.lineCount) { line ->
            appendLine("line[$line] start=${result.getLineStart(line)}, end=${result.getLineEnd(line)}, " +
                "visibleEnd=${result.getLineEnd(line, visibleEnd = true)}, left=${result.getLineLeft(line)}, " +
                "right=${result.getLineRight(line)}, top=${result.getLineTop(line)}, " +
                "bottom=${result.getLineBottom(line)}, baseline=${result.getLineBaseline(line)}, " +
                "ellipsized=${result.isLineEllipsized(line)}")
        }
        text.indices.filterNot { Character.isLowSurrogate(text[it]) || text[it] == '\n' }.forEach { offset ->
            appendLine("glyph[$offset] U+${text.codePointAt(offset).toString(16)} bounds=${result.getBoundingBox(offset)}")
        }
    }
}
