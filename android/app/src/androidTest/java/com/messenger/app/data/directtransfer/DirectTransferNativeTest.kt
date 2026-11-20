package com.messenger.app.data.directtransfer

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.local.chat.ChatWorkspaceValue
import com.messenger.app.data.local.chat.MessengerDatabase
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Two physical phones exchange SDP through the QA host only; this tests actual RTC/FS, not Signal mailbox auth. */
class DirectTransferNativeTest {
    @Test fun devicePeer() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("com.messenger.app.directqa", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("run_id")); require(canonicalUuid(run))
        val role = requireNotNull(args.getString("role")); require(role in setOf("sender", "receiver"))
        val phase = args.getString("phase") ?: "full"
        val size = (args.getString("bytes") ?: "136315007").toLong()
        val offset = (args.getString("offset") ?: "0").toLong()
        val interruptAfter = (args.getString("interrupt_after") ?: "4194304").toLong()
        require(size > 100 * 1024 * 1024 && offset in 0..size && phase in setOf("full", "interrupt", "resume", "cancel"))
        val folder = File(context.filesDir, "direct-qa/$run/$phase").apply { mkdirs() }
        val working = AtomicBoolean(true)
        val storage = DirectFileStorage(context, "native-$run-$role") { if (!working.get()) throw CancellationException("Owned QA attempt stopped") }
        val input = File(context.cacheDir, "direct-qa-input-$run.bin")
        val expected = expectedHash(size)
        if (role == "sender") {
            if (!storage.file(run, "source").isFile) {
                createSource(input, size)
                val staged = storage.stage(run, Uri.fromFile(input))
                assertEquals(size, staged.size); assertEquals(expected, staged.sha256)
            }
            assertEquals(size, storage.file(run, "source").length())
            assertEquals(expected, storage.hash(storage.file(run, "source")))
        }
        val prefix = if (offset == 0L) DirectFileStorage.digest(byteArrayOf()) else storage.hash(storage.file(run, if (role == "sender") "source" else "partial"), offset)
        val offer = DirectFileOffer(transferId = run, offerClientMessageId = UUID.nameUUIDFromBytes((run + "message").toByteArray()).toString(),
            senderUserId = "aaaaaaaa-1111-4111-8111-aaaaaaaaaaa1", senderDeviceId = "bbbbbbbb-2222-4222-8222-bbbbbbbbbbb2",
            offerNonce = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }),
            recipientUserId = "cccccccc-3333-4333-8333-ccccccccccc3", filename = "qa-original.bin", mimeType = "application/octet-stream", size = size,
            sha256 = expected, expiresAt = System.currentTimeMillis() / 1000 + 3600).validated()
        val attempt = UUID.nameUUIDFromBytes((run + phase).toByteArray()).toString()
        val record = DirectTransferRecord(offer, "eeeeeeee-5555-4555-8555-eeeeeeeeeee5", role, state = "connecting", consent = true, durableOffset = offset, attemptId = attempt)
        val databaseName = "direct-native-$run-$role.db"
        val database = Room.databaseBuilder(context, MessengerDatabase::class.java, databaseName).build()
        val receiverDurable = AtomicLong(offset)
        val complete = AtomicBoolean(false)
        val interrupt = AtomicBoolean(false)
        val sentBytes = AtomicLong(0)
        val rtc = WebRtcDirectPeerTransport(context, DirectPeerIceConfig(emptyList(), relayOnly = false))
        val transport = object : DirectPeerTransport by rtc {
            override suspend fun send(bytes: ByteArray, binary: Boolean) {
                if (binary && bytes.size >= 14 && bytes[1].toInt() == DirectFileWire.CHUNK) sentBytes.addAndGet((bytes.size - 14).toLong())
                rtc.send(bytes, binary)
            }
        }
        val statsFile = File(folder, "$role-stats.jsonl")
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val beforeAudio = audio.mode
        val started = System.nanoTime()
        var failure: Throwable? = null
        try {
            coroutineScope {
                val transfer = async {
                    runCatching {
                        DirectFilePump(record, storage, transport, { if (!working.get()) throw CancellationException("Owned QA stopped") }, { durable, stats ->
                            val snapshot = JSONObject().put("offset", durable).put("sample", stats?.sampleId ?: 0).put("speed", stats?.speedBytesPerSecond ?: 0)
                            // Actual generated Room DAO commit returns before the peer receives this ACK.
                            database.workspaceDao().put(ChatWorkspaceValue(record.chatId, "direct-qa:$role", snapshot.toString()))
                            receiverDurable.set(durable)
                            statsFile.appendText(snapshot.toString() + "\n")
                            if (role == "receiver" && phase in setOf("interrupt", "cancel") && durable >= interruptAfter) interrupt.set(true)
                        }, { complete.set(true) }).run()
                    }.exceptionOrNull()
                }
                val stop = launch {
                    if (role == "receiver" && phase in setOf("interrupt", "cancel")) {
                        while (!interrupt.get()) delay(10)
                        working.set(false); rtc.close()
                    }
                }
                try {
                    val description = if (role == "sender") rtc.createOffer() else {
                        val remote = waitRemote(folder)
                        rtc.acceptOffer(remote)
                    }
                    writeAtomic(File(folder, "$role.sdp"), description)
                    if (role == "sender") rtc.applyAnswer(waitRemote(folder))
                    failure = withTimeout(240_000) { transfer.await() }
                } finally { stop.cancel(); rtc.close(); transfer.cancel() }
            }
            if (phase in setOf("full", "resume")) {
                assertNull(failure?.javaClass?.name, failure)
                assertTrue("Full verification/completion must finish", complete.get())
                if (role == "receiver") {
                    assertEquals(size, storage.file(run, "complete").length())
                    assertEquals(expected, storage.hash(storage.file(run, "complete")))
                    assertFalse(storage.file(run, "partial").exists())
                } else assertEquals(size - offset, sentBytes.get())
            } else {
                assertFalse(complete.get())
                assertTrue(receiverDurable.get() >= interruptAfter || role == "sender")
                if (role == "receiver") {
                    assertTrue(storage.file(run, "partial").length() >= receiverDurable.get())
                    assertFalse(storage.file(run, "complete").exists())
                }
            }
            assertEquals("Data-only transport changed communication audio mode", beforeAudio, audio.mode)
            val pair = requireNotNull(rtc.provenDirectPair)
            assertTrue(DirectPeerPolicy.isDirectType(pair.first) && DirectPeerPolicy.isDirectType(pair.second))
            val durableJson = database.workspaceDao().get(record.chatId, "direct-qa:$role")?.let(::JSONObject)
            assertEquals(receiverDurable.get(), durableJson?.getLong("offset"))
            val saved = receiverDurable.get()
            if (phase == "cancel") storage.deleteIncomplete(run)
            val proof = JSONObject().put("passed", true).put("scope", "Two physical phones, actual RTC data-only and file pump; host fixture SDP, no Signal/mailbox/auth UI proof")
                .put("run_id", run).put("role", role).put("phase", phase).put("size", size).put("resume_offset", offset).put("resume_prefix_sha256", prefix)
                .put("durable_offset", saved).put("payload_bytes_sent", sentBytes.get()).put("completed", complete.get()).put("sha256", expected)
                .put("local_candidate_type", pair.first).put("remote_candidate_type", pair.second).put("audio_mode_unchanged", audio.mode == beforeAudio)
                .put("elapsed_ms", (System.nanoTime() - started) / 1_000_000).put("source_exists", storage.file(run, "source").exists())
                .put("partial_exists", storage.file(run, "partial").exists()).put("complete_exists", storage.file(run, "complete").exists())
                .put("new_failure_class", failure?.javaClass?.simpleName ?: JSONObject.NULL)
            writeAtomic(File(folder, "$role-proof.json"), proof.toString())
        } finally {
            rtc.close(); database.close()
            // Own source fixture remains across interrupt/resume; never a user's selected file.
            if (phase in setOf("full", "resume", "cancel")) input.delete()
        }
    }

    private suspend fun waitRemote(folder: File): String = withTimeout(90_000) {
        val file = File(folder, "remote.sdp")
        while (!file.isFile) delay(100)
        require(file.length() in 1..32768)
        file.readText(Charsets.UTF_8)
    }
    private fun writeAtomic(file: File, value: String) {
        val part = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(part).use { stream -> stream.write(value.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
        assertTrue(part.renameTo(file))
    }
    private fun pattern() = ByteArray(1024 * 1024).also { Random(793421).nextBytes(it) }
    private fun createSource(file: File, size: Long) {
        val bytes = pattern()
        FileOutputStream(file).use { output ->
            var remaining = size
            while (remaining > 0) { val count = minOf(remaining, bytes.size.toLong()).toInt(); output.write(bytes, 0, count); remaining -= count }
            output.fd.sync()
        }
    }
    private fun expectedHash(size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = pattern(); var remaining = size
        while (remaining > 0) { val count = minOf(remaining, bytes.size.toLong()).toInt(); digest.update(bytes, 0, count); remaining -= count }
        return DirectFileStorage.hex(digest.digest())
    }
}
