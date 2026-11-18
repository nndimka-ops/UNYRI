package com.messenger.app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.LocalCocoColors

/** Presentation only: retry/cancel retain the caller's outbox/selection policy. */
@Composable
internal fun PendingMessageFailureNotice(
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val label = stringResource(R.string.msg_status_failed)
    if (!compact) {
        MessageFailureNoticeContent(label, onRetry, onCancel, modifier)
        return
    }

    val colors = LocalCocoColors.current
    var expanded by remember { mutableStateOf(false) }
    // An overlay control has a small visible badge and a separate 48dp target.
    // Opening its anchored popup never changes the parent's media geometry.
    Box(modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)
            .semantics { contentDescription = label }) {
            Canvas(Modifier.size(24.dp).background(colors.elevated, CircleShape)) {
                drawLine(colors.danger, Offset(center.x, size.height * .23f), Offset(center.x, size.height * .54f),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(colors.danger, radius = 1.2.dp.toPx(), center = Offset(center.x, size.height * .76f))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 160.dp, max = 280.dp)) {
            Text(label, color = colors.secondaryText, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            DropdownMenuItem(text = { Text(stringResource(R.string.retry), color = colors.primaryText) },
                modifier = Modifier.heightIn(min = 48.dp), onClick = { expanded = false; onRetry() })
            DropdownMenuItem(text = { Text(stringResource(R.string.cancel), color = colors.secondaryText) },
                modifier = Modifier.heightIn(min = 48.dp), onClick = { expanded = false; onCancel() })
        }
    }
}

/** Shared static surface for failed sends and failed edits/deletes/reactions. */
@Composable
internal fun MessageFailureNoticeContent(
    label: String,
    onRetry: (() -> Unit)?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCocoColors.current
    Surface(modifier = modifier.fillMaxWidth(), color = colors.elevated,
        contentColor = colors.primaryText, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(label, color = colors.secondaryText, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onRetry != null) TextButton(onClick = onRetry,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.primaryText)) {
                    Text(stringResource(R.string.retry))
                }
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.secondaryText)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}
