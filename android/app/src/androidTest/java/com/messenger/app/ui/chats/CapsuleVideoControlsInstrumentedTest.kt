package com.messenger.app.ui.chats

import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoTheme
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real production controls over a source-less fake Player: no media, audio or network. */
@OptIn(ExperimentalTestApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CapsuleVideoControlsInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    private class Playback {
        var position=15_000L
        var volume=1f
        var speed=1f
        var plays=0
        val seeks=mutableListOf<Long>()
        val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)) {proxy,method,args->
            when(method.name) {
                "getDuration","getBufferedPosition"->60_000L
                "getCurrentPosition"->position
                "getVolume"->volume
                "getPlaybackParameters"->PlaybackParameters(speed)
                "getPlaybackState"->Player.STATE_READY
                "getPlayWhenReady","isPlaying"->false
                "getApplicationLooper"->Looper.getMainLooper()
                "addListener","removeListener"->null
                "seekTo"->{position=(args!!.last() as Number).toLong();seeks+=position;null}
                "play"->{plays++;null}
                "setVolume"->{volume=(args!![0] as Number).toFloat();null}
                "setPlaybackSpeed"->{speed=(args!![0] as Number).toFloat();null}
                "equals"->proxy===args?.firstOrNull()
                "hashCode"->System.identityHashCode(proxy)
                "toString"->"SourceLessCapsuleTestPlayer"
                else->when(method.returnType) {
                    java.lang.Boolean.TYPE->false
                    java.lang.Integer.TYPE->0
                    java.lang.Long.TYPE->0L
                    java.lang.Float.TYPE->0f
                    else->null
                }
            }
        } as Player
    }

    @Test fun narrow320dpWithLargeTextKeepsAllSixControlTargetsSeparate()=check(320f,380f,false)
    @Test fun shortLandscapeKeepsProgressAndControlsInsideTheViewport()=check(568f,320f,true)

    private fun check(width:Float,height:Float,landscape:Boolean) {
        val playback=Playback()
        var actions=0
        val scrubbing=mutableListOf<Boolean>()
        compose.setContent {CocoTheme {
            val actual=LocalConfiguration.current
            val density=LocalDensity.current
            val fit=minOf(1f,actual.screenWidthDp.toFloat()/width)
            val configuration=Configuration(actual).apply {
                orientation=if(landscape)Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                screenWidthDp=width.toInt();screenHeightDp=height.toInt()
            }
            CompositionLocalProvider(LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density*fit,fontScale=1.3f)) {
                Box(Modifier.width(width.dp).height(height.dp).background(Color.Black).testTag("video-control-fixture")) {
                    VideoPlaybackControls(playback.player,{},{scrubbing+=it},controlsPinned=true,actions={
                        IconButton(onClick={actions++},modifier=Modifier.size(48.dp)) {
                            Icon(MediaViewerIcons.Forward,compose.activity.getString(R.string.media_actions),tint=Color.White)
                        }
                    })
                }
            }
        }}
        val labels=listOf(R.string.media_actions,R.string.video_back_ten,R.string.play,
            R.string.video_forward_ten,R.string.video_speed,R.string.video_mute).map {compose.activity.getString(it)}
        fun button(label:String)=compose.onNode(hasContentDescription(label) and hasClickAction())
        val host=compose.onNodeWithTag("video-control-fixture").fetchSemanticsNode().boundsInRoot
        val bounds=labels.map {label->button(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot}
        fun inside(rect:Rect)=rect.left>=host.left-.5f&&rect.top>=host.top-.5f&&rect.right<=host.right+.5f&&rect.bottom<=host.bottom+.5f
        bounds.forEachIndexed {i,rect->assertTrue("Control outside viewport: ${labels[i]}",inside(rect))}
        for(i in bounds.indices)for(j in i+1 until bounds.size) {
            val a=bounds[i];val b=bounds[j]
            assertTrue("Overlapping control targets: ${labels[i]} / ${labels[j]}",
                a.right<=b.left+.5f||b.right<=a.left+.5f||a.bottom<=b.top+.5f||b.bottom<=a.top+.5f)
        }
        val progress=compose.onNodeWithTag("video-timebar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Progress is inside the viewer",inside(progress))
        assertTrue("Progress must not cover the button row",progress.bottom<=bounds.first().top+.5f)
        button(labels[0]).performTouchInput{click()}
        button(labels[1]).performTouchInput{click()}
        button(labels[3]).performTouchInput{click()}
        button(labels[2]).performTouchInput{click()}
        button(labels[4]).performTouchInput{click()}
        compose.onNodeWithText("1.5×").assertIsDisplayed().performTouchInput{click()}
        button(labels[5]).performTouchInput{click()}
        compose.runOnIdle {
            assertEquals(1,actions);assertEquals(listOf(5000L,15000L),playback.seeks)
            assertEquals(1,playback.plays);assertEquals(1.5f,playback.speed,0f);assertEquals(0f,playback.volume,0f)
        }
        val seekCount=playback.seeks.size
        compose.onNodeWithTag("video-timebar").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(this.width*.25f,this.height/2f))
            moveTo(androidx.compose.ui.geometry.Offset(this.width*.75f,this.height/2f),delayMillis=250)
        }
        compose.runOnIdle { assertEquals("Dragging locks the media pager",true,scrubbing.lastOrNull()) }
        compose.onNodeWithTag("video-timebar").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals("Releasing the native slider unlocks the media pager",listOf(true,false),scrubbing)
            assertEquals("The native slider commits one seek",seekCount+1,playback.seeks.size)
            assertTrue("Dragging seeks into the selected part of the clip",playback.seeks.last() in 30_000L..59_999L)
        }
        val folder=File(compose.activity.getExternalFilesDir(null),"capsules-uiqa").apply{mkdirs()}
        File(folder,"capsule-video-controls-${if(landscape)"landscape"else"320-large"}.png").outputStream().use {
            compose.onNodeWithTag("video-control-fixture").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
