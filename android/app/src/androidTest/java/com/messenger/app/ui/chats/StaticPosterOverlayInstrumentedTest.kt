package com.messenger.app.ui.chats

import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.messenger.app.data.local.ChatImageCache
import com.messenger.app.domain.model.*
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StaticPosterOverlayInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun coldVideoPosterLoadsUnderEmojiAndSurvivesInsetsOverlaysAndRowRecreation() {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        val encoded = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }
        bitmap.recycle()
        val attachment = Attachment("poster-test", "row", AttachmentMediaType.FILE, "video/mp4", "clip.mp4",
            100, 40, 30, 1000, encoded, null, AttachmentDownloadState.NOT_DOWNLOADED,
            AttachmentUploadState.SENT, 100)
        val work = TimelineMediaWork().apply { started = true; covered = true; visible = setOf("row") }
        val generation = mutableIntStateOf(0)
        var displayed: Bitmap? = null
        ChatImageCache.clear()
        compose.setContent {
            CompositionLocalProvider(LocalTimelineMediaWork provides work, LocalMediaRowKey provides "row") {
                key(generation.intValue) {
                    val poster = rememberConversationMediaPreview(attachment)
                    SideEffect { displayed = poster.sharp ?: poster.soft }
                }
            }
        }
        // Both playback and receipts are blocked, yet the cold visible poster must load.
        compose.waitUntil(5000) { displayed != null }
        val original = displayed
        for (covered in listOf(false, true, false, true)) {
            compose.runOnIdle { work.covered = covered; work.visible = emptySet() }
            compose.runOnIdle { assertSame(original, displayed); work.visible = setOf("row") }
            compose.runOnIdle { assertSame(original, displayed) }
        }
        // Same bytes, cold cache and a remounted row while an overlay is open.
        compose.runOnIdle { ChatImageCache.clear(); generation.intValue++; displayed = null }
        compose.waitUntil(5000) { displayed != null }
        compose.runOnIdle { assertEquals(40, displayed!!.width); assertEquals(30, displayed!!.height) }
    }
}
