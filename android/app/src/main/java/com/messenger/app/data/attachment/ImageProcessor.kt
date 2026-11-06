package com.messenger.app.data.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class ProcessedImage(val file: File, val width: Int, val height: Int, val thumbnailBase64: String, val mimeType: String = "image/jpeg")

/**
 * Decode -> downscale (never upscale a small photo) -> orientation-correct
 * -> re-encode as a FRESH JPEG. Re-encoding from raw decoded pixels is what
 * guarantees EXIF privacy (Этап H п.21): `Bitmap.compress()` never writes
 * an EXIF segment, so the output file structurally cannot carry the
 * original's GPS/camera-model/timestamp metadata - there is nothing to
 * "strip" after the fact because it was never copied over in the first
 * place.
 */
object ImageProcessor {
    private const val MAX_DIMENSION = 2048
    private const val JPEG_QUALITY = 85

    fun process(context: Context, uri: Uri, outputFile: File, highQuality: Boolean = false): ProcessedImage {
        val resolver = context.contentResolver

        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 150_000_000L) { "Unsupported image dimensions" }
        val maxDimension = if (highQuality) 4096 else MAX_DIMENSION
        val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)

        val orientation = runCatching { resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: error("Could not decode image at $uri")

        val rotated = applyOrientation(decoded, orientation)
        val finalBitmap = downscaleIfNeeded(rotated, maxDimension)

        val preserve = bounds.outMimeType in setOf("image/gif", "image/webp")
        val mime = if (preserve) bounds.outMimeType else if (finalBitmap.hasAlpha()) "image/png" else "image/jpeg"
        val extension = when (mime) { "image/gif" -> "gif"; "image/webp" -> "webp"; "image/png" -> "png"; else -> "jpg" }
        val actualFile = File(outputFile.parentFile, outputFile.nameWithoutExtension + "." + extension)
        if (preserve) {
            resolver.openInputStream(uri)?.use { input -> actualFile.outputStream().use { out ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= AttachmentFormat.MAX_IMAGE_BYTES) { "Image too large" }; out.write(buffer,0,n) }
            } } ?: error("Cannot read image")
        } else FileOutputStream(actualFile).use { out ->
            check(finalBitmap.compress(if (mime == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (highQuality) 92 else JPEG_QUALITY, out)) { "Image encoding failed" }
        }
        val thumbnailBase64 = createThumbnailBase64(finalBitmap)

        if (rotated !== decoded) decoded.recycle()
        if (finalBitmap !== rotated) rotated.recycle()
        val width = finalBitmap.width
        val height = finalBitmap.height
        finalBitmap.recycle()

        return ProcessedImage(actualFile, if (preserve) bounds.outWidth else width, if (preserve) bounds.outHeight else height, thumbnailBase64, mime)
    }

    /** Optional bounded preview; never rewrites the original file. */
    fun originalThumbnail(file: File): String? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 150_000_000L) return null
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, 512)
        }
        val decoded = android.graphics.BitmapFactory.decodeFile(file.path, options) ?: return null
        var oriented = decoded
        try {
            val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            oriented = applyOrientation(decoded, orientation)
            createThumbnailBase64(oriented).takeIf { it.isNotEmpty() }
        } finally {
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
        }
    }.getOrNull()

    private fun computeSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxDimension || h / 2 >= maxDimension) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Never upscales a photo smaller than the target (Этап H п.20). */
    private fun downscaleIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largest
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /** More detail for simple scenes, with the same 16 KiB wire budget as video. */
    internal fun createThumbnailBase64(bitmap: Bitmap): String {
        for (edge in listOf(512, 384, 256, 128)) {
            val thumb = downscaleIfNeeded(bitmap, edge)
            try {
                for (quality in listOf(82, 70, 55)) {
                    val out = ByteArrayOutputStream()
                    thumb.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    if (out.size() <= 16 * 1024) return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }
            } finally { if (thumb !== bitmap) thumb.recycle() }
        }
        return ""
    }

    /** Bounded, EXIF-correct local preview; invoked through ChatImageCache off the UI thread. */
    internal fun chatPreview(file: File, edge: Int): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 150_000_000L) return null
        val target = edge.coerceIn(256, 768)
        val decoded = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, target)
        }) ?: return null
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val oriented = applyOrientation(decoded, orientation)
        if (oriented !== decoded) decoded.recycle()
        val result = downscaleIfNeeded(oriented, target)
        if (result !== oriented) oriented.recycle()
        return result
    }
}
