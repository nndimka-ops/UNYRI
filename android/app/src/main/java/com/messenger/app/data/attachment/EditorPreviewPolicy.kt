package com.messenger.app.data.attachment

internal const val EDITOR_PREVIEW_EDGE = 2560
internal enum class ImagePreviewPurpose { CHAT, EDITOR }
internal data class ImagePreviewPlan(val edge: Int, val sampleSize: Int, val outputWidth: Int, val outputHeight: Int)
internal fun imagePreviewPlan(width: Int, height: Int, purpose: ImagePreviewPurpose, requestedEdge: Int = 768): ImagePreviewPlan {
    require(width > 0 && height > 0 && width.toLong() * height <= 150_000_000L)
    val edge = if (purpose == ImagePreviewPurpose.EDITOR) EDITOR_PREVIEW_EDGE else requestedEdge.coerceIn(256, 768)
    var sample = 1
    while (width / sample / 2 >= edge || height / sample / 2 >= edge) sample *= 2
    val scale = minOf(1.0, edge.toDouble() / maxOf(width, height))
    return ImagePreviewPlan(edge, sample, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
}
