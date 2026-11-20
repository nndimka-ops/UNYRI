package com.messenger.app.ui.auth

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.messenger.app.R
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Loading : AuthUiState
    data class Error(@StringRes val messageRes: Int, val code: String? = null) : AuthUiState
}

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    val authState: StateFlow<AuthSessionState> = repository.authState
    val cacheCleanupFailed = repository.cacheCleanupFailed

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState

    init {
        (repository as? com.messenger.app.data.auth.AuthRepositoryImpl)?.let { auth ->
            viewModelScope.launch { auth.terminalReason.collect { reason ->
                if (reason != null) _uiState.value = AuthUiState.Error(authTerminalMessage(reason), reason)
            } }
        }
    }

    fun register(username: String, password: String, displayName: String, inviteCode: String? = null) {
        if (_uiState.value is AuthUiState.Loading) return
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            repository.registerWithInvite(username, password, displayName, inviteCode)
                .onSuccess { _uiState.value = AuthUiState.Idle }
                .onFailure { _uiState.value = AuthUiState.Error(errorMessage(it), (it as? AuthApiException.Api)?.code) }
        }
    }

    fun login(username: String, password: String) {
        if (_uiState.value is AuthUiState.Loading) return
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            repository.login(username, password)
                .onSuccess { _uiState.value = AuthUiState.Idle }
                .onFailure { _uiState.value = AuthUiState.Error(errorMessage(it)) }
        }
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    /** Clears a lingering [AuthUiState.Error] back to Idle (e.g. after the user
     *  starts editing a field). Never touches Loading or any other state. */
    fun clearError() {
        if (_uiState.value is AuthUiState.Error) _uiState.value = AuthUiState.Idle
    }

    /** Maps stable, machine-readable backend error codes to localized string
     *  resources - a raw English server message never reaches the UI (P0). */
    @StringRes
    private fun errorMessage(t: Throwable): Int = when (t) {
        is AuthApiException.Network -> R.string.error_network
        is AuthApiException.Api -> com.messenger.app.ui.selfhost.registrationFailureMessage(t.code) ?: when (t.code) {
            "installation_restricted" -> R.string.auth_installation_restricted
            "secure_connection_required" -> R.string.auth_installation_https_required
            "device_proof_stale" -> R.string.error_network
            "invalid_credentials" -> R.string.error_wrong_credentials
            // The sign-up field is the private login (B-029).
            "username_taken" -> R.string.error_login_taken
            "username_reserved" -> R.string.profile_username_reserved
            "display_name_reserved" -> R.string.profile_name_reserved
            "display_name_link" -> R.string.profile_name_link
            "display_name_leading_at" -> R.string.profile_name_leading_at
            "username_link" -> R.string.profile_username_link
            "validation_error" -> R.string.profile_validation_error
            "rate_limited" -> R.string.auth_error_rate_limited
            "too_many_devices" -> R.string.auth_error_too_many_devices
            "common_password" -> R.string.auth_error_common_password
            "session_revoked", "invalid_refresh_token", "unauthorized" -> R.string.auth_error_session_ended
            "account_deleted", "account_suspended", com.messenger.app.data.auth.HARDWARE_BINDING_REQUIRED -> authTerminalMessage(t.code)
            else -> R.string.error_generic
        }
        else -> R.string.error_generic
    }
}

@StringRes
internal fun authTerminalMessage(reason: String): Int = when (reason) {
    "account_deleted" -> R.string.auth_account_deleted
    "account_suspended" -> R.string.auth_account_suspended
    com.messenger.app.data.auth.HARDWARE_BINDING_REQUIRED -> R.string.auth_hardware_binding_required
    else -> R.string.auth_session_ended
}

class AuthViewModelFactory(private val repository: AuthRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AuthViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
