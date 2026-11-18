package com.messenger.app.ui.calls

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.data.api.CallDto
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic call data and counters only; no call controller, account, or network. */
class CallCardSelectionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun selectionNeverMovesOrShrinksEitherDirectionWithOrWithoutNameAtLargeFont() {
        val own = mutableStateOf(true)
        val selecting = mutableStateOf(false)
        val selected = mutableStateOf(false)
        val showName = mutableStateOf(false)
        val fontScale = mutableStateOf(1f)
        var cardBounds = Rect.Zero
        var cardTaps = 0
        var markerTaps = 0
        compose.setContent {
            CocoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                    Box(Modifier.width(360.dp).testTag("call-frame")) {
                        CallHistoryCard(call(own.value), "owner", name = if (showName.value) "Alex" else null,
                            cardModifier = Modifier.testTag("call-card").onGloballyPositioned { cardBounds = it.boundsInRoot() }
                                .clickable { cardTaps++ },
                            selectionMark = if (selecting.value) ({
                                Marker(selected.value) { selected.value = it; markerTaps++ }
                            }) else null, selected = selected.value)
                    }
                }
            }
        }
        val px = compose.activity.resources.displayMetrics.density
        for (scale in listOf(1f, 2f)) for (outgoing in listOf(false, true)) for (withName in listOf(false, true)) {
            compose.runOnIdle {
                selecting.value = false; selected.value = false; own.value = outgoing; showName.value = withName; fontScale.value = scale
            }
            compose.waitForIdle()
            val before = cardBounds
            val rowBefore = compose.onNodeWithTag("call-frame").fetchSemanticsNode().boundsInRoot
            if (withName) compose.onNodeWithText("Alex").assertIsDisplayed()
            val tapsBefore = cardTaps
            compose.onNodeWithTag("call-card").performTouchInput { click() }
            compose.runOnIdle { assertEquals(tapsBefore + 1, cardTaps); selecting.value = true }
            compose.waitForIdle()
            assertSameBounds(before, cardBounds)
            assertSameBounds(rowBefore, compose.onNodeWithTag("call-frame").fetchSemanticsNode().boundsInRoot)
            if (withName) compose.onNodeWithText("Alex").assertDoesNotExist()
            compose.onNodeWithText(compose.activity.getString(R.string.call_history_audio), substring = true).assertDoesNotExist()
            val mark = compose.onNodeWithContentDescription("Select call").assertIsDisplayed().assertHasClickAction()
            val markBounds = mark.fetchSemanticsNode().boundsInRoot
            assertEquals(48f * px, markBounds.width, 1f)
            assertEquals(48f * px, markBounds.height, 1f)
            if (outgoing) assertEquals(before.left, markBounds.right, 1f) else assertEquals(before.right, markBounds.left, 1f)
            val previousMarkerTaps = markerTaps
            mark.performTouchInput { click() }
            compose.runOnIdle { assertTrue(selected.value); assertEquals(previousMarkerTaps + 1, markerTaps); assertEquals(tapsBefore + 1, cardTaps) }
            compose.waitForIdle()
            assertSameBounds(before, cardBounds)
        }
    }

    @Test fun listRowIgnoresChatSelectionDecorationAndKeepsItsExistingContent() {
        var markerCompositions = 0
        compose.setContent {
            CocoTheme {
                Box(Modifier.width(360.dp)) {
                    CallHistoryCard(call(false), "owner", name = "Alex", listRow = true,
                        avatar = { Text("Avatar") }, selectionMark = { markerCompositions++; Marker(false) {} }, selected = true)
                }
            }
        }
        compose.onNodeWithText("Alex").assertIsDisplayed()
        compose.onNodeWithText("Avatar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Select call").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, markerCompositions) }
    }

    @Composable private fun Marker(selected: Boolean, onSelected: (Boolean) -> Unit) {
        Box(Modifier.size(48.dp).semantics { contentDescription = "Select call" }
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = onSelected)
            .background(Color.DarkGray), contentAlignment = Alignment.Center) { Text(if (selected) "✓" else "○", color = Color.White) }
    }

    private fun assertSameBounds(before: Rect, after: Rect) {
        assertEquals(before.left, after.left, 1f); assertEquals(before.top, after.top, 1f)
        assertEquals(before.right, after.right, 1f); assertEquals(before.bottom, after.bottom, 1f)
    }

    private fun call(own: Boolean) = CallDto("synthetic-call", if (own) "owner" else "peer", if (own) "peer" else "owner",
        "audio", "ended", "completed", createdAt = "2026-10-26T12:00:00Z", connectedAt = "2026-10-26T12:00:05Z", endedAt = "2026-10-26T12:00:25Z")
}
