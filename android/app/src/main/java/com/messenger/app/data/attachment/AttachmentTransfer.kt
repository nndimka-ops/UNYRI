package com.messenger.app.data.attachment

import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** Cancellation closes the socket even when execute/read/write is blocked. */
internal suspend fun <T> Call.withAttachmentResponse(block: (Response) -> T): T = coroutineScope {
    val call = this@withAttachmentResponse
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        coroutineContext.ensureActive()
        call.execute().use(block)
    } finally {
        cancellation.cancel()
    }
}

internal class AttachmentUploadBody(
    private val file: File,
    private val checkActive: () -> Unit,
    private val progress: (Int) -> Unit,
    private val offset: Long = 0,
    private val length: Long = file.length(),
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = length
    override fun writeTo(sink: BufferedSink) {
        val expected = contentLength()
        var written = 0L
        var last = -1
        file.inputStream().use { input ->
            input.channel.position(offset)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                if (written == expected) break
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - written).toInt())
                if (count < 0) break
                sink.write(buffer, 0, count)
                written += count
                // 100 means the storage server accepted the upload, not merely
                // that the client wrote the last byte into a socket buffer.
                val percent = if (expected == 0L) 0 else (written * 100 / expected).toInt().coerceIn(0, 99)
                if (percent != last) { last = percent; progress(percent) }
            }
        }
        checkActive()
        if (written != expected) throw IOException("Attachment changed during upload")
    }
}

internal fun requireAttachmentSpace(directory: File, bytes: Long) {
    val reserve = 16L * 1024 * 1024
    val available = directory.usableSpace
    if (bytes < 0 || bytes > Long.MAX_VALUE - reserve || available < bytes + reserve) {
        throw IOException("Not enough storage space for this attachment.")
    }
}
