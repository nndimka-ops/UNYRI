package com.messenger.app.conference

import com.messenger.app.R
import com.messenger.app.data.api.AuthApiException
import java.io.IOException
import kotlin.test.*

class ConferenceErrorPresentationTest {
    @Test fun `only a known busy response gets conversation guidance`() {
        assertEquals(R.string.media_busy_call_or_capture, ConferenceErrorPresentation.message(AuthApiException.Api("conflict", "busy", 409), false))
        assertEquals(R.string.conference_error_join, ConferenceErrorPresentation.message(AuthApiException.Api("conflict", "unrelated conflict", 409), false))
    }
    @Test fun `exact authenticated removal is explained on first join and reconnect`() {
        val removed = AuthApiException.Api("gone", "You were removed from this conference.", 410)
        for (connected in listOf(false, true)) assertEquals(R.string.conference_error_removed, ConferenceErrorPresentation.message(removed, connected))
    }
    @Test fun `native removal and REST removal have one user explanation`() {
        assertEquals(R.string.conference_error_removed, ConferenceErrorPresentation.message(ConferenceRemoved(), connected = true))
        assertFalse(ConferenceRecoveryPolicy.retryable(ConferenceRemoved()))
    }
    @Test fun `expiry missing room and unrecognized gone never claim creator removal`() {
        for (status in listOf(404, 410)) for (detail in listOf("Conference expired.", "Conference ended.", "", "You were removed from this conference")) {
            assertEquals(R.string.conference_error_closed, ConferenceErrorPresentation.message(AuthApiException.Api("gone", detail, status), connected = false))
        }
        assertEquals(R.string.conference_error_join, ConferenceErrorPresentation.message(AuthApiException.Api("denied", "You were removed from this conference.", 403), false))
    }
    @Test fun `locked admission and owner quota retain their existing guidance`() {
        assertEquals(R.string.conference_error_locked, ConferenceErrorPresentation.message(AuthApiException.Api("conflict", "Conference admission is locked.", 409), false))
        assertEquals(R.string.conference_error_owned_quota, ConferenceErrorPresentation.message(AuthApiException.Api("conflict", "End an existing conference before creating another.", 409), false))
    }
    @Test fun `encryption closed and exhausted recovery keep distinct explanations`() {
        assertEquals(R.string.conference_error_encryption, ConferenceErrorPresentation.message(ConferenceEncryptionFailure(), true))
        assertEquals(R.string.conference_error_closed, ConferenceErrorPresentation.message(ConferenceClosed(), true))
        assertEquals(R.string.conference_error_recovery, ConferenceErrorPresentation.message(ConferenceRecoveryExhausted(), true))
    }
    @Test fun `unexpected details never become UI text and transient retry behavior is untouched`() {
        val transient = AuthApiException.Api("unavailable", "synthetic-private-transport-detail", 503)
        assertTrue(ConferenceRecoveryPolicy.retryable(transient))
        for (error in listOf(transient, IOException("synthetic-private-transport-detail"))) {
            assertEquals(R.string.conference_error_join, ConferenceErrorPresentation.message(error, false))
            assertEquals(R.string.conference_error_closed, ConferenceErrorPresentation.message(error, true))
        }
    }
}
