package com.messenger.app.push

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded decoding off the UI thread; notification delivery never waits for an image. */
data class NotificationAvatar(val userId: String, val name: String, val bytes: ByteArray?) {
    suspend fun person(): Person = withContext(Dispatchers.Default) {
        val bitmap = bytes?.takeIf { it.size <= 3 * 1024 * 1024 }?.let { data ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@let null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        val icon = bitmap ?: Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { fallback ->
            val canvas = android.graphics.Canvas(fallback)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = android.graphics.Color.rgb(106, 78, 190)
            canvas.drawCircle(64f, 64f, 64f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 56f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            val initial = name.trim().takeIf { it.isNotEmpty() }?.let { String(Character.toChars(it.codePointAt(0))) } ?: "?"
            canvas.drawText(initial.uppercase(), 64f, 64f - (paint.ascent() + paint.descent()) / 2, paint)
        }
        Person.Builder().setKey(userId).setName(name.ifBlank { "UNYRI" })
            .setIcon(IconCompat.createWithBitmap(icon)).build()
    }
}
