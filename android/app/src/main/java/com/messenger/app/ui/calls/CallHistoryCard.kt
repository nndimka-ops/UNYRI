package com.messenger.app.ui.calls

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.messenger.app.R
import com.messenger.app.data.api.CallDto
import com.messenger.app.data.call.*
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.CocoIcons
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun CallHistoryCard(call: CallDto, owner: String, name: String? = null, listRow: Boolean = false, earlierHistory: Boolean = false, avatar: (@Composable () -> Unit)? = null, cardModifier: Modifier = Modifier, selectionMark: (@Composable () -> Unit)? = null, selected: Boolean = false) {
    val colors = LocalCocoColors.current
    val outcome = callOutcome(call, owner)
    val label = when (outcome) {
        CallHistoryOutcome.INCOMING -> R.string.call_history_incoming
        CallHistoryOutcome.OUTGOING -> R.string.call_history_outgoing
        CallHistoryOutcome.MISSED -> R.string.call_history_missed
        CallHistoryOutcome.CANCELLED -> if (call.endReason == "missed") R.string.call_history_no_answer else R.string.call_history_cancelled
        CallHistoryOutcome.REJECTED -> R.string.call_history_rejected
        CallHistoryOutcome.FAILED -> R.string.call_history_failed
    }
    if (listRow) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            avatar?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chats_unknown_user), style = MaterialTheme.typography.titleSmall, color = colors.primaryText, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                val duration = callDurationSeconds(call)?.let { " · ${it / 60}:${(it % 60).toString().padStart(2, '0')}" }.orEmpty()
                Text(stringResource(label) + duration, style = MaterialTheme.typography.bodySmall, color = if (outcome == CallHistoryOutcome.MISSED) colors.danger else colors.secondaryText)
                val date = runCatching { OffsetDateTime.parse(call.createdAt).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) }.getOrDefault("")
                Text(date, style = MaterialTheme.typography.labelSmall, color = colors.mutedText)
            }
        }
        return
    }
    val own = call.callerUserId == owner
    Box(Modifier.fillMaxWidth().padding(vertical = 5.dp), contentAlignment = if (own) Alignment.TopEnd else Alignment.TopStart) {
        com.messenger.app.ui.components.SelectionOverlayLayout(outgoing = own, selectionMark = selectionMark) {
        Surface(modifier = cardModifier.then(if (selectionMark != null) Modifier.clearAndSetSemantics {} else Modifier),
            color = if (selectionMark != null && selected) colors.deepPanel.copy(alpha = .86f) else colors.surfaceVariant,
            border = if (selectionMark != null && selected) BorderStroke(1.dp, colors.primaryText.copy(alpha = .3f)) else null,
            shape = RoundedCornerShape(16.dp)) {
            Row((if (listRow) Modifier.fillMaxWidth() else Modifier.widthIn(max = 300.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(CocoIcons.AudioCall, null, tint = if (outcome == CallHistoryOutcome.MISSED) colors.danger else colors.accent)
                Column(Modifier.weight(1f, fill = false)) {
                    if (earlierHistory) Text(stringResource(com.messenger.app.R.string.call_earlier_history), style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
                    if (name != null) Text(name, style = MaterialTheme.typography.titleSmall, color = colors.primaryText, maxLines = 1)
                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = colors.primaryText)
                    val kind = stringResource(if (call.mediaKind == "video") R.string.call_history_video else R.string.call_history_audio)
                    val duration = callDurationSeconds(call)?.let { " · ${it / 60}:${(it % 60).toString().padStart(2, '0')}" }.orEmpty()
                    Text(kind + duration, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                    val date = runCatching { OffsetDateTime.parse(call.createdAt).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) }.getOrDefault("")
                    Text(date, style = MaterialTheme.typography.labelSmall, color = colors.mutedText)
                }
            }
        }
        }
    }
}
