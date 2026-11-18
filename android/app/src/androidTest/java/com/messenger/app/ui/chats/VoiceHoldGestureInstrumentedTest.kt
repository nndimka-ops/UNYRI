package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceHoldGestureInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun releaseCancelAndLockAreExclusiveAcrossRecomposition() {
        val events = mutableListOf<String>()
        val recording = mutableStateOf(false)
        compose.setContent {
            Box(Modifier.size(320.dp, 64.dp).testTag("surface").voiceHoldGesture(!recording.value,
                start = { events += "start"; recording.value = true },
                finish = { events += "finish"; recording.value = false },
                cancel = { events += "cancel"; recording.value = false },
                lock = { events += "lock" }))
        }
        val surface = compose.onNodeWithTag("surface")
        surface.performTouchInput { down(Offset(width - 20f, height / 2f)); advanceEventTime(700); up() }
        compose.runOnIdle { assertEquals(listOf("start", "finish"), events); events.clear() }
        surface.performTouchInput { down(Offset(width - 20f, height / 2f)); advanceEventTime(700); moveBy(Offset(-width * .7f, 0f)); up() }
        compose.runOnIdle { assertEquals(listOf("start", "cancel"), events); events.clear() }
        surface.performTouchInput { down(Offset(width - 20f, height / 2f)); advanceEventTime(700); moveBy(Offset(0f, -height * 2f)); up() }
        compose.runOnIdle { assertEquals(listOf("start", "lock"), events) }
    }
    @Test fun compactMicrophoneDoesNotCaptureTheAdjacentEmojiArea() {
        var starts = 0
        compose.setContent {
            Box(Modifier.size(320.dp, 46.dp).testTag("compact").voiceHoldGesture(true,
                start = { starts++ }, finish = {}, cancel = {}, lock = {}, hitWidth = 44.dp))
        }
        val node = compose.onNodeWithTag("compact")
        node.performTouchInput { down(Offset(width - 54.dp.toPx(), height / 2f)); advanceEventTime(700); up() }
        compose.runOnIdle { assertEquals(0, starts) }
        node.performTouchInput { down(Offset(width - 22.dp.toPx(), height / 2f)); advanceEventTime(700); up() }
        compose.runOnIdle { assertEquals(1, starts) }
    }
}
