package com.messenger.app.data.message

import com.messenger.app.data.api.AuthApiException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/** N.2R Bug 4 regression: transient network/server failures must leave a
 *  message PENDING (retry), never FAILED. Only authoritative terminal auth or
 *  genuinely unrecoverable contract errors are permanent. */
class OutboxPolicyTest {
    @Test fun missingRecipientKeysAreRecoverableWithoutChangingEncryption() {
        assertEquals(OutboxFailureClass.TRANSIENT,OutboxPolicy.classify(RecipientDevicesUnavailableException()))
        assertEquals("recipient_devices_unavailable",OutboxPolicy.diagnosticCode(RecipientDevicesUnavailableException()))
        assertEquals(15_000L,OutboxPolicy.retryDelayMs(RecipientDevicesUnavailableException(),1))
        assertEquals(OutboxFailureClass.TRANSIENT,OutboxPolicy.classify(AuthApiException.Api("validation_error","At least one recipient envelope is required.",400)))
        assertEquals(OutboxFailureClass.TRANSIENT,OutboxPolicy.classify(AuthApiException.Api("validation_error","One or more recipient devices are not members of this chat.",400)))
        assertEquals("attachment_association_invalid",OutboxPolicy.diagnosticCode(AuthApiException.Api("validation_error","One or more attachments are invalid, not yet uploaded, or already attached to another message.",400)))
    }
    @org.junit.Test fun rateLimitedRetriesWaitForServerWindow() {
        kotlin.test.assertEquals(60_000L, OutboxPolicy.retryDelayMs(AuthApiException.Api("rate_limited", "Too many", 429), 1))
        kotlin.test.assertEquals(60_000L, OutboxPolicy.retryDelayMs(AuthApiException.Network("HTTP 429", 429), 1))
        kotlin.test.assertEquals(2_000L, OutboxPolicy.retryDelayMs(java.io.IOException("offline"), 1))
    }
    @Test
    fun changedKeyNeedsUserActionInsteadOfTimedRetry() {
        assertEquals(OutboxFailureClass.PERMANENT, OutboxPolicy.classify(
            com.messenger.app.data.signal.IdentityChangedException("peer", listOf(1))))
    }
    @Test fun invalidMediaDoesNotConvertForever() {
        assertEquals(OutboxFailureClass.PERMANENT, OutboxPolicy.classify(com.messenger.app.data.attachment.MediaPreparationException(IllegalArgumentException())))
        assertEquals(OutboxFailureClass.PERMANENT, OutboxPolicy.classify(com.messenger.app.data.attachment.AttachmentTooLargeException("large")))
        assertEquals(OutboxFailureClass.PERMANENT, OutboxPolicy.classify(java.io.FileNotFoundException()))
        assertEquals(OutboxFailureClass.TRANSIENT, OutboxPolicy.classify(IOException()))
    }


    @Test
    fun networkFailureIsTransient() {
        assertEquals(OutboxFailureClass.TRANSIENT, OutboxPolicy.classify(AuthApiException.Network("connection refused")))
    }

    @Test
    fun rawIOExceptionIsTransient() {
        assertEquals(OutboxFailureClass.TRANSIENT, OutboxPolicy.classify(IOException("socket timeout")))
    }

    @Test
    fun serverFiveHundredIsTransient() {
        assertEquals(OutboxFailureClass.TRANSIENT, OutboxPolicy.classify(AuthApiException.Api("internal_error", "boom")))
    }

    @Test
    fun rateLimitedIsTransient() {
        assertEquals(OutboxFailureClass.TRANSIENT, OutboxPolicy.classify(AuthApiException.Api("rate_limited", "slow down")))
    }

    @Test
    fun ownDeviceCoverageErrorIsTransient() {
        assertEquals(
            OutboxFailureClass.TRANSIENT,
            OutboxPolicy.classify(AuthApiException.Api("validation_error", "Every other active device of the sender must receive an envelope.")),
        )
    }

    @Test
    fun genericValidationErrorIsPermanent() {
        assertEquals(
            OutboxFailureClass.PERMANENT,
            OutboxPolicy.classify(AuthApiException.Api("validation_error", "Invalid base64 ciphertext.")),
        )
    }

    @Test
    fun notFoundIsPermanent() {
        assertEquals(OutboxFailureClass.PERMANENT, OutboxPolicy.classify(AuthApiException.Api("not_found", "Chat not found.")))
    }

    @Test
    fun invalidRefreshTokenIsAuthTerminal() {
        assertEquals(OutboxFailureClass.AUTH_TERMINAL, OutboxPolicy.classify(AuthApiException.Api("invalid_refresh_token", "expired")))
    }

    @Test
    fun sessionRevokedIsAuthTerminal() {
        assertEquals(OutboxFailureClass.AUTH_TERMINAL, OutboxPolicy.classify(AuthApiException.Api("session_revoked", "revoked")))
    }

    @Test
    fun unauthorizedIsAuthTerminal() {
        assertEquals(OutboxFailureClass.AUTH_TERMINAL, OutboxPolicy.classify(AuthApiException.Api("unauthorized", "bad token")))
    }

    @Test
    fun backoffProgression() {
        assertEquals(1_000L, OutboxPolicy.baseBackoffMs(0))
        assertEquals(2_000L, OutboxPolicy.baseBackoffMs(1))
        assertEquals(4_000L, OutboxPolicy.baseBackoffMs(2))
        assertEquals(8_000L, OutboxPolicy.baseBackoffMs(3))
        assertEquals(15_000L, OutboxPolicy.baseBackoffMs(4))
        assertEquals(30_000L, OutboxPolicy.baseBackoffMs(5))
        assertEquals(60_000L, OutboxPolicy.baseBackoffMs(6))
        assertEquals(60_000L, OutboxPolicy.baseBackoffMs(100))
    }
}
