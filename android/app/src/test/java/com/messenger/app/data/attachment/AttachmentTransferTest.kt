package com.messenger.app.data.attachment

import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

class AttachmentTransferTest {
    @Test fun partUploadReadsOnlyItsOwnRange() {
        val file = File.createTempFile("upload-range", ".bin")
        try {
            val bytes = ByteArray(300000) { (it % 251).toByte() }
            file.writeBytes(bytes)
            val sink = Buffer()
            AttachmentUploadBody(file, {}, {}, 12345, 77777).writeTo(sink)
            assertContentEquals(bytes.copyOfRange(12345, 12345+77777), sink.readByteArray())
        } finally { file.delete() }
    }
    @Test fun uploadReportsIntermediateProgressAndPreservesBytes() {
        val file = File.createTempFile("upload-progress", ".bin")
        try {
            val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
            file.writeBytes(bytes)
            val percentages = mutableListOf<Int>()
            val sink = Buffer()
            AttachmentUploadBody(file, {}, percentages::add).writeTo(sink)
            assertContentEquals(bytes, sink.readByteArray())
            assertTrue(percentages.any { it in 1..98 })
            assertEquals(99, percentages.last())
            assertEquals(percentages.distinct().sorted(), percentages)
        } finally { file.delete() }
    }

    @Test fun cancellationStopsBeforeReadingTheRestOfTheFile() {
        val file = File.createTempFile("upload-cancel", ".bin")
        try {
            file.writeBytes(ByteArray(2 * 1024 * 1024))
            var cancelled = false
            val sink = Buffer()
            val body = AttachmentUploadBody(file, { if (cancelled) throw CancellationException() }, { cancelled = true })
            assertFailsWith<CancellationException> { body.writeTo(sink) }
            assertTrue(sink.size in 1 until file.length())
        } finally { file.delete() }
    }

    @Test fun cancellationInterruptsWaitingForServerResponse() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = CompletableDeferred<Unit>()
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    accepted.complete(Unit)
                    // Never return HTTP headers. Closing the client must wake this read.
                    while (socket.getInputStream().read() >= 0) { }
                }
            }
            val client = OkHttpClient()
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
            val job = launch(Dispatchers.IO) { runCatching { call.withAttachmentResponse { it.code } } }
            withTimeout(3000) { accepted.await() }
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
            worker.join(1000)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
