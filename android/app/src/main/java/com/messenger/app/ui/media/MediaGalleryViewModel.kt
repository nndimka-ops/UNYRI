package com.messenger.app.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.attachment.AttachmentDownloader
import com.messenger.app.data.attachment.MediaGallerySource
import com.messenger.app.data.attachment.VoicePlayback
import com.messenger.app.domain.model.MediaItem
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class MediaTab(val wireType: String) { Photos("image"), Files("file"), Voice("voice") }

data class MediaGalleryUiState(
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val openingId: String? = null,
    val error: String? = null,
    val openedFile: File? = null,
    val openedFileMimeType: String? = null,
    val openedAttachmentId: String? = null,
)

class MediaGalleryViewModel(
    private val chatId: String,
    private val repository: MediaGallerySource,
    private val attachmentManager: AttachmentDownloader,
    private val voicePlayerController: VoicePlayback,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaGalleryUiState())
    val uiState: StateFlow<MediaGalleryUiState> = _uiState

    var currentTab: MediaTab = MediaTab.Photos
        private set

    private var pageJob: Job? = null
    private var generation = 0

    private var lastSequence: Long = Long.MAX_VALUE

    init {
        // M.2: the FIRST tab (Photos) must render on first open - no second
        // tap required (BUG 3). Exactly one initial request; recomposition
        // never re-runs init, and selectTab/loadPage handle the rest.
        loadPage()
    }

    fun selectTab(tab: MediaTab) {
        if (currentTab == tab) return
        generation++
        pageJob?.cancel()
        currentTab = tab
        lastSequence = Long.MAX_VALUE
        _uiState.value = MediaGalleryUiState(loading = true)
        loadPage()
    }

    fun loadPage() {
        if (pageJob?.isActive == true) return
        val requestGeneration = generation
        val tab = currentTab
        val cursor = lastSequence
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        pageJob = viewModelScope.launch {
            try {
                val items = repository.page(chatId, tab.wireType, beforeSequence = cursor)
                if (requestGeneration != generation) return@launch
                if (items.isNotEmpty()) lastSequence = items.last().sequence
                _uiState.value = _uiState.value.copy(
                    items = (_uiState.value.items + items).distinctBy { it.attachmentId },
                    loading = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) {
                    _uiState.value = _uiState.value.copy(loading = false, error = "Could not load media.")
                }
            }
        }
    }

    fun dismissError() { _uiState.value = _uiState.value.copy(error = null) }

    /** Reuses the existing attachment pipeline (H/H.2): download -> verify ->
     *  decrypt, never a second crypto path (M.21/M.22). */
    fun open(item: MediaItem) {
        if (_uiState.value.openingId != null) return
        val requestGeneration = generation
        _uiState.value = _uiState.value.copy(openingId = item.attachmentId, error = null)
        viewModelScope.launch {
            try {
                val file = attachmentManager.download(item.attachmentId)
                if (requestGeneration != generation) return@launch
                if (item.mediaType == MediaTab.Voice.wireType) {
                    voicePlayerController.togglePlay(item.attachmentId, file)
                } else {
                    // M.2 / BUG 4: carry the REAL MIME type (stored in Room)
                    // through to the FileProvider open intent; never hardcode
                    // application/octet-stream when the actual type is known.
                    _uiState.value = _uiState.value.copy(openedFile = file, openedFileMimeType = item.mimeType,
                        openedAttachmentId = item.attachmentId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) _uiState.value = _uiState.value.copy(error = "Download failed.")
            } finally {
                if (requestGeneration == generation) _uiState.value = _uiState.value.copy(openingId = null)
            }
        }
    }

    fun clearOpenedFile() {
        _uiState.value = _uiState.value.copy(openedFile = null, openedFileMimeType = null, openedAttachmentId = null)
    }

    /** The viewer requests only its selected page through the existing verified download pipeline.
     * No second open event or stale-tab result is published back to the gallery. */
    suspend fun downloadMediaForViewer(attachmentId: String): File {
        val requestGeneration = generation
        currentCoroutineContext().ensureActive()
        val file = attachmentManager.download(attachmentId)
        currentCoroutineContext().ensureActive()
        if (requestGeneration != generation) throw CancellationException("Media gallery changed")
        return file
    }
}

class MediaGalleryViewModelFactory(
    private val chatId: String,
    private val repository: MediaGallerySource,
    private val attachmentManager: AttachmentDownloader,
    private val voicePlayerController: VoicePlayback,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return MediaGalleryViewModel(chatId, repository, attachmentManager, voicePlayerController) as T
    }
}
