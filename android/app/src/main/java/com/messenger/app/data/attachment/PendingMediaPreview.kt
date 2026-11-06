package com.messenger.app.data.attachment

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import android.media.ExifInterface
import com.messenger.app.domain.model.*
import java.io.File
import org.json.JSONObject

/** Local metadata only, called on IO. No bitmap/frame allocation or network. */
internal object PendingMediaPreview {
    /** No source path, caption or key: only the reserved local position and shape. */
    fun presentation(job: JSONObject, createdAt: String, index: Int): JSONObject = JSONObject()
        .put("createdAt", createdAt).put("index", index)
        .put("width", job.optInt("width")).put("height", job.optInt("height"))
        .put("visual", job.getString("kind") in setOf("image", "image_high", "video", "video_high") || job.optString("mime").startsWith("video/"))

    fun reserve(messages: List<Message>, values: List<com.messenger.app.data.local.chat.ChatWorkspaceValue>): List<Message> {
        val byId = values.associateBy { it.name.removePrefix("presentation:") }
        return messages.map { message ->
            val layout = byId[message.clientMessageId]?.let { value -> runCatching {
                val json = JSONObject(value.value)
                val width = json.optInt("width"); val height = json.optInt("height")
                LocalMediaPresentation(json.getString("createdAt"), json.getInt("index"),
                    if (width > 0 && height > 0) width.toFloat() / height else null, json.getBoolean("visual"))
            }.getOrNull() }
            if (layout == null) message else message.copy(localMediaPresentation = layout)
        }
    }

    /** Complete queued presentation before the first list emission, including after restart. */
    fun seed(messages: List<Message>, jobs: List<com.messenger.app.data.local.chat.ChatWorkspaceValue>): List<Message> {
        val byId = jobs.associateBy { it.name.removePrefix("upload:") }
        return messages.map { message ->
            val raw = byId[message.clientMessageId]
            if (raw == null || message.status !in setOf(MessageStatus.PENDING, MessageStatus.FAILED)) message
            else {
                val preview = try {
                    attachment(message.clientMessageId, JSONObject(raw.value)).let {
                        if (message.status == MessageStatus.FAILED) it.copy(uploadState = AttachmentUploadState.FAILED) else it
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                  catch (_: Exception) { null }
                message.copy(queuedAttachment = preview)
            }
        }
    }
    fun job(uri: String, kind: String, duration: Long): JSONObject {
        val file = File(requireNotNull(Uri.parse(uri).path))
        val job = JSONObject().put("uri", uri).put("kind", kind).put("duration", duration)
        var mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        if (kind in setOf("image", "image_high") && !mime.startsWith("image/")) mime = "image/jpeg"
        if (kind in setOf("video", "video_high") && !mime.startsWith("video/")) mime = "video/mp4"
        try {
        if (kind == "image" || kind == "image_high") {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image metadata unavailable" }
            val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val swap = orientation in setOf(ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270)
            job.put("width", if (swap) bounds.outHeight else bounds.outWidth)
            job.put("height", if (swap) bounds.outWidth else bounds.outHeight)
            mime = bounds.outMimeType ?: "image/jpeg"
        } else if (kind == "video" || kind == "video_high") {
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(file.path)
                val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                require(width > 0 && height > 0) { "Video metadata unavailable" }
                val swap = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() in listOf(90, 270)
                job.put("width", if (swap) height else width).put("height", if (swap) width else height)
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { job.put("duration", it) }
                mime = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
            } finally { reader.release() }
        } else if (kind == "voice") mime = "audio/mp4"
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (_: Exception) { /* Optional preview must never prevent durable queuing. */ }
        return job.put("mime", mime).put("size", file.length()).put("filename", file.name)
    }

    /** The job survives process death; reuse metadata without re-reading media. */
    fun attachment(id: String, saved: JSONObject): Attachment {
        val kind = saved.getString("kind")
        val job = if (!saved.has("mime")) job(saved.getString("uri"), kind, saved.optLong("duration")) else saved
        val file = File(requireNotNull(Uri.parse(job.getString("uri")).path))
        return Attachment(id, id, when (kind) {
            "image", "image_high" -> AttachmentMediaType.IMAGE
            "voice" -> AttachmentMediaType.VOICE
            else -> AttachmentMediaType.FILE
        }, job.getString("mime"), job.optString("filename", file.name), job.optLong("size", file.length()),
            job.optInt("width").takeIf { it > 0 }, job.optInt("height").takeIf { it > 0 }, job.optLong("duration"), null,
            file.path, AttachmentDownloadState.DOWNLOADED, AttachmentUploadState.PENDING, 0)
    }
}
