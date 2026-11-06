package com.messenger.app.domain.model

data class AuthUser(
    val id: String,
    val username: String,
    val displayName: String,
)

sealed interface AuthSessionState {
    data object Unknown : AuthSessionState
    data object LoggedOut : AuthSessionState
    data class LoggedIn(val user: AuthUser) : AuthSessionState
}
