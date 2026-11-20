package com.messenger.app.ui.call

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.messenger.app.data.call.WebRtcCallStats
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.call.VideoUpgradeState
import com.messenger.app.domain.model.CallDirection
import com.messenger.app.domain.model.CallInfo
import com.messenger.app.domain.model.CallMediaKind
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** B-067 production layout; synthetic state only, without a network or media session. */
class CallControlRowInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun startingCallingConnectedAndUpgradingShowOnlyUsableEqualSizedCenteredControls() {
        val phase = mutableStateOf("starting")
        val width = mutableStateOf(360f)
        val fontScale = mutableStateOf(1f)
        val call = CallInfo("synthetic", "peer", "Synthetic peer", CallMediaKind.AUDIO, CallDirection.OUTGOING)
        val stats = MutableStateFlow<WebRtcCallStats?>(null)
        compose.setContent {
            val density = LocalDensity.current
            val connected = phase.value == "connected" || phase.value == "upgrading"
            val state: CallUiState = if (connected) CallUiState.Connected(call, 0,
                videoUpgrade = if (phase.value == "upgrading") VideoUpgradeState.Upgrading else VideoUpgradeState.Idle)
                else CallUiState.OutgoingRinging(call)
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value),
                LocalCallChrome provides CallChromeSnapshot(state, null, stats)) {
                Box(Modifier.size(width.value.dp, 640.dp).testTag("call-fixture")) {
                    ActiveCallScreen("Fixture", "Synthetic peer", null, onHangup = {},
                        controlsAvailable = phase.value != "starting",
                        onRequestVideo = if (phase.value == "connected") ({}) else null)
                }
            }
        }
        for (w in listOf(220f, 320f, 360f)) for (scale in listOf(1f, 2f)) {
            var circleSize: Float? = null
            for ((name, controls) in listOf(
                "starting" to listOf("end"),
                "calling" to listOf("mute", "end", "output"),
                "connected" to listOf("video", "mute", "end", "output"),
                "upgrading" to listOf("mute", "end", "output"),
            )) {
                compose.runOnIdle { width.value = w; fontScale.value = scale; phase.value = name }
                compose.waitForIdle()
                val row = compose.onNodeWithTag("call-control-row").fetchSemanticsNode().boundsInRoot
                val frames = controls.map { tag ->
                    compose.onNodeWithTag("call-$tag").assertIsDisplayed().assertHasClickAction()
                        .fetchSemanticsNode().boundsInRoot
                }
                for (tag in listOf("video", "camera", "mute", "end", "output") - controls.toSet())
                    compose.onNodeWithTag("call-$tag").assertDoesNotExist()
                for (frame in frames) {
                    if (circleSize == null) circleSize = frame.width
                    assertEquals(circleSize!!, frame.width, 1f)
                    assertEquals(frame.width, frame.height, 1f)
                    assertTrue(frame.left >= row.left - 1 && frame.right <= row.right + 1)
                }
                for (line in frames.groupBy { it.top }.values) {
                    assertEquals(row.center.x, (line.first().left + line.last().right) / 2, 1f)
                    val gaps = line.zipWithNext().map { (a, b) -> b.left - a.right }
                    if (gaps.isNotEmpty()) assertTrue(gaps.all { kotlin.math.abs(it - gaps.first()) <= 1f })
                }
            }
        }
    }
}
