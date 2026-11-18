package com.messenger.app.ui.theme

import android.content.Context
import android.graphics.Color as PixelColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationBackgroundInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val dots = CocoPatternSpec(kind="dots",opacity=.22f,scale=32f,density="dense",stroke=1f,
        color=Color(0xFF777777),offsetX=0f,offsetY=0f,rotation=0f)

    @Test fun rasterMatchesEditorDotCentersStaggerAndRadius() {
        val image = CocoPatternRasterCache.image(dots)
        val bitmap = image.asAndroidBitmap()
        assertEquals(384, bitmap.width); assertEquals(384, bitmap.height)
        val rows = intArrayOf(32,96,160,224,288,352)
        rows.forEachIndexed { row, y ->
            val first = if(row%2==0)32 else 40
            repeat(6) { column ->
                val pixel = bitmap.getPixel(first+64*column,y)
                assertEquals(255,PixelColor.alpha(pixel));assertEquals(119,PixelColor.red(pixel))
            }
        }
        assertEquals(0,PixelColor.alpha(bitmap.getPixel(0,0)))
        assertEquals(0,PixelColor.alpha(bitmap.getPixel(32,96))) // Alternating rows shift by 7.68 raster px.
        assertEquals(0,PixelColor.alpha(bitmap.getPixel(36,32))) // 1.45 logical radius × 2.
        assertSame(image,CocoPatternRasterCache.image(dots.copy(opacity=.5f,scale=64f,offsetX=12f)))
    }

    @Test fun viewportAndFontResizeKeepSameRasterAnd96dpRepeat() {
        val source=CocoPatternRasterCache.image(dots)
        val height=mutableStateOf(200f)
        val fontScale=mutableStateOf(1f)
        compose.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalCocoPatternEnabled provides true,LocalCocoPatternSpec provides dots,
                LocalDensity provides Density(density,fontScale.value)) {
                Box(Modifier.width(240.dp).height(height.value.dp)
                    .cocoConversationBackground(CocoDarkColors.copy(background=Color(0xFF0F0F0F))).testTag("pattern"))
            }
        }
        val density=compose.activity.resources.displayMetrics.density
        val repeat=(96*density).roundToInt()
        val center=(8*density).roundToInt() // 16 / 192 of a 96dp tile.
        val before=compose.onNodeWithTag("pattern").captureToImage().toPixelMap()
        val expected=(119*.22f+15*.78f)/255f
        assertEquals(expected,before[center,center].red,.02f)
        assertEquals(before[center,center].red,before[center+repeat,center].red,.01f)
        compose.runOnIdle {height.value=160f;fontScale.value=2f}
        val after=compose.onNodeWithTag("pattern").captureToImage().toPixelMap()
        assertEquals(before[center,center].red,after[center,center].red,.001f)
        assertSame(source,CocoPatternRasterCache.image(dots))
    }

    @Test fun designAdoptionRunsOnceAndPreservesLaterUserToggleAndFontChoice() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="coco-pattern-migration-${UUID.randomUUID()}"
        val preferences=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        try {
            preferences.edit().putBoolean("subtle-pattern",false).putInt("chat-text-size",18).commit()
            adoptSavedCapsulePattern(preferences)
            assertEquals(CocoSavedPattern.enabledByDefault,preferences.getBoolean("subtle-pattern",false))
            assertEquals(CocoSavedPattern.defaultChatTextSize,preferences.getInt("chat-text-size",0))
            preferences.edit().putBoolean("subtle-pattern",false).putInt("chat-text-size",19).commit()
            adoptSavedCapsulePattern(preferences)
            assertFalse(preferences.getBoolean("subtle-pattern",true))
            assertEquals(19,preferences.getInt("chat-text-size",0))
            // An explicitly saved complete preset adopts its chosen font once,
            // including when replacing the earlier background-only revision.
            preferences.edit().putInt("capsule-pattern-spec-version",6)
                .putBoolean("subtle-pattern",true).putInt("chat-text-size",16).commit()
            adoptSavedCapsulePattern(preferences)
            assertEquals(CocoSavedPattern.enabledByDefault,preferences.getBoolean("subtle-pattern",true))
            assertEquals(CocoSavedPattern.defaultChatTextSize,preferences.getInt("chat-text-size",0))
            assertEquals(CocoSavedPattern.revision,preferences.getInt("capsule-pattern-spec-version",0))
            preferences.edit().putBoolean("subtle-pattern",true).putInt("chat-text-size",17).commit()
            adoptSavedCapsulePattern(preferences)
            assertTrue(preferences.getBoolean("subtle-pattern",false))
            assertEquals(17,preferences.getInt("chat-text-size",0))
        } finally {context.deleteSharedPreferences(name)}
    }
}
