package com.messenger.app.data.avatar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvatarCropMathTest {

    @Test
    fun `cover scale covers both axes`() {
        // Portrait 1000x2000 into a 500 viewport: cover = max(0.5, 0.25) = 0.5
        assertEquals(0.5f, AvatarCropMath.coverScale(1000, 2000, 500f))
        // Landscape 2000x1000: cover = max(0.25, 0.5) = 0.5
        assertEquals(0.5f, AvatarCropMath.coverScale(2000, 1000, 500f))
    }

    @Test
    fun `crop rect is square`() {
        val rect = AvatarCropMath.cropRect(2000, 1000, 500f, 0.5f, 0f, 0f)
        assertEquals(500f / 0.5f, rect.size.toFloat(), "crop side = viewport / scale")
    }

    @Test
    fun `centered crop takes the middle square`() {
        // 2000x2000 source, viewport 500, cover scale = 500/2000 = 0.25.
        val rect = AvatarCropMath.cropRect(2000, 2000, 500f, 0.25f, 0f, 0f)
        assertEquals(2000, rect.size, "full source is exactly the square")
        assertEquals(0, rect.x)
        assertEquals(0, rect.y)
    }

    @Test
    fun `panning shifts the crop but stays clamped inside`() {
        // 2000x2000 source, viewport 1000, cover = 0.5, so side = 2000.
        // Panning does nothing meaningful (already full) - but a zoomed-in
        // crop (scale 1.0) with pan must stay inside the source.
        val rect = AvatarCropMath.cropRect(2000, 2000, 1000f, 1.0f, 400f, 300f)
        assertEquals(1000, rect.size)
        assertTrue(rect.x >= 0 && rect.x + rect.size <= 2000, "crop clamped in x")
        assertTrue(rect.y >= 0 && rect.y + rect.size <= 2000, "crop clamped in y")
    }

    @Test
    fun `max pan grows with zoom`() {
        // At cover scale the image exactly fills one axis, so pan along the
        // OTHER axis is allowed, and both grow as we zoom in.
        val base = AvatarCropMath.maxPanX(2000, 1000f, 0.5f)
        val zoomed = AvatarCropMath.maxPanX(2000, 1000f, 1.0f)
        assertTrue(zoomed > base, "zooming in allows more pan")
    }
}
