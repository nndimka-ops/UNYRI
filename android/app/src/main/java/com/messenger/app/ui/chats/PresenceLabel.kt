package com.messenger.app.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.messenger.app.R
import com.messenger.app.data.websocket.PeerPresence
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun presenceDate(timestamp: String, now: Instant, zone: ZoneId, locale: Locale): Pair<Int, String>? {
    val date = runCatching { Instant.parse(timestamp).atZone(zone) }.getOrNull() ?: return null
    val today = now.atZone(zone).toLocalDate()
    val day = date.toLocalDate()
    val pattern = when { day == today || day == today.minusDays(1) -> "HH:mm"; day.year == today.year -> "d MMM, HH:mm"; else -> "d MMM yyyy, HH:mm" }
    return (when(day) { today -> 0; today.minusDays(1) -> 1; else -> 2 }) to date.format(DateTimeFormatter.ofPattern(pattern, locale))
}
@Composable internal fun presenceLabel(value: PeerPresence?, lastConfirmedSeenAt: String? = null): String {
    if (value?.online == true) return stringResource(R.string.chat_peer_online)
    val last = value?.lastSeenAt ?: lastConfirmedSeenAt ?: return ""
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val result = presenceDate(last, Instant.now(), ZoneId.systemDefault(), locale) ?: return ""
    return stringResource(when(result.first) { 0 -> R.string.presence_today; 1 -> R.string.presence_yesterday; else -> R.string.presence_date }, result.second)
}
