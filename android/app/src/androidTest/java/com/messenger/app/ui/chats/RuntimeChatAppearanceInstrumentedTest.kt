package com.messenger.app.ui.chats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.messenger.app.domain.model.*
import com.messenger.app.ui.theme.*
import java.io.File
import java.util.UUID
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production bubbles and albums with local synthetic data only, no account or repository. */
@OptIn(ExperimentalTestApi::class)
class RuntimeChatAppearanceInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val canvasColor = Color(0xFF183C53)
    private var canvasWindow = Rect.Zero
    private var bodyWindow = Rect.Zero
    private var pixelDensity = 1f

    @Test fun draftStylesChangeRealTextVoiceAndFileBodiesAndRestoreTheSavedDefault() {
        val folder = fixtureDirectory()
        val file = File(folder, "Runtime.pdf").apply { writeText("Synthetic appearance fixture") }
        val settings = mutableStateOf(defaultChatAppearanceSettings())
        val kind = mutableStateOf<AttachmentMediaType?>(null)
        val own = mutableStateOf(true)
        var fileOpens = 0
        var reactions = 0
        val message = message("runtime-style", "Привет! Как дела?", reactions = listOf(ReactionSummary("❤️", 2, true)))
        compose.setContent {
            Fixture(settings.value) {
                Bubble(message, attachment(message, kind.value, file), own.value,
                    onFile = { fileOpens++ }, onReaction = { reactions++ })
            }
        }
        try {
            val custom = defaultChatAppearanceSettings().copy(fontSize = 19, fontFamily = "serif", mediumText = true,
                lineHeight = 1.5f, density = "relaxed", cornerRadius = 12f, outgoingColor = "violet", transparency = 40)
            val resolved = resolveChatAppearance(custom)
            for (outgoing in listOf(false, true)) for (media in listOf<AttachmentMediaType?>(null, AttachmentMediaType.VOICE, AttachmentMediaType.FILE)) {
                compose.runOnIdle { own.value = outgoing; kind.value = media; settings.value = custom }
                compose.waitForIdle()
                val background = when (media) {
                    AttachmentMediaType.VOICE -> resolved.voiceBackground(outgoing)
                    AttachmentMediaType.FILE -> resolved.fileBackground(outgoing)
                    else -> if (outgoing) resolved.outgoing else resolved.incoming
                }
                assertBodyPixel(background.compositeOver(canvasColor))
                if (media != AttachmentMediaType.VOICE) {
                    val text = if (media == AttachmentMediaType.FILE) "Runtime.pdf" else message.text
                    val layout = textLayout(text)
                    assertEquals(FontFamily.Serif, layout.layoutInput.style.fontFamily)
                    assertEquals(19.sp, layout.layoutInput.style.fontSize)
                    assertEquals(FontWeight.Medium, layout.layoutInput.style.fontWeight)
                    assertEquals(28.5.sp, layout.layoutInput.style.lineHeight)
                    assertFalse("Draft text must remain complete", layout.hasVisualOverflow)
                    val textBounds = textNode(text).fetchSemanticsNode().boundsInRoot
                    if (media == null) {
                        assertEquals("The real body uses draft horizontal padding", resolved.padX * pixelDensity,
                            textBounds.left - bodyInRoot().left, 1.5f)
                        assertEquals("The real body uses draft vertical padding", resolved.padY * pixelDensity,
                            textBounds.top - bodyInRoot().top, 1.5f)
                        assertBodyPixel(background.compositeOver(canvasColor), insetX = 5f, insetY = 5f)
                    }
                }
                // Appearance does not alter the emoji or coalesce duplicate reactions.
                compose.onAllNodesWithText("❤️", useUnmergedTree = true).assertCountEquals(2)
            }
            compose.runOnIdle { kind.value = AttachmentMediaType.FILE; own.value = true }
            compose.onNode(hasText("Runtime.pdf") or hasContentDescription("Runtime.pdf"), useUnmergedTree = true)
                .performTouchInput { click() }
            compose.waitUntil(3000) { fileOpens == 1 }
            compose.onAllNodesWithText("❤️")[0].performClick()
            compose.runOnIdle { assertEquals(1, reactions); kind.value = null; settings.value = defaultChatAppearanceSettings() }
            compose.waitForIdle()
            val restored = textLayout(message.text)
            assertEquals(CapsuleChatStyle.messageFontFamily, restored.layoutInput.style.fontFamily)
            assertEquals(CapsuleChatStyle.fontSize.sp, restored.layoutInput.style.fontSize)
            assertEquals(if (CapsuleChatStyle.mediumText) FontWeight.Medium else FontWeight.Normal, restored.layoutInput.style.fontWeight)
            assertBodyPixel(CapsuleChatStyle.outgoing.compositeOver(canvasColor))
        } finally { folder.deleteRecursively() }
    }

    @Test fun changingBubbleControlsDoesNotTintResizeOrReopenThePhoto() {
        val folder = fixtureDirectory()
        val image = File(folder, "runtime-photo.png")
        val photoColor = Color(0xFFAAC1D2)
        Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(photoColor.toArgb())
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val settings = mutableStateOf(defaultChatAppearanceSettings())
        var opens = 0
        val message = message("runtime-photo", "")
        val photo = attachment(message, AttachmentMediaType.IMAGE, image)!!
        compose.setContent { Fixture(settings.value) { Bubble(message, photo, true, onImage = { opens++ }) } }
        try {
            compose.waitUntil(5000) { bodyWindow.width > 0f && bodyWindow.height > 0f && bodyCenterMatches(photoColor) }
            val before = bodyWindow
            compose.runOnIdle {
                settings.value = settings.value.copy(fontFamily = "monospace", density = "compact", cornerRadius = 32f,
                    outgoingColor = "blue", transparency = 75)
            }
            compose.waitForIdle()
            assertEquals("The photo retains exact bounds when bubble controls change", before, bodyWindow)
            assertTrue("The decoded photo is never tinted by a bubble color", bodyCenterMatches(photoColor))
            compose.onNodeWithContentDescription(compose.activity.getString(com.messenger.app.R.string.photo_preview))
                .performTouchInput { click() }
            compose.waitUntil(3000) { opens == 1 }
            compose.runOnIdle { assertEquals(1, opens) }
        } finally { folder.deleteRecursively() }
    }

    @Test fun albumCaptionUsesDraftTypographyWithoutMovingTheMediaTiles() {
        val settings = mutableStateOf(defaultChatAppearanceSettings())
        val first = message("runtime-album-first", "").copy(caption = "Фотографии с прогулки", albumId = "runtime-album")
        val second = message("runtime-album-second", "").copy(albumId = "runtime-album")
        val messages = listOf(first, second)
        val attachments = messages.associate { it.clientMessageId to Attachment(it.clientMessageId, it.clientMessageId,
            AttachmentMediaType.IMAGE, "image/png", "example.png", 1024, 4, 3, null, null, null,
            AttachmentDownloadState.NOT_DOWNLOADED, AttachmentUploadState.SENT, 100) }
        val tileBounds = mutableMapOf<String, Rect>()
        var captionTaps = 0
        compose.setContent {
            Fixture(settings.value) {
                CapsuleChatAlbum(messages, true, attachments, onCaptionTap = { captionTaps++ }) { item ->
                    Box(Modifier.fillMaxSize().background(Color(0xFFAAC1D2))
                        .onGloballyPositioned { tileBounds[item.clientMessageId] = it.boundsInWindow() })
                }
            }
        }
        compose.waitUntil(5000) { tileBounds.size == 2 }
        val before = tileBounds.toMap()
        compose.runOnIdle {
            settings.value = settings.value.copy(fontSize = 19, fontFamily = "monospace", mediumText = true,
                lineHeight = 1.5f, density = "relaxed", outgoingColor = "green", transparency = 50)
        }
        compose.waitForIdle()
        val layout = textLayout(first.caption!!)
        assertEquals(FontFamily.Monospace, layout.layoutInput.style.fontFamily)
        assertEquals(19.sp, layout.layoutInput.style.fontSize)
        assertEquals(FontWeight.Medium, layout.layoutInput.style.fontWeight)
        assertEquals(28.5.sp, layout.layoutInput.style.lineHeight)
        assertFalse(layout.hasVisualOverflow)
        assertEquals("Caption changes must not rebuild mosaic geometry", before, tileBounds.toMap())
        saveCanvas("runtime-album-caption.png")
        compose.onNodeWithText(first.caption!!).performTouchInput { click() }
        compose.waitUntil(3000) { captionTaps == 1 }
    }

    @Test fun selectingACustomStyledMessageKeepsItsColorAndExactBounds() {
        val settings = defaultChatAppearanceSettings().copy(fontSize = 19, fontFamily = "monospace", density = "compact",
            cornerRadius = 16f, outgoingColor = "blue", transparency = 45)
        val selecting = mutableStateOf(false)
        val checked = mutableStateOf(false)
        var taps = 0
        val message = message("runtime-selection", "Настроенная переписка остаётся на месте при выборе сообщения.")
        compose.setContent {
            Fixture(settings) {
                Bubble(message, null, true, selectionMode = selecting.value, selected = checked.value, onSelection = { taps++ })
            }
        }
        compose.waitUntil(5000) { bodyWindow.width > 0f }
        val before = bodyWindow
        val base = resolveChatAppearance(settings).outgoing.compositeOver(canvasColor)
        assertBodyPixel(base)
        compose.runOnIdle { selecting.value = true; checked.value = true }
        compose.waitForIdle()
        assertEquals("Selecting does not move or resize custom text", before, bodyWindow)
        assertBodyPixel(CapsuleChatStyle.text.copy(alpha = .06f).compositeOver(base))
        saveCanvas("runtime-custom-style-selected.png")
        val mark = compose.onNode(isToggleable()).assertIsOn().assertIsDisplayed()
        val bounds = mark.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= 48f * pixelDensity - 1f)
        assertTrue(bounds.height >= 48f * pixelDensity - 1f)
        mark.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, taps); selecting.value = false; checked.value = false }
        compose.waitForIdle()
        assertEquals("Leaving selection restores the same custom text bounds", before, bodyWindow)
        assertBodyPixel(base)
    }

    @Test fun monochromeIsReversibleForActualTextVoiceAndFileBubblesWithoutLayoutOrTapChanges() {
        val folder = fixtureDirectory()
        val file = File(folder, "Runtime.pdf").apply { writeText("Synthetic monochrome fixture") }
        val custom = defaultChatAppearanceSettings().copy(fontSize = 18, fontFamily = "serif",
            mediumText = true, outgoingColor = "violet", transparency = 37, cornerRadius = 16f)
        val mode = mutableStateOf(AppColorStyle.COLORFUL)
        val kind = mutableStateOf<AttachmentMediaType?>(null)
        val own = mutableStateOf(true)
        var opens = 0
        var reactions = 0
        val message = message("runtime-app-palette", "Цвета можно вернуть", listOf(ReactionSummary("❤️", 1, true)))
        compose.setContent { Fixture(custom, mode.value) {
            Bubble(message, attachment(message, kind.value, file), own.value,
                onFile = { opens++ }, onReaction = { reactions++ })
        } }
        try {
            for (outgoing in listOf(false, true)) for (media in listOf<AttachmentMediaType?>(null, AttachmentMediaType.VOICE, AttachmentMediaType.FILE)) {
                compose.runOnIdle { own.value = outgoing; kind.value = media; mode.value = AppColorStyle.COLORFUL }
                compose.waitForIdle()
                val bounds = bodyWindow
                for (choice in listOf(AppColorStyle.MONOCHROME, AppColorStyle.COLORFUL)) {
                    compose.runOnIdle { mode.value = choice }
                    compose.waitForIdle()
                    val palette = resolveChatAppearance(custom, choice)
                    val fill = when (media) {
                        AttachmentMediaType.VOICE -> palette.voiceBackground(outgoing)
                        AttachmentMediaType.FILE -> palette.fileBackground(outgoing)
                        else -> if (outgoing) palette.outgoing else palette.incoming
                    }
                    assertBodyPixel(fill.compositeOver(canvasColor))
                    assertEquals("Only color changes, never message bounds", bounds, bodyWindow)
                    compose.onAllNodesWithText("❤️", useUnmergedTree = true).assertCountEquals(1)
                }
            }
            compose.runOnIdle { kind.value = AttachmentMediaType.FILE; mode.value = AppColorStyle.MONOCHROME }
            compose.onNode(hasText("Runtime.pdf") or hasContentDescription("Runtime.pdf"), useUnmergedTree = true).performTouchInput { click() }
            compose.waitUntil(3000) { opens == 1 }
            compose.onNodeWithText("❤️").performClick()
            compose.runOnIdle { assertEquals(1, reactions); assertEquals("violet", custom.outgoingColor); assertEquals(37, custom.transparency) }
        } finally { folder.deleteRecursively() }
    }

    /** Phase-2 comparison sheet: actual production bodies, no account or preference writes. */
    @Test fun defaultAndOverrideBodiesSideBySideAtNormalAndLargeFontScale() {
        val folder = fixtureDirectory()
        val file = File(folder, "Runtime.pdf").apply { writeText("Synthetic B01 fixture") }
        val scale = mutableStateOf(1f)
        val defaults = defaultChatAppearanceSettings()
        val variants = listOf("Default" to defaults, "Override" to defaults.copy(
            outgoingColor = "violet", transparency = 37, cornerRadius = 12f))
        var opens = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                MaterialTheme(typography = CocoTypography) {
                    Row(Modifier.fillMaxSize().background(Color.Black).testTag("runtime-appearance-canvas")
                        .padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((label, settings) in variants) {
                            val appearance = remember(settings) { resolveChatAppearance(settings, AppColorStyle.MONOCHROME) }
                            CompositionLocalProvider(LocalChatAppearanceStyle provides appearance,
                                LocalChatAppearanceSettings provides settings, LocalChatTextSize provides settings.fontSize,
                                LocalCocoColors provides CapsuleChatStyle.colors) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(label, color = Color.White)
                                    for (kind in listOf<AttachmentMediaType?>(null, AttachmentMediaType.FILE, AttachmentMediaType.VOICE)) {
                                        val item = message("b01-$label-$kind", "$label text")
                                        Box(Modifier.fillMaxWidth()) {
                                            Bubble(item, attachment(item, kind, file), true, onFile = { opens++ })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        try {
            val captures = mutableListOf<File>()
            for (fontScale in listOf(1f, 1.3f)) {
                compose.runOnIdle { scale.value = fontScale }
                compose.waitForIdle()
                for ((label, _) in variants) {
                    val body = "$label text"
                    val layout = textLayout(body)
                    assertFalse("$body must fit at $fontScale", layout.hasVisualOverflow)
                    assertTrue("The matched production node must retain inline metadata",
                        layout.layoutInput.text.text.startsWith(body) && layout.layoutInput.text.length > body.length)
                }
                captures += saveCanvas("b01-default-override-font-scale-${(fontScale * 100).toInt()}.png")
            }
            assertEquals("Each font scale needs its own capture", 2, captures.distinct().size)
            for (capture in captures) {
                val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(capture.path, dimensions)
                assertTrue("Capture must decode as a nonempty PNG: ${capture.name}",
                    dimensions.outWidth > 0 && dimensions.outHeight > 0 && dimensions.outMimeType == "image/png")
            }
            compose.onAllNodes(hasText("Runtime.pdf") or hasContentDescription("Runtime.pdf"), useUnmergedTree = true)
                .onFirst().performTouchInput { click() }
            compose.waitUntil(3000) { opens == 1 }
        } finally { folder.deleteRecursively() }
    }

    @Composable private fun Fixture(settings: ChatAppearanceSettings, appColorStyle: AppColorStyle = AppColorStyle.COLORFUL, content: @Composable () -> Unit) {
        val appearance = remember(settings, appColorStyle) { resolveChatAppearance(settings, appColorStyle) }
        CompositionLocalProvider(LocalChatAppearanceStyle provides appearance, LocalChatAppearanceSettings provides settings,
            LocalChatTextSize provides settings.fontSize, LocalCocoColors provides CapsuleChatStyle.colors) {
            MaterialTheme(typography = CocoTypography) {
                pixelDensity = LocalDensity.current.density
                Box(Modifier.width(320.dp).fillMaxSize().background(canvasColor).testTag("runtime-appearance-canvas")
                    .onGloballyPositioned { canvasWindow = it.boundsInWindow() }.padding(20.dp)) { content() }
            }
        }
    }

    @Composable private fun Bubble(message: Message, attachment: Attachment?, own: Boolean,
        onFile: () -> Unit = {}, onImage: () -> Unit = {}, onReaction: () -> Unit = {},
        selectionMode: Boolean = false, selected: Boolean = false, onSelection: () -> Unit = {}) {
        MessageBubble(message = message, attachment = attachment, isOwnMessage = own, playingAttachmentId = null,
            selectionMode = selectionMode, selected = selected,
            onBoundsChanged = { bodyWindow = it }, onRetry = {}, onDownload = {}, onTogglePlay = { _, _ -> },
            onOpenFile = { _, _ -> onFile() }, onOpenImage = { onImage() }, onLongPress = {}, onSelectionTap = onSelection,
            onToggleReaction = { onReaction() }, onQuickReaction = null, onScrollToReply = {})
    }

    private fun message(id: String, text: String, reactions: List<ReactionSummary> = emptyList()) =
        Message(id, "synthetic-$id", "runtime-appearance-chat", "synthetic-owner", text, 1,
            "2026-10-26T14:28:00Z", MessageStatus.READ, reactions = reactions)

    private fun attachment(message: Message, type: AttachmentMediaType?, file: File): Attachment? = type?.let {
        Attachment("runtime-${it.name}", message.clientMessageId, it,
            when(it) { AttachmentMediaType.IMAGE -> "image/png"; AttachmentMediaType.VOICE -> "audio/mp4"; else -> "application/pdf" },
            file.name, 240_000, if(it == AttachmentMediaType.IMAGE)96 else null, if(it == AttachmentMediaType.IMAGE)72 else null,
            if(it == AttachmentMediaType.VOICE)12_000 else null, null, file.path, AttachmentDownloadState.DOWNLOADED,
            AttachmentUploadState.SENT, 100)
    }

    private fun fixtureDirectory() = File(compose.activity.cacheDir, "runtime-style-fixture-${UUID.randomUUID()}").apply { mkdirs() }

    // Production inline metadata shares the body's AnnotatedString. A body-only exact
    // match misses that node; requiring the layout action excludes descriptive containers.
    private fun textNode(text: String) = compose.onNode(
        (hasText(text, substring = true) or hasContentDescription(text, substring = true)) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
        useUnmergedTree = true,
    )

    private fun textLayout(text: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        textNode(text)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    private fun bodyInRoot(): Rect {
        val canvas = compose.onNodeWithTag("runtime-appearance-canvas").fetchSemanticsNode().boundsInRoot
        return bodyWindow.translate(Offset(canvas.left - canvasWindow.left, canvas.top - canvasWindow.top))
    }

    private fun assertBodyPixel(expected: Color, insetX: Float = 7f, insetY: Float? = null) {
        val image = compose.onNodeWithTag("runtime-appearance-canvas").captureToImage().asAndroidBitmap()
        try {
            val x = (bodyWindow.left - canvasWindow.left + insetX * pixelDensity).toInt().coerceIn(0, image.width - 1)
            val y = (if(insetY == null) bodyWindow.center.y - canvasWindow.top else bodyWindow.top - canvasWindow.top + insetY * pixelDensity)
                .toInt().coerceIn(0, image.height - 1)
            assertPixel(expected.toArgb(), image.getPixel(x, y))
        } finally { image.recycle() }
    }

    private fun bodyCenterMatches(expected: Color): Boolean {
        if (bodyWindow.width <= 0f || bodyWindow.height <= 0f) return false
        val image = compose.onNodeWithTag("runtime-appearance-canvas").captureToImage().asAndroidBitmap()
        return try {
            val x = (bodyWindow.center.x - canvasWindow.left).toInt().coerceIn(0, image.width - 1)
            val y = (bodyWindow.center.y - canvasWindow.top).toInt().coerceIn(0, image.height - 1)
            val value = image.getPixel(x, y)
            listOf(0, 8, 16).all { abs(((expected.toArgb() shr it) and 255) - ((value shr it) and 255)) <= 2 }
        } finally { image.recycle() }
    }

    private fun assertPixel(expected: Int, actual: Int) {
        for (shift in listOf(0, 8, 16)) assertTrue("Expected actual rendered surface channel",
            abs(((expected shr shift) and 255) - ((actual shr shift) and 255)) <= 2)
    }

    private fun saveCanvas(name: String): File {
        val bitmap = compose.onNodeWithTag("runtime-appearance-canvas").captureToImage().asAndroidBitmap()
        try {
            val directory = compose.activity.getExternalFilesDir("capsules-uiqa")!!.apply { mkdirs() }
            val output = File(directory, name)
            output.outputStream().use {
                assertTrue("Capture PNG encoding must succeed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            return output
        } finally { bitmap.recycle() }
    }
}
