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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.LocalCocoColors

/**
 * Inset-free heading. Prefer page centering, then the space between controls,
 * then a full-width title above the controls, according to its longest word.
 * Callers own navigation guards, actions and insets. Titles wrap between words.
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
        val placement = pageHeaderPlacement(
            width, leading.width, trailing.width, gap,
            measurables[1].minIntrinsicWidth(Constraints.Infinity),
        )
        val actionsBelow = placement.mode == PageHeaderMode.ActionsBelow
        val heading = measurables[1].measure(loose.copy(minWidth = placement.titleWidth, maxWidth = placement.titleWidth))
        val rowHeight = if (actionsBelow) heading.height else maxOf(leading.height, heading.height, trailing.height)
        val actionHeight = if (actionsBelow) gap + maxOf(leading.height, trailing.height) else 0
        layout(width, constraints.constrainHeight(rowHeight + actionHeight)) {
            leading.placeRelative(0, if (actionsBelow) rowHeight + gap else (rowHeight - leading.height) / 2)
            heading.placeRelative(placement.titleStart, (rowHeight - heading.height) / 2)
            trailing.placeRelative(width - trailing.width,
                if (actionsBelow) rowHeight + gap else (rowHeight - trailing.height) / 2)
        }
    }
}
