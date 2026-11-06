package com.messenger.app.ui.call

/**
 * Pure, deterministic layout policy for the COCO Glass Flow call UI
 * (Этап Phase 4 §41/§42). All values are in dp; no Android framework types
 * are referenced so the functions are directly unit-testable on the JVM.
 *
 * The policy collapses the scattered "magic" widths/heights that used to live
 * inside the call composables into a single source of truth:
 *  - avatar adapts to available height (104–136) and is compact (96) on video;
 *  - control circles adapt to available width + control count (52–64);
 *  - the local video preview is 26–31% of width, clamped 96–150, 4:3 aspect.
 */
object CallLayoutPolicy {

    const val MIN_AVATAR = 104f
    const val MAX_AVATAR = 136f
    const val VIDEO_AVATAR = 96f

    const val CONTROL_MIN = 52f
    const val CONTROL_MAX = 64f
    const val CONTROL_TOUCH_MIN = 48f

    const val PREVIEW_MIN = 96f
    const val PREVIEW_MAX = 150f
    const val PREVIEW_WIDTH_RATIO = 0.28f
    const val PREVIEW_ASPECT = 4f / 3f

    const val TRAY_MAX_WIDTH = 420f
    const val TRAY_MARGIN_NARROW = 8f
    const val TRAY_MARGIN_STANDARD = 16f
    const val TRAY_PADDING_NARROW = 4f
    const val TRAY_PADDING_STANDARD = 16f

    const val CALL_NAME_MAX_LINES = 2
    const val USERNAME_MAX_LINES = 1

    /** Avatar diameter for the audio identity. Scales with the available
     *  height between [MIN_AVATAR] and [MAX_AVATAR]; video placeholders use a
     *  fixed compact [VIDEO_AVATAR] so they never crowd the video surface. */
    fun avatarSize(availableHeightDp: Float, isVideo: Boolean): Float {
        if (isVideo) return VIDEO_AVATAR
        val target = availableHeightDp * 0.22f
        return target.coerceIn(MIN_AVATAR, MAX_AVATAR)
    }

    /** Control-circle diameter. Computes the largest circle that lets
     *  [controlCount] controls plus spacing fit inside the floating tray's
     *  inner width (tray minus its internal padding), then clamps to the
     *  52–64 preferred range, retaining a native 48dp touch target in narrow
     *  windows. The tray wraps when even that native target cannot fit. */
    fun controlSize(availableWidthDp: Float, controlCount: Int): Float {
        val spacing = controlSpacing(availableWidthDp, controlCount)
        val inner = trayWidth(availableWidthDp) - 2f * trayPadding(availableWidthDp)
        val fit = (inner - spacing * (controlCount - 1)) / controlCount
        return fit.coerceIn(CONTROL_TOUCH_MIN, CONTROL_MAX)
    }

    /** Inter-control gap. Tight on very narrow screens so five controls always
     *  fit inside the tray, relaxed on wider screens. */
    fun controlSpacing(availableWidthDp: Float, controlCount: Int): Float = when {
        availableWidthDp < 300f -> 4f
        availableWidthDp < 400f -> 6f
        else -> 12f
    }

    /** Whether call controls render a visible caption under the icon. Captions
     *  are always clamped to the control's own width (truncating with an
     *  ellipsis, never overlapping), so they are shown whenever the control
     *  circle itself is large enough to read — i.e. whenever the 52–64 circle
     *  fits. Accessibility labels stay fully descriptive regardless. */
    fun showCallCaptions(availableWidthDp: Float, controlCount: Int): Boolean =
        controlSize(availableWidthDp, controlCount) >= CONTROL_CAPTION_MIN_WIDTH

    const val CONTROL_CAPTION_MIN_WIDTH = 52f

    /** Local video preview size as (width, height). Width is ~26–31% of the
     *  screen width (0.28 ratio), clamped to [PREVIEW_MIN]–[PREVIEW_MAX];
     *  height keeps a 4:3 camera aspect. */
    fun localPreviewSize(availableWidthDp: Float): Pair<Float, Float> {
        val width = (availableWidthDp * PREVIEW_WIDTH_RATIO).coerceIn(PREVIEW_MIN, PREVIEW_MAX)
        return width to width * PREVIEW_ASPECT
    }

    /** Fit the preview above controls even in landscape or split-screen. */
    fun localPreviewSize(availableWidthDp: Float, availableHeightDp: Float): Pair<Float, Float> {
        val regular = localPreviewSize(availableWidthDp)
        val height = regular.second.coerceAtMost(availableHeightDp.coerceAtLeast(1f))
        val width = (height / PREVIEW_ASPECT).coerceAtMost(availableWidthDp.coerceAtLeast(1f))
        return width to width * PREVIEW_ASPECT
    }

    fun resizedPreviewSize(width: Float, height: Float, scale: Float): Pair<Float, Float> {
        val fit = minOf(width.coerceAtLeast(1f), height.coerceAtLeast(1f) / PREVIEW_ASPECT)
        val maximum = minOf(240f, fit * 0.7f).coerceAtLeast(1f)
        val minimum = minOf(PREVIEW_MIN, maximum)
        val base = localPreviewSize(width, height).first
        val target = (base * scale).coerceIn(minimum, maximum)
        return target to target * PREVIEW_ASPECT
    }

    /** Final rendered dimensions, after resizing; never expose a tiny drag target. */
    fun canShowPreview(renderedWidthDp: Float, renderedHeightDp: Float): Boolean =
        renderedWidthDp.isFinite() && renderedHeightDp.isFinite() &&
            renderedWidthDp >= CONTROL_TOUCH_MIN && renderedHeightDp >= CONTROL_TOUCH_MIN

    /** Keep a movable camera out of the minimize hit region, including after
     *  resizing/rotation. Return no origin if neither safe side can fit. */
    fun previewOrigin(x: Float, y: Float, maxX: Float, maxY: Float, margin: Float,
                      headerRight: Float, headerBottom: Float): Pair<Float, Float>? {
        val left = x.coerceIn(margin, maxX.coerceAtLeast(margin))
        val top = y.coerceIn(margin, maxY.coerceAtLeast(margin))
        if (left >= headerRight || top >= headerBottom) return left to top
        val rightFits = maxX >= headerRight
        val belowFits = maxY >= headerBottom
        return when {
            belowFits && (!rightFits || headerBottom - top <= headerRight - left) -> left to headerBottom
            rightFits -> headerRight to top
            else -> null
        }
    }

    /** Horizontal margin flanking the control tray (narrower on small screens). */
    fun trayMargin(availableWidthDp: Float): Float = when {
        availableWidthDp < 400f -> TRAY_MARGIN_NARROW
        else -> TRAY_MARGIN_STANDARD
    }

    /** Internal horizontal padding of the control tray. */
    fun trayPadding(availableWidthDp: Float): Float = when {
        availableWidthDp < 400f -> TRAY_PADDING_NARROW
        else -> TRAY_PADDING_STANDARD
    }

    /** Control tray width: never stretched beyond [TRAY_MAX_WIDTH] on tablets,
     *  never wider than the screen minus its margins. */
    fun trayWidth(availableWidthDp: Float): Float =
        (availableWidthDp - 2f * trayMargin(availableWidthDp)).coerceAtMost(TRAY_MAX_WIDTH)
}
