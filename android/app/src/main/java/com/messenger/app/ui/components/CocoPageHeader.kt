package com.messenger.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.LocalCocoColors

/**
 * Inset-free page heading. Equal space on both sides keeps the title centered
 * in the whole page, even when only one side has controls. Callers own every
 * navigation guard, action, and system inset. Text may wrap at large font sizes.
 */
@Composable
internal fun CocoPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    titleColor: Color = LocalCocoColors.current.primaryText,
    titleFontWeight: FontWeight? = null,
    start: @Composable () -> Unit = {},
    end: @Composable () -> Unit = {},
) {
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) { start() }
            Text(
                text = title,
                style = titleStyle,
                color = titleColor,
                fontWeight = titleFontWeight,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }.testTag("coco-page-title"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) { end() }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leading = measurables[0].measure(loose)
        val trailing = measurables[2].measure(loose)
        val gap = 8.dp.roundToPx()
        val symmetricSide = maxOf(leading.width, trailing.width)
        val inlineTitleWidth = (width - 2 * (symmetricSide + if (symmetricSide > 0) gap else 0)).coerceAtLeast(0)
        // Wide translated actions get their own row instead of covering the
        // heading or shrinking Back/Save/Delete below their existing targets.
        val actionsBelow = trailing.width > 0 && inlineTitleWidth < 112.dp.roundToPx()
        val titleSide = if (actionsBelow) leading.width else symmetricSide
        val titleWidth = (width - 2 * (titleSide + if (titleSide > 0) gap else 0)).coerceAtLeast(0)
        val heading = measurables[1].measure(loose.copy(minWidth = titleWidth, maxWidth = titleWidth))
        val rowHeight = maxOf(leading.height, heading.height, if (actionsBelow) 0 else trailing.height)
        val actionHeight = if (actionsBelow) gap + trailing.height else 0
        layout(width, constraints.constrainHeight(rowHeight + actionHeight)) {
            leading.placeRelative(0, (rowHeight - leading.height) / 2)
            heading.placeRelative((width - heading.width) / 2, (rowHeight - heading.height) / 2)
            trailing.placeRelative(width - trailing.width,
                if (actionsBelow) rowHeight + gap else (rowHeight - trailing.height) / 2)
        }
    }
}
