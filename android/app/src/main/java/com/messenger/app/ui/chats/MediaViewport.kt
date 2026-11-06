package com.messenger.app.ui.chats

/** Pan the fitted picture, not its letterboxed full-screen container. */
internal fun mediaPanBounds(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float, scale: Float): Pair<Float, Float> {
    if (listOf(viewWidth, viewHeight, imageWidth, imageHeight, scale).any { !it.isFinite() || it <= 0 }) return 0f to 0f
    val fit = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
    val zoom = scale.coerceIn(1f, 5f)
    return maxOf(0f, (imageWidth * fit * zoom - viewWidth) / 2f) to
        maxOf(0f, (imageHeight * fit * zoom - viewHeight) / 2f)
}
