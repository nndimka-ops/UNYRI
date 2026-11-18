package com.messenger.app.ui.theme

import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Checks retained source geometry, not GPU/frame claims or screen-size snapshots. */
@RunWith(AndroidJUnit4::class)
class CocoPatternRasterInstrumentedTest {
    private val coco = CocoPatternSpec(kind = "coco", opacity = .15f, scale = 32f,
        density = "dense", stroke = 1f, color = Color(0xFF777777),
        offsetX = 0f, offsetY = 0f, rotation = 0f)

    private fun inkNear(bitmap: Bitmap, x: Int, y: Int): Int =
        (y - 1..y + 1).maxOf { py ->
            (x - 1..x + 1).maxOf { px -> PixelColor.alpha(bitmap.getPixel(px, py)) }
        }

    @Test fun editorCocoSequenceUsesChatOrbitStarDiamondCrossAndOrbitAtExactCenters() {
        val bitmap = CocoPatternRasterCache.image(coco).asAndroidBitmap()
        assertEquals(384, bitmap.width)
        assertEquals(384, bitmap.height)
        // Six editor centers on the first dense row, with the same 2x source scale.
        arrayOf(12 to 32, 32 to 18, 22 to 54, 114 to 32, 160 to 14,
            224 to 14, 288 to 32, 352 to 50).forEach { (x, y) ->
            assertTrue("Missing editor outline near $x,$y", inkNear(bitmap, x, y) > 180)
        }
        // Shapes stay outlines; only the small cross reaches its center.
        intArrayOf(32, 96, 160, 224, 352).forEach { x ->
            assertEquals("Outline must stay hollow at $x,32", 0,
                PixelColor.alpha(bitmap.getPixel(x, 32)))
        }
        assertEquals(119, PixelColor.red(bitmap.getPixel(288, 32)))
        assertEquals(0, PixelColor.alpha(bitmap.getPixel(0, 0)))
    }

    @Test fun sparseEditorPresetUsesFourMotifsAndStaggersOnlySecondRow() {
        val bitmap = CocoPatternRasterCache.image(coco.copy(density = "sparse")).asAndroidBitmap()
        // Row 1: chat (48,48), orbit (144,48). Row 2 shifts by 96*.12.
        arrayOf(76 to 96, 86 to 118, 306 to 96, 119 to 270,
            311 to 270, 329 to 288).forEach { (x, y) ->
            assertTrue("Missing sparse editor geometry near $x,$y", inkNear(bitmap, x, y) > 150)
        }
        assertEquals(0, PixelColor.alpha(bitmap.getPixel(96, 96)))
        assertEquals(0, PixelColor.alpha(bitmap.getPixel(288, 96)))
        assertEquals(0, PixelColor.alpha(bitmap.getPixel(96, 288)))
        assertEquals(0, PixelColor.alpha(bitmap.getPixel(192, 192)))
    }

    @Test fun rotationIsLocalToEachMotifAndScaleOpacityAndOffsetReuseRaster() {
        val normal = coco.copy(density = "normal")
        val source = CocoPatternRasterCache.image(normal)
        val original = source.asAndroidBitmap()
        assertTrue(inkNear(original, 54, 86) > 150) // Original chat tail.
        assertSame(source, CocoPatternRasterCache.image(normal.copy(opacity = .4f,
            scale = 96f, offsetX = 17f, offsetY = -8f)))
        val rotated = CocoPatternRasterCache.image(normal.copy(rotation = 90f))
        assertNotSame(source, rotated)
        assertTrue(inkNear(rotated.asAndroidBitmap(), 42, 54) > 150)
        assertEquals(0, inkNear(rotated.asAndroidBitmap(), 54, 86))
        // A raster retained by another screen remains valid after the next design.
        assertTrue(inkNear(original, 54, 86) > 150)
    }
}
