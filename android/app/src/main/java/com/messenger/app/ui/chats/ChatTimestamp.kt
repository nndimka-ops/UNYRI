package com.messenger.app.ui.chats

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun chatTimestamp(value: String, now: Instant, zone: ZoneId, locale: Locale): String? = runCatching {
    val date = OffsetDateTime.parse(value).atZoneSameInstant(zone)
    val formatter = if (date.toLocalDate() == now.atZone(zone).toLocalDate()) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    } else DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    date.format(formatter.withLocale(locale))
}.getOrNull()
