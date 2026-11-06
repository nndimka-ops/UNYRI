package com.messenger.app.domain.call

/** Identifies one call in one signed-in session; never an authorization token. */
data class CallForegroundServiceTicket(val callId: String, val generation: Long, val owner: String)
