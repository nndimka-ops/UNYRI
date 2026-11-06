package com.messenger.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.local.MediaCacheManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Data & Storage screen state (Phase 9.1). All file-system work runs inside
 * [MediaCacheManager] on Dispatchers.IO, so scan/clear never block the UI.
 */
class StorageViewModel(private val manager: MediaCacheManager) : ViewModel() {

    /** Null = calculating. */
    private val _usage = MutableStateFlow<MediaCacheManager.Usage?>(null)
    val usage: StateFlow<MediaCacheManager.Usage?> = _usage

    private val _clearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = _clearing

    /** Number of files that could not be removed by the last clear (0 = clean). */
    private val _clearFailedCount = MutableStateFlow(0)
    val clearFailedCount: StateFlow<Int> = _clearFailedCount

    private val _chats = MutableStateFlow<List<MediaCacheManager.ChatUsage>>(emptyList())
    val chats: StateFlow<List<MediaCacheManager.ChatUsage>> = _chats
    private val _policy = MutableStateFlow(manager.policy())
    val policy: StateFlow<com.messenger.app.data.local.CachePolicy> = _policy
    fun savePolicy(policy: com.messenger.app.data.local.CachePolicy) {
        manager.savePolicy(policy); _policy.value = manager.policy()
        viewModelScope.launch { manager.enforcePolicy(); refresh() }
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _usage.value = manager.scan(); _chats.value = manager.chatUsage() }
    }

    fun clear(chatId: String? = null) {
        if (_clearing.value) return
        viewModelScope.launch {
            _clearing.value = true
            try {
                val result = manager.clear(chatId)
                _clearFailedCount.value = result.failedFiles
                _usage.value = manager.scan(); _chats.value = manager.chatUsage()
            } finally { _clearing.value = false }
        }
    }
}
