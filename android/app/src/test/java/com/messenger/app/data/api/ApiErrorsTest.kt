package com.messenger.app.data.api

import com.messenger.app.data.message.OutboxPolicy
import com.messenger.app.data.message.OutboxFailureClass
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiErrorsTest {
    @Test fun opaquePermanentResponsesDoNotRetryForever() {
        for (status in listOf(400, 404, 405, 410, 413, 415, 422)) {
            assertEquals(OutboxFailureClass.PERMANENT,
                OutboxPolicy.classify(parseApiException(Json, status, "<html>error</html>")))
        }
    }
    @Test fun temporaryResponsesKeepMessagePending() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            assertEquals(OutboxFailureClass.TRANSIENT,
                OutboxPolicy.classify(parseApiException(Json, status, "")))
        }
    }
    @Test fun opaqueAuthResponseDoesNotInvalidateSession() {
        for (status in listOf(401, 403)) {
            assertEquals(OutboxFailureClass.TRANSIENT,
                OutboxPolicy.classify(parseApiException(Json, status, "")))
        }
    }
    @Test fun structuredRevocationStillStopsOutbox() {
        assertEquals(OutboxFailureClass.AUTH_TERMINAL,
            OutboxPolicy.classify(parseApiException(Json, 401,
                """{"error":{"code":"session_revoked","message":"revoked"}}""")))
    }
}
