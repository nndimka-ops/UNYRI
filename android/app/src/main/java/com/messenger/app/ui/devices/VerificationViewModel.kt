package com.messenger.app.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.local.signal.PeerIdentityEntity
import com.messenger.app.data.signal.IdentityVerificationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VerificationUiState(
    val otherUserId: String,
    val peers: List<PeerIdentityEntity> = emptyList(),
    val actionError: String? = null,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
)

class VerificationViewModel(
    private val identityVerification: IdentityVerificationManager,
    private val otherUserId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VerificationUiState(otherUserId))
    val uiState: StateFlow<VerificationUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            identityVerification.observeForUser(otherUserId).collect { peers ->
                _uiState.value = _uiState.value.copy(peers = peers)
            }
        }
    }

    fun refresh() {
        if (_uiState.value.loading) return
        _uiState.value = _uiState.value.copy(loading = true, loadFailed = false)
        viewModelScope.launch {
            try { identityVerification.refreshKnownDevices(otherUserId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _uiState.value = _uiState.value.copy(loadFailed = true) }
            finally { _uiState.value = _uiState.value.copy(loading = false) }
        }
    }

    fun markVerified(signalDeviceId: Int) {
        viewModelScope.launch {
            runCatching { identityVerification.markVerified(otherUserId, signalDeviceId) }
                .onSuccess { _uiState.value = _uiState.value.copy(actionError = null) }
                .onFailure { _uiState.value = _uiState.value.copy(actionError = it.message) }
        }
    }

    fun acceptNewKey(peer: PeerIdentityEntity) {
        viewModelScope.launch {
            runCatching { identityVerification.acceptNewKey(otherUserId, peer.signalDeviceId, requireNotNull(peer.identityKeyB64)) }
                .onSuccess { _uiState.value = _uiState.value.copy(actionError = null) }
                .onFailure { _uiState.value = _uiState.value.copy(actionError = it.message) }
        }
    }
}

class VerificationViewModelFactory(
    private val identityVerification: IdentityVerificationManager,
    private val otherUserId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        VerificationViewModel(identityVerification, otherUserId) as T
}
