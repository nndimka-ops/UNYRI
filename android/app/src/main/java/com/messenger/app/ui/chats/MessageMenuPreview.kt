package com.messenger.app.ui.chats

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal data class CapturedMessagePreview(val bitmap: Bitmap, val bounds: Rect)

/** One bounded, in-memory copy on long press; never records every chat frame. */
internal suspend fun captureMessageMenuPreview(view: View, bounds: Rect): CapturedMessagePreview? {
    var context = view.context
    while (context is ContextWrapper && context !is Activity) context = context.baseContext
    val window = (context as? Activity)?.window ?: return null
    val area = android.graphics.Rect(
        bounds.left.toInt().coerceAtLeast(0), bounds.top.toInt().coerceAtLeast(0),
        bounds.right.toInt().coerceAtMost(window.decorView.width),
        bounds.bottom.toInt().coerceAtMost(window.decorView.height),
    )
    if (area.width() <= 0 || area.height() <= 0) return null
    val scale = minOf(1f, 1200f / maxOf(area.width(), area.height()))
    val bitmap = Bitmap.createBitmap((area.width() * scale).toInt().coerceAtLeast(1),
        (area.height() * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(window, area, bitmap, { result ->
                if (!continuation.isActive || result != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resume(null)
                } else continuation.resume(CapturedMessagePreview(bitmap, Rect(area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat())))
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            bitmap.recycle()
            if (continuation.isActive) continuation.resume(null)
        }
    }
}
