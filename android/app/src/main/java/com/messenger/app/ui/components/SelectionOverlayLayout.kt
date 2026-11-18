package com.messenger.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/** The filled message body supplies this line; receipts and reactions remain outside it. */
private val MessageBodyCenter = HorizontalAlignmentLine { first, second -> minOf(first, second) }

internal fun Modifier.messageSelectionBodyCenter(): Modifier = layout { measurable, constraints ->
    val body = measurable.measure(constraints)
    layout(body.width, body.height, mapOf(MessageBodyCenter to body.height / 2)) {
        body.placeRelative(0, 0)
    }
}

/**
 * Measures message content exactly as usual, then places its independent 48dp
 * selection target. A wide bubble may leave less than 48dp beside it; clamp the
 * target into the actual row instead of clipping it or moving the message.
 */
@Composable
internal fun SelectionOverlayLayout(
    outgoing: Boolean,
    markerInside: Boolean = false,
    selectionMark: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Box { content() }
            if (selectionMark != null) Box(contentAlignment = Alignment.Center) { selectionMark() }
        },
    ) { measurables, constraints ->
        val body = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else body.width
        val height = constraints.constrainHeight(body.height)
        val marker = measurables.getOrNull(1)?.measure(Constraints.fixed(48.dp.roundToPx(), 48.dp.roundToPx()))
        val bodyCenter = body[MessageBodyCenter].takeUnless { it == AlignmentLine.Unspecified } ?: (body.height / 2)
        val bodyX = if (outgoing) width - body.width else 0
        layout(width, height) {
            body.placeRelative(bodyX, 0)
            marker?.let {
                val requestedX = when {
                    markerInside -> bodyX + body.width - it.width
                    outgoing -> bodyX - it.width
                    else -> bodyX + body.width
                }
                // The target may extend beyond a short body, without adding height to the row.
                val markerY = bodyCenter - it.height / 2
                it.placeRelative(requestedX.coerceIn(0, (width - it.width).coerceAtLeast(0)), markerY)
            }
        }
    }
}
