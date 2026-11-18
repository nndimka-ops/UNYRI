package com.messenger.app.ui.chats

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

internal fun mediaCanSaveToGallery(mime: String) = mime.startsWith("image/") || mime.startsWith("video/")

/** Publish only after the complete original has been copied; failures leave no empty asset. */
internal suspend fun saveMediaToGallery(context: Context, source: File, mime: String, title: String) = withContext(Dispatchers.IO) {
    require(mediaCanSaveToGallery(mime))
    val resolver = context.contentResolver
    val video = mime.startsWith("video/")
    val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, title)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/UNYRI")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(collection, values) ?: throw java.io.IOException("Media destination unavailable")
    try {
        resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
        } } ?: throw java.io.IOException("Media destination unavailable")
        currentCoroutineContext().ensureActive()
        if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } catch (error: Exception) { runCatching { resolver.delete(uri, null, null) }; throw error }
}
