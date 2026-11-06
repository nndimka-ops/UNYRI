package com.messenger.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.messenger.app.domain.model.ConnectionState
import com.messenger.app.domain.repository.ConnectionRepository
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(
    private val repository: ConnectionRepository
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val log: StateFlow<List<String>> = repository.log

    fun connect() = repository.connect()
    fun sendPing() = repository.sendPing()
    fun disconnect() = repository.disconnect()

    override fun onCleared() {
        super.onCleared()
        repository.disconnect()
    }
}

class MainViewModelFactory(
    private val repository: ConnectionRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return MainViewModel(repository) as T
    }
}
