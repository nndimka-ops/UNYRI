package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.messenger.app.domain.model.ReactionSummary
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Rule
import org.junit.Test

class MediaParityInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun alreadyComposedRowResumesAfterForegroundAndCoverWithoutRebinding() {
        val work = TimelineMediaWork()
        compose.setContent {
            CompositionLocalProvider(LocalTimelineMediaWork provides work, LocalMediaRowKey provides "row") {
                Text(if (rememberMediaWorkAllowed()) "work" else "idle")
            }
        }
        compose.onNodeWithText("idle").assertExists()
        compose.runOnIdle { work.visible = setOf("row"); work.started = true }
        compose.onNodeWithText("work").assertExists()
        compose.runOnIdle { work.covered = true }
        compose.onNodeWithText("idle").assertExists()
        compose.runOnIdle { work.covered = false }
        compose.onNodeWithText("work").assertExists()
        compose.runOnIdle { work.started = false }
        compose.onNodeWithText("idle").assertExists()
        compose.runOnIdle { work.started = true; work.visible = emptySet() }
        compose.onNodeWithText("idle").assertExists()
        compose.runOnIdle { work.visible = setOf("row") }
        compose.onNodeWithText("work").assertExists()
    }
    @Test fun mediaVotesAreCountedOnceAndChipIsSelected() {
        compose.setContent { CocoTheme {
            MediaReactionChips(listOf(ReactionSummary("❤", 100, true)), 80.dp, true) {}
        } }
        compose.onAllNodesWithText("❤", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("100", useUnmergedTree = true).assertExists()
        compose.onNode(isSelected()).assertExists()
    }
}
