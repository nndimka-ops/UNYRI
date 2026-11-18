package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phone-free in B06: compile here; run with the lead's phase-2 instrumentation. */
class MediaViewerChromeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun narrowVideoTransportUsesDisjointSlots() {
        compose.setContent { Column(Modifier.width(304.dp)) { ViewerTransportRow(
            actions = { Box(Modifier.size(48.dp).testTag("action-slot")) },
            transport = { Box(Modifier.size(144.dp, 48.dp).testTag("transport-slot")) },
            options = { Box(Modifier.size(96.dp, 48.dp).testTag("options-slot")) }) } }
        val actions = compose.onNodeWithTag("action-slot").getUnclippedBoundsInRoot()
        val transport = compose.onNodeWithTag("transport-slot").getUnclippedBoundsInRoot()
        val options = compose.onNodeWithTag("options-slot").getUnclippedBoundsInRoot()
        assertTrue(actions.right <= transport.left)
        assertTrue(transport.right <= options.left)
    }

    @Test fun narrowChromeHasReachableIndependentActionsAndRealMetadata() {
        var closed = 0; var forwarded = 0; var edited = 0; var scanned = 0; var more = 0
        val fixtures = List(90) { "item-$it" }
        compose.setContent { CocoTheme {
            Column(Modifier.width(320.dp).testTag("viewer-chrome-fixture")) {
                MediaViewerHeader("Вы", "сегодня в 23:42", fixtures.lastIndex - 1, fixtures.size, { closed++ })
                MediaViewerPhotoToolbar(true, "Forward", { forwarded++ }, { edited++ }, { scanned++ }, {
                    ViewerGlyphButton(MediaViewerIcons.More, "More", glass = true, onClick = { more++ })
                })
            }
        } }
        compose.onNodeWithText("89 / 90").assertIsDisplayed()
        assertEquals(compose.onNodeWithTag("viewer-chrome-fixture").getUnclippedBoundsInRoot().right,
            compose.onNodeWithTag("media-page-counter").getUnclippedBoundsInRoot().right)
        compose.onNodeWithTag("media-metadata").assertTextContains("Вы")
        compose.onNodeWithContentDescription("Forward").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_markup)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.viewer_text_in_photo)).performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.dismiss)).performClick()
        compose.runOnIdle { assertEquals(listOf(1, 1, 1, 1, 1), listOf(closed, forwarded, edited, scanned, more)) }
    }

    @Test fun unavailablePhotoDisablesActionsAndSingleItemHasNoCounter() {
        compose.setContent { CocoTheme { Column {
            MediaViewerHeader(null, null, 0, 1, {})
            MediaViewerPhotoToolbar(false, "Forward", {}, {}, {}, {
                ViewerGlyphButton(MediaViewerIcons.More, "More", enabled = false, glass = true, onClick = {})
            })
        } } }
        compose.onNodeWithTag("media-page-counter").assertDoesNotExist()
        compose.onNodeWithTag("media-metadata").assertDoesNotExist()
        compose.onNodeWithContentDescription("Forward").assertIsNotEnabled()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_markup)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.viewer_text_in_photo)).assertIsNotEnabled()
        compose.onNodeWithContentDescription("More").assertIsNotEnabled()
    }
}
