package com.messenger.app.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.messenger.app.domain.model.DeviceInfo
import com.messenger.app.domain.model.SecurityEventInfo
import com.messenger.app.domain.repository.DevicesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface DevicesUiState {
    data object Loading : DevicesUiState
    data class Loaded(
        val devices: List<DeviceInfo>,
        val events: List<SecurityEventInfo>,
        val actionError: String? = null,
        val isCached: Boolean = false,
    ) : DevicesUiState
    data class Error(val message: String) : DevicesUiState
}

class DevicesViewModel(
    private val repository: DevicesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DevicesUiState>(DevicesUiState.Loading)
    val uiState: StateFlow<DevicesUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        repository.cachedDevices()?.let { _uiState.value = DevicesUiState.Loaded(it, emptyList(), isCached = true) }
        if (_uiState.value !is DevicesUiState.Loaded) _uiState.value = DevicesUiState.Loading
        viewModelScope.launch {
            val devices = repository.listDevices()
            val events = repository.securityEvents(limit = 30)
            _uiState.value = when {
                devices.isFailure -> (_uiState.value as? DevicesUiState.Loaded)?.copy(actionError = devices.exceptionOrNull()?.message)
                    ?: DevicesUiState.Error(devices.exceptionOrNull()?.message ?: "Failed to load devices")
                else -> DevicesUiState.Loaded(
                    devices = devices.getOrDefault(emptyList()),
                    events = events.getOrDefault(emptyList<SecurityEventInfo>() to null).first,
                )
            }
        }
    }

    fun rename(deviceId: String, newName: String) {
        viewModelScope.launch {
            repository.renameDevice(deviceId, newName)
            refresh()
        }
    }

    fun revoke(deviceId: String) {
        viewModelScope.launch {
            val result = repository.revokeDevice(deviceId)
            (current() as? DevicesUiState.Loaded)?.let { state ->
                _uiState.value = state.copy(actionError = result.exceptionOrNull()?.message)
            }
            refresh()
        }
    }

    fun revokeOthers() {
        viewModelScope.launch {
            repository.revokeOtherDevices()
            refresh()
        }
    }

    private fun current(): DevicesUiState = _uiState.value
}

class DevicesViewModelFactory(
    private val repository: DevicesRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DevicesViewModel(repository) as T
}
