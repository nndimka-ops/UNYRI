package com.messenger.app.ui.call

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallMinimizeInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun minimizedOutgoingCallShowsPeerAndAFullWidthReturnAction() {
        var restored = 0
        val info = com.messenger.app.domain.model.CallInfo("fixture-call", "peer", "Native Peer", com.messenger.app.domain.model.CallMediaKind.AUDIO, com.messenger.app.domain.model.CallDirection.OUTGOING)
        compose.setContent { com.messenger.app.ui.theme.CocoTheme {
            Box(Modifier.width(360.dp)) { CallReturnStrip(com.messenger.app.domain.call.CallUiState.OutgoingRinging(info)) { restored++ } }
        } }
        compose.onNodeWithText("Native Peer").assertIsDisplayed()
        compose.onNodeWithTag("call_return_strip").assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, restored) }
    }

    @Test fun headerSwipeIsDirectionalAndUsesLatestCallCallback() {
        var first = 0
        var second = 0
        val revision = mutableIntStateOf(0)
        compose.setContent {
            val callback: () -> Unit = if (revision.intValue == 0) ({ first++ }) else ({ second++ })
            Box(Modifier.width(200.dp).height(200.dp).testTag("call-header")
                .callMinimizeSwipe("same-peer", callback))
        }
        compose.onNodeWithTag("call-header").performTouchInput {
            swipe(Offset(width * .2f, height * .3f), Offset(width * .8f, height * .35f))
        }
        compose.runOnIdle { assertEquals(0, first) }
        compose.onNodeWithTag("call-header").performTouchInput {
            swipe(Offset(width * .5f, height * .15f), Offset(width * .5f, height * .85f))
        }
        compose.runOnIdle { assertEquals(1, first); revision.intValue = 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("call-header").performTouchInput {
            swipe(Offset(width * .5f, height * .15f), Offset(width * .5f, height * .85f))
        }
        compose.runOnIdle { assertEquals(1, first); assertEquals(1, second) }
    }

    @Test fun newCallCancelsGestureAdmittedForPreviousCallWithSamePeerName() {
        var completed = 0
        val owner = mutableStateOf("call-a")
        compose.setContent {
            Box(Modifier.width(200.dp).height(200.dp).testTag("call-header")
                .callMinimizeSwipe(owner.value) { completed++ })
        }
        compose.onNodeWithTag("call-header").performTouchInput {
            down(Offset(width * .5f, height * .15f))
            moveTo(Offset(width * .5f, height * .85f))
        }
        compose.runOnIdle { owner.value = "call-b" }
        compose.waitForIdle()
        compose.onNodeWithTag("call-header").performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, completed) }
    }
}
