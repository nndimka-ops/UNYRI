package com.messenger.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val decodedAvatars = object : LruCache<ByteArray, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: ByteArray, value: Bitmap) = key.size + value.byteCount
}

/** Reuse decoded avatars on navigation; never decode images on the UI thread. */
@Composable
internal fun rememberAvatarBitmap(bytes: ByteArray?): Bitmap? {
    if (bytes == null) return null
    return key(bytes) {
        val bitmap by produceState(decodedAvatars.get(bytes), bytes) {
            if (value == null) value = withContext(Dispatchers.Default) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.also { decodedAvatars.put(bytes, it) }
            }
        }
        bitmap
    }
}
