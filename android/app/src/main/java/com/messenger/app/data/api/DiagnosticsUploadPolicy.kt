package com.messenger.app.data.api

internal object DiagnosticsUploadPolicy {
    fun delayMillis(enabled: Boolean, active: Boolean, online: Boolean, hasData: Boolean, backoff: Long): Long? =
        backoff.coerceIn(5_000, 60_000).takeIf { enabled && active && online && hasData }

    fun afterAttempt(previous: Long, success: Boolean): Long =
        if (success) 5_000 else (previous.coerceIn(5_000, 60_000) * 2).coerceAtMost(60_000)
}
