package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.components.*
import com.messenger.app.ui.media.EditorButton
import com.messenger.app.ui.media.EditorGlyph
import com.messenger.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Qa2ReleaseBlockersComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun selfIncludingJumpControlRendersOpaqueInsideCaptureAndGlassOutside() {
        compose.setContent { CocoTheme {
            val source = rememberGlassBackdrop(blurEnabled = true)
            CompositionLocalProvider(LocalCocoChromeBackdrop provides source,
                LocalCocoChromePolicy provides CocoChromePolicy(true, false, false)) {
                Box(Modifier.size(200.dp)) {
                    GlassBackdropSource(source, Modifier.fillMaxSize().testTag("capture")) {
                        // Regression: same control was recorded into its own source.
                        ChatNavigationButton(onClick = {})
                        Box(Modifier.size(30.dp).glassPanel(androidx.compose.foundation.shape.CircleShape, source))
                    }
                    Box(Modifier.padding(top = 90.dp)) { ChatNavigationButton(onClick = {}) }
                }
            }
        } }
        // Forces actual hardware display-list preparation, not just composition.
        compose.onNodeWithTag("capture").captureToImage()
        compose.waitForIdle()
    }

    @Test fun editorSurfaceDoesNotMeasureTheLooseScreenConstraints() {
        compose.setContent { CocoTheme {
            Row(Modifier.fillMaxSize()) {
                EditorButton(EditorGlyph.Close, R.string.editor_close, true, {}, surface = true,
                    modifier = Modifier.testTag("close"))
                Spacer(Modifier.weight(1f))
                EditorButton(EditorGlyph.Up, R.string.editor_attach, true, {}, surface = true,
                    modifier = Modifier.testTag("attach"))
            }
        } }
        compose.onNodeWithTag("close").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("attach").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
    }

    @Test fun tileCenterOpensAndSeparateAccessibleCornerRemoves() {
        var opens = 0
        var removes = 0
        compose.setContent { CocoTheme {
            ComposerAttachmentTile(false, 72.dp, 0, true, "Remove", { removes++ }) {
                Box(Modifier.fillMaxSize().background(Color.Blue).clickable { opens++ })
            }
        } }
        compose.onNodeWithTag("composer-attachment-0").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, opens); assertEquals(0, removes) }
        compose.onNodeWithTag("composer-remove-0").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            .performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, opens); assertEquals(1, removes) }
    }

    @Test fun emojiGridDoesNotMountUntilOemKeyboardHasFinishedHiding() {
        val state = EmojiInputState().apply { focusChanged(true); toggle() }
        val imeHeight = mutableIntStateOf(600)
        compose.setContent { CocoTheme {
            EmojiKeyboardHost(state, true, {}, ime = WindowInsets(bottom = imeHeight.intValue),
                target = WindowInsets(bottom = 0)) { Box(Modifier.fillMaxSize()) }
        } }
        compose.onNodeWithTag("emoji-keyboard").assertDoesNotExist()
        compose.runOnIdle { imeHeight.intValue = 0 }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("emoji-keyboard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("emoji-keyboard").assertIsDisplayed()
    }
}
