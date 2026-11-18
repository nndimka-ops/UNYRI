package com.messenger.app.data.attachment

import com.messenger.app.domain.repository.QueuedMediaDraft
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ComposerSourceInfo(val name: String, val bytes: Long?, val mimeType: String) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

/** The provider boundary is always IO, including MIME and metadata lookups. */
class ComposerSourceResolver(private val read: (String) -> ComposerSourceInfo, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun resolve(source: String): ComposerSourceInfo = withContext(dispatcher) { read(source) }
}

class ComposerSourceUnavailableException : FileNotFoundException("Source needs reselection")
fun isUnavailableComposerSource(error: Throwable): Boolean =
    error is SecurityException || error is FileNotFoundException || error is java.nio.file.NoSuchFileException

data class PreparedComposerItem(val selectionUri: String, val media: QueuedMediaDraft)
data class PreparedComposerBatch(val items: List<PreparedComposerItem>) {
    val selectionUris: Set<String> get() = items.map { it.selectionUri }.toSet()
    val media: List<QueuedMediaDraft> get() = items.map { it.media }
    fun albumDrafts(caption: String?, albumId: String): List<QueuedMediaDraft> =
        media.mapIndexed { index, draft -> draft.copy(caption = caption.takeIf { index == 0 }, albumId = albumId) }
}

/** A revoked/deleted source retires only its own preparation, preserving pick order. */
suspend fun prepareComposerItems(sources: List<String>, unavailable: (String) -> Unit,
    prepare: suspend (String) -> QueuedMediaDraft): PreparedComposerBatch {
    val ready = mutableListOf<PreparedComposerItem>()
    for (source in sources.distinct()) {
        try { ready += PreparedComposerItem(source, prepare(source)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (isUnavailableComposerSource(error)) unavailable(source) else throw error }
    }
    return PreparedComposerBatch(ready)
}
