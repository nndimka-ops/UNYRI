package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.components.CocoChromePolicy
import com.messenger.app.ui.components.LocalCocoChromePolicy
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.theme.LocalGlassBackdrop
import com.messenger.app.ui.theme.GlassBackdropSource
import com.messenger.app.ui.theme.rememberGlassBackdrop
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** P2 fixture: black/colorful source, multiline input, attachments and explicit fallback gates. */
@OptIn(ExperimentalTestApi::class)
class ChatChromeParityComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun composerGrowsWithoutLosingDraftAndKeepsSendGuards() {
        val draft = mutableStateOf("")
        val submitting = mutableStateOf(false)
        var sent = ""
        compose.setContent { CocoTheme {
            ComposerBar(keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {},
                input = draft.value, submitting = submitting.value, onInputChange = { draft.value = it },
                isRecording = false, onAttach = {}, onMicToggle = {}, onSend = { sent = draft.value })
        } }
        val row = compose.onNodeWithTag("composer-input-row")
        val singleLine = row.fetchSemanticsNode().boundsInRoot.height
        compose.onNode(hasSetTextAction()).performTextReplacement("First\nSecond\nThird")
        assertTrue(row.fetchSemanticsNode().boundsInRoot.height > singleLine)
        val fourLines = "First\nSecond\nThird\nFourth"
        compose.onNode(hasSetTextAction()).performTextReplacement(fourLines)
        val cappedHeight = row.fetchSemanticsNode().boundsInRoot.height
        for (extra in listOf("\nFifth", "\nFifth\nSixth")) {
            compose.onNode(hasSetTextAction()).performTextReplacement(fourLines + extra)
            assertEquals("The input must scroll after four lines", cappedHeight, row.fetchSemanticsNode().boundsInRoot.height, 1f)
            compose.runOnIdle { assertEquals(fourLines + extra, draft.value) }
        }
        compose.onNode(hasSetTextAction()).performTextInput(" tail")
        compose.runOnIdle { assertEquals(fourLines + "\nFifth\nSixth tail", draft.value) }
        assertEquals(cappedHeight, row.fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithTag("composer-send").performClick()
        compose.runOnIdle { assertEquals(draft.value, sent); submitting.value = true }
        compose.onNodeWithTag("composer-send").assertIsNotEnabled()
        compose.runOnIdle { submitting.value = false; draft.value = "" }
        compose.onNodeWithTag("composer-record").assertExists()
        assertEquals(singleLine, row.fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @Test fun feedbackAndTrayStayInsideOneMaterialAndRemovalIsWired() {
        val uris = mutableStateOf(listOf(android.net.Uri.parse("file:///missing-a.png"), android.net.Uri.parse("file:///missing-b.png")))
        compose.setContent { CocoTheme {
            ComposerBar(attachmentCount = uris.value.size, attachmentFeedback = "Добавлено: 2",
                attachments = if (uris.value.isEmpty()) null else ({
                    ComposerAttachmentTray(photos = uris.value, file = null,
                        unavailable = uris.value.toSet(), onRemovePhoto = { uri -> uris.value = uris.value - uri }, onRemoveFile = {})
                }), keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {},
                input = "caption", submitting = false, onInputChange = {}, isRecording = false,
                onAttach = {}, onMicToggle = {}, onSend = {})
        } }
        val surface = compose.onNodeWithTag("composer-input-field").fetchSemanticsNode().boundsInRoot
        val feedback = compose.onNodeWithTag("composer-fill-feedback").assertTextEquals("Добавлено: 2").fetchSemanticsNode().boundsInRoot
        assertTrue(surface.contains(feedback.topLeft) && surface.contains(feedback.bottomRight))
        val first = compose.onNodeWithTag("composer-attachment-0").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("composer-attachment-1").fetchSemanticsNode().boundsInRoot
        assertEquals(first.width, first.height, 1f)
        assertTrue(second.left > first.right)
        compose.onNodeWithTag("composer-tray-separator").assertExists()
        compose.onNodeWithTag("composer-remove-0").performClick()
        compose.runOnIdle { assertEquals(1, uris.value.size) }
    }

    @Test fun materialIsStaticAndFallbackTracksAccessibilityWithoutChangingTheDraft() {
        val policy = mutableStateOf(CocoChromePolicy(true, false, false))
        val sourceColors = mutableStateOf(Color.Red to Color.Blue)
        var density = 1f
        compose.setContent { CocoTheme {
            density = LocalDensity.current.density
            val glass = rememberGlassBackdrop(policy.value.samplesBackdrop(android.os.Build.VERSION.SDK_INT >= 31))
            CompositionLocalProvider(LocalGlassBackdrop provides glass, LocalCocoChromePolicy provides policy.value) {
                Box(Modifier.width(360.dp).height(240.dp).testTag("b02-material-fixture")) {
                    GlassBackdropSource(glass, Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(sourceColors.value.first))
                        Box(Modifier.weight(1f).fillMaxHeight().background(sourceColors.value.second))
                    }
                    }
                    Column {
                        ChatHeaderSurface(Modifier.padding(12.dp).testTag("b02-header-surface")) { Text("Аня QA", Modifier.padding(12.dp)) }
                        ComposerBar(keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {},
                            input = "Draft", submitting = false, onInputChange = {}, isRecording = false,
                            onAttach = {}, onMicToggle = {}, onSend = {})
                    }
                }
            }
        } }
        for (fallback in listOf(false, true)) {
            compose.runOnIdle { policy.value = policy.value.copy(reduceTransparency = fallback) }
            compose.waitForIdle()
            val before = compose.onNodeWithTag("b02-material-fixture").captureToImage().asAndroidBitmap()
            save(before, if (fallback) "material-reduced-transparency.png" else "material-colorful.png")
            val frame = compose.onNodeWithTag("b02-material-fixture").fetchSemanticsNode().boundsInRoot
            val input = compose.onNodeWithTag("composer-input-field").fetchSemanticsNode().boundsInRoot
            val y = (input.center.y - frame.top).toInt().coerceIn(0, before.height - 1)
            val left = before.getPixel((before.width * .4f).toInt(), y)
            val right = before.getPixel((before.width * .6f).toInt(), y)
            if (fallback || android.os.Build.VERSION.SDK_INT < 31) assertEquals("Solid fallback hides the backdrop", left, right)
            else assertNotEquals("Material must sample the colorful backdrop", left, right)
            compose.mainClock.advanceTimeBy(1000)
            val after = compose.onNodeWithTag("b02-material-fixture").captureToImage().asAndroidBitmap()
            try { assertTrue("Unchanged chrome must stay still", before.sameAs(after)) }
            finally { before.recycle(); after.recycle() }
            compose.onNodeWithText("Draft").assertExists()
        }
        compose.runOnIdle {
            sourceColors.value = Color.White to Color.White
            policy.value = CocoChromePolicy(true, false, false)
        }
        val bright = compose.onNodeWithTag("b02-material-fixture").captureToImage().asAndroidBitmap()
        try {
            save(bright, "material-white-photo.png")
            val frame = compose.onNodeWithTag("b02-material-fixture").fetchSemanticsNode().boundsInRoot
            val header = compose.onNodeWithTag("b02-header-surface").fetchSemanticsNode().boundsInRoot
            val input = compose.onNodeWithTag("composer-input-field").fetchSemanticsNode().boundsInRoot
            val samples = listOf(
                (header.center.x - frame.left) to (header.top + 4f * density - frame.top),
                (frame.width * .4f) to (input.center.y - frame.top),
            )
            for ((x, y) in samples) {
                val panel = Color(bright.getPixel(x.toInt().coerceIn(0, bright.width - 1), y.toInt().coerceIn(0, bright.height - 1)))
                for (ink in listOf(CapsuleChatStyle.text, CapsuleChatStyle.muted, ChatControls.mint)) {
                    val contrast = (ink.luminance() + .05f) / (panel.luminance() + .05f)
                    assertTrue("White photo must not hide header/input ink $ink: $contrast", contrast >= 4.5f)
                }
            }
        } finally { bright.recycle() }
        compose.runOnIdle { sourceColors.value = Color.Black to Color.Black; policy.value = policy.value.copy(lowPower = true) }
        compose.onNodeWithText("Draft").assertExists()
        val black = compose.onNodeWithTag("b02-material-fixture").captureToImage().asAndroidBitmap()
        try { save(black, "material-black-low-power.png") } finally { black.recycle() }
    }

