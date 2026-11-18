package com.messenger.app.ui.call

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.call.AudioRoute
import com.messenger.app.data.call.AudioRouteKind
import com.messenger.app.domain.call.*
import com.messenger.app.domain.model.CallDirection
import com.messenger.app.domain.model.CallInfo
import com.messenger.app.domain.model.CallMediaKind
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual production boundary + native renderer and production controls; no peer/capture/account. */
class CallTouchIsolationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val routes = listOf(AudioRoute(AudioRouteKind.EARPIECE, "Phone"), AudioRoute(AudioRouteKind.SPEAKER, "Speaker"))

    @Before fun wakeFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (command in listOf("input keyevent 224", "wm dismiss-keyguard")) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        instrumentation.runOnMainSync { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun originalBlankVideoHoleIsReproducedAndFullBoundaryClosesIt() {
        val f = Fixture().apply { isolated.value = false }
        compose.setContent { fixture(f) }
        // Negative control: same native-view/placeholder stack, original plain Box root.
        tapBackdrop()
        compose.runOnIdle { assertEquals("Baseline must actually reproduce photo opening", 1, f.photos); f.isolated.value = true }
        for (remote in RemoteVideoState.entries) for (controls in listOf(true, false)) {
            compose.runOnIdle { f.remote.value = remote; f.controls.value = controls }
            tapBackdrop()
            compose.onNodeWithTag("touch-root").performTouchInput { click(Offset(width * .12f, height * .35f)) }
            compose.runOnIdle { assertEquals(1, f.photos); assertEquals(1, f.underlyingDowns); assertEquals(0, f.buttons) }
            screenshot("video-${remote.name.lowercase()}-controls-$controls")
        }
    }

    @Test fun nativeSurfaceCameraOnAlsoCannotDeliverPointersToUnderlyingPhoto() {
        val f = Fixture().apply { remote.value = RemoteVideoState.ON; controls.value = false }
        compose.setContent { fixture(f) }
        repeat(3) { tapBackdrop() }
        compose.onNodeWithTag("touch-root").performTouchInput { click(Offset(width * .08f, height * .08f)) }
        val underlyingButton = compose.onNodeWithTag("chat-photo-button").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("touch-root").performTouchInput { click(underlyingButton) }
        compose.runOnIdle { assertEquals(0, f.photos); assertEquals(0, f.underlyingDowns); assertEquals(0, f.buttons) }
        screenshot("native-video-on-hidden-controls")
    }

    @Test fun realCallControlsAndPreviewDragStillReceiveInput() {
        val f = Fixture()
        compose.setContent { fixture(f) }
        for (tag in listOf("call-mute", "call-camera", "call-end")) {
            compose.onNodeWithTag(tag).assertIsDisplayed().performTouchInput { click() }
        }
        compose.onNodeWithTag("touch-preview").performTouchInput { swipe(Offset(width * .25f, height * .25f), Offset(width * .75f, height * .7f)) }
        compose.runOnIdle {
            assertEquals(1, f.mutes); assertEquals(1, f.cameras); assertEquals(1, f.ends)
            assertTrue("Preview drag must remain interactive", f.previewMoves > 0)
            assertEquals(0, f.photos); assertEquals(0, f.underlyingDowns)
        }
    }

    @Test fun intentionalMinimizeAllowsChatAndNativeSurfaceRemainsMountedForRestore() {
        val f = Fixture()
        compose.setContent { fixture(f) }
        compose.onNodeWithTag("touch-minimize").performTouchInput { click() }
        tapBackdrop()
        compose.runOnIdle { assertEquals(1, f.photos); assertEquals(1, f.surfaceAttaches) }
        compose.onNodeWithTag("touch-return").performTouchInput { click() }
        tapBackdrop()
        compose.runOnIdle { assertEquals(1, f.photos); assertEquals(1, f.surfaceAttaches); assertEquals(0, f.surfaceDetaches) }
        screenshot("restored-keeps-native-surface")
    }

    @Test fun baselineInFlightPhotoTapSurvivesRestoreAndOwnershipGuardCancelsIt() {
        val f = Fixture().apply { expanded.value = false; transitionGuard.value = false }
        compose.setContent { fixture(f) }
        downBackdrop()
        compose.runOnIdle { f.expanded.value = true }
        compose.onNodeWithTag("touch-root").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals("Prove retained DOWN path before adding transition guard", 1, f.photos)
            f.expanded.value = false; f.transitionGuard.value = true
        }
        downBackdrop()
        compose.runOnIdle { f.expanded.value = true }
        compose.onNodeWithTag("touch-root").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, f.photos) }
        // The guard must not eat the next fresh minimized chat gesture.
        compose.runOnIdle { f.expanded.value = false }
        tapBackdrop()
        compose.runOnIdle { assertEquals(2, f.photos) }
    }

    @Test fun downMinimizeRestoreThenUpAndMultiTouchNeverReachUnderlyingChat() {
        val f = Fixture()
        compose.setContent { fixture(f) }
        downBackdrop()
        compose.runOnIdle { f.expanded.value = false }
        compose.runOnIdle { f.expanded.value = true }
        compose.onNodeWithTag("touch-root").performTouchInput { up() }
        compose.onNodeWithTag("touch-root").performTouchInput {
            down(0, Offset(width * .25f, height * .45f)); down(1, Offset(width * .5f, height * .65f))
            moveTo(0, Offset(width * .2f, height * .4f)); moveTo(1, Offset(width * .6f, height * .7f))
            up(0); up(1)
        }
        compose.runOnIdle { assertEquals(0, f.photos); assertEquals(0, f.underlyingDowns) }
        downBackdrop()
        compose.runOnIdle { f.expanded.value = false }
        compose.onNodeWithTag("touch-root").performTouchInput { cancel() }
        tapBackdrop()
        compose.runOnIdle { assertEquals("CANCEL must not poison the next minimized chat tap", 1, f.photos) }
    }

    @Test fun expandedIncomingAudioCallingConnectingAndRecoveryProtectBlankRegions() {
        val f = Fixture()
        compose.setContent { fixture(f) }
        val call = video().call
        for (state in listOf(CallUiState.IncomingRinging(call), CallUiState.OutgoingRinging(call),
            CallUiState.Connecting(call), video().copy(videoMode = VideoMode.AUDIO),
            CallUiState.Reconnecting(call, video().copy(videoMode = VideoMode.AUDIO)))) {
            compose.runOnIdle { f.presentation.value = state }
            compose.onNodeWithTag("touch-root").performTouchInput { click(Offset(width * .02f, height * .4f)) }
            compose.runOnIdle { assertEquals(0, f.photos); assertEquals(0, f.underlyingDowns) }
        }
    }

    @Test fun conferenceRestoreAlsoCancelsAnAlreadyAdmittedPhotoGesture() {
        val minimized = mutableStateOf(true)
        val includeConference = mutableStateOf(false)
        var photos = 0
        compose.setContent { CallTheme {
            val privateVisible = if (includeConference.value) CallTouchPolicy.privateContentVisible(true, !minimized.value) else true
            Box(Modifier.fillMaxSize().callTouchTransitionGuard(privateVisible).testTag("room-touch-root")) {
                Image(ColorPainter(Color.DarkGray), "Underlying photo", Modifier.fillMaxSize().clickable { photos++ })
                if (!minimized.value) androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Color.Black) { Text("Room") }
            }
        } }
        fun pressPhoto() = compose.onNodeWithTag("room-touch-root").performTouchInput { down(Offset(width * .4f, height * .5f)) }
        fun releasePhoto() = compose.onNodeWithTag("room-touch-root").performTouchInput { up() }
        // Previous MainActivity observed only the ordinary-call boolean.
        pressPhoto(); compose.runOnIdle { minimized.value = false }; releasePhoto()
        compose.runOnIdle { assertEquals("Old room restore leaves the original photo click alive", 1, photos) }
        compose.runOnIdle { minimized.value = true; includeConference.value = true; photos = 0 }
        pressPhoto(); compose.runOnIdle { minimized.value = false }; releasePhoto()
        compose.runOnIdle { assertEquals("The combined ownership fence cancels the old photo gesture", 0, photos) }
        compose.runOnIdle { minimized.value = true }
        compose.onNodeWithTag("room-touch-root").performTouchInput { click(Offset(width * .4f, height * .5f)) }
        compose.runOnIdle { assertEquals("Intentional minimized chat stays usable", 1, photos) }
    }

    private class Fixture {
        val expanded = mutableStateOf(true)
        val isolated = mutableStateOf(true)
        val transitionGuard = mutableStateOf(true)
        val remote = mutableStateOf(RemoteVideoState.OFF)
        val controls = mutableStateOf(true)
        val presentation = mutableStateOf<CallUiState>(video())
        var photos = 0; var buttons = 0; var underlyingDowns = 0
        var mutes = 0; var cameras = 0; var ends = 0; var previewMoves = 0
        var surfaceAttaches = 0; var surfaceDetaches = 0
    }

    @Composable private fun fixture(f: Fixture) = CallTheme {
        val guard = if (f.transitionGuard.value) Modifier.callTouchTransitionGuard(!f.expanded.value) else Modifier
        Box(Modifier.fillMaxSize().then(guard).testTag("touch-root")) {
            Image(ColorPainter(Color(0xFF63718A)), "Underlying chat photo", Modifier.fillMaxSize().testTag("chat-photo")
                .pointerInput(Unit) { awaitPointerEventScope { while (true) {
                    val event = awaitPointerEvent()
                    f.underlyingDowns += event.changes.count { it.pressed && !it.previousPressed }
                } } }.clickable { f.photos++ })
            Button(onClick = { f.buttons++ }, modifier = Modifier.align(Alignment.BottomEnd).testTag("chat-photo-button")) { Text("Open photo") }
            val content: @Composable BoxScope.() -> Unit = {
                val state = f.presentation.value
                when {
                    state is CallUiState.Connected && state.videoMode == VideoMode.VIDEO -> videoFixture(f)
                    f.expanded.value && state is CallUiState.IncomingRinging -> IncomingCallScreen("Touch fixture", false, {}, {})
                    f.expanded.value -> ActiveCallScreen("Connecting", "Touch fixture", null, onHangup = {})
                }
            }
            if (f.isolated.value) CallTouchLayer(CallTouchPolicy.ownsBackdrop(f.presentation.value, f.expanded.value), content = content)
            else Box(Modifier.fillMaxSize(), content = content)
        }
    }

    @Composable private fun BoxScope.videoFixture(f: Fixture) {
        // Same mounted/off-screen minimize contract as CallOverlay; actual native renderer.
        Box(Modifier.fillMaxSize().offset { if (f.expanded.value) IntOffset.Zero else IntOffset(-20000, -20000) }) {
            VideoSurface(attach = { f.surfaceAttaches++ }, detach = { f.surfaceDetaches++; it.release() }, modifier = Modifier.fillMaxSize())
            if (f.remote.value != RemoteVideoState.ON) Box(Modifier.fillMaxSize().background(Color.Black))
            if (f.controls.value) {
                Box(Modifier.align(Alignment.CenterEnd).size(96.dp, 120.dp).background(Color.DarkGray).testTag("touch-preview")
                    .pointerInput(Unit) { detectTransformGestures { _, _, _, _ -> f.previewMoves++ } })
                VideoCallControlTray(video(), routes, supportsCameraFlip = false,
                    onCamera = { f.cameras++ }, onToggleMute = { f.mutes++ }, onHangup = { f.ends++ },
                    onSelectRoute = {}, onFlip = {}, modifier = Modifier.align(Alignment.BottomCenter))
                CallMinimizeButton({ f.expanded.value = false }, Modifier.align(Alignment.TopStart).testTag("touch-minimize"))
            }
        }
        if (!f.expanded.value) Box(Modifier.align(Alignment.TopCenter).testTag("touch-return")) {
            CallIslandControl(null, true, { f.expanded.value = true })
        }
    }

    private fun tapBackdrop() = compose.onNodeWithTag("touch-root").performTouchInput { click(Offset(width * .42f, height * .45f)) }
    private fun downBackdrop() = compose.onNodeWithTag("touch-root").performTouchInput { down(Offset(width * .42f, height * .45f)) }
    private fun screenshot(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "call-touch-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        private fun video() = CallUiState.Connected(
            CallInfo("synthetic-touch", "peer", "Touch fixture", CallMediaKind.VIDEO, CallDirection.OUTGOING),
            0, localVideoEnabled = true, videoMode = VideoMode.VIDEO,
            localCameraState = LocalCameraState.ON, remoteVideoState = RemoteVideoState.ON,
        )
    }
}
