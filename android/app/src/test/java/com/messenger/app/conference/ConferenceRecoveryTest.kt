package com.messenger.app.conference

import com.messenger.app.data.api.AuthApiException
import io.livekit.android.events.DisconnectReason
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConferenceRecoveryTest {
    @Test fun `only transport service and throttling failures retry`() {
        for (status in listOf(400, 401, 403, 404, 405, 409, 410, 413, 415, 422)) {
            assertFalse(ConferenceRecoveryPolicy.retryable(AuthApiException.Api("denied", "ignored", status)))
            assertFalse(ConferenceRecoveryPolicy.retryable(AuthApiException.Network("ignored", status)))
        }
        for (status in listOf(429, 500, 502, 503, 504)) {
            assertTrue(ConferenceRecoveryPolicy.retryable(AuthApiException.Api("transient", "ignored", status)))
            assertTrue(ConferenceRecoveryPolicy.retryable(AuthApiException.Network("ignored", status)))
        }
        assertTrue(ConferenceRecoveryPolicy.retryable(IOException()))
        assertTrue(ConferenceRecoveryPolicy.retryable(AuthApiException.Network("refresh offline")))
        assertFalse(ConferenceRecoveryPolicy.retryable(ConferenceEncryptionFailure()))
        assertFalse(ConferenceRecoveryPolicy.retryable(SecurityException()))
        assertFalse(ConferenceRecoveryPolicy.retryable(IllegalArgumentException()))
        assertEquals(30_000, ConferenceRecoveryPolicy.delayMs(1, AuthApiException.Api("rate_limited", "ignored", 429)))
    }

    @Test fun `room end removal duplicate identity and client leave never fight server`() {
        for (reason in listOf(DisconnectReason.CLIENT_INITIATED, DisconnectReason.DUPLICATE_IDENTITY,
            DisconnectReason.ROOM_DELETED, DisconnectReason.ROOM_CLOSED,
            DisconnectReason.USER_UNAVAILABLE, DisconnectReason.USER_REJECTED)) {
            assertEquals(ConferenceLoss.CLOSED, ConferenceRecoveryPolicy.loss(reason))
        }
        assertEquals(ConferenceLoss.REMOVED, ConferenceRecoveryPolicy.loss(DisconnectReason.PARTICIPANT_REMOVED))
        for (reason in listOf(DisconnectReason.UNKNOWN_REASON, DisconnectReason.SIGNAL_CLOSE,
            DisconnectReason.MIGRATION, DisconnectReason.SERVER_SHUTDOWN, DisconnectReason.MEDIA_FAILURE,
            DisconnectReason.CONNECTION_TIMEOUT)) {
            assertEquals(ConferenceLoss.TRANSPORT, ConferenceRecoveryPolicy.loss(reason))
        }
    }

    @Test fun `old media disposal finishes before a fresh admission and room`() = runTest {
        val log = mutableListOf<String>()
        var rooms = 0
        var captureOwners = 0
        val result = runCatching {
            ConferenceRecoveryLoop { testScheduler.currentTime }.run(
                connect = {
                    assertEquals(0, captureOwners, "old capturer must already be released")
                    rooms++
                    log += "join-$rooms"
                    log += "new-room-$rooms"
                    // Each replacement, unlike native republish, starts with a disabled track.
                    log += "disabled-$rooms"
                    log += "publish-and-cryptor-$rooms"
                    captureOwners++
                    log += "capture-$rooms"
                },
                awaitLoss = { if (rooms == 1) ConferenceLoss.TRANSPORT else ConferenceLoss.CLOSED },
                dispose = { log += "stop-$rooms"; delay(200); captureOwners--; log += "disposed-$rooms" },
                recovering = { log += "recovering" },
            )
        }
        assertIs<ConferenceClosed>(result.exceptionOrNull())
        assertEquals(2, rooms)
        assertEquals(0, captureOwners)
        assertTrue(log.indexOf("disposed-1") < log.indexOf("join-2"))
        assertTrue(log.indexOf("publish-and-cryptor-2") < log.indexOf("capture-2"))
        assertEquals(1_400, testScheduler.currentTime)
    }

    @Test fun `repeated admission network failures have finite increasing backoff`() = runTest {
        val admissions = mutableListOf<Long>()
        var disposed = 0
        val result = runCatching {
            ConferenceRecoveryLoop { testScheduler.currentTime }.run(
                connect = { admissions += testScheduler.currentTime; throw IOException() },
                awaitLoss = { error("not connected") }, dispose = { disposed++ }, recovering = {},
            )
        }
        assertIs<ConferenceRecoveryExhausted>(result.exceptionOrNull())
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 27_000L, 42_000L, 62_000L), admissions)
        assertEquals(8, disposed)
    }

    @Test fun `rapid successful connect then drop does not reset outage budget`() = runTest {
        var admissions = 0
        val result = runCatching {
            ConferenceRecoveryLoop { testScheduler.currentTime }.run(
                connect = { admissions++ }, awaitLoss = { delay(1); ConferenceLoss.TRANSPORT },
                dispose = {}, recovering = {},
            )
        }
        assertIs<ConferenceRecoveryExhausted>(result.exceptionOrNull())
        assertEquals(8, admissions)
        assertTrue(testScheduler.currentTime < ConferenceRecoveryPolicy.OUTAGE_BUDGET_MS)
    }

    @Test fun `a stable recovered call gets a fresh budget for a later outage`() = runTest {
        var admissions = 0
        val result = runCatching {
            ConferenceRecoveryLoop { testScheduler.currentTime }.run(
                connect = { admissions++ },
                awaitLoss = {
                    delay(ConferenceRecoveryPolicy.STABLE_CONNECTION_MS)
                    if (admissions == 10) ConferenceLoss.CLOSED else ConferenceLoss.TRANSPORT
                }, dispose = {}, recovering = {},
            )
        }
        assertIs<ConferenceClosed>(result.exceptionOrNull())
        assertEquals(10, admissions)
    }

    @Test fun `one stalled replacement cannot exceed total outage deadline`() = runTest {
        var admissions = 0
        var disposed = 0
        val result = runCatching {
            ConferenceRecoveryLoop { testScheduler.currentTime }.run(
                connect = { admissions++; delay(200_000) }, awaitLoss = { error("not connected") },
                dispose = { disposed++ }, recovering = {},
            )
        }
        assertIs<ConferenceRecoveryExhausted>(result.exceptionOrNull())
        assertEquals(1, admissions)
        assertEquals(1, disposed)
        assertEquals(120_000, testScheduler.currentTime)
    }

    @Test fun `leave during backoff cancels all later admissions`() = runTest {
        var admissions = 0
        var disposed = 0
        val job = launch {
            ConferenceRecoveryLoop { testScheduler.currentTime }.run(
                connect = { admissions++; throw IOException() }, awaitLoss = { error("not connected") },
                dispose = { disposed++ }, recovering = {},
            )
        }
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(200_000)
        assertEquals(1, admissions)
        assertEquals(1, disposed)
    }

    @Test fun `canceled inflight admission is observed before final leave and cannot start capture`() = runTest {
        val committed = CompletableDeferred<Unit>()
        val log = mutableListOf<String>()
        val job = launch {
            ConferenceRecoveryLoop { testScheduler.currentTime }.run(
                connect = {
                    withContext(NonCancellable) { log += "join-start"; committed.await(); log += "join-committed" }
                    currentCoroutineContext().ensureActive()
                    log += "capture"
                }, awaitLoss = { error("not connected") },
                dispose = { log += "dispose" }, recovering = {},
            )
        }
        runCurrent()
        job.cancel()
        val cleanup = launch { job.join(); log += "leave" }
        runCurrent()
        assertEquals(listOf("join-start"), log)
        assertTrue(cleanup.isActive, "busy lease must include cleanup waiting for admission")
        committed.complete(Unit)
        cleanup.join()
        assertEquals(listOf("join-start", "join-committed", "dispose", "leave"), log)
    }

    @Test fun `cancel waits for teardown and never overlaps a successor capturer`() = runTest {
        val tearingDown = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        var capturers = 0
        val job = launch {
            ConferenceRecoveryLoop { testScheduler.currentTime }.run(
                connect = { capturers++ }, awaitLoss = { CompletableDeferred<ConferenceLoss>().await() },
                dispose = { tearingDown.complete(Unit); closed.await(); capturers-- }, recovering = {},
            )
        }
        runCurrent()
        job.cancel()
        tearingDown.await()
        val successor = async { job.join(); assertEquals(0, capturers); "safe" }
        runCurrent()
        assertFalse(successor.isCompleted)
        closed.complete(Unit)
        assertEquals("safe", successor.await())
    }

    @Test fun `permanent admission and encryption errors never request a second token`() = runTest {
        for (failure in listOf(AuthApiException.Api("gone", "ignored", 410),
            AuthApiException.Api("not_found", "ignored", 404), ConferenceEncryptionFailure())) {
            var admissions = 0
            var disposed = 0
            val result = runCatching {
                ConferenceRecoveryLoop { testScheduler.currentTime }.run(
                    connect = { admissions++; throw failure }, awaitLoss = { error("not connected") },
                    dispose = { disposed++ }, recovering = { error("permanent failure retried") },
                )
            }
            assertTrue(failure.javaClass == result.exceptionOrNull()?.javaClass)
            assertEquals(1, admissions)
            assertEquals(1, disposed)
        }
    }

    @Test fun `failed old media teardown fails closed before another admission`() = runTest {
        var admissions = 0
        val result = runCatching {
            ConferenceRecoveryLoop { testScheduler.currentTime }.run(
                connect = { admissions++ }, awaitLoss = { ConferenceLoss.TRANSPORT },
                dispose = { throw IllegalStateException("cannot release capturer") },
                recovering = { error("must not retry a live old capturer") },
            )
        }
        assertIs<IllegalStateException>(result.exceptionOrNull())
        assertEquals(1, admissions)
    }

    @Test fun `terminal loss during startup retains its reason instead of becoming a transport retry`() = runTest {
        for (loss in listOf(ConferenceLoss.CLOSED, ConferenceLoss.REMOVED, ConferenceLoss.ENCRYPTION)) {
            var admissions = 0
            val result = runCatching {
                ConferenceRecoveryLoop { testScheduler.currentTime }.run(
                    connect = {
                        admissions++
                        // The native room can report a loss before connect/publish completes.
                        throw CompletableDeferred(loss).await().failure()
                    }, awaitLoss = { error("startup did not finish") }, dispose = {},
                    recovering = { error("terminal startup loss retried") },
                )
            }
            if (loss == ConferenceLoss.CLOSED) assertIs<ConferenceClosed>(result.exceptionOrNull())
            else if (loss == ConferenceLoss.REMOVED) assertIs<ConferenceRemoved>(result.exceptionOrNull())
            else assertIs<ConferenceEncryptionFailure>(result.exceptionOrNull())
            assertEquals(1, admissions)
        }
    }

    @Test fun `local connect timeout preserves terminal loss while parent cancellation still wins`() = runTest {
        val timeout = assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            kotlinx.coroutines.withTimeout(1) { delay(10) }
        }
        assertFailsWith<ConferenceClosed> { failConferenceConnect(timeout, CompletableDeferred(ConferenceLoss.CLOSED)) }
        assertFailsWith<ConferenceRemoved> { failConferenceConnect(timeout, CompletableDeferred(ConferenceLoss.REMOVED)) }
        assertFailsWith<ConferenceEncryptionFailure> { failConferenceConnect(timeout, CompletableDeferred(ConferenceLoss.ENCRYPTION)) }
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { failConferenceConnect(timeout, CompletableDeferred()) }
        val canceled = launch {
            currentCoroutineContext().job.cancel()
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                failConferenceConnect(timeout, CompletableDeferred(ConferenceLoss.CLOSED))
            }
        }
        canceled.join()
    }

    @Test fun `SDK removal disposes once and never attempts recovery`() = runTest {
        var admissions = 0; var disposed = 0; var recovering = 0
        val result = runCatching {
            ConferenceRecoveryLoop { testScheduler.currentTime }.run(
                connect = { admissions++ },
                awaitLoss = { ConferenceRecoveryPolicy.loss(DisconnectReason.PARTICIPANT_REMOVED) },
                dispose = { disposed++ }, recovering = { recovering++ })
        }
        assertIs<ConferenceRemoved>(result.exceptionOrNull())
        assertEquals(1, admissions); assertEquals(1, disposed); assertEquals(0, recovering)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `removed REST admission is terminal and does not start media or retry`() = runTest {
        var admissions = 0; var disposed = 0; var recovering = 0
        val failure = AuthApiException.Api("gone", "You were removed from this conference.", 410)
        val result = runCatching {
            ConferenceRecoveryLoop { testScheduler.currentTime }.run(
                connect = { admissions++; throw failure }, awaitLoss = { error("No media admission") },
                dispose = { disposed++ }, recovering = { recovering++ })
        }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, admissions); assertEquals(1, disposed); assertEquals(0, recovering)
        assertEquals(0L, testScheduler.currentTime)
    }
}
