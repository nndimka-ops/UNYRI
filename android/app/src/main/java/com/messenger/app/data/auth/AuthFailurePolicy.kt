package com.messenger.app.data.auth

import com.messenger.app.data.api.AuthApiException

/** Only an authenticated terminal decision erases local credentials. Clock challenges and outages do not. */
internal fun isTerminalAuthFailure(e: Exception): Boolean =
    (e as? AuthApiException.Api)?.let { isTerminalAuthCode(it.code) } == true

internal fun isTerminalAuthCode(code: String): Boolean = code in setOf("invalid_refresh_token", "session_revoked", "account_deleted", "account_suspended", HARDWARE_BINDING_REQUIRED)

/** The authenticated revocation frame itself terminates a session, including older reason strings. */
internal fun sessionRevocationReason(reason: String?): String = reason?.takeIf(::isTerminalAuthCode) ?: "session_revoked"
