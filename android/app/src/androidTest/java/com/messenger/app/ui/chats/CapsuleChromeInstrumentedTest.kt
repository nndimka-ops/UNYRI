package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CapsuleChromeInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun approvedGradientFadesInBothDirectionsWithoutInterceptingUnderlyingTouches() {
        var taps=0
        var density=1f
        compose.setContent {
            density=LocalDensity.current.density
            Row {
            Box(Modifier.size(100.dp,150.dp).background(Color.White).clickable {taps++}.testTag("top-scrim-canvas")) {
                Box(Modifier.size(100.dp,120.dp).clickable {taps++}
                    .capsuleChromeScrim(top=true).testTag("top-scrim"))
            }
            Box(Modifier.size(100.dp,120.dp).background(Color.White).clickable {taps++}
                .capsuleChromeScrim(top=false).testTag("bottom-scrim"))
        }}
        val top=compose.onNodeWithTag("top-scrim-canvas").captureToImage().toPixelMap()
        val bottom=compose.onNodeWithTag("bottom-scrim").captureToImage().toPixelMap()
        val x=top.width/2
        val headerHeight=compose.onNodeWithTag("top-scrim").fetchSemanticsNode().boundsInRoot.height.roundToInt()
        fun topRed(dpBelow:Float)=top[x,(headerHeight+dpBelow*density).roundToInt()].red
        assertTrue(top[x,1].red<.03f)
        assertTrue(top[x,headerHeight/2].red<.03f)
        assertTrue(top[x,headerHeight-2].red<.03f)
        assertEquals(.25f,topRed(5.5f),.04f)
        assertEquals(.5f,topRed(11f),.04f)
        assertEquals(.75f,topRed(16.5f),.04f)
        assertTrue(topRed(25f)>.97f)
        assertTrue(bottom[x,1].red>.97f)
        assertEquals(1f/3f,bottom[x,bottom.height/2].red,.02f)
        assertTrue(bottom[x,bottom.height-2].red<.09f)
        compose.onNodeWithTag("top-scrim").performTouchInput {click()}
        compose.onNodeWithTag("bottom-scrim").performTouchInput {click()}
        compose.runOnIdle {assertEquals(2,taps)}
        compose.onNodeWithTag("top-scrim-canvas").performTouchInput {
            click(Offset(center.x,headerHeight+11f*density))
        }
        compose.runOnIdle {assertEquals("The drawn fade must not intercept the content below the header",3,taps)}
    }
}
