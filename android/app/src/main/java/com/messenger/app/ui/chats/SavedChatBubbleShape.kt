package com.messenger.app.ui.chats

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * Matches the editor's CSS border-radius normalization: when any adjacent pair
 * overlaps, every radius is reduced by the same factor. CornerBasedShape instead
 * reduces the two vertical pairs independently, which changes short asymmetric
 * message silhouettes. The outline stays a native rounded rectangle.
 */
@Immutable
internal data class SavedChatBubbleShape(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
) : Shape {
    init {
        require(listOf(topStart, topEnd, bottomEnd, bottomStart).all { it.value.isFinite() && it.value >= 0f })
    }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val startTop = with(density) { topStart.toPx() }
        val endTop = with(density) { topEnd.toPx() }
        val endBottom = with(density) { bottomEnd.toPx() }
        val startBottom = with(density) { bottomStart.toPx() }
        val width = size.width.coerceAtLeast(0f)
        val height = size.height.coerceAtLeast(0f)
        fun fit(edge: Float, sum: Float) = if (sum > 0f) edge / sum else 1f
        val factor = minOf(
            minOf(1f, fit(width, startTop + endTop), fit(width, startBottom + endBottom)),
            fit(height, startTop + startBottom), fit(height, endTop + endBottom))
        fun radius(value: Float) = CornerRadius(value * factor, value * factor)
        val ltr = layoutDirection == LayoutDirection.Ltr
        return Outline.Rounded(RoundRect(
            left = 0f, top = 0f, right = width, bottom = height,
            topLeftCornerRadius = radius(if (ltr) startTop else endTop),
            topRightCornerRadius = radius(if (ltr) endTop else startTop),
            bottomRightCornerRadius = radius(if (ltr) endBottom else startBottom),
            bottomLeftCornerRadius = radius(if (ltr) startBottom else endBottom),
        ))
    }
}
