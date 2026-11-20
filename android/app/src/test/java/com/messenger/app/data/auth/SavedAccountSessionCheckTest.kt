package com.messenger.app.data.auth

import com.messenger.app.data.api.AuthApiException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SavedAccountSessionCheckTest {
    @Test fun inactiveTerminalRefreshErasesImmediatelyWithItsExactReason() = runTest {
        for (code in listOf("account_deleted", "account_suspended", "session_revoked", "invalid_refresh_token")) {
            var erased: String? = null
            var holdingRefresh = true
            checkSavedAccountSession({
                try { throw AuthApiException.Api(code, "terminal", 401) }
                finally { holdingRefresh = false }
            }, { assertFalse(holdingRefresh); erased = it })
            assertEquals(code, erased)
        }
    }
    @Test fun offlineServerFailureAndCancellationKeepInactiveWorkspace() = runTest {
        for (error in listOf(IOException("offline"), AuthApiException.Api("internal_error", "temporary", 503), CancellationException("background"))) {
            var erased = false
            try {
                checkSavedAccountSession({ throw error }, { erased = true })
                fail("Transient and cancelled requests must retain their retry failure")
            } catch (actual: Exception) { assertSame(error, actual) }
            assertFalse(erased)
        }
    }
    @Test fun successfulRotationPersistsAndDoesNotErase() = runTest {
        var saved = false
        checkSavedAccountSession({ saved = true }, { fail("Valid saved account must remain available") })
        assertTrue(saved)
    }
}
