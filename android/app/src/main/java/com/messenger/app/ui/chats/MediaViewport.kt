package com.messenger.app.ui.chats

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

internal fun viewerDragFraction(distance: Float, height: Float): Float =
    if (!distance.isFinite() || !height.isFinite() || height <= 0f) 0f else (abs(distance) / (height * .45f)).coerceIn(0f, 1f)

internal fun viewerVideoDragFraction(distanceDp: Float): Float =
    if (!distanceDp.isFinite()) 0f else (abs(distanceDp) / 450f).coerceIn(0f, 1f)

/** Distance/height in dp and velocity in dp/second, matching the reference's point units. */
internal fun viewerDismisses(distance: Float, velocity: Float, height: Float): Boolean =
    distance.isFinite() && velocity.isFinite() && height.isFinite() && height > 0f &&
        (abs(distance) > height * .15f || (abs(distance) > 24f && abs(velocity) > 900f && distance * velocity > 0f))

internal fun viewerFitRect(width: Float, height: Float, imageWidth: Float, imageHeight: Float): Rect? {
    if (listOf(width, height, imageWidth, imageHeight).any { !it.isFinite() || it <= 0f }) return null
    val fit = minOf(width / imageWidth, height / imageHeight)
    val w = imageWidth * fit; val h = imageHeight * fit
    return Rect((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
}

internal fun viewerTransformedRect(fit: Rect, width: Float, height: Float, scale: Float, x: Float, y: Float): Rect =
    Rect((fit.left - width / 2) * scale + width / 2 + x,
        (fit.top - height / 2) * scale + height / 2 + y,
        (fit.right - width / 2) * scale + width / 2 + x,
        (fit.bottom - height / 2) * scale + height / 2 + y)

/** Clipped or off-screen tiles fade instead of flying through the header/composer. */
internal fun viewerUsableSource(bounds: Rect, width: Float, height: Float): Boolean =
    listOf(bounds.left, bounds.top, bounds.right, bounds.bottom, width, height).all { it.isFinite() } &&
        bounds.width > 1 && bounds.height > 1 && bounds.left >= 0 && bounds.top >= 0 &&
        bounds.right <= width + 1 && bounds.bottom <= height + 1

/** Pan the fitted picture, not its letterboxed full-screen container. */
internal fun mediaPanBounds(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float, scale: Float): Pair<Float, Float> {
    if (listOf(viewWidth, viewHeight, imageWidth, imageHeight, scale).any { !it.isFinite() || it <= 0 }) return 0f to 0f
    val fit = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
    val zoom = scale.coerceIn(1f, 5f)
    return maxOf(0f, (imageWidth * fit * zoom - viewWidth) / 2f) to
        maxOf(0f, (imageHeight * fit * zoom - viewHeight) / 2f)
}
