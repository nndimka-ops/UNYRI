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
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Loading : AuthUiState
    data class Error(@StringRes val messageRes: Int) : AuthUiState
}

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    val authState: StateFlow<AuthSessionState> = repository.authState
    val cacheCleanupFailed = repository.cacheCleanupFailed

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState

    fun register(username: String, password: String, displayName: String) {
        if (_uiState.value is AuthUiState.Loading) return
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            repository.register(username, password, displayName)
                .onSuccess { _uiState.value = AuthUiState.Idle }
                .onFailure { _uiState.value = AuthUiState.Error(errorMessage(it)) }
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
        is AuthApiException.Api -> when (t.code) {
            "installation_restricted" -> R.string.auth_installation_restricted
            "secure_connection_required" -> R.string.auth_installation_https_required
            "invalid_credentials" -> R.string.error_wrong_credentials
            "username_taken" -> R.string.error_username_taken
            "rate_limited" -> R.string.auth_error_rate_limited
            "session_revoked", "invalid_refresh_token", "unauthorized" -> R.string.auth_error_session_ended
            else -> R.string.error_generic
        }
        else -> R.string.error_generic
    }
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
