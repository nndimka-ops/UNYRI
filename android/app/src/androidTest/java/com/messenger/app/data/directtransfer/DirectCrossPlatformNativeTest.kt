package com.messenger.app.data.directtransfer

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.api.ChatsApi
import com.messenger.app.data.api.EnvelopeDto
import com.messenger.app.data.api.MessagesApi
import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.local.message.MessageEntity
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Sender endpoint for root-coordinated actual Android ↔ isolated full iOS QA app. */
class DirectCrossPlatformNativeTest {
    @Test fun sendOriginalAndCancelToOwnedIosQa(): Unit = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("server_ready") == "1")
        val otherUser = requireNotNull(args.getString("recipient_user_id")).also { require(canonicalUuid(it)) }
        val otherDevice = requireNotNull(args.getString("recipient_device_id")).also { require(canonicalUuid(it)) }
        val run = args.getString("run_id")?.also { require(canonicalUuid(it)) } ?: UUID.randomUUID().toString()
        val lifecycle = args.getString("receiver_lifecycle") == "1"
        val fixture = DirectServiceNativeTest()
        assertEquals("com.messenger.app.directqa", fixture.context.packageName)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = fixture.account(run, "x", scope)
        val wake = (fixture.context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "coco-direct-qa:cross").apply { acquire(1_200_000) }
        val messages = MessagesApi(fixture.client, fixture.json, fixture.base)
        val chat = ChatsApi(fixture.client, fixture.json, fixture.base).createDirect(a.auth.token, otherUser).chatId
        a.db.chatDao().upsertChatMetadata(chat, "direct", java.time.Instant.now().toString(), null)
        val input = if(lifecycle) File(File(fixture.context.cacheDir,"camera").apply { mkdirs() },"COCO-owned-original-video-$run.mp4")
            else File(fixture.context.cacheDir, "cross-original-$run.bin")
        val bytes = 130L * 1024 * 1024 + 127
        if(lifecycle) {
            val seed=File(fixture.context.cacheDir,"direct-owned-video-seed.mp4")
            require(seed.isFile && seed.length()==bytes)
            seed.inputStream().use { source -> input.outputStream().use { target -> source.copyTo(target,64*1024) } }
        } else input.outputStream().use { output ->
            val pattern = ByteArray(1024 * 1024).also { java.util.Random(793421).nextBytes(it) }
            var remaining = bytes
            while (remaining > 0) { val n = minOf(remaining, pattern.size.toLong()).toInt(); output.write(pattern, 0, n); remaining -= n }
        }
        val sourceUri = if(lifecycle) androidx.core.content.FileProvider.getUriForFile(fixture.context,"${fixture.context.packageName}.fileprovider",input) else Uri.fromFile(input)
        val ready = Channel<String>(8)
        val enqueueGate = Mutex()
        val enqueuedOffers = mutableSetOf<String>()
        val allowed = AtomicBoolean(true)
        val coordinator = DirectTransferCoordinator(fixture.context, a.auth, a.db.workspaceDao(), a.db.messageDao(), a.signal,
            DirectTransferApi(fixture.client, a.auth, fixture.base), { id, offer, _ ->
                enqueueGate.withLock {
                if (offer.transferId in enqueuedOffers) return@withLock
                val plain = MessageContentCodec.authenticate(MessageContentCodec.encodeText(DirectFileOffer.FALLBACK, directFile = offer), id, offer.offerClientMessageId, "content", null)
                val envelope = a.signal.buildEnvelopeForDevice(otherUser, otherDevice, a.auth.token, plain)
                val ack = messages.sendMessage(a.auth.token, id, offer.offerClientMessageId,
                    listOf(EnvelopeDto(envelope.recipientDeviceId, envelope.messageType, envelope.ciphertext)))
                a.db.messageDao().upsert(MessageEntity(offer.offerClientMessageId, ack.id, id, a.auth.response.user.id,
                    DirectFileOffer.FALLBACK, ack.sequence, ack.createdAt, "sent"))
                enqueuedOffers += offer.transferId
                ready.send(offer.transferId)
                }
            }, { otherUser }, { listOf("stun:stun.example.test:3478") }, { allowed.get() })
        val snapshots = File(fixture.context.filesDir, "direct-cross-$run-stats.jsonl")
        val samples = mutableSetOf<String>()
        val observe = scope.launch {
            a.db.workspaceDao().observeAllDirectTransfers().collect { values ->
                values.mapNotNull { DirectTransferCoordinator.decodeRecord(it.value) }.forEach { record ->
                    if (record.ackSampleId > 0) {
                        val key = "${record.offer.transferId}:${record.attemptId}:${record.ackSampleId}"
                        if (samples.add(key)) snapshots.appendText(JSONObject().put("transfer", record.offer.transferId).put("attempt", record.attemptId)
                            .put("sample", record.ackSampleId).put("offset", record.durableOffset).put("speed", record.speedBytesPerSecond).toString() + "\n")
                    }
                }
            }
        }
        scope.launch { coordinator.errors.collect { android.util.Log.w("DirectNativeQA", "cross-error=$it") } }
        fixture.watchCoordinator(scope, coordinator, "cross-$run")
        val identity = JSONObject().put("run", run).put("chat_id", chat).put("sender_user_id", a.auth.response.user.id)
            .put("sender_device_id", a.auth.response.deviceId).put("recipient_user_id", otherUser).put("recipient_device_id", otherDevice)
        File(fixture.context.filesDir, "direct-cross-$run-identity.json").writeText(identity.toString())
        try {
            // Host installs the exact fresh sender allowlist on the isolated iOS
            // receiver, then acknowledges readiness. No offer races that setup.
            val allowedByHost = File(fixture.context.filesDir, "direct-cross-$run-ready.json")
            withTimeout(120_000) {
                while (!allowedByHost.isFile) delay(200)
                require(allowedByHost.length() <= 1024)
                require(JSONObject(allowedByHost.readText()).getString("sender_user_id") == a.auth.response.user.id)
            }
            assertTrue(coordinator.offerOriginal(chat, sourceUri, 1))
            val first = withTimeout(90_000) { ready.receive() }
            android.util.Log.i("DirectNativeQA", "cross-offer run=$run transfer=$first")
            if(lifecycle) {
                // This mode never pauses the sender or sends Cancel. The full
                // receiver app must execute native Background/Resume/Open/Cancel.
                fixture.awaitState(a, first, 300_000) { it.durableOffset >= 4L * 1024 * 1024 }
                val background=fixture.awaitState(a, first, 300_000) { it.state=="paused" && it.reason=="background" }
                val paused=fixture.record(a,first)
                require(paused.durableOffset>=4L*1024*1024 && paused.durableOffset<bytes)
                File(fixture.context.filesDir,"direct-cross-$run-background.json").writeText(JSONObject()
                    .put("transfer_id",first).put("offset",paused.durableOffset).put("attempt_id",paused.attemptId).put("reason",paused.reason).toString())
                fixture.awaitState(a, first, 900_000) { it.completed && it.completionReceived && it.mailboxClosed }
                val full=fixture.record(a,first)
                assertEquals(bytes,full.durableOffset)
                assertEquals("1f39cc74fe23b2efa2d228d9b95ce466ced07f88fc7fbb5a4c34209d83f0a930",full.offer.sha256)
                assertEquals("video/mp4",full.offer.mimeType)
                require(full.attemptId!=paused.attemptId)
                File(fixture.context.filesDir,"direct-cross-$run-full.json").writeText(JSONObject()
                    .put("transfer_id",first).put("bytes",bytes).put("sha256",full.offer.sha256).put("completed",full.completed)
                    .put("mailbox_closed",full.mailboxClosed).put("resumed_attempt",full.attemptId).put("paused_attempt",paused.attemptId).toString())
                val openAck=File(fixture.context.filesDir,"direct-cross-$run-allow-second.json")
                withTimeout(300_000) { while(!openAck.isFile)delay(100) }
                require(JSONObject(openAck.readText()).getString("first_transfer_id")==first)
                assertTrue(coordinator.offerOriginal(chat,sourceUri,1))
                val cancelled=withTimeout(90_000) { ready.receive() }
                File(fixture.context.filesDir,"direct-cross-$run-second.json").writeText(JSONObject().put("transfer_id",cancelled).toString())
                fixture.awaitState(a,cancelled,300_000) { it.state=="cancelled" && it.mailboxClosed }
                val final=fixture.record(a,cancelled)
                require(final.durableOffset>0 && final.durableOffset<bytes)
                val proof=JSONObject().put("passed",true).put("scope","PhysicalAndroid Signal/mailbox/coordinator and fulliOS nativeAccept/Background/Resume/Open/receiverCancel; recipient external UI/hash receipt required")
                    .put("identity",identity).put("full_transfer",first).put("bytes",bytes).put("sha256",full.offer.sha256)
                    .put("background_prefix",paused.durableOffset).put("background_attempt",paused.attemptId).put("resumed_attempt",full.attemptId)
                    .put("receiver_cancel_transfer",cancelled).put("receiver_cancel_offset",final.durableOffset)
                    .put("sender_cancel_called",false).put("sender_pause_injected",false).put("authoritative_sample_file",snapshots.name)
                File(fixture.context.filesDir,"direct-cross-$run-proof.json").writeText(proof.toString())
            } else {
            fixture.awaitState(a, first, 180_000) { it.durableOffset >= 4L * 1024 * 1024 || it.state == "paused" }
            val live = fixture.record(a, first)
            require(live.durableOffset >= 4L * 1024 * 1024) { "Strict direct route unavailable (${live.reason}); no TURN/upload fallback" }
            val before = live.durableOffset
            allowed.set(false)
            fixture.awaitState(a, first) { it.state == "paused" }
            delay(1500); allowed.set(true); coordinator.recoveryEvent()
            fixture.awaitState(a, first, 300_000) { it.completed && it.completionReceived && it.mailboxClosed }
            val full = fixture.record(a, first)
            assertEquals(bytes, full.durableOffset)
            assertEquals("82b016b9ee30f1a1dcc597af223597e2777f4ae0b2ea1969790018aa323f0e77", full.offer.sha256)
            assertTrue(coordinator.offerOriginal(chat, sourceUri, 1))
            val cancel = withTimeout(90_000) { ready.receive() }
            android.util.Log.i("DirectNativeQA", "cross-cancel-offer run=$run transfer=$cancel")
            fixture.awaitState(a, cancel, 180_000) { it.durableOffset >= 4L * 1024 * 1024 }
            coordinator.cancel(cancel)
            fixture.awaitState(a, cancel) { it.state == "cancelled" && it.mailboxClosed }
            val proof = JSONObject().put("passed", true).put("scope", "Samsung actual Auth/AndroidKeystore Signal/Room/coordinator ↔ isolated full iOS QA app; HTTPS Signal mailbox, strict direct RTC, no TURN/upload")
                .put("identity", identity).put("full_transfer", first).put("bytes", bytes).put("sha256", full.offer.sha256)
                .put("resume_prefix", before).put("cancel_transfer", cancel).put("authoritative_sample_file", snapshots.name)
                .put("ios_receiver_verification_requires_peer_receipt", true)
            File(fixture.context.filesDir, "direct-cross-$run-proof.json").writeText(proof.toString())
            android.util.Log.i("DirectNativeQA", "cross-proof=direct-cross-$run-proof.json passed=true")
            }
        } catch(error:Exception) {
            if(lifecycle) {
                File(fixture.context.filesDir,"direct-cross-$run-failure.json").writeText(JSONObject()
                    .put("sender_user_id",a.auth.response.user.id).put("failure_class",error.javaClass.simpleName).put("preserved_for_root",true).toString())
                withContext(NonCancellable) { withTimeoutOrNull(300_000) {
                    val release=File(fixture.context.filesDir,"direct-cross-$run-release-cleanup.json")
                    while(!release.isFile)delay(100)
                    require(JSONObject(release.readText()).getString("sender_user_id")==a.auth.response.user.id)
                } }
            }
            throw error
        } finally {
            delay(3000); observe.cancel(); fixture.cleanupOwned(a); scope.cancel(); input.delete()
            if (wake.isHeld) wake.release()
        }
    }
}
