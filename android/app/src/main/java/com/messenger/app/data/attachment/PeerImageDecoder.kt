package com.messenger.app.data.attachment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/** All bounds probes are gated too. Default decodes get the existing 2048px ceiling. */
object PeerImageDecoder {
    private fun dimensions(options: BitmapFactory.Options) = options.outWidth in 1..32768 && options.outHeight in 1..32768 &&
        options.outWidth.toLong() * options.outHeight <= 150_000_000L
    private fun options(bounds: BitmapFactory.Options, requested: BitmapFactory.Options?): BitmapFactory.Options =
        requested ?: BitmapFactory.Options().apply { while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > 2048) inSampleSize = inSampleSize.coerceAtLeast(1) * 2 }
    fun allowed(file: File): Boolean = runCatching { file.inputStream().use { allowed(it) } }.getOrDefault(false)
    private fun allowed(input: InputStream): Boolean {
        val header = ByteArray(12); var count = 0
        while (count < header.size) { val n = input.read(header, count, header.size - count); if (n <= 0) break; count += n }
        return PeerImageSignature.allowed(header, 0, count)
    }
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: BitmapFactory.Options? = null): Bitmap? {
        if (!PeerImageSignature.allowed(data, offset, length)) return null
        val bounds = opts?.takeIf { it.inJustDecodeBounds } ?: BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, offset, length, bounds)
        if (!dimensions(bounds)) { bounds.outWidth = -1; bounds.outHeight = -1; return null }
        if (opts?.inJustDecodeBounds == true) return null
        return BitmapFactory.decodeByteArray(data, offset, length, options(bounds, opts))
    }
    fun decodeFile(path: String?, opts: BitmapFactory.Options? = null): Bitmap? = runCatching {
        if (path == null || !allowed(File(path))) return null
        val bounds = opts?.takeIf { it.inJustDecodeBounds } ?: BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (!dimensions(bounds)) { bounds.outWidth = -1; bounds.outHeight = -1; return null }
        if (opts?.inJustDecodeBounds == true) return null
        BitmapFactory.decodeFile(path, options(bounds, opts))
    }.getOrNull()
    fun decodeStream(input: InputStream, padding: Rect? = null, opts: BitmapFactory.Options? = null): Bitmap? {
        val stream = if (input.markSupported()) input else BufferedInputStream(input)
        stream.mark(12)
        if (!allowed(stream)) return null
        stream.reset()
        // Stream callers already perform a bounded probe followed by a sampled decode.
        if (opts == null) return null
        val bitmap = BitmapFactory.decodeStream(stream, padding, opts)
        if (opts.inJustDecodeBounds && !dimensions(opts)) { opts.outWidth = -1; opts.outHeight = -1 }
        return bitmap
    }
}
