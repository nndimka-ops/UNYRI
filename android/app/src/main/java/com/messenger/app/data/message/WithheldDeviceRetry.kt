package com.messenger.app.data.message

import com.messenger.app.data.api.AuthApiException

/** A rebuild performs fresh peer and own discovery; it never substitutes missing ciphertext. */
internal suspend fun <T> retryWithheldOnce(hasWithheld: () -> Boolean, attempt: suspend () -> T): T {
    try { return attempt() }
    catch (error: AuthApiException.Api) {
        if (error.code != "validation_error" || !hasWithheld()) throw error
        return attempt()
    }
}
