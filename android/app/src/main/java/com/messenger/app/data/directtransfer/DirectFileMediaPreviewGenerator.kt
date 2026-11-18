package com.messenger.app.data.directtransfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Build
import com.messenger.app.data.attachment.ImageProcessor
import com.messenger.app.data.local.Api26VideoFrame
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.*

/** Optional single-frame work on owned files only; never follows a peer URL or uploads an attachment. */
internal object DirectFileMediaPreviewGenerator {
    private val worker = Dispatchers.IO.limitedParallelism(1)

    suspend fun read(file: File, mime: String, sourceBytes: Long): DirectFileMediaPreview? = withContext(worker) {
        if (sourceBytes !in 1..DirectFileWire.MAX_FILE_BYTES || !file.isFile || file.length() != sourceBytes) return@withContext null
        withTimeoutOrNull(3_000) {
            val job = currentCoroutineContext()
            try {
                val (frame, duration) = when {
                    mime.startsWith("image/") -> (image(file, sourceBytes, 512) to null)
                    mime.startsWith("video/") -> video(file, 512, true) { job.ensureActive() }
                    else -> return@withTimeoutOrNull null
                }
                frame ?: return@withTimeoutOrNull null
                try {
                    job.ensureActive()
                    val bytes = DirectPreviewBounds.encode(frame.width, frame.height) { width, height, quality ->
                        job.ensureActive()
                        // iOS renders into an opaque black-backed canvas before encoding.
                        val opaque = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(opaque).apply {
                                drawColor(Color.BLACK)
                                drawBitmap(frame, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                            }
                            ByteArrayOutputStream().use { output ->
                                if (opaque.compress(Bitmap.CompressFormat.JPEG, quality, output)) output.toByteArray() else null
                            }
                        } finally { opaque.recycle() }
                    } ?: return@withTimeoutOrNull null
                    job.ensureActive()
                    val dimensions = DirectFileMediaPreview.jpegDimensions(bytes) ?: return@withTimeoutOrNull null
                    DirectFileMediaPreview(if (mime.startsWith("image/")) "image" else "video",
                        Base64.getEncoder().encodeToString(bytes), dimensions.first, dimensions.second, duration).takeIf { it.valid }
                } finally { frame.recycle() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }

    /** Invoked only inside ChatImageCache's bounded IO decode slots. */
    fun decode(preview: DirectFileMediaPreview): Bitmap? {
        if (!preview.valid) return null
        val bytes = preview.jpegData() ?: return null
        if (!DirectFileMediaPreview.singleJpeg(bytes)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth != preview.width || bounds.outHeight != preview.height) return null
        var sample = 1
        while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bounded(decoded, 320)
    }

    fun local(file: File, offer: DirectFileOffer, checkActive: () -> Unit): Bitmap? {
        try {
            checkActive()
            if (!file.isFile || file.length() != offer.size) return null
            val frame = when {
                offer.mimeType.startsWith("image/") -> ImageProcessor.chatPreview(file, 256)
                offer.mimeType.startsWith("video/") -> video(file, 256, false, checkActive).first
                else -> null
            }
            try { checkActive() } catch (cancelled: CancellationException) { frame?.recycle(); throw cancelled }
            return frame
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
    }

    private fun image(file: File, bytes: Long, edge: Int): Bitmap? {
        if (bytes !in 1..DirectPreviewBounds.MAX_IMAGE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (!DirectPreviewBounds.imageAllowed(bytes, bounds.outWidth, bounds.outHeight)) return null
        return ImageProcessor.chatPreview(file, edge)
    }

    private fun video(file: File, edge: Int, readDuration: Boolean, checkActive: () -> Unit): Pair<Bitmap?, Long?> {
        val reader = MediaMetadataRetriever()
        try {
            file.inputStream().use { reader.setDataSource(it.fd) }
            checkActive()
            val duration = if (readDuration) reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.takeIf { it in 0..DirectFileMediaPreview.MAX_DURATION_MS } else null
            val frame = if (Build.VERSION.SDK_INT == 26) Api26VideoFrame.read(file, edge, 0, checkActive)
            else {
                val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null to duration
                val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null to duration
                if (width <= 0 || height <= 0) return null to duration
                val (w, h) = DirectPreviewBounds.fit(width, height, edge)
                reader.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
            }
            try { checkActive() } catch (cancelled: CancellationException) { frame?.recycle(); throw cancelled }
            return frame?.let { bounded(it, edge) } to duration
        } finally { reader.release() }
    }

    private fun bounded(bitmap: Bitmap, edge: Int): Bitmap {
        val (w, h) = DirectPreviewBounds.fit(bitmap.width, bitmap.height, edge)
        if (w == bitmap.width && h == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, w, h, true).also { bitmap.recycle() }
    }
}
