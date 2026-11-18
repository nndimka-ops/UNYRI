package com.messenger.app.data.directtransfer

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Data-plane-only baseline, two actual native RTC peers on one Samsung. No Signal or two-phone claim. */
class DirectTransferLocalNativeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val size = 130L * 1024 * 1024 + 127

    @Test fun original130MiBActualRtc() = runBlocking(Dispatchers.IO) {
        val run = UUID.randomUUID().toString()
        val proof = phase(run, "full", 0)
        save(run, JSONObject().put("passed", true).put("scope", "One Samsung, two actual native RTC/DTLS peers, real file/Room; no mailbox/Signal/UI/two-device proof").put("full", proof))
    }

    @Test fun disconnectResumeAndCancelActualRtc() = runBlocking(Dispatchers.IO) {
        val run = UUID.randomUUID().toString()
        val broken = phase(run, "interrupt", 0)
        val offset = broken.getLong("receiver_durable")
        assertTrue(offset in 4194304 until size)
        val resumed = phase(run, "resume", offset)
        val cancelled = phase(UUID.randomUUID().toString(), "cancel", 0)
        save(run, JSONObject().put("passed", true).put("scope", "One Samsung, two actual native RTC/DTLS peers; host-owned file fixture, no mailbox/Signal/UI/two-device proof")
            .put("interrupt", broken).put("resume", resumed).put("cancel", cancelled))
    }

    private suspend fun phase(run: String, phase: String, offset: Long): JSONObject = coroutineScope {
        assertEquals("com.messenger.app.directqa", context.packageName)
        val working = AtomicBoolean(true)
        val sourceStorage = DirectFileStorage(context, "local-$run-s") { if (!working.get()) throw CancellationException("QA stop") }
        val receiverStorage = DirectFileStorage(context, "local-$run-r") { if (!working.get()) throw CancellationException("QA stop") }
        val input = File(context.cacheDir, "direct-local-$run.bin")
        if (!sourceStorage.file(run, "source").isFile) {
            val pattern = ByteArray(1024 * 1024).also { Random(793421).nextBytes(it) }
            FileOutputStream(input).use { output ->
                var remaining = size
                while (remaining > 0) { val n = minOf(remaining, pattern.size.toLong()).toInt(); output.write(pattern, 0, n); remaining -= n }
                output.fd.sync()
            }
            val staged = sourceStorage.stage(run, Uri.fromFile(input)); assertEquals(size, staged.size)
        }
        val hash = sourceStorage.hash(sourceStorage.file(run, "source"))
        if (offset > 0) assertEquals(sourceStorage.hash(sourceStorage.file(run, "source"), offset), receiverStorage.hash(receiverStorage.file(run, "partial"), offset))
        val offer = DirectFileOffer(transferId = run, offerClientMessageId = UUID.randomUUID().toString(),
            senderUserId = "aaaaaaaa-1111-4111-8111-aaaaaaaaaaa1", senderDeviceId = "bbbbbbbb-2222-4222-8222-bbbbbbbbbbb2",
            recipientUserId = "cccccccc-3333-4333-8333-ccccccccccc3", filename = "qa-original.bin", mimeType = "application/octet-stream",
            offerNonce = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
            size = size, sha256 = hash, expiresAt = System.currentTimeMillis() / 1000 + 3600).validated()
        val chat = "eeeeeeee-5555-4555-8555-eeeeeeeeeee5"
        val attempt = UUID.randomUUID().toString()
        val s = DirectTransferRecord(offer, chat, "sender", state = "connecting", consent = true, durableOffset = offset, attemptId = attempt)
        val r = s.copy(role = "receiver")
        val database = Room.databaseBuilder(context, MessengerDatabase::class.java, "local-direct-$run.db").build()
        val a = WebRtcDirectPeerTransport(context, emptyList())
        val b = WebRtcDirectPeerTransport(context, emptyList())
        val sent = AtomicLong(0)
        val senderTransport = object : DirectPeerTransport by a {
            override suspend fun send(bytes: ByteArray, binary: Boolean) {
                if (binary && bytes.size > 14 && bytes[1].toInt() == 1) sent.addAndGet((bytes.size - 14).toLong())
                a.send(bytes, binary)
            }
        }
        val completeS = AtomicBoolean(false); val completeR = AtomicBoolean(false)
        val cut = AtomicBoolean(false); val durable = AtomicLong(offset)
        val senderStats = mutableListOf<String>(); val receiverStats = mutableListOf<String>()
        val started = System.nanoTime()
        val wakeLock = (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "coco-direct-qa:native").apply { acquire(300_000) }
        try {
            val sender = async { runCatching { DirectFilePump(s, sourceStorage, senderTransport, { if (!working.get()) throw CancellationException() }, { o, stats ->
                val text = JSONObject().put("offset", o).put("sample", stats?.sampleId ?: 0).put("speed", stats?.speedBytesPerSecond ?: 0).toString()
                database.workspaceDao().put(ChatWorkspaceValue(chat, "local-s", text)); senderStats.add(text)
            }, { completeS.set(true) }).run() }.exceptionOrNull() }
            val receiver = async { runCatching { DirectFilePump(r, receiverStorage, b, { if (!working.get()) throw CancellationException() }, { o, stats ->
                val text = JSONObject().put("offset", o).put("sample", stats?.sampleId ?: 0).put("speed", stats?.speedBytesPerSecond ?: 0).toString()
                database.workspaceDao().put(ChatWorkspaceValue(chat, "local-r", text)); receiverStats.add(text); durable.set(o)
                if (phase in setOf("interrupt", "cancel") && o >= 4194304) cut.set(true)
            }, { completeR.set(true) }).run() }.exceptionOrNull() }
            val stopper = launch {
                if (phase in setOf("interrupt", "cancel")) {
                    while (!cut.get()) delay(5)
                    working.set(false); a.close(); b.close()
                }
            }
            val offerSdp = a.createOffer()
            val answer = b.acceptOffer(offerSdp)
            a.applyAnswer(answer)
            val errors = withTimeout(240_000) { sender.await() to receiver.await() }
            stopper.cancel()
            if (phase in setOf("full", "resume")) {
                assertNull(errors.first?.javaClass?.name, errors.first); assertNull(errors.second?.javaClass?.name, errors.second)
                assertTrue(completeS.get()); assertTrue(completeR.get())
                assertEquals(size - offset, sent.get())
                assertEquals(size, receiverStorage.file(run, "complete").length())
                assertEquals(hash, receiverStorage.hash(receiverStorage.file(run, "complete")))
                assertEquals(receiverStats, senderStats)
            } else {
                assertFalse(completeS.get()); assertFalse(completeR.get())
                assertTrue(durable.get() >= 4194304)
                assertEquals(durable.get(), JSONObject(requireNotNull(database.workspaceDao().get(chat, "local-r"))).getLong("offset"))
                val stopped = sent.get(); delay(250); assertEquals(stopped, sent.get())
            }
            val sourcePair = requireNotNull(a.provenDirectPair); val receiverPair = requireNotNull(b.provenDirectPair)
            assertTrue(listOf(sourcePair.first, sourcePair.second, receiverPair.first, receiverPair.second).all(DirectPeerPolicy::isDirectType))
            if (phase == "cancel") { sourceStorage.deleteIncomplete(run); receiverStorage.deleteIncomplete(run); assertFalse(receiverStorage.file(run, "partial").exists()) }
            return@coroutineScope JSONObject().put("phase", phase).put("size", size).put("offset", offset).put("payload_bytes", sent.get()).put("receiver_durable", durable.get())
                .put("complete", completeS.get() && completeR.get()).put("sha256", hash).put("sender_pair", sourcePair.toString()).put("receiver_pair", receiverPair.toString())
                .put("sender_samples", JSONArray(senderStats)).put("receiver_samples", JSONArray(receiverStats)).put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
        } finally { a.close(); b.close(); database.close(); if (wakeLock.isHeld) wakeLock.release(); if (phase != "interrupt") input.delete() }
    }
    private fun save(run: String, value: JSONObject) {
        File(context.filesDir, "direct-local-$run.json").writeText(value.toString())
        android.util.Log.i("DirectNativeQA", "proof=direct-local-$run.json passed=true")
    }
}
