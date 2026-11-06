package com.messenger.app.data.avatar

/**
 * Pure crop geometry for the square avatar crop UI. No Android types - JVM
 * testable and deliberately separate from the Bitmap layer so the pan/zoom ->
 * source-rectangle mapping is provable without an emulator.
 *
 * Model: the source bitmap (w x h) is displayed inside a square viewport of
 * side [viewport], scaled by a total scale [scale] that always covers the
 * viewport (scale >= coverScale), and panned by ([tx], [ty]) pixels relative to
 * the centered position. The visible square maps back to a square region of the
 * source, which is exactly what gets cropped and resized to 512x512.
 */
object AvatarCropMath {

    /** Minimum scale at which the bitmap fully covers the square viewport. */
    fun coverScale(w: Int, h: Int, viewport: Float): Float {
        require(w > 0 && h > 0 && viewport > 0f)
        return maxOf(viewport / w, viewport / h)
    }

    /** Maximum safe pan on each axis before a gap would appear (image edge
     *  retreating inside the viewport). Always >= 0. */
    fun maxPanX(w: Int, viewport: Float, scale: Float): Float = ((w * scale - viewport) / 2f).coerceAtLeast(0f)
    fun maxPanY(h: Int, viewport: Float, scale: Float): Float = ((h * scale - viewport) / 2f).coerceAtLeast(0f)

    /** The square source-pixel rectangle currently visible, clamped inside the
     *  source. The returned size is always square and >= 1. */
    fun cropRect(w: Int, h: Int, viewport: Float, scale: Float, tx: Float, ty: Float): CropRect {
        require(scale > 0f)
        val side = (viewport / scale).coerceAtMost(w.toFloat()).coerceAtMost(h.toFloat())
        val left = ((w - side) / 2f - tx / scale).coerceIn(0f, (w - side).coerceAtLeast(0f))
        val top = ((h - side) / 2f - ty / scale).coerceIn(0f, (h - side).coerceAtLeast(0f))
        return CropRect(left.toInt(), top.toInt(), side.toInt().coerceAtLeast(1))
    }
}

/** A square crop rectangle in source-pixel coordinates. */
data class CropRect(val x: Int, val y: Int, val size: Int)
