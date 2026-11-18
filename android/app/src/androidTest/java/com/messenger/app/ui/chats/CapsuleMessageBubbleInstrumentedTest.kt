package com.messenger.app.ui.chats

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.domain.model.Attachment
import com.messenger.app.domain.model.AttachmentDownloadState
import com.messenger.app.domain.model.AttachmentMediaType
import com.messenger.app.domain.model.AttachmentUploadState
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.domain.model.ReactionSummary
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.theme.LocalChatTextSize
import com.messenger.app.ui.theme.LocalCocoColors
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production MessageBubble with synthetic data only: no workspace, account, player, or network calls. */
@OptIn(ExperimentalTestApi::class)
class CapsuleMessageBubbleInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val canvasColor = Color(0xFF183C53)
    private var bodyBounds = Rect.Zero
    private var canvasBounds = Rect.Zero
    private var pixelsPerDp = 1f

    @Test fun approvedTranslucentTextVoiceAndFileBodiesKeepReadableContentBothDirections() {
        val shown = mutableStateOf(Fixture())
        compose.setContent { FixtureCanvas(shown.value) }
        for (own in listOf(false, true)) {
            for (kind in listOf<AttachmentMediaType?>(null, AttachmentMediaType.VOICE, AttachmentMediaType.FILE)) {
                val fixture = Fixture(own = own, kind = kind)
                compose.runOnIdle { shown.value = fixture }
                compose.waitForIdle()
                val surface = when (kind) {
                    AttachmentMediaType.VOICE -> CapsuleChatStyle.voiceBackground(own)
                    AttachmentMediaType.FILE -> CapsuleChatStyle.fileBackground(own)
                    else -> if (own) CapsuleChatStyle.outgoing else CapsuleChatStyle.incoming
                }
                assertBodyPixel(surface.compositeOver(canvasColor))
                val content = when (kind) {
                    AttachmentMediaType.VOICE -> "0:12"
                    AttachmentMediaType.FILE -> "Capsule.pdf"
                    else -> "Capsule body"
                }
                val layout = textLayout(content)
                assertEquals(if (own) CapsuleChatStyle.outText else CapsuleChatStyle.text, layout.layoutInput.style.color)
                assertEquals(if (kind == AttachmentMediaType.VOICE) CapsuleChatStyle.timeSize.sp else CapsuleChatStyle.fontSize.sp,
                    layout.layoutInput.style.fontSize)
                when (kind) {
                    null -> assertEquals("The approved message text uses Regular emphasis", FontWeight.Normal, layout.layoutInput.style.fontWeight)
                    AttachmentMediaType.FILE -> assertEquals("File name retains Medium emphasis", FontWeight.Medium, layout.layoutInput.style.fontWeight)
                    else -> Unit
                }
                val timeText = formatTime(fixture.message.createdAt)
                compose.onAllNodesWithText(timeText, useUnmergedTree = true).assertCountEquals(1)
                val time = compose.onNodeWithText(timeText, useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val body = bodyInRoot()
                if (kind == AttachmentMediaType.VOICE || kind == null) {
                    val footerTop = if (own) minOf(time.top,
                        compose.onNodeWithContentDescription(compose.activity.getString(R.string.msg_status_read),
                            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top) else time.top
                    assertEquals("The saved below mode keeps metadata outside the body with the approved gap",
                        CapsuleChatStyle.timeOffset * pixelsPerDp, footerTop - body.bottom, 1.5f)
                } else {
                    assertTrue("Text/file time must be inside the padded body", time.top >= body.top - 1f && time.bottom <= body.bottom + 1f)
                    assertTrue("Internal metadata must fit horizontally", time.left >= body.left - 1f && time.right <= body.right + 1f)
                }
                assertEquals(CapsuleChatStyle.timeSize.sp, textLayout(timeText).layoutInput.style.fontSize)
                // Equal reactions remain two visible emoji, without a numeric badge.
                compose.onAllNodesWithText("❤️", useUnmergedTree = true).assertCountEquals(2)
                compose.onNodeWithText("2", useUnmergedTree = true).assertDoesNotExist()
                saveCanvas("capsules-schema2-${if (own) "out" else "in"}-${kind?.name?.lowercase() ?: "text"}.png")
            }
        }
    }

    @Test fun highlightedTransparentBubbleRetainsVisibleSurface() {
        compose.setContent { FixtureCanvas(Fixture(highlighted = true)) }
        compose.waitForIdle()
        assertBodyPixel(CapsuleChatStyle.entry)
        saveCanvas("capsules-schema2-highlighted.png")
    }

    @Test fun textSizePreferenceAndReactionTouchSurviveLargeSystemFont() {
        var reactionTaps = 0
        val shown = mutableStateOf(Fixture(own = true))
        compose.setContent {
            val realDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(realDensity.density, 1.6f)) {
                FixtureCanvas(shown.value, textSize = 19, onReaction = { reactionTaps++ })
            }
        }
        assertEquals(19.sp, textLayout("Capsule body").layoutInput.style.fontSize)
        assertEquals(FontWeight.Normal, textLayout("Capsule body").layoutInput.style.fontWeight)
        assertFalse("Body text must remain unclipped", textLayout("Capsule body").hasVisualOverflow)
        compose.runOnIdle { shown.value = Fixture(own = true, kind = AttachmentMediaType.VOICE) }
        assertFalse("Voice duration must remain unclipped", textLayout("0:12").hasVisualOverflow)
        compose.runOnIdle { shown.value = Fixture(own = true, kind = AttachmentMediaType.FILE) }
        assertEquals(19.sp, textLayout("Capsule.pdf").layoutInput.style.fontSize)
        assertEquals(FontWeight.Medium, textLayout("Capsule.pdf").layoutInput.style.fontWeight)
        assertFalse("File name must remain unclipped", textLayout("Capsule.pdf").hasVisualOverflow)
        val emojiLayout = textLayout("❤️", index = 0)
        assertEquals(CapsuleChatStyle.emojiSize.sp, emojiLayout.layoutInput.style.fontSize)
        val emojiGeometry = "font=${emojiLayout.layoutInput.style.fontSize} line=${emojiLayout.layoutInput.style.lineHeight} " +
            "measured=${emojiLayout.size} paragraph=${emojiLayout.multiParagraph.width}x${emojiLayout.multiParagraph.height} " +
            "constraints=${emojiLayout.layoutInput.constraints} intrinsic=${emojiLayout.multiParagraph.intrinsics.maxIntrinsicWidth} " +
            "lineLeft=${emojiLayout.getLineLeft(0)} lineRight=${emojiLayout.getLineRight(0)} " +
            "lines=${emojiLayout.lineCount} overflowWidth=${emojiLayout.didOverflowWidth} overflowHeight=${emojiLayout.didOverflowHeight}"
        android.util.Log.i("CapsuleBubbleUIQA", "reaction fontScale=1.6 $emojiGeometry")
        saveCanvas("capsules-schema2-large-font-before-reaction-tap.png")
        assertFalse("Emoji line must fit at large system font: $emojiGeometry", emojiLayout.hasVisualOverflow)
        assertEquals("Reaction must remain one complete line: $emojiGeometry", 1, emojiLayout.lineCount)
        assertTrue("Emoji must start inside its own cell: $emojiGeometry", emojiLayout.getLineLeft(0) >= -1f)
        assertTrue("Emoji must end inside its own cell: $emojiGeometry", emojiLayout.getLineRight(0) <= emojiLayout.size.width + 1f)
        compose.onAllNodesWithText("❤️")[0].performClick()
        compose.runOnIdle { assertEquals(1, reactionTaps) }
        saveCanvas("capsules-schema2-large-font.png")
    }

    @Test fun approvedShapeFamiliesKeepFileCornersHorizontallyMirrored() {
        // Leave enough height to assert the saved 25dp radii before framework
        // normalization; a separate short-body assertion checks the clamp.
        fun corners(shape: Shape, height: Float = 100f) = (shape.createOutline(Size(195f, height), LayoutDirection.Ltr, Density(1f)) as Outline.Rounded).roundRect
        val incomingFile = corners(CapsuleChatStyle.fileShape(false))
        val outgoingFile = corners(CapsuleChatStyle.fileShape(true))
        assertEquals(25f, incomingFile.topLeftCornerRadius.x, .01f)
        assertEquals(25f, incomingFile.topRightCornerRadius.x, .01f)
        assertEquals(incomingFile.topLeftCornerRadius, outgoingFile.topRightCornerRadius)
        assertEquals(incomingFile.bottomLeftCornerRadius, outgoingFile.bottomRightCornerRadius)
        assertEquals(incomingFile.topRightCornerRadius, outgoingFile.topLeftCornerRadius)
        assertEquals(incomingFile.bottomRightCornerRadius, outgoingFile.bottomLeftCornerRadius)
        val incomingText = corners(CapsuleChatStyle.bodyShape(false))
        val outgoingText = corners(CapsuleChatStyle.bodyShape(true))
        assertEquals(25f, incomingText.topLeftCornerRadius.x, .01f)
        assertEquals(25f, incomingText.topRightCornerRadius.x, .01f)
        assertEquals(25f, incomingText.bottomRightCornerRadius.x, .01f)
        assertEquals(0f, incomingText.bottomLeftCornerRadius.x, .01f)
        assertEquals(25f, outgoingText.topLeftCornerRadius.x, .01f)
        assertEquals(25f, outgoingText.topRightCornerRadius.x, .01f)
        assertEquals(0f, outgoingText.bottomRightCornerRadius.x, .01f)
        assertEquals(25f, outgoingText.bottomLeftCornerRadius.x, .01f)
        assertEquals(Color(0xC42A2A2A), CapsuleChatStyle.incoming)
        assertEquals(Color(0xC4003D4D), CapsuleChatStyle.outgoing)
        assertEquals(Color(0xBF2A2A2A), CapsuleChatStyle.voiceBackground(false))
        assertEquals(Color(0xBF003D4D), CapsuleChatStyle.voiceBackground(true))
        assertEquals(Color(0xBF2A2A2A), CapsuleChatStyle.fileBackground(false))
        assertEquals(Color(0xBF003D4D), CapsuleChatStyle.fileBackground(true))
        for (own in listOf(false, true)) {
            assertEquals(25f, corners(CapsuleChatStyle.voiceShape(own)).topLeftCornerRadius.x, .01f)
            assertEquals("A 70dp body preserves the approved 25dp corner without enlargement",
                25f, corners(CapsuleChatStyle.voiceShape(own), height = 70f).topLeftCornerRadius.x, .01f)
            assertEquals("Short rounded bodies proportionally fit both corners into the actual height",
                20f, corners(CapsuleChatStyle.voiceShape(own), height = 40f).topLeftCornerRadius.x, .01f)
        }
    }

    @Composable private fun FixtureCanvas(fixture: Fixture, textSize: Int = CapsuleChatStyle.fontSize.toInt(), onReaction: () -> Unit = {}) {
        CocoTheme {
            CompositionLocalProvider(LocalCocoColors provides CapsuleChatStyle.colors, LocalChatTextSize provides textSize) {
                pixelsPerDp = LocalDensity.current.density
                Box(Modifier.fillMaxSize().testTag("capsule-test-canvas").background(canvasColor)
                    .onGloballyPositioned { canvasBounds = it.boundsInWindow() }.padding(24.dp), contentAlignment = Alignment.TopCenter) {
                    MessageBubble(message = fixture.message, attachment = fixture.attachment,
                        isOwnMessage = fixture.own, playingAttachmentId = null, highlighted = fixture.highlighted,
                        onBoundsChanged = { bodyBounds = it }, onRetry = {}, onDownload = {},
                        onTogglePlay = { _, _ -> }, onOpenFile = { _, _ -> }, onOpenImage = {},
                        onLongPress = {}, onSelectionTap = {}, onToggleReaction = { onReaction() },
                        onQuickReaction = null, onScrollToReply = {})
                }
            }
        }
    }

    private fun textLayout(text: String, index: Int = 0): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onAllNodes(hasText(text) or hasContentDescription(text), useUnmergedTree = true)[index]
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun bodyInRoot(): Rect {
        val canvas = compose.onNodeWithTag("capsule-test-canvas").fetchSemanticsNode().boundsInRoot
        val dx = canvas.left - canvasBounds.left
        val dy = canvas.top - canvasBounds.top
        return Rect(bodyBounds.left + dx, bodyBounds.top + dy, bodyBounds.right + dx, bodyBounds.bottom + dy)
    }

    private fun assertBodyPixel(expected: Color) {
        compose.waitForIdle()
        val image = compose.onNodeWithTag("capsule-test-canvas").captureToImage().asAndroidBitmap()
        try {
            // At the body's vertical midpoint, 7dp is safely within the fill
            // while remaining before both the 8dp voice control and 21dp text inset.
            val x = (bodyBounds.left - canvasBounds.left + 7f * pixelsPerDp).toInt().coerceIn(0, image.width - 1)
            val y = (bodyBounds.center.y - canvasBounds.top).toInt().coerceIn(0, image.height - 1)
            val actual = image.getPixel(x, y)
            val wanted = expected.toArgb()
            for (shift in listOf(0, 8, 16)) assertTrue("Body background channel at $x,$y",
                kotlin.math.abs(((wanted shr shift) and 255) - ((actual shr shift) and 255)) <= 2)
        } finally { image.recycle() }
    }

    private fun saveCanvas(name: String) {
        val image = compose.onNodeWithTag("capsule-test-canvas").captureToImage().asAndroidBitmap()
        try {
            val folder = compose.activity.getExternalFilesDir("capsules-uiqa")!!
            folder.mkdirs()
            File(folder, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }

    private data class Fixture(val own: Boolean = false, val kind: AttachmentMediaType? = null, val highlighted: Boolean = false) {
        val message = Message("capsule-$own-$kind", "synthetic-server-id", "synthetic-chat", "synthetic-sender", "Capsule body", 1,
            "2026-10-26T12:34:00Z", MessageStatus.READ, reactions = listOf(ReactionSummary("❤️", 2, true)))
        val attachment = kind?.let {
            Attachment("synthetic-attachment", message.clientMessageId, it,
                if (it == AttachmentMediaType.VOICE) "audio/ogg" else "application/pdf", "Capsule.pdf", 1024,
                null, null, 12_000, null, null, AttachmentDownloadState.NOT_DOWNLOADED, AttachmentUploadState.SENT, 100)
        }
    }
}