    @Test fun attachmentPopupUsesAnOpaqueFallbackAndKeepsItsAction() {
        val sourceColor = mutableStateOf(Color.White)
        var openedPhotos = 0
        compose.setContent { CocoTheme {
            val policy = CocoChromePolicy(true, false, false)
            val glass = rememberGlassBackdrop(policy.samplesBackdrop(android.os.Build.VERSION.SDK_INT >= 31))
            CompositionLocalProvider(LocalGlassBackdrop provides glass, LocalCocoChromePolicy provides policy) {
                Box(Modifier.fillMaxSize()) {
                    GlassBackdropSource(glass, Modifier.fillMaxSize().background(sourceColor.value)) {}
                    ComposerBar(keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {},
                        input = "Draft", submitting = false, onInputChange = {}, isRecording = false,
                        onAttach = { openedPhotos++ }, onMicToggle = {}, onSend = {})
                }
            }
        } }
        compose.onNodeWithTag("composer-attachment-toggle").performClick()
        val menu = compose.onNodeWithTag("composer-attachment-menu").assertIsDisplayed()
        val overWhite = menu.captureToImage().asAndroidBitmap()
        compose.runOnIdle { sourceColor.value = Color.Black }
        val overBlack = menu.captureToImage().asAndroidBitmap()
        try {
            save(overWhite, "attachment-popup-white-backdrop.png")
            for (fraction in listOf(.25f, .5f, .75f)) {
                val x = (overWhite.width * .9f).toInt()
                val y = (overWhite.height * fraction).toInt()
                assertEquals("Popup interior must be opaque", 255, android.graphics.Color.alpha(overWhite.getPixel(x, y)))
                assertEquals("Popup history must not show sharply through its surface", overWhite.getPixel(x, y), overBlack.getPixel(x, y))
            }
        } finally { overWhite.recycle(); overBlack.recycle() }
        compose.onNodeWithTag("composer-attachment-photos").performClick()
        compose.runOnIdle { assertEquals(1, openedPhotos) }
        menu.assertDoesNotExist()
    }

