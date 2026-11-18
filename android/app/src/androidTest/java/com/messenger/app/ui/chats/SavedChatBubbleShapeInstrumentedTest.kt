package com.messenger.app.ui.chats

import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic geometry only: no accounts, network, rendering loop or device state. */
@RunWith(AndroidJUnit4::class)
class SavedChatBubbleShapeInstrumentedTest {
    private val incoming = SavedChatBubbleShape(42.dp, 42.dp, 42.dp, 0.dp)
    private val outgoing = SavedChatBubbleShape(42.dp, 42.dp, 0.dp, 42.dp)

    private fun outline(shape: SavedChatBubbleShape, width: Float, height: Float,
        direction: LayoutDirection = LayoutDirection.Ltr, density: Float = 1f): RoundRect =
        (shape.createOutline(Size(width, height), direction, Density(density)) as Outline.Rounded).roundRect

    @Test fun shortAsymmetricBodiesUseOneUniformCssFactorAndMirrorExactly() {
        val left = outline(incoming, 195f, 32f)
        val right = outline(outgoing, 195f, 32f)
        // 32 / (42 + 42): all three rounded corners must become 16, including
        // the corner opposite the zero-radius end. No independent 32px corner.
        assertEquals(16f, left.topLeftCornerRadius.x, .001f)
        assertEquals(16f, left.topRightCornerRadius.x, .001f)
        assertEquals(16f, left.bottomRightCornerRadius.x, .001f)
        assertEquals(0f, left.bottomLeftCornerRadius.x, .001f)
        assertEquals(left.topLeftCornerRadius, right.topRightCornerRadius)
        assertEquals(left.topRightCornerRadius, right.topLeftCornerRadius)
        assertEquals(left.bottomLeftCornerRadius, right.bottomRightCornerRadius)
        assertEquals(left.bottomRightCornerRadius, right.bottomLeftCornerRadius)
    }

    @Test fun horizontalAndVerticalOverlapsScaleEveryCornerByTheSmallestFactor() {
        val shape = SavedChatBubbleShape(80.dp, 20.dp, 10.dp, 40.dp)
        val heightLimited = outline(shape, 90f, 100f)
        assertEquals(80f * 100f / 120f, heightLimited.topLeftCornerRadius.x, .001f)
        assertEquals(20f * 100f / 120f, heightLimited.topRightCornerRadius.x, .001f)
        assertEquals(10f * 100f / 120f, heightLimited.bottomRightCornerRadius.x, .001f)
        assertEquals(40f * 100f / 120f, heightLimited.bottomLeftCornerRadius.x, .001f)
        val widthLimited = outline(shape, 90f, 200f)
        assertEquals(72f, widthLimited.topLeftCornerRadius.x, .001f)
        assertEquals(18f, widthLimited.topRightCornerRadius.x, .001f)
        assertEquals(9f, widthLimited.bottomRightCornerRadius.x, .001f)
        assertEquals(36f, widthLimited.bottomLeftCornerRadius.x, .001f)
    }

    @Test fun relativeCornersMirrorInRtlAndRespectPixelDensity() {
        val shape = SavedChatBubbleShape(8.dp, 20.dp, 10.dp, 4.dp)
        val ltr = outline(shape, 140f, 120f, density = 2f)
        val rtl = outline(shape, 140f, 120f, LayoutDirection.Rtl, 2f)
        assertEquals(16f, ltr.topLeftCornerRadius.x, .001f)
        assertEquals(40f, ltr.topRightCornerRadius.x, .001f)
        assertEquals(20f, ltr.bottomRightCornerRadius.x, .001f)
        assertEquals(8f, ltr.bottomLeftCornerRadius.x, .001f)
        assertEquals(ltr.topLeftCornerRadius, rtl.topRightCornerRadius)
        assertEquals(ltr.topRightCornerRadius, rtl.topLeftCornerRadius)
        assertEquals(ltr.bottomLeftCornerRadius, rtl.bottomRightCornerRadius)
        assertEquals(ltr.bottomRightCornerRadius, rtl.bottomLeftCornerRadius)
    }

    @Test fun ampleHeightPreservesSaved42dpAndEmptyBodiesRemainFinite() {
        val full = outline(incoming, 195f, 100f)
        assertEquals(42f, full.topLeftCornerRadius.x, .001f)
        assertEquals(42f, full.topRightCornerRadius.x, .001f)
        assertEquals(42f, full.bottomRightCornerRadius.x, .001f)
        assertEquals(0f, full.bottomLeftCornerRadius.x, .001f)
        val empty = outline(incoming, 0f, 0f)
        assertEquals(0f, empty.width, 0f)
        assertEquals(0f, empty.height, 0f)
        assertEquals(0f, empty.topLeftCornerRadius.x, 0f)
        assertEquals(0f, empty.topRightCornerRadius.x, 0f)
        assertEquals(0f, empty.bottomRightCornerRadius.x, 0f)
        assertEquals(0f, empty.bottomLeftCornerRadius.x, 0f)
    }
}
