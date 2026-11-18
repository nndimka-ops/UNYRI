package com.messenger.app.ui.call

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.data.call.*
import com.messenger.app.domain.call.*
import com.messenger.app.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic state and content only. No account, camera, media engine, networking or phone call. */
class CallParityComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val call=CallInfo("synthetic","peer","Peer",CallMediaKind.VIDEO,CallDirection.OUTGOING)
    private val state=CallUiState.Connected(call,1_000,videoMode=VideoMode.VIDEO,localVideoEnabled=true,localCameraState=LocalCameraState.ON)
    private val routes=listOf(AudioRoute(AudioRouteKind.EARPIECE,"Phone"),AudioRoute(AudioRouteKind.SPEAKER,"Speaker"))

    @Test fun fourButtonTrayWrapsAsTwoPairsAndNeverContainsFlip() {
        val width=mutableStateOf(360f)
        compose.setContent { Box(Modifier.width(width.value.dp)) {
            VideoCallControlTray(state,routes,true,{},{},{},{},{})
        } }
        val tags=listOf("call-camera","call-mute","call-end","call-output")
        tags.forEach { compose.onNodeWithTag(it).assertIsDisplayed().assertHasClickAction() }
        compose.onNodeWithTag("call-flip").assertDoesNotExist()
        val row=tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(row.zipWithNext().all { (a,b)->a.left<b.left && a.top==b.top })
        compose.runOnIdle { width.value=200f }
        val wrapped=tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        assertEquals(wrapped[0].top,wrapped[1].top,.5f)
        assertEquals(wrapped[2].top,wrapped[3].top,.5f)
        assertTrue(wrapped[2].top>wrapped[0].bottom)
    }
    @Test fun encryptionSheetHasNoUnverifiedCodeAndStatisticsStartFolded() {
        val stats=MutableStateFlow<WebRtcCallStats?>(WebRtcCallStats(selectedCandidatePair=WebRtcCandidatePairStats(localCandidateType="host",remoteCandidateType="relay",protocol="udp")))
        val quality=MutableStateFlow(CallQualityUiState(CallQualityPresentation.Result(4),relay=true))
        compose.setContent { CompositionLocalProvider(LocalCallChrome provides CallChromeSnapshot(state,null,stats,quality=quality)) {
            CallTopBar({})
        } }
        compose.onNodeWithTag("call-status").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.call_encryption_relay)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.call_quality_excellent)).assertIsDisplayed()
        compose.onNodeWithTag("call-verification-code").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.call_stat_video_codec)).assertDoesNotExist()
        compose.onNodeWithTag("call-statistics-toggle").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.call_stat_video_codec)).assertExists()
    }
    @Test fun xHidesOnlyMiniWindowAndSubsequentMinimizeRevealsIt() {
        val expanded=mutableStateOf(false);val hidden=mutableStateOf(false)
        val memory=CallMiniWindowMemory();var returns=0;var attached=0;var detached=0
        compose.setContent { Box(Modifier.size(400.dp,700.dp)) {
            CallMiniVideoHost(state,expanded.value,hidden.value,memory,
                onHide={memory.hide(call.callId);hidden.value=true},onReturn={returns++},onToggleMute={}) {
                DisposableEffect(Unit) { attached++; onDispose { detached++ } }
                Box(Modifier.fillMaxSize().background(Color.DarkGray))
            }
        } }
        compose.onNodeWithTag("call-mini-hide").performClick()
        compose.onNodeWithTag("call-mini-window").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0,returns); assertTrue(memory.hidden);expanded.value=true }
        compose.runOnIdle { memory.minimized(call.callId);hidden.value=false;expanded.value=false }
        compose.onNodeWithTag("call-mini-window").assertExists()
        compose.runOnIdle { assertEquals(1,attached);assertEquals(0,detached) }
    }
}
