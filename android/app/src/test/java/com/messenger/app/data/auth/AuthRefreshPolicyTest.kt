package com.messenger.app.data.auth

import com.messenger.app.data.api.AuthApiException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N.2R regression: a refresh failure must clear the session ONLY on a
 *  definitive terminal auth rejection. Network/5xx/unknown failures must keep
 *  the session (the user must never be logged out by a transient outage). */
class AuthRefreshPolicyTest {

    @Test
    fun invalidRefreshTokenIsTerminal() {
        assertTrue(isTerminalAuthFailure(AuthApiException.Api("invalid_refresh_token", "expired")))
    }

    @Test
    fun sessionRevokedIsTerminal() {
        assertTrue(isTerminalAuthFailure(AuthApiException.Api("session_revoked", "revoked")))
    }

    @Test
    fun networkFailureIsNotTerminal() {
        assertFalse(isTerminalAuthFailure(AuthApiException.Network("connection refused")))
    }

    @Test
    fun rawIOExceptionIsNotTerminal() {
        assertFalse(isTerminalAuthFailure(IOException("socket timeout")))
    }

    @Test
    fun serverFiveHundredIsNotTerminal() {
        assertFalse(isTerminalAuthFailure(AuthApiException.Api("internal_error", "boom")))
    }

    @Test
    fun genericApiErrorIsNotTerminal() {
        assertFalse(isTerminalAuthFailure(AuthApiException.Api("rate_limited", "slow down")))
    }
}
