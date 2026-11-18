package com.messenger.app.capture

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.messenger.app.data.auth.AccountFileProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only the two private files allocated for this exact capture may be returned. */
internal class DirectCaptureFiles private constructor(val photo: File, val video: File) {
    fun file(isVideo: Boolean) = if (isVideo) video else photo
    fun uri(context: Context, isVideo: Boolean): Uri {
        val ownerContext = context.applicationContext
        return AccountFileProvider.uriForFile(ownerContext, "${ownerContext.packageName}.fileprovider", file(isVideo))
    }
    fun discard() { photo.delete(); video.delete() }

    companion object {
        fun from(context: Context, record: PendingExternalCapture): DirectCaptureFiles? = from(context, record) { ownerContext, photo ->
            AccountFileProvider.uriForFile(ownerContext, "${ownerContext.packageName}.fileprovider", photo).toString()
        }

        internal fun from(context: Context, record: PendingExternalCapture, photoUri: (Context, File) -> String): DirectCaptureFiles? = runCatching {
            val ownerContext = context.applicationContext
            val root = captureCacheDirectory(ownerContext, "camera").canonicalFile
            val photo = File(requireNotNull(record.directSource)).canonicalFile
            require(photo.parentFile == root && photo.name == "capture_${record.id}.jpg")
            require(java.util.UUID.fromString(record.id).toString() == record.id)
            val result = DirectCaptureFiles(photo, File(root, "capture_${record.id}.mp4"))
            require(photoUri(ownerContext, photo) == record.uri)
            result
        }.getOrNull()
    }
}

/** Raw Activity and provider contexts must resolve the account-owned cache through the application. */
internal fun captureCacheDirectory(context: Context, directory: String): File =
    File(context.applicationContext.cacheDir, directory)

/** The same byte-level validation gates review confirmation and durable replay. */
internal suspend fun validDirectCaptureMedia(file: File, isVideo: Boolean): Boolean = withContext(Dispatchers.IO) {
    if (!file.isFile || file.length() == 0L) return@withContext false
    try {
        if (isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" &&
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" &&
                    (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0 &&
                    (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0) > 0
            } finally { retriever.release() }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == "image/jpeg"
        }
    } catch (cancel: CancellationException) { throw cancel }
      catch (_: Exception) { false }
}
