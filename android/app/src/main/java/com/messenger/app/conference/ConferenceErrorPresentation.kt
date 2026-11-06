package com.messenger.app.conference

import androidx.annotation.StringRes
import com.messenger.app.R
import com.messenger.app.data.api.AuthApiException

/** Presentation only: terminal/retry decisions remain in ConferenceRecoveryPolicy. */
internal object ConferenceErrorPresentation {
    @StringRes fun message(error: Exception, connected: Boolean): Int = when (error) {
        is ConferenceJoinApprovalFailure -> when (error.reason) {
            ConferenceJoinApprovalFailure.Reason.DENIED -> R.string.conference_join_denied
            ConferenceJoinApprovalFailure.Reason.EXPIRED -> R.string.conference_join_expired
            ConferenceJoinApprovalFailure.Reason.CANCELLED -> R.string.conference_join_cancelled
        }
        is ConferenceRemoved -> R.string.conference_error_removed
        is ConferenceEncryptionFailure -> R.string.conference_error_encryption
        is ConferenceClosed -> R.string.conference_error_closed
        is ConferenceRecoveryExhausted -> R.string.conference_error_recovery
        is AuthApiException.Api -> when {
            error.httpStatus == 409 && error.code == "conflict" && error.message == "busy" -> R.string.media_busy_call_or_capture
            error.httpStatus == 410 && error.message == "You were removed from this conference." -> R.string.conference_error_removed
            error.httpStatus == 404 || error.httpStatus == 410 -> R.string.conference_error_closed
            error.httpStatus == 409 && error.message == "Conference admission is locked." -> R.string.conference_error_locked
            error.httpStatus == 409 && error.message == "End an existing conference before creating another." -> R.string.conference_error_owned_quota
            else -> fallback(connected)
        }
        else -> fallback(connected)
    }

    @StringRes private fun fallback(connected: Boolean) = if (connected) R.string.conference_error_closed else R.string.conference_error_join
}
