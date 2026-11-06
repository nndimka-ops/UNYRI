package com.messenger.app.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Cold-start preferences come from durable storage, never an uninitialized screen flow. */
internal class NotificationDeliveryGate(
    private val owner: () -> String?,
    private val silenced: suspend (String) -> Boolean,
    private val timeoutMillis: Long = 2000,
    private val enabled: () -> Boolean = { true },
) {
    enum class Decision { ALERT, SILENT, SUPPRESS }
    suspend fun decide(chatId: String): Decision {
        if (!enabled()) return Decision.SUPPRESS
        val expected = owner() ?: return Decision.SUPPRESS
        val muted = try { withTimeoutOrNull(timeoutMillis) { silenced(chatId) } }
        catch (cancelled: CancellationException) {
            if (owner() != expected) return Decision.SUPPRESS
            throw cancelled
        }
        catch (_: Exception) { null }
        if (owner() != expected || !enabled()) return Decision.SUPPRESS
        return when (muted) { true -> Decision.SUPPRESS; false -> Decision.ALERT; null -> Decision.SILENT }
    }
}
