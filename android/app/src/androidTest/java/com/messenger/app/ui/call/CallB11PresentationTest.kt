package com.messenger.app.ui.call

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import com.messenger.app.data.call.AudioRoute
import com.messenger.app.data.call.AudioRouteKind
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.R
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.call.CallFailureReason
import com.messenger.app.domain.call.VideoMode
import com.messenger.app.domain.model.*
import com.messenger.app.data.call.WebRtcCallStats
import com.messenger.app.data.call.WebRtcCandidatePairStats
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** B11 component fixtures; no device media, permissions, signaling or account changes. */
class CallB11PresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun returnBannerReservesOneRowAndReturnsOnce() {
        var returns = 0
        var reserved = 0.dp
        val shown = mutableStateOf(false)
        compose.setContent {
            Column {
                if (shown.value) CallReturnBanner("Аня QA", "01:47 · Нажмите, чтобы вернуться",
                    CocoIcons.AudioCall, { returns++ }, onHeight = { reserved = it })
                Box(Modifier.size(48.dp).testTag("destination-header"))
            }
        }
        val before = compose.onNodeWithTag("destination-header").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithTag("call_return_strip").assertIsDisplayed().assertHasClickAction().performClick()
        val during = compose.onNodeWithTag("destination-header").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle {
            assertEquals(1, returns)
            // Verify the composed row, including its external padding, rather than a token assignment.
            assertEquals(56.dp, reserved)
            assertTrue(during > before)
            shown.value = false
        }
        compose.onNodeWithTag("call_return_strip").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag("destination-header").fetchSemanticsNode().boundsInRoot.top, .5f)
    }

    @Test fun oneAndTwoLinePeerNamesKeepTheControlsAtTheSameOrigin() {
        val name = mutableStateOf("Аня QA")
        compose.setContent {
            Column(Modifier.width(320.dp)) {
                CallPeerName(name.value)
                Box(Modifier.size(48.dp).testTag("controls-anchor"))
            }
        }
        val before = compose.onNodeWithTag("controls-anchor").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { name.value = "Аня\nQA" }
        compose.onNodeWithText("Аня\nQA").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("controls-anchor").fetchSemanticsNode().boundsInRoot.top, .5f)
    }

    @Test fun videoButtonsDispatchOnceAndSelectedOutputRemainsAvailable() {
        var camera = 0; var mute = 0; var hangup = 0
        val route = mutableStateOf(AudioRouteKind.EARPIECE)
        val routes = listOf(AudioRoute(AudioRouteKind.EARPIECE, "Phone"), AudioRoute(AudioRouteKind.SPEAKER, "Fixture speaker"))
        compose.setContent {
            VideoCallControlTray(CallPresentationPolicy.VideoControls("fixture", true, false, route.value), routes,
                { camera++ }, { mute++ }, { hangup++ }, { route.value = it.kind }, Modifier.width(402.dp))
        }
        listOf("call-camera", "call-mute", "call-end").forEach {
            compose.onNodeWithTag(it).assertHasClickAction().performClick()
        }
        compose.onNodeWithTag("call-output").performClick()
        compose.onNodeWithText("Fixture speaker").performClick()
        compose.runOnIdle {
            assertEquals(1, camera); assertEquals(1, mute); assertEquals(1, hangup)
            assertEquals(AudioRouteKind.SPEAKER, route.value)
        }
        compose.onNodeWithTag("call-output").assertIsDisplayed().assertHasClickAction()
    }

    @Test fun discoveringRoutesKeepsTheFourControlSlotsStable() {
        val routes = mutableStateOf(emptyList<AudioRoute>())
        compose.setContent {
            VideoCallControlTray(CallPresentationPolicy.VideoControls("fixture", true, false, AudioRouteKind.EARPIECE),
                routes.value, {}, {}, {}, {}, Modifier.width(402.dp))
        }
        compose.onNodeWithTag("call-output").assertIsDisplayed().assertIsNotEnabled()
        val before = compose.onNodeWithTag("call-end").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { routes.value = listOf(AudioRoute(AudioRouteKind.EARPIECE, "Phone")) }
        compose.onNodeWithTag("call-output").assertIsEnabled().assertHasClickAction()
        assertEquals(before, compose.onNodeWithTag("call-end").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun audioRingingAndConnectedKeepFourSlotsAndEnableVideoOnlyWhenAvailable() {
        val connected = mutableStateOf(false)
        var videoRequests = 0
        compose.setContent {
            Box(Modifier.size(402.dp, 778.dp)) {
                ActiveCallScreen(title = "", name = "Аня QA", durationMs = null,
                    availableRoutes = listOf(AudioRoute(AudioRouteKind.EARPIECE, "Phone")),
                    onHangup = {}, onRequestVideo = if (connected.value) ({ videoRequests++ }) else null)
            }
        }
        val tags = listOf("call-output", "call-mute", "call-video", "call-end")
        tags.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        compose.onNodeWithTag("call-video").assertIsNotEnabled()
        val ringing = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(ringing.zipWithNext().all { (a, b) -> a.left < b.left && a.top == b.top })
        compose.runOnIdle { connected.value = true }
        compose.onNodeWithTag("call-video").assertIsEnabled().performClick()
        assertEquals(ringing, tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot })
        compose.runOnIdle { assertEquals(1, videoRequests) }
    }

    @Test fun referenceStatusOpensRealEncryptionDetailsAndStatistics() {
        val state = CallUiState.Connected(CallInfo("fixture", "peer", "Peer", CallMediaKind.AUDIO, CallDirection.OUTGOING), 1_000)
        val stats = MutableStateFlow<WebRtcCallStats?>(WebRtcCallStats(selectedCandidatePair =
            WebRtcCandidatePairStats(localCandidateType = "host", remoteCandidateType = "relay", protocol = "udp")))
        compose.setContent {
            CompositionLocalProvider(LocalCallChrome provides CallChromeSnapshot(state, null, stats)) {
                CallReferenceTopBar({})
            }
        }
        compose.onNodeWithTag("call-status").assertHasClickAction().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.call_encryption_relay)).assertIsDisplayed()
        compose.onNodeWithTag("call-verification-code").assertDoesNotExist()
        compose.onNodeWithTag("call-statistics-toggle").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.call_stat_video_codec)).assertExists()
    }

    @Test fun pendingEncryptionDisablesStatisticsUntilMeasurementsExist() {
        val state = CallUiState.OutgoingRinging(CallInfo("fixture", "peer", "Peer", CallMediaKind.AUDIO, CallDirection.OUTGOING))
        val stats = MutableStateFlow<WebRtcCallStats?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalCallChrome provides CallChromeSnapshot(state, null, stats)) {
                CallReferenceTopBar({})
            }
        }
        compose.onNodeWithTag("call-status").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.call_encryption_pending)).assertIsDisplayed()
        compose.onNodeWithTag("call-statistics-toggle").assertIsNotEnabled()
    }

    @Test fun terminalFrameKeepsIdentityAndFourLockedControlsInPlace() {
        val call = CallInfo("fixture", "peer", "Аня QA", CallMediaKind.AUDIO, CallDirection.OUTGOING)
        val state = mutableStateOf<CallUiState>(CallUiState.Connected(call, 1_000))
        compose.setContent { TerminalTransitionFixture(state.value) {} }
        val tags = listOf("call-peer-name", "call-output", "call-mute", "call-video", "call-end")
        val before = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        compose.runOnIdle { state.value = CallUiState.Ended(call, CallEndReason.HANGUP) }
        assertEquals(before, tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot })
        tags.drop(1).forEach { compose.onNodeWithTag(it).assertIsDisplayed().assertIsNotEnabled() }
    }

    @Test fun serviceAdmissionFailureWithCallHasVisibleDismissalAction() {
        val call = CallInfo("fixture", "peer", "Аня QA", CallMediaKind.VIDEO, CallDirection.OUTGOING)
        val state = mutableStateOf<CallUiState>(CallUiState.OutgoingRinging(call))
        var dismissals = 0
        compose.setContent { TerminalTransitionFixture(state.value) { dismissals++; state.value = CallUiState.Idle } }
        compose.runOnIdle { state.value = CallUiState.Failed(call, CallFailureReason.DEVICE_RESTRICTED) }
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithTag("call-terminal-dismiss").assertIsDisplayed().assertIsEnabled().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, dismissals); assertEquals(CallUiState.Idle, state.value) }
        compose.onNodeWithTag("call-terminal-dismiss").assertDoesNotExist()
    }

    @Test fun negotiatedVideoKeepsEndSlotThroughTerminationAndSavedStateRestore() {
        val call = CallInfo("fixture", "peer", "Аня QA", CallMediaKind.AUDIO, CallDirection.OUTGOING)
        val state = mutableStateOf<CallUiState>(CallUiState.Connected(call, 1_000))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TerminalTransitionFixture(state.value) {} }
        compose.runOnIdle {
            state.value = CallUiState.Connected(call, 1_000, videoMode = VideoMode.VIDEO,
                localVideoEnabled = false, isMuted = true, audioRoute = AudioRouteKind.BLUETOOTH)
        }
        val tags = listOf("call-camera", "call-mute", "call-end", "call-output")
        val before = tags.map { compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertTrue(before.zipWithNext().all { (a, b) -> a.left < b.left && a.top == b.top })
        compose.runOnIdle { state.value = CallUiState.Ended(call, CallEndReason.HANGUP) }
        fun assertTerminalVideo() {
            assertEquals(before, tags.map { compose.onNodeWithTag(it).assertIsNotEnabled().fetchSemanticsNode().boundsInRoot })
            compose.onNodeWithTag("call-video").assertDoesNotExist()
            compose.onNodeWithTag("call-output").assertContentDescriptionContains(
                compose.activity.getString(R.string.call_audio_output) + ": Fixture headset")
        }
        assertTerminalVideo()
        restoration.emulateSavedInstanceStateRestore()
        assertTerminalVideo()
    }

    /** Actual Ended/Failed entry and production retained-state adapter; no terminal flag shortcut. */
    @Composable
    private fun TerminalTransitionFixture(state: CallUiState, onDismiss: () -> Unit) {
        val retained = rememberCallTerminalControls(state)
        val stats = remember { MutableStateFlow<WebRtcCallStats?>(null) }
        val routes = remember { MutableStateFlow(listOf(AudioRoute(AudioRouteKind.EARPIECE, "Phone"),
            AudioRoute(AudioRouteKind.BLUETOOTH, "Fixture headset"))) }
        CompositionLocalProvider(LocalCallChrome provides CallChromeSnapshot(state, null, stats, audioRoutes = routes)) {
            Box(Modifier.size(402.dp, 778.dp)) {
                when (state) {
                    is CallUiState.Ended -> EndedCallScreen(state.reason, onDismiss = onDismiss, retainedControls = retained)
                    is CallUiState.Failed -> EndedCallScreen(CallEndReason.FAILED, message = "Service admission fixture",
                        onDismiss = onDismiss, retainedControls = retained)
                    CallUiState.Idle -> Unit
                    else -> ActiveCallScreen(title = "", name = "Аня QA", durationMs = null,
                        isMuted = retained?.isMuted == true, currentRouteKind = retained?.audioRoute ?: AudioRouteKind.EARPIECE,
                        availableRoutes = routes.value, onHangup = {}, onRequestVideo = {},
                        videoControls = retained?.videoControls, onToggleCamera = {})
                }
            }
        }
    }
}
