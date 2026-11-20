package com.messenger.app.ui.call

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
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
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.call.LocalCameraState
import com.messenger.app.domain.call.RemoteVideoState
import com.messenger.app.domain.call.VideoMode
import com.messenger.app.domain.model.CallDirection
import com.messenger.app.domain.model.CallInfo
import com.messenger.app.domain.model.CallMediaKind
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Actual production composables, synthetic state/callbacks; no call or account created. */
class CallControlsLayoutInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val routes = listOf(AudioRoute(AudioRouteKind.EARPIECE, "Phone"), AudioRoute(AudioRouteKind.SPEAKER, "Speaker"))

    @Before fun keepFixtureAwake() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Device orchestration must obtain a normal unlocked screen first.
        // A keyguard bypass is neither needed nor proof of a reachable control.
        val context = instrumentation.targetContext
        check(context.getSystemService(android.os.PowerManager::class.java).isInteractive)
        check(!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        instrumentation.runOnMainSync {
            compose.activity.enableEdgeToEdge()
            if (android.os.Build.VERSION.SDK_INT >= 29) compose.activity.window.isNavigationBarContrastEnforced = false
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (InstrumentationRegistry.getArguments().getString("landscape") == "true")
                compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (InstrumentationRegistry.getArguments().getString("landscape") == "true") {
            val until = android.os.SystemClock.uptimeMillis() + 5_000
            while (compose.activity.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE && android.os.SystemClock.uptimeMillis() < until)
                android.os.SystemClock.sleep(50)
            assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, compose.activity.resources.configuration.orientation)
        }
    }

    @Test fun videoFlipOnlyLocalOnCentersVisibleControls() {
        val state = mutableStateOf(video())
        val short = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        val width = mutableStateOf(320f)
        var cameraTaps = 0; var endTaps = 0; var muteTaps = 0; var flipTaps = 0
        compose.setContent {
            CallTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                    Box(Modifier.size(width.value.dp, if (short.value) 240.dp else 520.dp).background(Color.Black).testTag("call-fixture")) {
                        VideoCallControlTray(state.value, routes, supportsCameraFlip = true,
                            onCamera = { cameraTaps++ }, onToggleMute = { muteTaps++ }, onHangup = { endTaps++ },
                            onSelectRoute = {}, onFlip = { flipTaps++ }, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        for (fontScale in listOf(1f, 2f)) for (compact in listOf(false, true)) {
            compose.runOnIdle { scale.value = fontScale; short.value = compact; width.value = if(fontScale == 2f && compact) 220f else 320f; state.value = video() }
            assertVisibleVideoGroupCentered(false)
            compose.onNodeWithTag("call-flip").assertDoesNotExist()
            for (hidden in listOf(
                video().copy(localVideoEnabled = false, localCameraState = LocalCameraState.OFF),
                video().copy(localVideoEnabled = false, localCameraState = LocalCameraState.ON),
                video().copy(localCameraState = LocalCameraState.STARTING),
                video().copy(localCameraState = LocalCameraState.FAILED),
                video().copy(localVideoEnabled = false, localCameraState = LocalCameraState.OFF,
                    remoteVideoEnabled = true, remoteVideoState = RemoteVideoState.ON),
            )) {
                compose.runOnIdle { state.value = hidden }
                compose.onNodeWithTag("call-flip", useUnmergedTree = true).assertDoesNotExist()
                compose.onAllNodesWithContentDescription(compose.activity.getString(com.messenger.app.R.string.call_switch_camera),useUnmergedTree = true).assertCountEquals(0)
                assertVisibleVideoGroupCentered(false)
            }
            screenshot("video-hidden-remote-on-font$fontScale-compact$compact")
        }
        compose.onNodeWithTag("call-camera").performTouchInput { click() }
        compose.onNodeWithTag("call-mute").performTouchInput { click() }
        compose.onNodeWithTag("call-end").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, cameraTaps); assertEquals(1, muteTaps); assertEquals(1, endTaps) }
    }

    @Test fun disabledVideoTrayKeepsRealHeightIncludingWrappedRowsAndBottomGap() {
        val enabled = mutableStateOf(true)
        val width = mutableStateOf(320f)
        var measuredHeight = 0
        var actionCount = 0
        compose.setContent {
            CallTheme {
                Box(Modifier.size(width.value.dp, 640.dp).testTag("call-fixture")) {
                    VideoCallControlTray(CallPresentationPolicy.VideoControls("retained", false, false, AudioRouteKind.EARPIECE),
                        routes, { actionCount++ }, { actionCount++ }, { actionCount++ }, { actionCount++ },
                        modifier = Modifier.align(Alignment.BottomCenter), onMeasured = { measuredHeight = it }, enabled = enabled.value)
                }
            }
        }
        val heights = mutableMapOf<Float, Int>()
        for (windowWidth in listOf(320f, 220f)) {
            compose.runOnIdle { width.value = windowWidth; enabled.value = true }
            compose.waitForIdle()
            val liveHeight = measuredHeight
            assertTrue(liveHeight > 0)
            compose.onNodeWithTag("call-end").assertHasClickAction()
            compose.runOnIdle { enabled.value = false }
            compose.waitForIdle()
            assertEquals("Terminal tray must retain rows and bottom gap at width $windowWidth", liveHeight, measuredHeight)
            for (tag in listOf("call-camera", "call-mute", "call-end", "call-output"))
                compose.onNodeWithTag(tag, useUnmergedTree = true).assertDoesNotExist()
            heights[windowWidth] = measuredHeight
        }
        assertTrue("Narrow window must reserve both rows", heights.getValue(220f) > heights.getValue(320f))
        compose.runOnIdle { assertEquals(0, actionCount) }
    }

    @Test fun terminalVideoKeepsPeerIdentityPositionAtNormalAndWrappedWidths() {
        val terminal = mutableStateOf(false)
        val width = mutableStateOf(320f)
        compose.setContent {
            Box(Modifier.size(width.value.dp, 640.dp).testTag("call-fixture")) {
                ActiveCallScreen("Connected", "Synthetic peer", null, availableRoutes = routes, onHangup = {},
                    videoControls = CallPresentationPolicy.VideoControls("retained", false, false, AudioRouteKind.EARPIECE),
                    onToggleCamera = {}, controlsAvailable = !terminal.value, terminal = terminal.value)
            }
        }
        for (windowWidth in listOf(320f, 220f)) {
            compose.runOnIdle { width.value = windowWidth; terminal.value = false }
            compose.waitForIdle()
            val liveName = compose.onNodeWithTag("call-peer-name").fetchSemanticsNode().boundsInRoot
            val liveIdentity = compose.onNodeWithTag("call-identity-scroll").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { terminal.value = true }
            compose.waitForIdle()
            assertEquals("Peer name must stay still at width $windowWidth", liveName,
                compose.onNodeWithTag("call-peer-name").fetchSemanticsNode().boundsInRoot)
            assertEquals("Avatar and name stage must retain its height at width $windowWidth", liveIdentity,
                compose.onNodeWithTag("call-identity-scroll").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun incomingLargeTextShortViewportKeepsAcceptAndDeclineOutsideIdentityScroll() {
        var accepted = 0; var declined = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.size(320.dp, 320.dp).testTag("call-fixture")) {
                    IncomingCallScreen("Alexandria Synthetic Long Caller Name", true, { accepted++ }, { declined++ })
                }
            }
        }
        val acceptBefore = reachable("call-accept"); val declineBefore = reachable("call-decline")
        compose.onNodeWithTag("call-identity-scroll").performTouchInput { swipeUp() }
        assertEquals(acceptBefore, reachable("call-accept")); assertEquals(declineBefore, reachable("call-decline"))
        screenshot("incoming-large-text-short")
        compose.onNodeWithTag("call-accept").performTouchInput { click() }
        compose.onNodeWithTag("call-decline").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, accepted); assertEquals(1, declined) }
    }

    @Test fun audioRealInnerWidthAndPendingVideoPreserveEndAtLargeText() {
        val pending = mutableStateOf(false)
        var ended = 0; var upgraded = 0
        val audio = CallUiState.Connected(CallInfo("synthetic-audio", "peer", "UI fixture", CallMediaKind.AUDIO, CallDirection.OUTGOING), 0)
        val stats = kotlinx.coroutines.flow.MutableStateFlow<com.messenger.app.data.call.WebRtcCallStats?>(null)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f),
                LocalCallChrome provides CallChromeSnapshot(audio, null, stats)) {
                Box(Modifier.size(300.dp, 320.dp).testTag("call-fixture")) {
                    ActiveCallScreen(title = "Connected", name = "Alexandria Synthetic Long Caller Name", durationMs = null,
                        availableRoutes = routes, requestingVideo = pending.value, onHangup = { ended++ }, onRequestVideo = { upgraded++ })
                }
            }
        }
        val before = reachable("call-end")
        for (tag in listOf("call-mute", "call-output", "call-video")) reachable(tag)
        compose.onNodeWithTag("call-video").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, upgraded); pending.value = true }
        compose.onNodeWithTag("call-video").assertIsEnabled()
        assertEquals(before, reachable("call-end"))
        screenshot("audio-large-text-300dp-pending-upgrade")
        compose.onNodeWithTag("call-end").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, ended) }
    }

    @Test fun nativeSafeFooterAndExitHaveNoHiddenFlipHit() {
        val isVideo = mutableStateOf(false); val scale = mutableStateOf(1f)
        val state = mutableStateOf(video()); var flips = 0; var minimized = 0; var unexpectedTap = 0
        compose.setContent {
            CallTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                    Box(Modifier.fillMaxSize().background(Color.Black).testTag("call-fixture")) {
                        if (isVideo.value) VideoCallControlTray(state.value, routes, true, { unexpectedTap++ }, { unexpectedTap++ }, { unexpectedTap++ }, { unexpectedTap++ }, { flips++ },
                            modifier = Modifier.align(Alignment.BottomCenter).callSafePadding())
                        else ActiveCallScreen("Connected", "Native safe-area fixture", null, availableRoutes = routes,
                            onHangup = {}, onRequestVideo = {}, onMinimize = { minimized++ }, callId = "layout-only")
                    }
                }
            }
        }
        val results = JSONArray()
        for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle { scale.value = fontScale; isVideo.value = false }
            results.put(nativeFooter("audio-font$fontScale"))
            compose.onNodeWithContentDescription(compose.activity.getString(com.messenger.app.R.string.call_minimize)).performTouchInput { click() }
            compose.runOnIdle { isVideo.value = true; state.value = video() }
            results.put(nativeFooter("video-font$fontScale"))
        }
        compose.runOnIdle { scale.value = 1f; state.value = video() }
        val cameraBeforeExit = reachable("call-camera")
        compose.runOnIdle { state.value = video().copy(localVideoEnabled = false, localCameraState = LocalCameraState.OFF,
            remoteVideoEnabled = true, remoteVideoState = RemoteVideoState.ON) }
        compose.onNodeWithTag("call-flip").assertDoesNotExist()
        assertEquals("Four control geometry remains stable across camera state", cameraBeforeExit, reachable("call-camera"))
        results.put(nativeFooter("video-local-off-remote-on"))
        compose.runOnIdle { assertEquals(2, minimized) }
        outputFile("native-footer-result.json").writeText(JSONObject().put("passed", true).put("cases", results)
            .put("flipExitHasNoTagDescriptionOrHit", true).put("visibleControlsCenteredAndReachable", true)
            .put("nativeAnimatorsEnabled", android.animation.ValueAnimator.areAnimatorsEnabled())
            .put("fourButtonGeometryStable", true)
            .put("scope", "Real production controls/ActiveCallScreen in an edge-to-edge native window; synthetic call state/callbacks, no RTC/audio/camera session").toString(2))
    }

    private fun nativeFooter(name: String): JSONObject {
        compose.waitForIdle()
        screenshot(name)
        val root = compose.activity.window.decorView
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(root))
        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val position = IntArray(2); compose.runOnUiThread { root.getLocationOnScreen(position) }
        val end = reachable("call-end")
        for (resource in listOf(com.messenger.app.R.string.call_caption_end, com.messenger.app.R.string.call_caption_mute,
            com.messenger.app.R.string.call_caption_camera, com.messenger.app.R.string.call_caption_flip,
            com.messenger.app.R.string.call_caption_video)) {
            compose.onAllNodesWithText(compose.activity.getString(resource), useUnmergedTree = true).assertCountEquals(0)
        }
        compose.onNodeWithContentDescription(compose.activity.getString(com.messenger.app.R.string.call_end)).assertIsDisplayed()
        val density = compose.activity.resources.displayMetrics.density
        val safeBottom = position[1] + root.height - safe.bottom
        val gap = (safeBottom - position[1] - end.bottom) / density
        outputFile("geometry-$name.json").writeText(JSONObject().put("gapDp",gap).put("safeBottomPx",safeBottom).put("endIconBottomPx",end.bottom)
            .put("camera",runCatching { reachable("call-camera").toString() }.getOrNull()).put("end",reachable("call-end").toString())
            .put("flip",runCatching { reachable("call-flip").toString() }.getOrNull()).toString(2))
        assertTrue("$name footer gap $gap must be close to native safe bottom, without excess old32/16dp spacers", gap in 18f..22f)
        for (tag in listOf("call-mute", "call-output", "call-end")) reachable(tag)
        if (name.startsWith("video")) assertVisibleVideoGroupCentered(
            compose.onAllNodesWithTag("call-flip").fetchSemanticsNodes().isNotEmpty(),
            (safe.left + root.width - safe.right) / 2f)
        return JSONObject().put("case", name).put("rootHeightPx", root.height).put("rootTopPx", position[1])
            .put("systemAndCutoutBottomPx", safe.bottom).put("safeBottomScreenPx", safeBottom).put("endIconBottomPx", end.bottom)
            .put("iconToSafeBottomDp", gap).put("endControlHeightPx", end.height).put("noVisibleCaptions", true).put("endAccessibilityDescriptionRetained", true)
            .put("actualActivityFontScale", compose.activity.resources.configuration.fontScale)
    }

    private fun assertVisibleVideoGroupCentered(flipVisible: Boolean, centerX: Float? = null) {
        val tags = listOf("call-camera", "call-mute", "call-end", "call-output")
        val bounds = tags.map(::reachable)
        val viewport = compose.onNodeWithTag("call-fixture").fetchSemanticsNode().boundsInRoot
        val actual = (bounds.minOf { it.left } + bounds.maxOf { it.right }) / 2f
        assertEquals("Only the $tags visible controls must be centered, without an empty Flip slot", centerX ?: viewport.center.x, actual, 1.5f)
    }

    private fun reachable(tag: String): Rect {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("call-fixture").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("$tag width ${bounds.width}", bounds.width >= 48f * density - 1)
        assertTrue("$tag height ${bounds.height}", bounds.height >= 48f * density - 1)
        assertTrue("$tag $bounds outside $viewport", bounds.left >= viewport.left - 1 && bounds.top >= viewport.top - 1 && bounds.right <= viewport.right + 1 && bounds.bottom <= viewport.bottom + 1)
        return bounds
    }
    private val runId by lazy { InstrumentationRegistry.getArguments().getString("run") ?: java.util.UUID.randomUUID().toString() }
    private fun outputFile(name: String) = File(File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "call-controls-ui-$runId").apply { mkdirs() }, name)
    private fun screenshot(name: String) {
        compose.waitForIdle(); val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(2) {
            val ready = java.util.concurrent.CountDownLatch(1)
            compose.runOnUiThread { val root=compose.activity.window.decorView; root.viewTreeObserver.registerFrameCommitCallback { ready.countDown() }; root.postInvalidateOnAnimation() }
            check(ready.await(3, java.util.concurrent.TimeUnit.SECONDS))
        }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()); val file = outputFile("$name.png")
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        outputFile("$name.png.receipt.json").writeText(JSONObject().put("run",runId).put("sha256",sha).put("deviceAtMs",System.currentTimeMillis()).toString(2))
    }
    private fun video() = CallUiState.Connected(
        call = CallInfo("synthetic-call", "synthetic-peer", "UI fixture", CallMediaKind.VIDEO, CallDirection.OUTGOING),
        connectedAtElapsedRealtimeMs = 0, localVideoEnabled = true, videoMode = VideoMode.VIDEO,
        localCameraState = LocalCameraState.ON, audioRoute = AudioRouteKind.EARPIECE,
    )
}