    @Test fun composerOverlaysShareReadAndMediaCoverageThroughDismissalSwitchingAndDisposal() {
        val overlays = ComposerOverlayState()
        val showComposer = mutableStateOf(true)
        var photosOpened = 0
        compose.setContent { CocoTheme {
            val list = rememberLazyListState()
            val mediaWork = rememberTimelineMediaWork(list, overlays.covered, 0, 0)
            val readable = ChatReadPolicy.readable(true, true, overlays.covered)
            Column(Modifier.fillMaxSize()) {
                Text(if (readable) "readable" else "covered", Modifier.testTag("overlay-read-gate"))
                CompositionLocalProvider(LocalTimelineMediaWork provides mediaWork) {
                    LazyColumn(state = list, modifier = Modifier.weight(1f)) {
                        item(key = "probe") {
                            CompositionLocalProvider(LocalMediaRowKey provides "probe") {
                                Text(if (rememberMediaWorkAllowed()) "running" else "paused", Modifier.testTag("overlay-media-gate"))
                            }
                        }
                    }
                }
                if (showComposer.value) ComposerBar(overlayState = overlays,
                    keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {},
                    input = "Draft", submitting = false, onInputChange = {}, isRecording = false,
                    onAttach = { photosOpened++ }, onMicToggle = {}, onSend = {})
            }
        } }
        fun assertCoverage(covered: Boolean) {
            compose.onNodeWithTag("overlay-read-gate").assertTextEquals(if (covered) "covered" else "readable")
            compose.onNodeWithTag("overlay-media-gate").assertTextEquals(if (covered) "paused" else "running")
        }
        assertCoverage(false)
        compose.onNodeWithTag("composer-attachment-toggle").performClick()
        assertCoverage(true)
        compose.runOnIdle { overlays.show(ComposerOverlay.Emoji) }
        assertCoverage(true)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertCoverage(false)
        compose.onNodeWithTag("composer-emoji").performClick()
        assertCoverage(true)
        compose.runOnIdle { overlays.show(ComposerOverlay.Attachments) }
        assertCoverage(true)
        compose.onNodeWithTag("composer-attachment-photos").performClick()
        compose.runOnIdle { assertEquals(1, photosOpened) }
        assertCoverage(false)
        compose.onNodeWithTag("composer-attachment-toggle").performClick()
        assertCoverage(true)
        compose.runOnIdle { showComposer.value = false }
        assertCoverage(false)
        compose.runOnIdle { assertFalse(overlays.covered) }
    }

    private fun save(bitmap: android.graphics.Bitmap, name: String) {
        val folder = compose.activity.getExternalFilesDir("b02-ui-parity")!!
        folder.mkdirs()
        java.io.File(folder, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
