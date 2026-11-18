package com.messenger.app.conference

import com.messenger.app.data.api.AuthApiException
import io.livekit.android.events.DisconnectReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

internal enum class ConferenceLoss {
    TRANSPORT, CLOSED, REMOVED, ENCRYPTION;
    fun failure(): Exception = when (this) {
        TRANSPORT -> ConferenceTransportFailure()
        CLOSED -> ConferenceClosed()
        REMOVED -> ConferenceRemoved()
        ENCRYPTION -> ConferenceEncryptionFailure()
    }
}
internal class ConferenceClosed : Exception()
internal class ConferenceRemoved : Exception()
internal class ConferenceEncryptionFailure : Exception()
internal class ConferenceRecoveryExhausted : Exception()
internal class ConferenceTransportFailure : IOException()

/** A local SDK-connect timeout must not hide a terminal room event. Parent/user
 * cancellation wins; an expired SFU signaling token can obtain a fresh grant. */
internal suspend fun failConferenceConnect(error: Exception, loss: Deferred<ConferenceLoss>?): Nothing {
    currentCoroutineContext().ensureActive()
    if (loss?.isCompleted == true) throw loss.await().failure()
    if (error is CancellationException) throw error
    throw ConferenceTransportFailure()
}

/** Only the app creates replacement Rooms. Native resume/full-republish stays disabled. */
internal object ConferenceRecoveryPolicy {
    const val MAX_ATTEMPTS = 8
    const val OUTAGE_BUDGET_MS = 120_000L
    const val STABLE_CONNECTION_MS = 10_000L

    fun loss(reason: DisconnectReason): ConferenceLoss = when (reason) {
        DisconnectReason.PARTICIPANT_REMOVED -> ConferenceLoss.REMOVED
        DisconnectReason.UNKNOWN_REASON, DisconnectReason.SERVER_SHUTDOWN,
        DisconnectReason.STATE_MISMATCH, DisconnectReason.JOIN_FAILURE,
        DisconnectReason.MIGRATION, DisconnectReason.SIGNAL_CLOSE,
        DisconnectReason.CONNECTION_TIMEOUT, DisconnectReason.MEDIA_FAILURE -> ConferenceLoss.TRANSPORT
        else -> ConferenceLoss.CLOSED
    }

    fun retryable(error: Exception): Boolean = when (error) {
        is IOException, is TimeoutCancellationException -> true
        is AuthApiException.Api -> error.httpStatus == 429 || error.httpStatus in 500..599
        is AuthApiException.Network -> error.httpStatus == null || error.httpStatus == 429 || error.httpStatus in 500..599
        else -> false
    }

    fun delayMs(failedAttempts: Int, error: Exception?): Long {
        val backoff = longArrayOf(1_000, 2_000, 4_000, 8_000, 12_000, 15_000, 20_000)
            .get((failedAttempts - 1).coerceIn(0, 6))
        val status = when (error) {
            is AuthApiException.Api -> error.httpStatus
            is AuthApiException.Network -> error.httpStatus
            else -> null
        }
        return if (status == 429) maxOf(30_000L, backoff) else backoff
    }
}

/** A single coroutine owns admission, capture and every replacement. A failed/old
 * Room is fully disposed before another admission starts. Cancellation also waits
 * for disposal; the controller's final /leave waits for this coroutine to finish. */
internal class ConferenceRecoveryLoop(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    suspend fun run(
        connect: suspend () -> Unit,
        awaitLoss: suspend () -> ConferenceLoss,
        dispose: suspend () -> Unit,
        recovering: () -> Unit,
    ): Nothing {
        var outageStarted = nowMs()
        var failedAttempts = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            var failure: Exception? = null
            try {
                val remaining = ConferenceRecoveryPolicy.OUTAGE_BUDGET_MS - (nowMs() - outageStarted)
                if (remaining <= 0) throw ConferenceRecoveryExhausted()
                withTimeout(remaining) { connect() }
                val connectedAt = nowMs()
                when (awaitLoss()) {
                    ConferenceLoss.CLOSED -> throw ConferenceClosed()
                    ConferenceLoss.REMOVED -> throw ConferenceRemoved()
                    ConferenceLoss.ENCRYPTION -> throw ConferenceEncryptionFailure()
                    ConferenceLoss.TRANSPORT -> Unit
                }
                // Rapid connect/drop cycles share one budget, rather than retrying forever.
                if (nowMs() - connectedAt >= ConferenceRecoveryPolicy.STABLE_CONNECTION_MS) {
                    outageStarted = nowMs()
                    failedAttempts = 0
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (!ConferenceRecoveryPolicy.retryable(error)) throw error
                failure = error
            } finally {
                withContext(NonCancellable) { dispose() }
            }
            currentCoroutineContext().ensureActive()
            recovering()
            failedAttempts++
            val pause = ConferenceRecoveryPolicy.delayMs(failedAttempts, failure)
            if (failedAttempts >= ConferenceRecoveryPolicy.MAX_ATTEMPTS ||
                nowMs() - outageStarted + pause >= ConferenceRecoveryPolicy.OUTAGE_BUDGET_MS) {
                throw ConferenceRecoveryExhausted()
            }
            delay(pause)
        }
    }
}
