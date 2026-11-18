package com.messenger.app.data.secure

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/** A unique ownership token prevents clearing a newer clip from this or another app. */
class ClipboardOwnership {
    private var label: String? = null
    private var expires = 0L
    fun copied(label: String, now: Long) { this.label = label; expires = now + 120_000 }
    fun shouldClear(actual: String?, now: Long, background: Boolean) = label != null && actual == label && (background || now >= expires)
    fun reset() { label = null; expires = 0 }
}

object SensitiveClipboard {
    private val ownership = ClipboardOwnership()
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var background = true
    fun onForeground() { background = false }
    fun onBackground(context: Context) { background = true; clearOwn(context) }
    fun copyText(context: Context, text: String) = copy(context, ClipData.newPlainText(label(), text))
    fun copyUri(context: Context, uri: Uri, mime: String) = copy(context,
        ClipData(android.content.ClipDescription(label(), arrayOf(mime)), ClipData.Item(uri)))
    private fun label() = "UNYRI-sensitive-" + java.util.UUID.randomUUID()
    private fun copy(context: Context, clip: ClipData) {
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        val app = context.applicationContext
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        ownership.copied(clip.description.label.toString(), android.os.SystemClock.elapsedRealtime())
        pending?.let(handler::removeCallbacks)
        pending = Runnable { clearOwn(app, background = false) }.also { handler.postDelayed(it, 120_000) }
        if (background) clearOwn(app)
    }
    fun clearOwn(context: Context, background: Boolean = true) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        runCatching {
            if (ownership.shouldClear(clipboard.primaryClipDescription?.label?.toString(), android.os.SystemClock.elapsedRealtime(), background)) {
                if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
        pending?.let(handler::removeCallbacks); pending = null; ownership.reset()
    }
}
