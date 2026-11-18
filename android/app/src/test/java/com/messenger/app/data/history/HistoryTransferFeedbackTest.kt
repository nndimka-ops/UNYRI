package com.messenger.app.data.history

import com.messenger.app.data.api.AuthApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryTransferFeedbackTest {
    @Test fun `network failure and server rejection are distinct`() {
        assertEquals(HistoryTransferFailureCategory.NETWORK,
            classifyHistoryTransferFailure(AuthApiException.Network("private-url")).category)
        assertEquals(HistoryTransferFailureCategory.SERVER,
            classifyHistoryTransferFailure(AuthApiException.Network("private-body", 503)).category)
        assertEquals(HistoryTransferFailureCategory.REQUEST_REJECTED,
            classifyHistoryTransferFailure(AuthApiException.Api("private-code", "private-body", 400)).category)
    }

    @Test fun `session expired missing request conflict and throttling retain fixed HTTP categories`() {
        val cases = mapOf(401 to HistoryTransferFailureCategory.SESSION, 403 to HistoryTransferFailureCategory.SESSION,
            404 to HistoryTransferFailureCategory.REQUEST_MISSING, 410 to HistoryTransferFailureCategory.REQUEST_MISSING,
            409 to HistoryTransferFailureCategory.CONFLICT, 429 to HistoryTransferFailureCategory.RATE_LIMIT)
        cases.forEach { (status, category) ->
            val result = classifyHistoryTransferFailure(AuthApiException.Api("secret", "secret", status))
            assertEquals(category, result.category); assertEquals(status, result.httpStatus)
        }
    }

    @Test fun `key readiness source history and request validation stay separate`() {
        assertEquals(HistoryTransferFailureCategory.KEY_MISSING, classifyHistoryTransferFailure(HistoryRecoveryKeyMissing()).category)
        assertEquals(HistoryTransferFailureCategory.UPLOADS_DISABLED, classifyHistoryTransferFailure(HistoryUploadsDisabled()).category)
        assertEquals(HistoryTransferFailureCategory.SOURCE_NOT_READY, classifyHistoryTransferFailure(HistorySourceNotReady()).category)
        assertEquals(HistoryTransferFailureCategory.WRONG_KEY, classifyHistoryTransferFailure(javax.crypto.AEADBadTagException("secret")).category)
        assertEquals(HistoryTransferFailureCategory.WRONG_ACCOUNT,
            classifyHistoryTransferFailure(HistoryTransferValidationFailure(HistoryTransferValidationFailure.Reason.WRONG_ACCOUNT)).category)
    }

    @Test fun `unexpected exception cannot leak its contents into reference or metrics`() {
        val secret = "private QR key URL and exception body"
        val result = classifyHistoryTransferFailure(IllegalStateException(secret))
        assertEquals(HistoryTransferFailureCategory.UNEXPECTED, result.category)
        assertFalse(result.reference(HistoryTransferPhase.PREPARE).contains(secret))
        assertEquals(setOf("phase", "operation", "status", "success"), result.metrics(HistoryTransferPhase.PREPARE).keys)
        assertTrue(result.metrics(HistoryTransferPhase.PREPARE).values.all { it.isFinite() && it >= 0 && it <= 1e12 })
    }

    @Test fun `metadata remains in existing numeric server allowlist without exception derived fields`() {
        val result = HistoryTransferFailure(HistoryTransferFailureCategory.RATE_LIMIT, 429)
        assertEquals(mapOf("phase" to 8.0, "operation" to 7415.0, "status" to 429.0, "success" to 0.0),
            result.metrics(HistoryTransferPhase.WAIT))
        assertEquals(0.0, result.copy(httpStatus = -100).metrics(HistoryTransferPhase.WAIT)["status"])
    }
}
