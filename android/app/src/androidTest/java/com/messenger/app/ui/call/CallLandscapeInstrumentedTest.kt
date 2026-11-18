package com.messenger.app.ui.call

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.call.AudioRoute
import com.messenger.app.data.call.AudioRouteKind
import com.messenger.app.data.call.WebRtcCallStats
import com.messenger.app.domain.call.*
import com.messenger.app.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import java.io.File

/** Production layouts at both required landscape heights. No account/media mutations. */
class CallLandscapeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val routes = listOf(AudioRoute(AudioRouteKind.SPEAKER, "Speaker"))
    private val call = CallInfo("landscape-fixture", "peer", "Alexandria Landscape", CallMediaKind.AUDIO, CallDirection.OUTGOING)
    private val stats = MutableStateFlow<WebRtcCallStats?>(null)

    @Before fun landscapeFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.getSystemService(android.os.PowerManager::class.java).isInteractive) { "Wake and swipe-unlock the test device first" }
        check(!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) { "Swipe-unlock the test device first" }
        compose.mainClock.autoAdvance = false
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        while (compose.activity.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(50)
        assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, compose.activity.resources.configuration.orientation)
        compose.activityRule.scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    private fun settle() { compose.mainClock.advanceTimeBy(500); compose.waitForIdle() }

    @Composable private fun Fixture(height: Float, content: @Composable () -> Unit) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val d = LocalDensity.current
            val fit = minOf(maxWidth.value / 720f, maxHeight.value / height, 1f)
            CompositionLocalProvider(LocalDensity provides Density(d.density * fit, 1f)) {
                Box(Modifier.requiredSize(720.dp, height.dp).testTag("landscape-fixture")) { content() }
            }
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    private fun controls(tags: List<String>) {
        val stage = bounds("landscape-fixture")
        val rects = tags.map(::bounds)
        assertTrue(rects.all { it.width > 0 && it.height > 0 && stage.contains(it.center) && it.bottom <= stage.bottom + 1 })
        assertTrue(rects.zipWithNext().all { (a,b) -> a.right <= b.left + 1 && kotlin.math.abs(a.top-b.top) < 1 })
    }
    private fun screenshot(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "orientation-layouts").apply { mkdirs() }
        // Capture the measured fixture after its Compose draw; for the sheet,
        // select its separate window root instead of the occluded activity root.
        val node = if (name.endsWith("encryption-sheet")) compose.onNode(isDialog())
            else compose.onNodeWithTag("landscape-fixture")
        val bitmap = node.captureToImage().asAndroidBitmap()
        try { File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun everyAudioStageAndIncomingKeepIdentityAndControlsReachable() {
        val height = mutableStateOf(360f)
        val stage = mutableStateOf("incoming")
        var accepted = 0; var declined = 0
        compose.setContent {
            Fixture(height.value) {
                val state: CallUiState = when (stage.value) {
                    "incoming" -> CallUiState.IncomingRinging(call)
                    "calling" -> CallUiState.OutgoingRinging(call)
                    "connecting" -> CallUiState.Connecting(call)
                    "ended" -> CallUiState.Ended(call, CallEndReason.HANGUP)
                    "failed" -> CallUiState.Failed(call, CallFailureReason.NETWORK)
                    else -> CallUiState.Connected(call, 1_000)
                }
                CompositionLocalProvider(LocalCallChrome provides CallChromeSnapshot(state, null, stats)) {
                    when (stage.value) {
                        "incoming" -> IncomingCallScreen(call.otherDisplayName, false, { accepted++ }, { declined++ })
                        "ended", "failed" -> EndedCallScreen(if (stage.value == "failed") CallEndReason.FAILED else CallEndReason.HANGUP, onDismiss = {})
                        else -> ActiveCallScreen(stage.value, call.otherDisplayName, null,
                            availableRoutes = routes, onHangup = {}, onRequestVideo = {}, onMinimize = {},
                            ringPhase = if (stage.value == "calling") CallRingIndicator.Phase.RINGING else CallRingIndicator.Phase.ANSWERED)
                    }
                }
            }
        }
        for (h in listOf(360f, 411f)) for (s in listOf("incoming", "calling", "connecting", "connected", "ended", "failed")) {
            compose.runOnIdle { height.value = h; stage.value = s }
            settle()
            if (s == "incoming") {
                bounds("call-landscape-identity")
                controls(listOf("call-decline", "call-accept"))
                compose.onNodeWithTag("call-accept").performClick()
                compose.onNodeWithTag("call-decline").performClick()
            } else if (s != "failed") {
                bounds("call-landscape-identity")
                assertTrue(bounds("call-landscape-avatar").right < bounds("call-peer-name").left)
                bounds("call-status")
                compose.onAllNodesWithTag("call-stage-status").assertCountEquals(0)
                controls(listOf("call-output", "call-mute", "call-video", "call-end"))
            } else bounds("call-terminal-dismiss")
            screenshot("${h.toInt()}-$s")
        }
        compose.runOnIdle { assertEquals(2, accepted); assertEquals(2, declined) }
    }

    @Test fun landscapeEncryptionSheetKeepsStatisticsReachable() {
        compose.mainClock.autoAdvance = true
        compose.setContent {
            // A modal owns a native window: use its actual density and bounds,
            // rather than the density-scaled component fixture underneath it.
            CompositionLocalProvider(LocalCallChrome provides CallChromeSnapshot(CallUiState.Connected(call, 1_000),
                null, stats)) {
                ActiveCallScreen("Connected", call.otherDisplayName, null, onHangup = {})
            }
        }
        settle()
        compose.onNodeWithTag("call-status").performClick()
        settle()
        compose.onNodeWithTag("call-statistics-toggle").performScrollTo()
        settle()
        screenshot("360-encryption-sheet")
        bounds("call-statistics-toggle")
    }

    @Test fun landscapeVideoTrayAndCompensatedSelfViewKeepOneMountedPicture() {
        val height = mutableStateOf(360f)
        val rotation = mutableStateOf(0)
        var mounts = 0; var disposals = 0
        compose.setContent {
            Fixture(height.value) {
                CallTheme {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        CallSelfViewLayout("fixture", true, true, 0, Modifier.fillMaxSize(), picture = {
                            DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                            Box(Modifier.fillMaxSize().background(Color.Blue).testTag("mounted-picture"))
                        }, controls = {}, rotation = rotation.value)
                        VideoCallControlTray(CallPresentationPolicy.VideoControls("fixture", true, false, AudioRouteKind.SPEAKER),
                            routes, {}, {}, {}, {}, Modifier.align(androidx.compose.ui.Alignment.BottomCenter), landscape = true)
                    }
                }
            }
        }
        for (h in listOf(360f, 411f)) for (r in listOf(0, 90, -90, 180)) {
            compose.runOnIdle { height.value = h; rotation.value = r }
            settle()
            controls(listOf("call-camera", "call-mute", "call-end", "call-output"))
            val self = bounds("call-self-view")
            assertTrue(self.width > self.height)
            val tray = bounds("call-controls-tray")
            assertTrue(self.bottom < tray.top)
            bounds("mounted-picture")
            screenshot("${h.toInt()}-video-$r")
            compose.runOnIdle { assertEquals(1, mounts); assertEquals(0, disposals) }
        }
    }

    @Test fun inactiveOrMinimizedCallCannotOverrideViewerLeaseOnRecomposition() {
        val redraw = mutableIntStateOf(0)
        val minimizedCall = mutableStateOf(false)
        compose.setContent {
            val owner = remember { Any() }
            DisposableEffect(Unit) {
                com.messenger.app.OrientationLease.acquire(compose.activity, owner,
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                onDispose { com.messenger.app.OrientationLease.release(compose.activity, owner) }
            }
            CallScreenRotation(fullScreen = false)
            Box(Modifier.testTag("viewer-${redraw.intValue}"))
            if (minimizedCall.value) CallTheme {
                CallReturnStrip(CallUiState.Connected(call, 1_000)) { }
            }
        }
        for (minimized in listOf(false, true)) {
            compose.runOnIdle { minimizedCall.value = minimized }
            repeat(4) { compose.runOnIdle { redraw.intValue++ }; settle() }
            // A real display stays landscape beyond the old rotation/recomposition race.
            android.os.SystemClock.sleep(1_000)
            compose.runOnIdle {
                assertEquals("minimized=$minimized", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    compose.activity.requestedOrientation)
                assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                    compose.activity.resources.configuration.orientation)
            }
        }
    }

    @Test fun portraitSelfViewKeepsTallTileWhileRendererSwapsInsideIt() {
        val rotation = mutableIntStateOf(0)
        var mounts = 0
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val d = LocalDensity.current
                val fit = minOf(maxWidth.value / 360f, maxHeight.value / 760f)
                CompositionLocalProvider(LocalDensity provides Density(d.density * fit, 1f)) {
                    Box(Modifier.requiredSize(360.dp, 760.dp)) {
                        CallSelfViewLayout("portrait", true, true, 0, Modifier.fillMaxSize(), picture = {
                            DisposableEffect(Unit) { mounts++; onDispose {} }
                            Box(Modifier.fillMaxSize().background(Color.Blue).testTag("portrait-picture"))
                        }, controls = {}, rotation = rotation.intValue)
                    }
                }
            }
        }
        var original: Rect? = null
        for (r in listOf(0, 90, -90, 180, 0)) {
            compose.runOnIdle { rotation.intValue = r }
            settle()
            val tile = bounds("call-self-view")
            assertTrue(tile.height > tile.width)
            original?.let { assertEquals(it.width, tile.width, 1f); assertEquals(it.height, tile.height, 1f) }
            original = tile
            compose.runOnIdle { assertEquals(1, mounts) }
        }
    }
}
