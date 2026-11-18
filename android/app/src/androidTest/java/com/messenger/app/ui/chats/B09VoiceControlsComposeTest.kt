package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.messenger.app.data.attachment.VoiceRecorder
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** No microphone or device automation needed by the fixture; P2 can run it on a host. */
class B09VoiceControlsComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun recordingRetainsTheMountedInputFocusSelectionAndFootprint() {
        val recording = mutableStateOf(false)
        compose.setContent { CocoTheme {
            ComposerBar(keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {},
                input = "", submitting = false, onInputChange = {}, isRecording = recording.value,
                onAttach = {}, onMicToggle = {}, onSend = {}, recordingContent = {
                    val recorder = remember { VoiceRecorder(compose.activity) }
                    RecordingBanner(true, {}, {}, {}, {}, recorder = recorder, drag = Offset.Zero)
                })
        } }
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        val height = compose.onNodeWithTag("composer-input-field").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { recording.value = true }
        compose.onNodeWithTag("voice-recording-locked").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0) // hidden from TalkBack
        assertEquals(height, compose.onNodeWithTag("composer-input-field").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.runOnIdle { recording.value = false }
        field.assertIsFocused()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange.Zero))
    }

    @Test fun eachLockedControlHasOneWorkingActionAndHoldingHasNoSend() {
        val events = mutableListOf<String>()
        val locked = mutableStateOf(true)
        val paused = mutableStateOf(false)
        compose.setContent { CocoTheme {
            val context = LocalContext.current
            val recorder = remember { VoiceRecorder(context) }
            Box(Modifier.width(402.dp)) {
                RecordingBanner(paused.value, { events += "pause"; paused.value = !paused.value },
                    { events += "delete" }, { events += "review" }, { events += "send" },
                    locked.value, recorder, Offset.Zero)
            }
        } }
        compose.onNodeWithTag("voice-recording-pause").performClick()
        compose.onNodeWithTag("voice-recording-pause").performClick()
        compose.onNodeWithTag("voice-recording-stop").performClick()
        compose.onNodeWithTag("voice-recording-send").performClick()
        compose.onNodeWithTag("voice-recording-cancel").performClick()
        compose.runOnIdle {
            assertEquals(listOf("pause", "pause", "review", "send", "delete"), events)
            locked.value = false
        }
        compose.onNodeWithTag("voice-recording-holding").assertExists()
        compose.onNodeWithTag("voice-recording-send").assertDoesNotExist()
        compose.onNodeWithTag("voice-recording-stop").assertDoesNotExist()
    }

    @Test fun holdUses150msAndStolenTouchLocksWhileAShortTapNeverStarts() {
        val events = mutableListOf<String>()
        compose.setContent {
            Box(Modifier.size(402.dp, 60.dp).testTag("hold").voiceHoldGesture(true,
                { events += "start" }, { events += "send" }, { events += "discard" }, { events += "lock" }))
        }
        val host = compose.onNodeWithTag("hold")
        host.performTouchInput { down(Offset(width - 20f, height / 2f)); advanceEventTime(100); up() }
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
        host.performTouchInput { down(Offset(width - 20f, height / 2f)); advanceEventTime(151); cancel() }
        compose.runOnIdle { assertEquals(listOf("start", "lock"), events) }
    }
}
