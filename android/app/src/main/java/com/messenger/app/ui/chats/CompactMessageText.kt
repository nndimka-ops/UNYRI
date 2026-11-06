package com.messenger.app.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/**
 * Measures message text and its receipt exactly once in the same layout pass.
 * A one-line message shares a row with metadata only when both naturally fit.
 * Wrapping text keeps its full contents and puts metadata beneath it instead.
 * The caller owns body padding, surrounding quotes/reactions and visibility.
 */
@Composable
internal fun CompactMessageText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    isOwnMessage: Boolean,
    inlineGap: Dp = 6.dp,
    belowGap: Dp = 2.dp,
    modifier: Modifier = Modifier,
    metadata: @Composable () -> Unit,
) {
    // Chat bodies are width-bounded. Keep the component safe in an unbounded
    // preview/container too, without an intrinsic or asynchronous second pass.
    val screenWidth = LocalConfiguration.current.screenWidthDp.coerceAtLeast(1).dp
    val fallbackMaxWidth = with(LocalDensity.current) { screenWidth.roundToPx().coerceAtLeast(1) }
    Layout(
        modifier = modifier,
        content = {
            Text(text = text, style = style, color = color)
            Box { metadata() }
        },
    ) { measurables, constraints ->
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else fallbackMaxWidth
        val childConstraints = constraints.copy(minWidth = 0, maxWidth = maxWidth, minHeight = 0)
        val message = measurables[0].measure(childConstraints)
        val receipt = measurables[1].measure(childConstraints)
        val first = message[FirstBaseline]
        val last = message[LastBaseline]
        val oneLine = text.text.none { it == '\n' || it == '\r' } &&
            first != AlignmentLine.Unspecified && last != AlignmentLine.Unspecified && first == last
        val rowGap = inlineGap.roundToPx().coerceAtLeast(0)
        val columnGap = belowGap.roundToPx().coerceAtLeast(0)
        val inline = oneLine && message.width.toLong() + rowGap + receipt.width <= maxWidth.toLong()
        val width = constraints.constrainWidth(
            if (inline) message.width + rowGap + receipt.width else maxOf(message.width, receipt.width),
        )
        val height = constraints.constrainHeight(
            if (inline) maxOf(message.height, receipt.height) else message.height + columnGap + receipt.height,
        )
        layout(width, height) {
            if (inline) {
                message.placeRelative(0, (height - message.height) / 2)
                receipt.placeRelative(message.width + rowGap, (height - receipt.height) / 2)
            } else {
                message.placeRelative(0, 0)
                receipt.placeRelative(if (isOwnMessage) width - receipt.width else 0, message.height + columnGap)
            }
        }
    }
}
