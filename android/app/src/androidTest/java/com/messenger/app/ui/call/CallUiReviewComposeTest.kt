package com.messenger.app.ui.call

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.messenger.app.R
import com.messenger.app.data.call.WebRtcCallStats
import com.messenger.app.domain.call.*
import com.messenger.app.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production input/layout components, fake media state, no camera or account required. */
class CallUiReviewComposeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val call=CallInfo("a","peer","Peer",CallMediaKind.VIDEO,CallDirection.OUTGOING)
    private val video=CallUiState.Connected(call,1000,videoMode=VideoMode.VIDEO,
        localVideoEnabled=true,localCameraState=LocalCameraState.ON,remoteVideoState=RemoteVideoState.ON)
    private class FakeSelfViewEngine(var live: CallUiState) {
        var multiple=true
        val flipped=mutableListOf<String>()
        fun flip() { flipped+=CallPresentationPolicy.connected(live)!!.call.callId }
    }

    @Test fun shortLandscapeKeepsSelfViewAndFlipVisibleAndTouchReachableTogether() {
        var measuredTray by mutableIntStateOf(0)
        var flipped=0
        var mounted=0
        var unmounted=0
        val direction=mutableStateOf(LayoutDirection.Ltr)
        val chrome=mutableStateOf(true)
        val routes=listOf(com.messenger.app.data.call.AudioRoute(com.messenger.app.data.call.AudioRouteKind.SPEAKER,"Speaker"))
        compose.setContent {
            // A real 640x336dp layout that fits on a portrait test host too.
            // No orientation change, camera, account or native renderer is needed.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val native=LocalDensity.current
                val fit=minOf(1f,maxWidth.value/640f,maxHeight.value/336f)
                CompositionLocalProvider(LocalDensity provides Density(native.density*fit,1f),
                    LocalLayoutDirection provides direction.value) {
                    Box(Modifier.requiredSize(640.dp,336.dp).testTag("short-landscape")) {
                        CallSelfViewLayout(call.callId,true,chrome.value,measuredTray,Modifier.fillMaxSize().zIndex(2f),
                            picture={
                                DisposableEffect(Unit) { mounted++;onDispose { unmounted++ } }
                                Box(Modifier.fillMaxSize().background(Color.DarkGray))
                            },
                            controls={ CallSelfViewControls(video,true,chrome.value,{video},{true},{flipped++},
                                Modifier.align(Alignment.BottomEnd)) })
                        VideoCallControlTray(CallPresentationPolicy.videoControls(video)!!,routes,{},{},{},{},
                            Modifier.align(Alignment.BottomCenter),onMeasured={measuredTray=it})
                        Box(Modifier.fillMaxWidth().height(56.dp).testTag("self-view-header")) { CallTopBar({}) }
                    }
                }
            }
        }
        val viewport=compose.onNodeWithTag("short-landscape").fetchSemanticsNode().boundsInRoot
        val d=viewport.width/640f
        assertEquals(336f,viewport.height/d,.5f)
        fun assertReachableTogether() {
            val tile=compose.onNodeWithTag("call-self-view").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val flip=compose.onNodeWithTag("call-flip").assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().boundsInRoot
            val header=compose.onNodeWithTag("self-view-header").fetchSemanticsNode().boundsInRoot
            val tray=compose.onNodeWithTag("call-controls-tray").fetchSemanticsNode().boundsInRoot
            assertTrue(tile.left>=viewport.left && tile.right<=viewport.right)
            assertTrue(tile.top>=header.bottom+12*d-1f)
            assertTrue(tile.bottom+16*d<=minOf(tray.top,viewport.bottom-144*d)+1f)
            assertTrue(flip.left>=tile.left-1f && flip.right<=tile.right+1f)
            assertTrue(flip.top>=tile.top-1f && flip.bottom<=tile.bottom+1f)
            assertTrue(flip.width>=44*d-1f && flip.height>=44*d-1f)
            compose.onNodeWithTag("call-flip").performTouchInput { click(center) }
        }
        assertReachableTogether()
        compose.runOnIdle { direction.value=LayoutDirection.Rtl }
        assertReachableTogether()
        compose.onNodeWithTag("call-self-view").performTouchInput {
            swipe(center,center+Offset(-400*d,-200*d),400)
        }
        assertReachableTogether()
        val beforeHide=compose.onNodeWithTag("call-self-view").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { chrome.value=false }
        assertEquals(beforeHide,compose.onNodeWithTag("call-self-view").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("call-flip").assertDoesNotExist()
        compose.runOnIdle { chrome.value=true }
        assertEquals(beforeHide,compose.onNodeWithTag("call-self-view").fetchSemanticsNode().boundsInRoot)
        assertReachableTogether()
        compose.runOnIdle { assertEquals(4,flipped);assertEquals(1,mounted);assertEquals(0,unmounted) }
    }

    @Test fun pendingFlipPressIsCancelledWhenCameraStopsOrCallIsReplaced() {
        val snapshot=mutableStateOf(video)
        val engine=FakeSelfViewEngine(video)
        compose.setContent { Box(Modifier.size(120.dp,168.dp)) {
            CallSelfViewControls(snapshot.value,true,true,{engine.live},{engine.multiple},engine::flip)
        } }
        for(next in listOf(video.copy(localVideoEnabled=false,localCameraState=LocalCameraState.OFF),
            video.copy(call=call.copy(callId="b")))) {
            compose.runOnIdle { snapshot.value=video;engine.live=video }
            compose.onNodeWithTag("call-flip").performTouchInput { down(center) }
            compose.runOnUiThread { engine.live=next;snapshot.value=next }
            compose.waitForIdle()
            compose.onRoot().performTouchInput { up() }
            compose.runOnIdle { assertTrue(engine.flipped.isEmpty()) }
        }
        compose.onNodeWithTag("call-flip").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("b"),engine.flipped) }
    }

    @Test fun liveFlipGuardRejectsStoppedReplacedOrSingleCameraBeforeCompositionCatchesUp() {
        val engine=FakeSelfViewEngine(video)
        compose.setContent { Box(Modifier.size(120.dp,168.dp)) {
            CallSelfViewControls(video,true,true,{engine.live},{engine.multiple},engine::flip)
        } }
        for(next in listOf(video.copy(localVideoEnabled=false),video.copy(call=call.copy(callId="other")),video)) {
            compose.runOnIdle { engine.live=video;engine.multiple=true }
            compose.onNodeWithTag("call-flip").performTouchInput { down(center) }
            compose.runOnUiThread { engine.live=next;if(next===video)engine.multiple=false }
            compose.onRoot().performTouchInput { up() }
        }
        compose.runOnIdle { assertTrue(engine.flipped.isEmpty()) }
    }

    @Test fun hiddenChromeAlertReceivesTouchAboveBackgroundPlane() {
        var toggles=0
        val state=CallUiState.Reconnecting(call,video)
        val stats=MutableStateFlow<WebRtcCallStats?>(null)
        compose.setContent { CompositionLocalProvider(LocalCallChrome provides CallChromeSnapshot(state,null,stats)) {
            Box(Modifier.size(360.dp,600.dp)) {
                CallVideoTapPlane(call.callId,true,true,false,{toggles++},{})
                CallTopBar({},chrome=false,overVideo=true)
            }
        } }
        compose.mainClock.advanceTimeBy(1900)
        compose.waitUntil(4000) { compose.onAllNodesWithTag("call-status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("call-status").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText(compose.activity.getString(R.string.call_encryption_title)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0,toggles) }
    }

    @Test fun pausedPiPStillDrawsPeerAndStoppedWindowPausesIt() {
        val owner=object: LifecycleOwner { override val lifecycle=LifecycleRegistry(this) }
        var flags=false to false
        compose.runOnUiThread { owner.lifecycle.currentState=Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            val value=CallPresentationPolicy.rendererVisibility(callForeground(),true,false,true,true,
                started=callWindowStarted())
            SideEffect { flags=value }
        } }
        compose.runOnUiThread { owner.lifecycle.currentState=Lifecycle.State.STARTED }
        compose.runOnIdle { assertEquals(false to true,flags) }
        compose.runOnUiThread { owner.lifecycle.currentState=Lifecycle.State.CREATED }
        compose.runOnIdle { assertEquals(false to false,flags) }
    }

    @Test fun RTLDragUsesPhysicalXAndTucksAtThePhysicalSide() {
        val memory=CallMiniWindowMemory()
        compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.size(300.dp,600.dp).testTag("mini-fixture")) {
                CallMiniVideoHost(video,false,false,memory,{},{},{}) { Box(Modifier.fillMaxSize().background(Color.DarkGray)) }
            }
        } }
        val d=compose.density.density
        val initial=compose.onNodeWithTag("call-mini-window").fetchSemanticsNode().boundsInRoot
        val container=compose.onNodeWithTag("mini-fixture").fetchSemanticsNode().boundsInRoot
        assertTrue("RTL starts at the physical leading corner",initial.left<container.left+24*d)
        compose.onNodeWithTag("call-mini-window").performTouchInput { down(center);moveBy(Offset(60*d,0f),200) }
        val moved=compose.onNodeWithTag("call-mini-window").fetchSemanticsNode().boundsInRoot
        assertTrue("A rightward finger must move the window right in RTL",moved.left>initial.left+40*d)
        compose.onRoot().performTouchInput { advanceEventTime(160);up() }
        compose.waitForIdle()
        compose.onNodeWithTag("call-mini-window").performTouchInput { swipe(center,center-Offset(250*d,0f),400) }
        compose.waitForIdle()
        compose.onNodeWithTag("call-mini-tab").assertExists()
        compose.runOnIdle { assertTrue(memory.placement(call.callId).corner.left);assertTrue(memory.placement(call.callId).tucked) }
        val fixture=compose.onNodeWithTag("mini-fixture").fetchSemanticsNode().boundsInRoot
        val tab=compose.onNodeWithTag("call-mini-tab").fetchSemanticsNode().boundsInRoot
        assertTrue(tab.right<fixture.left+30*d)
        compose.onNodeWithTag("call-mini-tab").performClick()
        compose.onNodeWithTag("call-mini-window").assertExists()
    }

    @Test fun miniHasOneWindowActionAndSeparateHideWithNoDecorativeTalkBackNodes() {
        val stats=MutableStateFlow<WebRtcCallStats?>(null)
        val muted=video.copy(isMuted=true,remoteVideoState=RemoteVideoState.OFF)
        compose.setContent { CompositionLocalProvider(LocalCallChrome provides CallChromeSnapshot(CallUiState.Reconnecting(call,muted),null,stats)) {
            Box(Modifier.size(300.dp,600.dp)) {
                CallMiniVideoHost(muted,false,false,CallMiniWindowMemory(),{},{},{}) {
                    Box(Modifier.fillMaxSize().background(Color.White)) { Text("Decorative peer name") }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(1600)
        // Confirm the reconnect dim really appeared before checking that its text is hidden from TalkBack.
        compose.waitUntil(4000) {
            val pixels=compose.onNodeWithTag("call-mini-window").captureToImage().toPixelMap()
            pixels[pixels.width/2,pixels.height/2].red<.8f
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.onAllNodesWithText("Decorative peer name",useUnmergedTree=true).assertCountEquals(0)
        compose.onAllNodesWithText(compose.activity.getString(R.string.call_reconnecting),useUnmergedTree=true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.call_unmute),useUnmergedTree=true).assertCountEquals(0)
        compose.onNodeWithTag("call-mini-window").assertHasClickAction()
        compose.onNodeWithTag("call-mini-hide").assertHasClickAction()
    }

    @Test fun createdVideoPhasesKeepTheSameCameraMicEndOutputBounds() {
        val snapshot=mutableStateOf<CallUiState>(CallUiState.OutgoingRinging(call))
        var camera=0
        val routes=listOf(com.messenger.app.data.call.AudioRoute(com.messenger.app.data.call.AudioRouteKind.SPEAKER,"Speaker"))
        compose.setContent { Box(Modifier.width(360.dp)) {
            VideoCallControlTray(CallPresentationPolicy.videoControls(snapshot.value)!!,routes,{camera++},{},{},{})
        } }
        val tags=listOf("call-camera","call-mute","call-end","call-output")
        val bounds=tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        for(next in listOf(CallUiState.Connecting(call),video)) {
            compose.runOnIdle { snapshot.value=next }
            tags.forEachIndexed { i,tag -> assertEquals(bounds[i],compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot) }
        }
        compose.onNodeWithTag("call-camera").performClick()
        compose.runOnIdle { assertEquals(1,camera) }
    }
}
