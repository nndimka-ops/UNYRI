package com.messenger.app.ui.chats

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.media.ExifInterface
import androidx.room.Room
import com.messenger.app.R
import com.messenger.app.data.attachment.PendingMediaPreview
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.domain.model.AttachmentUploadState
import com.messenger.app.ui.theme.CocoTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PendingMediaPreviewInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun photo(): File = File(compose.activity.cacheDir, "pending-oriented.jpg").also { file ->
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } } finally { bitmap.recycle() }
        ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
    }

    @Test fun queuedMetadataSurvivesDatabaseReopenForRotatedPhotoAndVideo() = runBlocking {
        val context = compose.activity
        val name = "pending-preview-${UUID.randomUUID()}"
        val photo = photo()
        val video = createVideoTestFixture(File(context.cacheDir, "pending-video.mp4"), rotation = 90)
        var db = Room.databaseBuilder(context, MessengerDatabase::class.java, name).build()
        try {
            for ((id, file, kind) in listOf(Triple("photo", photo, "image"), Triple("video", video, "video"))) {
                val job = PendingMediaPreview.job(Uri.fromFile(file).toString(), kind, 0)
                db.messageDao().enqueueMedia(MessageEntity(id, null, "chat", "me", file.name, null, "2026-10-09T00:00:00Z", "pending"), job.toString())
            }
            db.close()
            db = Room.databaseBuilder(context, MessengerDatabase::class.java, name).build()
            val image = PendingMediaPreview.attachment("photo", JSONObject(db.workspaceDao().get("chat", "upload:photo")!!))
            val movie = PendingMediaPreview.attachment("video", JSONObject(db.workspaceDao().get("chat", "upload:video")!!))
            assertEquals(60, image.width); assertEquals(120, image.height)
            assertEquals(360, movie.width); assertEquals(640, movie.height)
            assertTrue(movie.durationMs!! >= 2900)
            assertEquals(photo.path, image.localPath)
            assertEquals(AttachmentUploadState.PENDING, image.uploadState)
            assertTrue(image.mimeType.startsWith("image/")); assertTrue(movie.mimeType.startsWith("video/"))
            // Same presentation seeding used before repository publication: no null/file first frame.
            val first = com.messenger.app.data.attachment.PendingMediaPreview.seed(
                listOf("photo", "video").map { id -> com.messenger.app.domain.model.Message(
                    id, null, "chat", "me", "", null, "2026-10-09T00:00:00Z", com.messenger.app.domain.model.MessageStatus.PENDING
                ) }, db.workspaceDao().mediaJobs("chat")
            )
            assertEquals(image, first[0].queuedAttachment)
            assertEquals(movie, first[1].queuedAttachment)
        } finally { db.close(); context.deleteDatabase(name); photo.delete(); video.delete() }
    }

    @Test fun offlinePhotoShowsThumbnailAndCancelWithoutGeometryChangeOnSend() {
        val file = photo()
        val pending = PendingMediaPreview.attachment("image", PendingMediaPreview.job(Uri.fromFile(file).toString(), "image", 0))
        val attachment = mutableStateOf(pending)
        compose.setContent { CocoTheme { ImageAttachmentContent(attachment.value, true, {}, {}) } }
        val description = compose.activity.getString(R.string.photo_preview)
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.cancel_sending)).assertIsDisplayed()
        val before = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { attachment.value = pending.copy(uploadState = AttachmentUploadState.SENT) }
        compose.waitForIdle()
        val after = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.cancel_sending)).assertDoesNotExist()
    }

    @Test fun unavailablePreviewMetadataKeepsDurableSendIntent() {
        val file = File(compose.activity.cacheDir, "unsupported-preview.jpg").apply { writeText("unsupported image payload") }
        try {
            val saved = PendingMediaPreview.job(Uri.fromFile(file).toString(), "image", 0).toString()
            val restored = PendingMediaPreview.attachment("pending", JSONObject(saved))
            assertEquals(file.path, restored.localPath)
            assertEquals(AttachmentUploadState.PENDING, restored.uploadState)
            assertNull(restored.width); assertNull(restored.height)
            assertTrue(restored.mimeType.startsWith("image/"))
        } finally { file.delete() }
    }
}
