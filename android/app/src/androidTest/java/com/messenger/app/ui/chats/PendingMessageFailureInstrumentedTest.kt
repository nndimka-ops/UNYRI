package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.domain.repository.FailedMessageAction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PendingMessageFailureInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedSendOffersWorkingRetryAndCancellation() {
        var retried = 0
        var cancelled = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(240.dp)) {
                    PendingMessageFailureNotice({ retried++ }, { cancelled++ })
                }
            }
        }
        compose.onNodeWithText(label(R.string.msg_status_failed)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.retry)).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retried); assertEquals(1, cancelled) }
    }

    @Test fun compactMediaControlKeepsTileSizeAndOffersBothLabeledActions() {
        var retried = 0
        var cancelled = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.size(96.dp).testTag("tile")) {
                    PendingMessageFailureNotice({ retried++ }, { cancelled++ },
                        modifier = Modifier.align(Alignment.BottomEnd), compact = true)
                }
            }
        }
        val before = bounds("tile")
        val control = compose.onNodeWithContentDescription(label(R.string.msg_status_failed))
        val target = control.fetchSemanticsNode().boundsInRoot
        assertMinimumTouchTarget(target)
        control.performClick()
        assertEquals(before, bounds("tile"))
        compose.onNodeWithText(label(R.string.retry)).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.retry)).assertDoesNotExist()
        control.performClick()
        compose.onNodeWithText(label(R.string.cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retried); assertEquals(1, cancelled) }
        assertEquals(before, bounds("tile"))
    }

    @Test fun deletedReactionTargetCannotRetryButCanCancelWithoutChangingOtherActions() {
        var retried = 0
        var cancelled = 0
        val action = mutableStateOf(FailedMessageAction("test", "reaction", "reaction_target_deleted"))
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(240.dp)) {
                        FailedMessageActionNotice(action.value, { retried++ }, { cancelled++ })
                    }
                }
            }
        }
        compose.onNodeWithText(label(R.string.chat_reaction_target_deleted)).assertIsDisplayed()
        assertLabelUntruncated(R.string.chat_reaction_target_deleted)
        compose.onNodeWithText(label(R.string.retry)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        compose.runOnIdle {
            assertEquals(0, retried)
            assertEquals(1, cancelled)
            action.value = action.value.copy(kind = "edit")
        }
        compose.onNodeWithText(label(R.string.chat_failed_edit)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.retry)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retried); assertEquals(1, cancelled) }
    }

    @Test fun narrowNoticeAtDoubledFontKeepsLabelsAndSeparate48dpTargets() {
        var retried = 0
        var cancelled = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(180.dp)) {
                        PendingMessageFailureNotice({ retried++ }, { cancelled++ },
                            modifier = Modifier.testTag("notice"))
                    }
                }
            }
        }
        val notice = bounds("notice")
        val retry = compose.onNodeWithText(label(R.string.retry)).assertIsDisplayed()
        val cancel = compose.onNodeWithText(label(R.string.cancel)).assertIsDisplayed()
        val retryBounds = retry.fetchSemanticsNode().boundsInRoot
        val cancelBounds = cancel.fetchSemanticsNode().boundsInRoot
        assertMinimumTouchTarget(retryBounds)
        assertMinimumTouchTarget(cancelBounds)
        assertTrue(retryBounds.right <= cancelBounds.left || cancelBounds.right <= retryBounds.left)
        assertContains(notice, retryBounds)
        assertContains(notice, cancelBounds)
        assertLabelUntruncated(R.string.retry)
        assertLabelUntruncated(R.string.cancel)
        retry.performClick()
        cancel.performClick()
        compose.runOnIdle { assertEquals(1, retried); assertEquals(1, cancelled) }
    }

    private fun label(id: Int) = compose.activity.getString(id)
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertMinimumTouchTarget(bounds: Rect) {
        val minimum = 48 * compose.activity.resources.displayMetrics.density
        assertTrue("target=$bounds, minimum=$minimum", bounds.width >= minimum && bounds.height >= minimum)
    }

    private fun assertContains(outer: Rect, inner: Rect) {
        assertTrue("outer=$outer inner=$inner", inner.left >= outer.left && inner.right <= outer.right &&
            inner.top >= outer.top && inner.bottom <= outer.bottom)
    }

    private fun assertLabelUntruncated(id: Int) {
        val text = label(id)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse(layout.didOverflowHeight)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        repeat(layout.lineCount) { line ->
            assertFalse(layout.isLineEllipsized(line))
            assertTrue(layout.getLineRight(line) <= layout.size.width)
        }
    }
}
