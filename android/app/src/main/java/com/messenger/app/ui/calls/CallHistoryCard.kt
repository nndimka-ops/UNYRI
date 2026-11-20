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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.rotate
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
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            avatar?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chats_unknown_user), modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                    val yesterday = stringResource(R.string.history_list_yesterday)
                    val zone = java.time.ZoneId.systemDefault()
                    val date = androidx.compose.runtime.remember(call.createdAt, locale, zone, yesterday, java.time.LocalDate.now(zone)) {
                        auxiliaryListDate(call.createdAt, java.time.Instant.now(), zone, locale, yesterday)
                    }
                    Text(date, style = MaterialTheme.typography.labelSmall, color = colors.mutedText, maxLines = 1)
                }
                val kind = stringResource(if (call.mediaKind == "video") R.string.call_history_video else R.string.call_history_audio)
                val duration = callDurationSeconds(call)?.let { " · ${it / 60}:${(it % 60).toString().padStart(2, '0')}" }.orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(CocoIcons.ArrowBack, null, tint = if (outcome == CallHistoryOutcome.MISSED) colors.danger else colors.secondaryText,
                        modifier = Modifier.size(11.dp).rotate(if (call.callerUserId == owner) 135f else -45f))
                    Text(stringResource(label) + " · " + kind + duration, style = MaterialTheme.typography.bodySmall, color = if (outcome == CallHistoryOutcome.MISSED) colors.danger else colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
                    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                    val date = com.messenger.app.ui.chats.callEventTime(call.createdAt, java.time.ZoneId.systemDefault(), locale)
                    Text(date, style = MaterialTheme.typography.labelSmall, color = colors.mutedText)
                }
            }
        }
        }
    }
}

/** Same calendar boundaries as iOS RelativeTime.listLabel; no clock or animation loop. */
internal fun auxiliaryListDate(value: String, now: java.time.Instant, zone: java.time.ZoneId,
    locale: java.util.Locale, yesterday: String): String = runCatching {
    val date = OffsetDateTime.parse(value).atZoneSameInstant(zone)
    val today = now.atZone(zone).toLocalDate()
    val day = date.toLocalDate()
    if (day == today.minusDays(1)) yesterday else {
        val formatter = when {
            day == today -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            day >= today.minusDays(6) && date.toInstant() < now -> DateTimeFormatter.ofPattern("EEE")
            day.year == today.year -> DateTimeFormatter.ofPattern("d MMM")
            else -> DateTimeFormatter.ofPattern("dd.MM.yy")
        }
        date.format(formatter.withLocale(locale))
    }
}.getOrDefault("")
