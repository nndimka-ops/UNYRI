package com.messenger.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.theme.SavedSidebarStyle
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real profile/footer layout; the avatar slot avoids accounts, HTTP and user data. */
class SavedSidebarProfileInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun compactHeaderUsesResponsiveAvatarAndSeparateCloseTarget() {
        val metrics = SavedSidebarStyle.metrics(390f)
        var edits = 0
        var closes = 0
        compose.setContent {
            CocoTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    Box(Modifier.requiredSize(metrics.contentWidth.dp, 180.dp)) {
                        SavedSidebarProfile("Dimon", "dima95", metrics, false, { edits++ }, { closes++ }) { size, fill, _ ->
                            Box(Modifier.size(size).background(fill))
                        }
                    }
                }
            }
        }
        val avatar = compose.onNodeWithTag("saved-sidebar-avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithTag("saved-sidebar-name", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithTag("saved-sidebar-close").assertHasClickAction().fetchSemanticsNode().boundsInRoot
        assertEquals("compact", metrics.profileLayout)
        assertEquals(39.5f, metrics.avatarSize, .001f)
        assertTrue(abs(avatar.width - 39.5f) <= 1f)
        assertTrue(abs(avatar.height - 39.5f) <= 1f)
        assertTrue("Name must start after the small avatar", name.left >= avatar.right)
        assertTrue("Close target must not cover the profile name", name.right <= close.left)
        assertEquals(44f, close.width, 1f)
        assertEquals(44f, close.height, 1f)
        compose.onNodeWithTag("saved-sidebar-profile-edit").performClick()
        compose.onNodeWithTag("saved-sidebar-close").performClick()
        compose.runOnIdle { assertEquals(1, edits); assertEquals(1, closes) }
        compose.onNodeWithTag("saved-sidebar-online", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun narrowLargeTextHeaderDoesNotOverlapAndOnlineNeedsActualConnection() {
        val metrics = SavedSidebarStyle.metrics(240f)
        val online = mutableStateOf(false)
        compose.setContent {
            CocoTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    Box(Modifier.requiredSize(metrics.contentWidth.dp, 620.dp).testTag("narrow-header")) {
                        SavedSidebarProfile("Long profile name", "long_username", metrics, online.value, {}, {}) { size, fill, _ ->
                            Box(Modifier.size(size).background(fill))
                        }
                    }
                }
            }
        }
        assertEquals("center", metrics.profileLayout)
        val parent = compose.onNodeWithTag("narrow-header").fetchSemanticsNode().boundsInRoot
        val avatar = compose.onNodeWithTag("saved-sidebar-avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithTag("saved-sidebar-close").fetchSemanticsNode().boundsInRoot
        assertTrue("Centered avatar must clear the separate close button", avatar.top >= close.bottom)
        for (tag in listOf("saved-sidebar-name", "saved-sidebar-username")) {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= parent.left && bounds.right <= parent.right)
            val layout = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            assertFalse("Long header text must wrap completely", layout.single().hasVisualOverflow)
        }
        compose.onNodeWithTag("saved-sidebar-online", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { online.value = true }
        compose.onNodeWithTag("saved-sidebar-online", useUnmergedTree = true).assertExists()
        compose.runOnIdle { online.value = false }
        compose.onNodeWithTag("saved-sidebar-online", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun narrowLargeTextSignOutWrapsAndKeepsActionAndVersion() {
        var exits = 0
        compose.setContent {
            CocoTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    Box(Modifier.requiredSize(128.dp, 360.dp).testTag("narrow-footer")) {
                        SavedSidebarFooter { exits++ }
                    }
                }
            }
        }
        val label = compose.onNodeWithTag("saved-sidebar-logout-label", useUnmergedTree = true)
        val layout = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertTrue("Fixture must exercise wrapped sign-out text", layout.single().lineCount > 1)
        assertFalse("Sign-out label must never clip", layout.single().hasVisualOverflow)
        val button = compose.onNodeWithTag("saved-sidebar-logout").assertHasClickAction()
        val buttonBounds = button.fetchSemanticsNode().boundsInRoot
        val labelBounds = label.fetchSemanticsNode().boundsInRoot
        assertTrue(buttonBounds.height >= 48f)
        assertTrue(labelBounds.left >= buttonBounds.left && labelBounds.right <= buttonBounds.right)
        assertTrue(labelBounds.top >= buttonBounds.top && labelBounds.bottom <= buttonBounds.bottom)
        val footer = compose.onNodeWithTag("saved-sidebar-footer").fetchSemanticsNode().boundsInRoot
        val version = compose.onNodeWithTag("saved-sidebar-version", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(version.top >= buttonBounds.bottom)
        assertTrue(abs(version.center.x - footer.center.x) <= 1f)
        button.performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }
}
