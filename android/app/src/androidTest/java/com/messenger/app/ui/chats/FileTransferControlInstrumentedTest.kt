package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FileTransferControlInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cancelRemainsAccessibleOfflineAndDuringProgressWithoutMoving() {
        val waiting = mutableStateOf(true)
        val percent = mutableStateOf(0)
        var cancelled = 0
        compose.setContent {
            CocoTheme { FileTransferControl(percent.value, waiting.value, "Cancel sending") { cancelled++ } }
        }
        val cancel = compose.onNodeWithContentDescription("Cancel sending")
        cancel.assertIsDisplayed().assertHasClickAction()
        val before = cancel.fetchSemanticsNode().boundsInRoot
        cancel.performClick()
        compose.runOnIdle { assertEquals(1, cancelled); waiting.value = false; percent.value = 43 }
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "43%")).assertExists()
        assertEquals(before, cancel.fetchSemanticsNode().boundsInRoot)
        cancel.performClick()
        compose.runOnIdle { assertEquals(2, cancelled); percent.value = 100 }
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "100%")).assertExists()
        assertEquals(before, cancel.fetchSemanticsNode().boundsInRoot)
    }
}
