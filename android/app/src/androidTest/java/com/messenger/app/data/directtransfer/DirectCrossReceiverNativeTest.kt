package com.messenger.app.data.directtransfer

import android.content.Context
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.api.MessagesApi
import com.messenger.app.data.api.ServerEndpoint
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Physical Android receiver for the root-owned full iOS QA app sender. */
class DirectCrossReceiverNativeTest {
    @Test fun receiveOriginalVideoAndPeerCancellation(): Unit = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("server_ready") == "1")
        val run = requireNotNull(args.getString("run_id")).also { require(canonicalUuid(it)) }
        val fixture = DirectServiceNativeTest()
        assertEquals("com.messenger.app.directqa", fixture.context.packageName)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val side = fixture.account(run, "r", scope)
        val wake = (fixture.context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "coco-direct-qa:receiver").apply { acquire(1_200_000) }
        val api = MessagesApi(fixture.client, fixture.json, fixture.base)
        val ingest = fixture.ingestion(side, api)
        val identity = JSONObject().put("run", run).put("user_id", side.auth.response.user.id).put("device_id", side.auth.response.deviceId)
            .put("username", side.auth.response.user.username)
        File(fixture.context.filesDir, "direct-reverse-$run-identity.json").writeText(identity.toString())
        try {
            val command = File(fixture.context.filesDir, "direct-reverse-$run-command.json")
            val selection = withTimeout(480_000) { while (!command.isFile) delay(100); require(command.length() in 1..4096); JSONObject(command.readText()) }
            val chat = selection.getString("chat_id").also { require(canonicalUuid(it)) }
            val sender = selection.getString("sender_user_id").also { require(canonicalUuid(it)) }
            val device = selection.getString("sender_device_id").also { require(canonicalUuid(it)) }
            side.db.chatDao().upsertChatMetadata(chat, "direct", java.time.Instant.now().toString(), null)
            val coordinator = DirectTransferCoordinator(fixture.context, side.auth, side.db.workspaceDao(), side.db.messageDao(), side.signal,
                DirectTransferApi(fixture.client, side.auth, fixture.base), { _, _, _ -> error("Receiver cannot create offers") }, { sender },
                { listOf("stun:stun.example.test:3478") }, { true })
            fixture.watchCoordinator(scope, coordinator, "reverse-$run")
            val snapshots = File(fixture.context.filesDir, "direct-reverse-$run-stats.jsonl")
            val observed = mutableSetOf<String>()
            scope.launch { side.db.workspaceDao().observeAllDirectTransfers().collect { values -> values.forEach { value ->
                val record = DirectTransferCoordinator.decodeRecord(value.value) ?: return@forEach
                if (record.ackSampleId > 0 && observed.add("${record.offer.transferId}:${record.attemptId}:${record.ackSampleId}"))
                    snapshots.appendText(JSONObject().put("transfer", record.offer.transferId).put("attempt", record.attemptId).put("sample", record.ackSampleId)
                        .put("offset", record.durableOffset).put("speed", record.speedBytesPerSecond).toString() + "\n")
            } } }
            @Suppress("UNCHECKED_CAST")
            val live = DirectTransferCoordinator::class.java.getDeclaredField("records").apply { isAccessible = true }.get(coordinator) as StateFlow<Map<String, DirectTransferRecord>>
            val seen = mutableSetOf<String>()
            suspend fun nextOffer(): DirectTransferRecord = withTimeout(240_000) {
                while (true) {
                    val messages = api.listMessages(side.auth.token, chat).messages.filter { it.senderId == sender && it.senderDeviceId == device }
                    if (messages.isNotEmpty()) assertTrue(ingest.ingest(messages, true).advancedFully)
                    val next = side.db.workspaceDao().allDirectTransfers().mapNotNull { DirectTransferCoordinator.decodeRecord(it.value) }
                        .firstOrNull { it.chatId == chat && it.role == "receiver" && it.offer.transferId !in seen }
                    if (next != null) {
                        require(next.offer.senderUserId == sender && next.offer.senderDeviceId == device && next.offer.recipientUserId == side.auth.response.user.id)
                        while (next.offer.transferId !in live.value) delay(20)
                        seen += next.offer.transferId
                        return@withTimeout next
                    }
                    delay(1000)
                }
                error("Unreachable")
            }
            val first = nextOffer()
            require(first.offer.size > 100L * 1024 * 1024 && first.offer.mimeType == "video/mp4")
            coordinator.accept(first.offer.transferId)
            fixture.awaitState(side, first.offer.transferId, 360_000) { it.completed && it.completionAcknowledged && it.mailboxClosed }
            val received = requireNotNull(coordinator.completedFile(first.offer.transferId))
            assertEquals(first.offer.size, received.first.length())
            val storage = DirectFileStorage(fixture.context, "reverse-verify-$run") {}
            assertEquals(first.offer.sha256, storage.hash(received.first))
            val duration = android.media.MediaMetadataRetriever().let { retriever ->
                try { retriever.setDataSource(received.first.absolutePath); retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
                finally { retriever.release() }
            }
            require(duration != null && duration > 0) { "Verified original has no readable MP4 duration" }
            File(fixture.context.filesDir, "direct-reverse-$run-full.json").writeText(JSONObject().put("phase_passed", true).put("id", first.offer.transferId)
                .put("bytes", first.offer.size).put("sha256", first.offer.sha256).put("mp4_duration_ms", duration).toString())
            android.util.Log.i("DirectNativeQA", "reverse-full run=$run id=${first.offer.transferId} bytes=${first.offer.size}")
            val second = nextOffer()
            coordinator.accept(second.offer.transferId)
            fixture.awaitState(side, second.offer.transferId, 240_000) { it.state == "cancelled" && it.mailboxClosed }
            val owned = DirectFileStorage(fixture.context, ServerEndpoint.current() + "|" + side.auth.response.user.id + "|" + side.auth.response.deviceId) {}
            assertFalse(owned.file(second.offer.transferId, "partial").exists())
            assertNull(coordinator.completedFile(second.offer.transferId))
            File(fixture.context.filesDir, "direct-reverse-$run-proof.json").writeText(JSONObject().put("passed", true).put("identity", identity)
                .put("full_id", first.offer.transferId).put("bytes", first.offer.size).put("sha256", first.offer.sha256).put("mp4_duration_ms", duration)
                .put("cancel_id", second.offer.transferId).put("partial_removed", true).put("ack_file", snapshots.name).toString())
        } finally { fixture.cleanupOwned(side); scope.cancel(); if (wake.isHeld) wake.release() }
    }
}
