package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import com.messenger.app.ui.theme.GlassBackdropSource
import com.messenger.app.ui.theme.rememberGlassBackdrop
import com.messenger.app.ui.theme.LocalCocoColors
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.messenger.app.domain.model.ChatSummary
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/** Phase 2 can run these alongside the S15 screenshot fixtures; no device needed to compile. */
class ForwardPickerComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val picker = ForwardPickerSession().apply {
        open(listOf("source"), ForwardPickerPreview(1, snippets = listOf("Selected message")), emptyMap())
    }
    private var sends = 0
    private var shares = 0
    private var reloads = 0
    private var fallbackColor = 0
    private val chats = listOf(ChatSummary("saved", "saved", null, "", null), ChatSummary("a", "direct", null, "", null))

    private fun mount(targets: List<ChatSummary> = chats) {
        compose.setContent {
            CocoTheme {
                val state by picker.state.collectAsState()
                if (state.visible) ForwardChatPickerDialog(targets, state, picker::dismiss, picker::query, picker::comment,
                    { picker.toggle(it) }, { reloads++ }, { sends++ }, { shares++ }, backdrop = null)
            }
        }
    }

    @Test fun multiSelectionSearchClearAndChipRemovalKeepComment() {
        mount()
        compose.onNodeWithTag("forward-preview").assertTextEquals("Selected message")
        compose.onNodeWithTag("forward-confirm").assertDoesNotExist()
        compose.onNodeWithTag("forward-target-saved").performClick().assertIsOn()
        compose.onNodeWithTag("forward-comment").performTextInput("Comment")
        compose.onNodeWithTag("forward-target-a").performClick().assertIsOn()
        compose.onNodeWithTag("forward-search").performTextInput("unmatched")
        compose.onNodeWithTag("forward-target-saved").assertDoesNotExist()
        compose.onNodeWithTag("forward-clear-search").performClick()
        compose.onNodeWithTag("forward-target-saved").assertIsOn()
        compose.onNodeWithTag("forward-chip-a").performClick()
        compose.onNodeWithTag("forward-comment").assertTextEquals("Comment")
        compose.onNodeWithTag("forward-confirm").performClick()
        compose.runOnIdle { assertEquals(1, sends); assertEquals(listOf("saved"), picker.state.value.selectedIds) }
    }

    @Test fun cancelReopenAndShareRetainThePickerDraft() {
        mount()
        compose.onNodeWithTag("forward-target-saved").performClick()
        compose.onNodeWithTag("forward-comment").performTextInput("Keep me")
        compose.onNodeWithTag("forward-share").performClick()
        compose.onNodeWithTag("forward-cancel").performClick()
        compose.onNodeWithTag("forward-picker").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, shares)
            picker.open(listOf("source"), ForwardPickerPreview(1), emptyMap())
        }
        compose.onNodeWithTag("forward-target-saved").assertIsOn()
        compose.onNodeWithTag("forward-comment").assertTextEquals("Keep me")
    }

    @Test fun loadFailureOffersWorkingRetryAndNoDestinationCannotSend() {
        picker.loading(false, failed = true)
        mount(emptyList())
        compose.onNodeWithTag("forward-retry-load").assertIsEnabled().performClick()
        compose.onNodeWithTag("forward-confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, reloads); assertEquals(0, sends) }
    }

    @Test fun busyShareDisablesRecipientChangesAndSendButAllowsClose() {
        picker.toggle("saved"); picker.beginShare()
        mount()
        compose.onNodeWithTag("forward-target-saved").assertIsNotEnabled()
        compose.onNodeWithTag("forward-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("forward-share").assertIsNotEnabled()
        compose.onNodeWithTag("forward-cancel").assertIsEnabled().performClick()
        compose.onNodeWithTag("forward-picker").assertDoesNotExist()
    }

    @Test fun disappearedRecipientRemainsRemovableEvenWhenItWasTheOnlySelection() {
        val targets = mutableStateOf(chats)
        picker.toggle("saved", chats.first())
        compose.setContent { CocoTheme {
            val state by picker.state.collectAsState()
            ForwardChatPickerDialog(targets.value, state, picker::dismiss, picker::query, picker::comment,
                { picker.toggle(it) }, {}, {}, {}, backdrop = null)
        } }
        compose.runOnIdle { targets.value = emptyList() }
        compose.onNodeWithTag("forward-chip-saved").assertIsEnabled().performClick()
        compose.onNodeWithTag("forward-confirm").assertDoesNotExist()
    }

    @Test fun explicitBackdropSamplesMediaOutsideAnyChromeProvider() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 31)
        mountOverRedMedia(blurEnabled = true)
        val image = compose.onNodeWithTag("forward-targets").captureToImage().asAndroidBitmap()
        try {
            val pixel = image.getPixel(image.width / 2, image.height - 4)
            assertTrue("The supplied media layer must tint the picker", android.graphics.Color.red(pixel) > android.graphics.Color.green(pixel) + 30)
        } finally { image.recycle() }
    }

    @Test fun explicitReducedEffectsBackdropKeepsTheOpaqueFallback() {
        mountOverRedMedia(blurEnabled = false)
        val image = compose.onNodeWithTag("forward-targets").captureToImage().asAndroidBitmap()
        try {
            val pixel = image.getPixel(image.width / 2, image.height - 4)
            assertTrue(kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(fallbackColor)) <= 2)
            assertTrue(kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(fallbackColor)) <= 2)
            assertTrue(kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(fallbackColor)) <= 2)
        } finally { image.recycle() }
    }

    private fun mountOverRedMedia(blurEnabled: Boolean) {
        compose.setContent { CocoTheme {
            val backdrop = rememberGlassBackdrop(blurEnabled)
            fallbackColor = LocalCocoColors.current.deepPanel.toArgb()
            Box(Modifier.fillMaxSize()) {
                GlassBackdropSource(backdrop, Modifier.fillMaxSize().background(Color.Red)) {}
                // Deliberately no LocalCocoChromeBackdrop provider, matching the production host.
                ForwardChatPickerDialog(emptyList(), picker.state.value, picker::dismiss, picker::query, picker::comment,
                    { picker.toggle(it) }, {}, {}, {}, backdrop = backdrop)
            }
        } }
    }
}
