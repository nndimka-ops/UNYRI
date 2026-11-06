package com.messenger.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.avatar.AvatarRepository
import com.messenger.app.domain.model.AvatarUpdate
import com.messenger.app.domain.model.BioUpdate
import com.messenger.app.domain.model.UserProfile
import com.messenger.app.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class Loaded(val profile: UserProfile, val isCached: Boolean = false) : ProfileUiState
    data class Error(val message: String) : ProfileUiState
}

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data object Success : SaveState
    data class Error(val message: String) : SaveState
}

class ProfileViewModel(
    private val repository: ProfileRepository,
    private val avatarRepository: AvatarRepository,
    private val authRepository: com.messenger.app.domain.repository.AuthRepository,
    private val connection: kotlinx.coroutines.flow.Flow<com.messenger.app.domain.repository.SocketConnectionState> = kotlinx.coroutines.flow.emptyFlow(),
) : ViewModel() {

    private val _deleteState = MutableStateFlow<SaveState>(SaveState.Idle)
    val deleteState: StateFlow<SaveState> = _deleteState

    fun resetDeleteState() { if (_deleteState.value !is SaveState.Saving) _deleteState.value = SaveState.Idle }

    fun deleteAccount(password: String) {
        if (!isCurrentOwner() || _deleteState.value is SaveState.Saving) return
        _deleteState.value = SaveState.Saving
        viewModelScope.launch {
            try {
                authRepository.deleteAccount(password)
                    .onSuccess { _deleteState.value = SaveState.Success }
                    .onFailure { _deleteState.value = SaveState.Error("") }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                _deleteState.value = SaveState.Idle
                throw cancel
            }
        }
    }

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState

    private var refreshJob: kotlinx.coroutines.Job? = null
    private val epoch = authRepository.currentSessionEpoch()
    private val ownerId = (authRepository.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id
    private val origin = com.messenger.app.data.api.ServerEndpoint.current()
    private fun isCurrentOwner() = authRepository.currentSessionEpoch() == epoch &&
        com.messenger.app.data.api.ServerEndpoint.current() == origin

    private fun acceptProfile(profile: UserProfile) {
        if (!isCurrentOwner() || profile.id != ownerId) return
        val loaded = (_uiState.value as? ProfileUiState.Loaded)?.profile
        if (loaded != null && (loaded.id != profile.id || loaded.profileVersion > profile.profileVersion ||
                    loaded.avatarVersion > profile.avatarVersion)) return
        _uiState.value = ProfileUiState.Loaded(profile)
    }

    init {
        refresh()
        viewModelScope.launch {
            repository.profileUpdates.collect { acceptProfile(it) }
        }
        viewModelScope.launch {
            connection.collect { state ->
                if (state == com.messenger.app.domain.repository.SocketConnectionState.CONNECTED &&
                    !repository.usesAppRefreshCoordinator) refresh()
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (!isCurrentOwner()) return@launch
            val cached = repository.cachedMe()
            if (_uiState.value !is ProfileUiState.Loaded && cached != null) _uiState.value = ProfileUiState.Loaded(cached, isCached = true)
            else if (_uiState.value !is ProfileUiState.Loaded) _uiState.value = ProfileUiState.Loading
            val result = repository.getMe()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!isCurrentOwner()) return@launch
            result.onSuccess { acceptProfile(it) }
                .onFailure { if (_uiState.value !is ProfileUiState.Loaded) _uiState.value = ProfileUiState.Error(errorMessage(it)) }
        }
    }

    fun save(username: String?, displayName: String?, bioUpdate: BioUpdate, avatarUpdate: AvatarUpdate) {
        performSave(closeEditor = true) {
            if (username == null && displayName == null && bioUpdate == BioUpdate.Keep && avatarUpdate == AvatarUpdate.Keep) {
                (_uiState.value as? ProfileUiState.Loaded)?.profile ?: repository.getMe().getOrThrow()
            } else repository.updateProfile(username, displayName, bioUpdate, avatarUpdate).getOrThrow()
        }
    }

    /** E.1: upload a downscaled 512x512 avatar JPEG, commit its object key as
     *  the authoritative avatar (server HEAD-verifies + bumps versions exactly
     *  once), and warm the app cache so the UI renders it instantly. */
    fun uploadAvatar(bytes: ByteArray) {
        performSave(closeEditor = false) {
            val intent = repository.createAvatarUpload(bytes.size.toLong()).getOrThrow()
            checkSaveOwner()
            repository.uploadAvatarBytes(intent.uploadUrl, bytes).getOrThrow()
            checkSaveOwner()
            val profile = repository.completeAvatarUpload(intent.objectKey).getOrThrow()
            checkSaveOwner()
            if (profile.hasAvatar && profile.avatarUrl == intent.objectKey) updateCurrentAvatarCache(profile) {
                // The mutation response identifies only the bytes uploaded by this action.
                avatarRepository.put(profile.id, profile.avatarVersion, bytes)
            }
            profile
        }
    }

    /** E.1: authoritative server-side removal (bumps versions only if an avatar
     *  actually existed) + cache invalidation -> letter fallback. */
    fun removeAvatar() {
        performSave(closeEditor = false) {
            val profile = repository.removeAvatar().getOrThrow()
            checkSaveOwner()
            if (!profile.hasAvatar) updateCurrentAvatarCache(profile) { avatarRepository.invalidate(profile.id) }
            profile
        }
    }

    /** Canonical publication uses this same existing session gate. The version
     * check and cache write therefore cannot straddle a newer remote picture. */
    private suspend fun updateCurrentAvatarCache(profile: UserProfile, action: () -> Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            authRepository.withSessionWrite(epoch) {
                checkSaveOwner()
                val current = repository.cachedMe()
                if (current == null || (current.id == profile.id && current.hasAvatar == profile.hasAvatar &&
                    current.profileVersion <= profile.profileVersion && current.avatarVersion == profile.avatarVersion)) action()
            } ?: throw kotlinx.coroutines.CancellationException("Profile owner changed")
        }
    }

    /** Reserve synchronously: Compose can deliver another tap before recomposition.
     * Avatar changes update the picture without discarding the rest of the form. */
    private fun performSave(closeEditor: Boolean, action: suspend () -> UserProfile) {
        val before = _saveState.value
        if (!isCurrentOwner() || before == SaveState.Saving || before == SaveState.Success ||
            !_saveState.compareAndSet(before, SaveState.Saving)) return
        viewModelScope.launch {
            try {
                checkSaveOwner()
                val profile = action()
                checkSaveOwner()
                acceptProfile(profile)
                _saveState.value = if (closeEditor) SaveState.Success else SaveState.Idle
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (authRepository.currentSessionEpoch() == epoch) _saveState.value = SaveState.Error(errorMessage(failure))
            } finally {
                if (_saveState.value == SaveState.Saving) _saveState.value = SaveState.Idle
            }
        }
    }

    private suspend fun checkSaveOwner() {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (!isCurrentOwner()) throw kotlinx.coroutines.CancellationException("Profile owner changed")
    }

    fun resetSaveState() {
        if (_saveState.value != SaveState.Saving) _saveState.value = SaveState.Idle
    }

    private fun errorMessage(t: Throwable): String = when (t) {
        is AuthApiException.Api -> t.message ?: "Something went wrong."
        is AuthApiException.Network -> "Network error. Please try again."
        else -> "Something went wrong."
    }
}

class ProfileViewModelFactory(
    private val repository: ProfileRepository,
    private val avatarRepository: AvatarRepository,
    private val authRepository: com.messenger.app.domain.repository.AuthRepository,
    private val connection: kotlinx.coroutines.flow.Flow<com.messenger.app.domain.repository.SocketConnectionState> = kotlinx.coroutines.flow.emptyFlow(),
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ProfileViewModel(repository, avatarRepository, authRepository, connection) as T
    }
}
