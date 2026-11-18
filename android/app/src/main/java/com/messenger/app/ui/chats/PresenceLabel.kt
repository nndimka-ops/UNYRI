package com.messenger.app.ui.chats

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
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

/** Only calendar boundaries that can change today's/yesterday's/date/year text. DST uses local midnight. */
internal fun nextPresenceLabelChange(timestamp: String, now: Instant, zone: ZoneId): Long? = runCatching {
    val day = Instant.parse(timestamp).atZone(zone).toLocalDate()
    listOf(day, day.plusDays(1), day.plusDays(2), LocalDate.of(day.year, 1, 1), LocalDate.of(day.year + 1, 1, 1))
        .map { it.atStartOfDay(zone).toInstant() }.filter { it > now }.minOrNull()
        ?.let { Duration.between(now, it).toMillis().coerceAtLeast(1) }
}.getOrNull()

@Composable internal fun presenceLabel(value: PeerPresence?, lastConfirmedSeenAt: String? = null): String {
    if (value?.online == true) return stringResource(R.string.chat_peer_online)
    val last = value?.lastSeenAt ?: lastConfirmedSeenAt ?: return ""
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var timeRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(context, lifecycle) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) timeRevision++
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            addAction(android.content.Intent.ACTION_DATE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val zone = ZoneId.systemDefault()
    var now by remember(last, zone) { mutableStateOf(Instant.now()) }
    LaunchedEffect(last, zone, lifecycle, timeRevision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val current = Instant.now()
                now = current
                val wait = nextPresenceLabelChange(last, current, zone) ?: break
                delay(wait)
            }
        }
    }
    val result = presenceDate(last, now, zone, locale) ?: return ""
    return stringResource(when(result.first) { 0 -> R.string.presence_today; 1 -> R.string.presence_yesterday; else -> R.string.presence_date }, result.second)
}
