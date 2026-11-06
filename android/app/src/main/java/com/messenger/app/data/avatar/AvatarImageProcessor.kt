package com.messenger.app.data.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Avatar-specific image pipeline (E.1): sampled decode (never decodes a
 * 20-50MP phone photo at full resolution), orientation correction, square crop,
 * and a fresh 512x512 JPEG re-encode. Re-encoding from raw decoded pixels is
 * what guarantees EXIF/GPS privacy: `Bitmap.compress()` never writes an EXIF
 * segment, so the output structurally cannot carry the original's
 * GPS/camera-model/timestamp metadata - there is nothing to strip after the
 * fact because it was never copied over.
 */
object AvatarImageProcessor {

    /** Final avatar dimensions - square, JPEG. */
    const val AVATAR_SIZE = 512

    /** JPEG quality 85-90. */
    const val AVATAR_JPEG_QUALITY = 90

    /** Cap for the crop-editor decode: plenty for on-screen editing, far below
     *  a full-resolution phone photo. */
    const val MAX_EDIT_DIMENSION = 2048

    /** Sampled decode of a content Uri, applying EXIF orientation. Never
     *  upscales a small source. */
    fun decodeForEdit(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, MAX_EDIT_DIMENSION)

        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val decoded = BitmapFactory.Options().apply { inSampleSize = sample }.let { options ->
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: error("Could not decode image at $uri")
        }
        val oriented = applyOrientation(decoded, orientation)
        if (oriented !== decoded) decoded.recycle()
        return oriented
    }

    /** Power-of-two sample size so `inSampleSize` keeps BOTH dimensions at or
     *  under [maxDimension] (a decoded edit bitmap never exceeds the cap in
     *  either axis). Pure and JVM-testable. */
    fun computeSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    /** Crops [crop] out of [source] and scales it to exactly
     *  [AVATAR_SIZE]x[AVATAR_SIZE] (never recycles [source]). */
    fun cropToSquare(source: Bitmap, crop: CropRect): Bitmap {
        val x = crop.x.coerceIn(0, source.width - 1)
        val y = crop.y.coerceIn(0, source.height - 1)
        val size = crop.size.coerceAtMost(source.width - x).coerceAtMost(source.height - y).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(source, x, y, size, size)
        if (size != AVATAR_SIZE) {
            val scaled = Bitmap.createScaledBitmap(cropped, AVATAR_SIZE, AVATAR_SIZE, true)
            cropped.recycle()
            return scaled
        }
        return cropped
    }

    /** Encodes an already-square bitmap as a JPEG (fresh pixels -> no EXIF). */
    fun encodeJpeg(bitmap: Bitmap, outputFile: File): ProcessedAvatar {
        FileOutputStream(outputFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, out)
        }
        return ProcessedAvatar(outputFile, bitmap.width, bitmap.height)
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
}

data class ProcessedAvatar(val file: File, val width: Int, val height: Int)
