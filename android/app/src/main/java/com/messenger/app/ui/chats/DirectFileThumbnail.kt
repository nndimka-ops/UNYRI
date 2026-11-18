package com.messenger.app.ui.chats

import android.graphics.Bitmap
import androidx.compose.runtime.*
import com.messenger.app.data.directtransfer.*
import com.messenger.app.data.local.ChatImageCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** No repository observation in a row: only the immutable offer, completion and viewport gate are keys. */
@Composable
internal fun rememberDirectFileThumbnail(
    offer: DirectFileOffer,
    completed: Boolean,
    workAllowed: Boolean,
    checkedFile: suspend () -> File?,
): Bitmap? = key(offer) {
    val poster by produceState<Bitmap?>(null, completed, workAllowed) {
        if (!workAllowed) return@produceState
        val job = currentCoroutineContext()
        val result = try { withContext(Dispatchers.IO) {
            val file = if (completed) checkedFile() else null
            val safe = offer.safeMediaPreview
            val fallback: suspend () -> Bitmap? = {
                safe?.let { preview ->
                    ChatImageCache.load("direct-jpeg320:${preview.thumbnail}") { DirectFileMediaPreviewGenerator.decode(preview) }
                }
            }
            when (directFilePosterSource(offer, completed, file != null)) {
                DirectFilePosterSource.COMPLETED -> ChatImageCache.load("direct-local256:${file!!.path}:${offer.sha256}") {
                    DirectFileMediaPreviewGenerator.local(file, offer) { job.ensureActive() }
                } ?: fallback()
                DirectFilePosterSource.OFFER -> fallback()
                DirectFilePosterSource.NONE -> null
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        job.ensureActive()
        value = result
    }
    poster
}
