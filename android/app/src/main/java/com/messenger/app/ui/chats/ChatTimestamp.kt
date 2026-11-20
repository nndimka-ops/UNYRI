package com.messenger.app.ui.chats

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun chatTimestamp(value: String, now: Instant, zone: ZoneId, locale: Locale, yesterday: String): String? =
    com.messenger.app.ui.calls.auxiliaryListDate(value, now, zone, locale, yesterday).takeIf { it.isNotEmpty() }

/** The day separator already names the date; call events only need their local time. */
internal fun callEventTime(value: String, zone: ZoneId, locale: Locale): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(zone)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
}.getOrDefault("")
