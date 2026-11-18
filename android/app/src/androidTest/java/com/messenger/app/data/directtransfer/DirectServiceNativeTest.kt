package com.messenger.app.data.directtransfer

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.BuildConfig
import com.messenger.app.data.api.*
import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.signal.*
import com.messenger.app.data.sync.MessageIngestionPipeline
import com.messenger.app.data.sync.SyncCursorStore
import com.messenger.app.domain.model.*
import com.messenger.app.domain.repository.AuthRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual HTTPS + Signal + Room + ingestion + coordinator + RTC on ONE Samsung.
 * The injected enqueue seam sends the real authenticated offer through MessagesApi;
 * it does not claim ChatScreen/normal-outbox or two-physical-device coverage.
 */
class DirectServiceNativeTest {
    internal val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    internal val json = ApiJson
    private val httpCounts = ConcurrentHashMap<String, AtomicInteger>()
    internal val client = OkHttpClient.Builder().addInterceptor { chain ->
        val path = chain.request().url.encodedPath
        val category = path.split('/').filter(String::isNotBlank).firstOrNull().orEmpty()
        httpCounts.getOrPut(category) { AtomicInteger() }.incrementAndGet()
        require(category !in setOf("attachments", "uploads", "files", "storage")) { "Unexpected file upload API" }
        chain.proceed(chain.request()).also { response ->
            if (category == "direct-transfers") android.util.Log.i("DirectNativeQA", "service-api ${chain.request().method} $path status=${response.code}")
            if (!response.isSuccessful) android.util.Log.w("DirectNativeQA", "service-http category=$category status=${response.code}")
        }
    }.build()
    internal val base get() = BuildConfig.API_BASE_URL

    @Test fun originalOver100MiBThroughRealSignalMailboxAndCoordinator(): Unit = runBlocking(Dispatchers.IO) {
        assertEquals("com.messenger.app.directqa", context.packageName)
        require(InstrumentationRegistry.getArguments().getString("server_ready") == "1") { "Root must confirm the public candidate endpoint is live" }
        require(java.net.URI(base).scheme == "https")
        val run = UUID.randomUUID().toString()
        val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "coco-direct-qa:service").apply { acquire(900_000) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owned = mutableListOf<Side>()
        try {
        val a = account(run, "a", scope).also(owned::add)
        val b = account(run, "b", scope).also(owned::add)
        val messagesApi = MessagesApi(client, json, base)
        val chat = ChatsApi(client, json, base).createDirect(a.auth.token, b.auth.response.user.id).chatId
        for (side in listOf(a, b)) side.db.chatDao().upsertChatMetadata(chat, "direct", java.time.Instant.now().toString(), null)
        val input = File(context.cacheDir, "service-original-$run.bin")
        val byteCount = 130L * 1024 * 1024 + 127
        input.outputStream().use { out ->
            val pattern = ByteArray(64 * 1024).also { java.util.Random(98331).nextBytes(it) }
            var remaining = byteCount
            while (remaining > 0) { val n = minOf(pattern.size.toLong(), remaining).toInt(); out.write(pattern, 0, n); remaining -= n }
        }
        val senderAllowed = AtomicBoolean(true)
        val receiverAllowed = AtomicBoolean(true)
        val offersReady = Channel<String>(8)
        val offerEnqueueGate = Mutex()
        val enqueuedOffers = mutableSetOf<String>()
        val incoming = ingestion(b, messagesApi)
        lateinit var sender: DirectTransferCoordinator
        lateinit var receiver: DirectTransferCoordinator
        fun coordinator(side: Side, other: Side, send: suspend (String, DirectFileOffer, Int) -> Unit, allowed: () -> Boolean) =
            DirectTransferCoordinator(context, side.auth, side.db.workspaceDao(), side.db.messageDao(), side.signal,
                DirectTransferApi(client, side.auth, base), send, { other.auth.response.user.id }, { emptyList() }, allowed)
        receiver = coordinator(b, a, { _, _, _ -> error("Receiver must not create an offer") }, { receiverAllowed.get() })
        sender = coordinator(a, b, { id, offer, _ ->
            offerEnqueueGate.withLock {
            if (offer.transferId in enqueuedOffers) return@withLock
            val authenticated = MessageContentCodec.authenticate(MessageContentCodec.encodeText(DirectFileOffer.FALLBACK, directFile = offer),
                id, offer.offerClientMessageId, "content", null)
            val encrypted = a.signal.buildEnvelopeForDevice(b.auth.response.user.id, b.auth.response.deviceId, a.auth.token, authenticated)
            val ack = messagesApi.sendMessage(a.auth.token, id, offer.offerClientMessageId,
                listOf(EnvelopeDto(encrypted.recipientDeviceId, encrypted.messageType, encrypted.ciphertext)))
            a.db.messageDao().upsert(MessageEntity(offer.offerClientMessageId, ack.id, id, a.auth.response.user.id,
                DirectFileOffer.FALLBACK, ack.sequence, ack.createdAt, "sent"))
            val dto = messagesApi.listMessages(b.auth.token, id).messages.single { it.clientMessageId == offer.offerClientMessageId }
            assertTrue(incoming.ingest(listOf(dto), advanceCursor = true).advancedFully)
            val received = record(b, offer.transferId)
            assertTrue("Authenticated offer changed during ingestion", offer == received.offer)
            assertFalse(received.consent)
            assertEquals("receiver", received.role)
            enqueuedOffers += offer.transferId
            offersReady.send(offer.transferId)
            }
        }, { senderAllowed.get() })
        scope.launch { sender.errors.collect { android.util.Log.w("DirectNativeQA", "service-sender-error=$it") } }
        scope.launch { receiver.errors.collect { android.util.Log.w("DirectNativeQA", "service-receiver-error=$it") } }
        watchCoordinator(scope, sender, "service-$run-a")
        watchCoordinator(scope, receiver, "service-$run-b")
        val observations = JSONObject()
        try {
            assertTrue(sender.offerOriginal(chat, Uri.fromFile(input), 1))
            val transfer = withTimeout(90_000) { offersReady.receive() }
            awaitState(a, transfer) { it.registered }
            receiver.accept(transfer)
            awaitState(b, transfer) { it.durableOffset >= 4L * 1024 * 1024 }

            val pinnedA = record(a, transfer)
            val pinnedB = record(b, transfer)
            val cursors = mutableMapOf<Side, Long>()
            // Real Signal-authenticated packets with a valid-shape WRONG possession
            // nonce must only retire their cursor, never disturb a healthy live PC.
            for (type in listOf("accept", "resume", "answer", "pause", "cancel", "reject", "complete", "offer", "complete_ack")) {
                val from = if (type in setOf("offer", "complete_ack")) a else b
                val to = if (from === a) b else a
                val signalId = UUID.randomUUID().toString()
                val wrong = DirectFileControl(transferId = transfer, chatId = chat, offerClientMessageId = pinnedA.offer.offerClientMessageId,
                    clientSignalId = signalId, senderUserId = from.auth.response.user.id, senderDeviceId = from.auth.response.deviceId,
                    recipientUserId = to.auth.response.user.id, recipientDeviceId = to.auth.response.deviceId, offerNonce = DirectOfferNonce.create(),
                    attemptId = requireNotNull(pinnedA.attemptId), type = type,
                    sdp = if (type in setOf("offer", "answer")) minimalSdp() else null,
                    offset = if (type in setOf("accept", "resume", "complete", "complete_ack")) pinnedA.offer.size else null,
                    prefixSha256 = if (type in setOf("accept", "resume", "complete", "complete_ack")) pinnedA.offer.sha256 else null,
                    reason = if (type == "pause") "reconnect_required" else null)
                val encrypted = from.signal.buildEnvelopeForDevice(to.auth.response.user.id, to.auth.response.deviceId, from.auth.token,
                    DirectFileOffer.codec.encodeToString(DirectFileControl.serializer(), wrong).toByteArray())
                DirectTransferApi(client, from.auth, base).post(transfer, DirectSignalEnvelope(clientSignalId = signalId,
                    recipientDeviceId = to.auth.response.deviceId, messageType = encrypted.messageType, ciphertext = encrypted.ciphertext), 1)
                val posted = DirectTransferApi(client, to.auth, base).signals(transfer, 0, 1).signals.single { it.clientSignalId == signalId }
                cursors[to] = maxOf(cursors[to] ?: 0, posted.sequence)
            }
            cursors.forEach { (side, cursor) -> awaitState(side, transfer) { it.cursor >= cursor } }
            for ((side, before) in listOf(a to pinnedA, b to pinnedB)) {
                val after = record(side, transfer)
                assertEquals(before.attemptId, after.attemptId); assertEquals(before.peerDeviceId, after.peerDeviceId)
                assertTrue(after.consent); assertTrue(after.state in setOf("transferring", "verifying", "completed"))
            }
            observations.put("nine_wrong_nonce_controls_no_protocol_effects", true)

            // Real Signal/text and file controls share the existing ratchet safely.
            val ordinaryId = UUID.randomUUID().toString()
            val ordinary = MessageContentCodec.authenticate(MessageContentCodec.encodeText("ordinary-during-direct-$run"), chat, ordinaryId, "content", null)
            val envelope = a.signal.buildEnvelopeForDevice(b.auth.response.user.id, b.auth.response.deviceId, a.auth.token, ordinary)
            messagesApi.sendMessage(a.auth.token, chat, ordinaryId, listOf(EnvelopeDto(envelope.recipientDeviceId, envelope.messageType, envelope.ciphertext)))
            val ordinaryDto = messagesApi.listMessages(b.auth.token, chat).messages.single { it.clientMessageId == ordinaryId }
            assertTrue(incoming.ingest(listOf(ordinaryDto), true).advancedFully)
            assertEquals("ordinary-during-direct-$run", b.db.messageDao().findByClientMessageId(ordinaryId)?.text)

            // Exercise the real coordinator pause/resume path without touching a main call.
            senderAllowed.set(false)
            awaitState(a, transfer) { it.state == "paused" }
            val prefix = record(b, transfer).durableOffset
            assertTrue(prefix in 1 until byteCount)
            senderAllowed.set(true); sender.recoveryEvent(); receiver.recoveryEvent()
            awaitState(b, transfer) { it.state == "transferring" && it.durableOffset >= prefix + 2L * 1024 * 1024 }
            receiverAllowed.set(false)
            awaitState(b, transfer) { it.state == "paused" && it.pausedForLocalCall }
            receiverAllowed.set(true); receiver.recoveryEvent()
            awaitState(a, transfer, 240_000) { it.completed && it.mailboxClosed }
            awaitState(b, transfer, 60_000) { it.completed && it.completionAcknowledged }
            val received = requireNotNull(receiver.completedFile(transfer))
            assertEquals(byteCount, received.first.length())
            val ownDisk = DirectFileStorage(context, "verification-only-$run") {}
            assertEquals(record(a, transfer).offer.sha256, ownDisk.hash(received.first))
            assertEquals(byteCount, record(a, transfer).durableOffset)
            assertEquals(byteCount, record(b, transfer).durableOffset)
            observations.put("resume_prefix", prefix).put("bytes", byteCount).put("sha256", record(a, transfer).offer.sha256)
                .put("completed_sender", true).put("completed_receiver", true).put("ordinary_text_during_transfer", true)
                .put("sender_device", a.auth.response.deviceId).put("receiver_device", b.auth.response.deviceId)
                .put("account_ids", JSONArray(listOf(a.auth.response.user.id, b.auth.response.user.id)))
            assertFalse(record(b, transfer).pending.any { it.control.type == "complete" })

            // Rejected BEFORE consent: viewing did not claim; manual decline must
            // claim once and deliver the real Signal-authenticated rejection.
            assertTrue(sender.offerOriginal(chat, Uri.fromFile(input), 1))
            val declined = withTimeout(90_000) { offersReady.receive() }
            awaitState(a, declined) { it.registered }
            assertNull(DirectTransferApi(client, b.auth, base).get(declined, 1).recipientDeviceId)
            receiver.decline(declined)
            awaitState(a, declined) { it.state == "rejected" }
            awaitState(b, declined) { it.state == "rejected" && it.mailboxClosed }
            observations.put("decline_without_accept", true)

            assertTrue(sender.offerOriginal(chat, Uri.fromFile(input), 1))
            val cancelled = withTimeout(90_000) { offersReady.receive() }
            awaitState(a, cancelled) { it.registered }
            receiver.accept(cancelled)
            awaitState(b, cancelled) { it.durableOffset >= 4L * 1024 * 1024 }
            sender.cancel(cancelled)
            awaitState(a, cancelled) { it.state == "cancelled" && it.mailboxClosed }
            awaitState(b, cancelled) { it.state == "cancelled" }
            assertNull(receiver.completedFile(cancelled))
            val cancelledOffset = record(b, cancelled).durableOffset
            delay(1000); assertEquals(cancelledOffset, record(b, cancelled).durableOffset)
            observations.put("sender_cancel_stops_receiver", true)

            // Actual server block policy must stop an already-streaming peer,
            // not merely make a later metadata request fail while bytes continue.
            assertTrue(sender.offerOriginal(chat, Uri.fromFile(input), 1))
            val blocked = withTimeout(90_000) { offersReady.receive() }
            awaitState(a, blocked) { it.registered }
            receiver.accept(blocked)
            awaitState(b, blocked) { it.durableOffset >= 4L * 1024 * 1024 }
            ChatsApi(client, json, base).blockUser(b.auth.token, chat, a.auth.response.user.id)
            awaitState(a, blocked) { it.state == "paused" && it.reason == "access_unavailable" }
            awaitState(b, blocked) { it.state == "paused" }
            val blockedOffset = record(b, blocked).durableOffset
            delay(1500); assertEquals(blockedOffset, record(b, blocked).durableOffset)
            assertFalse(record(b, blocked).completed)
            observations.put("server_block_stops_active_bytes", true)
            ChatsApi(client, json, base).unblockUser(b.auth.token, chat)
            sender.cancel(blocked); receiver.cancel(blocked)
            val counters = JSONObject(); httpCounts.forEach { (key, value) -> counters.put(key, value.get()) }
            val proof = JSONObject().put("passed", true).put("scope", "One Samsung, two separately namespaced Android-keystore Signal stores/Auth/Room/coordinators, public HTTPS mailbox and real RTC; injected real MessagesApi enqueue, no normal outbox/UI/two-device claim")
                .put("run", run).put("result", observations).put("http_categories", counters)
            File(context.filesDir, "direct-service-$run.json").writeText(proof.toString())
            android.util.Log.i("DirectNativeQA", "service-proof=direct-service-$run.json passed=true")
        } finally {
            input.delete()
            if (wake.isHeld) wake.release()
            // Own test account IDs are recorded for root's controlled cleanup. No main account is touched.
        }
        } finally { for (side in owned) cleanupOwned(side); scope.cancel(); if (wake.isHeld) wake.release() }
    }

    internal suspend fun account(run: String, role: String, scope: CoroutineScope): Side {
        val authApi = AuthApi(client, json, base)
        val username = "dfqa" + run.replace("-", "").take(12) + role
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(context, "direct-service-$run-$role-keys", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        val password = UUID.randomUUID().toString() + "aA9!"
        val installation = UUID.randomUUID().toString()
        val credential = authApi.createInstallationCredential(base)
        check(prefs.edit().putString("qa_run", run).putString("qa_role", role).putString("qa_username", username)
            .putString("qa_password", password).putString("qa_installation", installation).putString("qa_installation_credential", credential).commit())
        val ledger = File(context.filesDir, "direct-service-owned-$run-$role.json")
        ledger.writeText(JSONObject().put("run", run).put("username", username).put("state", "registering")
            .put("scope", "Own QA only; credentials retained only in own Android-keystore encrypted namespace").toString())
        val response = authApi.register(RegisterRequestDto(username, password, "Direct QA $role",
            installation, deviceName = "Owned direct QA $role", installationCredential = credential))
        check(prefs.edit().putString("qa_user_id", response.user.id).putString("qa_device_id", response.deviceId).commit())
        ledger.writeText(JSONObject().put("run", run).put("username", username).put("user_id", response.user.id)
            .put("device_id", response.deviceId).put("scope", "Owned QA account only; no credentials").toString())
        val auth = OwnAuth(response)
        val database = Room.databaseBuilder(context, MessengerDatabase::class.java, "direct-service-$run-$role.db").build()
        val keyStore = SignalKeyStore(AndroidKeystoreSignalStore(prefs), auth::currentSessionEpoch,
            { "${response.user.id}:${response.deviceId}" })
        val identity = IdentityVerificationManager(keyStore, database.peerIdentityDao(), { response.user.id }, scope, auth::currentSessionEpoch,
            { epoch, work -> auth.withSessionWrite(epoch) { work() }; Unit })
        val signal = SignalSessionManager(keyStore, KeysApi(client, json, base), identity)
        val side = Side(auth, database, signal, prefs)
        try { signal.bootstrapKeysIfNeeded(response.accessToken) }
        catch (failure: Exception) { cleanupOwned(side); throw failure }
        return side
    }

    internal suspend fun cleanupOwned(side: Side) {
        val prefs = side.prefs
        val run = prefs.getString("qa_run", null)
        val role = prefs.getString("qa_role", null)
        val username = prefs.getString("qa_username", null)
        val ticket = JSONObject().put("user_id", side.auth.response.user.id).put("username", side.auth.response.user.username)
        try {
            require(run != null && canonicalUuid(run) && role in setOf("a", "b", "x", "r"))
            require(username == "dfqa" + run.replace("-", "").take(12) + role && username == side.auth.response.user.username)
            require(prefs.getString("qa_user_id", null) == side.auth.response.user.id && prefs.getString("qa_device_id", null) == side.auth.response.deviceId)
            AuthApi(client, json, base).deleteAccount(side.auth.token, requireNotNull(prefs.getString("qa_password", null)))
            ticket.put("deleted_through_normal_api", true)
            side.auth.logout(); delay(100)
            check(prefs.edit().clear().commit())
        } catch (failure: Exception) {
            ticket.put("deleted_through_normal_api", false).put("failure_class", failure.javaClass.simpleName)
            // Private encrypted credentials remain for a narrowly owned recovery.
            side.auth.logout()
        }
        File(context.filesDir, "direct-service-cleanup-${side.auth.response.user.id}.json").writeText(ticket.toString())
    }

    @Suppress("UNCHECKED_CAST")
    internal fun watchCoordinator(scope: CoroutineScope, coordinator: DirectTransferCoordinator, label: String) {
        require(label.matches(Regex("[a-zA-Z0-9-]+")))
        val flow = DirectTransferCoordinator::class.java.getDeclaredField("records").apply { isAccessible = true }
            .get(coordinator) as kotlinx.coroutines.flow.StateFlow<Map<String, DirectTransferRecord>>
        val ownerScope = DirectTransferCoordinator::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(coordinator) as CoroutineScope
        ownerScope.coroutineContext[Job]?.invokeOnCompletion { android.util.Log.i("DirectNativeQA", "scope-end $label cause=${it?.javaClass?.simpleName}") }
        scope.launch {
            val previous = mutableMapOf<String, String>()
            flow.collect { values -> values.values.forEach { value ->
                val safe = JSONObject().put("id", value.offer.transferId).put("state", value.state).put("completed", value.completed)
                    .put("completionReceived", value.completionReceived).put("completionAcknowledged", value.completionAcknowledged)
                    .put("mailboxClosed", value.mailboxClosed).put("closePending", value.closePending).put("reason", value.reason)
                    .put("pending", JSONArray(value.pending.map { it.control.type })).put("attempt", value.attemptId)
                val signature = safe.toString()
                if (previous.put(value.offer.transferId, signature) != signature) {
                    safe.put("at", System.currentTimeMillis()).put("offset", value.durableOffset)
                    File(context.filesDir, "direct-events-$label.jsonl").appendText(safe.toString() + "\n")
                    android.util.Log.i("DirectNativeQA", "service-state $label $safe")
                }
            } }
        }
    }

    private fun minimalSdp(): String = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=sctp-port:5000\r\na=ice-ufrag:qa\r\na=ice-pwd:owned-qa-only-password\r\na=fingerprint:sha-256 " + List(32) { "AA" }.joinToString(":") + "\r\n"

    internal fun ingestion(side: Side, api: MessagesApi): MessageIngestionPipeline {
        val cursor = object : SyncCursorStore {
            private val prefs = context.getSharedPreferences("direct-service-${side.auth.response.user.id}-cursor", Context.MODE_PRIVATE)
            override fun lastSyncedSequence() = prefs.getLong("cursor", 0)
            override fun saveLastSyncedSequence(sequence: Long) { check(prefs.edit().putLong("cursor", sequence).commit()) }
        }
        return MessageIngestionPipeline(side.db.messageDao(), side.db.chatDao(), side.db.attachmentDao(), side.db.reactionDao(),
            side.signal, side.auth, api, cursor, side.db.deletedChatDao(), workspaceDao = side.db.workspaceDao())
    }
    internal suspend fun record(side: Side, id: String): DirectTransferRecord = requireNotNull(
        side.db.workspaceDao().allDirectTransfers().firstOrNull { it.name == DirectTransferCoordinator.key(id) }
            ?.let { DirectTransferCoordinator.decodeRecord(it.value) })
    internal suspend fun awaitState(side: Side, id: String, timeout: Long = 60_000, predicate: (DirectTransferRecord) -> Boolean) = withTimeout(timeout) {
        var iteration = 0
        while (true) {
            val value = record(side, id)
            if (predicate(value)) break
            if (iteration++ % 8 == 0) android.util.Log.i("DirectNativeQA", "service-await id=$id role=${value.role} state=${value.state} reason=${value.reason} offset=${value.durableOffset} registered=${value.registered} completed=${value.completed} received=${value.completionReceived} closed=${value.mailboxClosed} closePending=${value.closePending} pending=${value.pending.map { it.control.type }}")
            delay(250)
        }
    }
    internal data class Side(val auth: OwnAuth, val db: MessengerDatabase, val signal: SignalSessionManager, val prefs: android.content.SharedPreferences)
    internal class OwnAuth(val response: AuthResponseDto) : AuthRepository {
        val token get() = response.accessToken
        private var epoch = 1
        private val gate = com.messenger.app.data.auth.SessionWriteGate { epoch }
        private val workspaceGate = com.messenger.app.data.auth.SessionWriteGate { epoch }
        override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser(response.user.id, response.user.username, response.user.displayName)))
        override suspend fun register(username: String, password: String, displayName: String): Result<Unit> = error("Already registered own fixture")
        override suspend fun login(username: String, password: String): Result<Unit> = error("No main account login permitted")
        override suspend fun logout() { epoch++; authState.value = AuthSessionState.LoggedOut }
        override suspend fun currentAccessToken(): String? = if (epoch == 1) token else null
        override fun currentDeviceId(): String = response.deviceId
        override fun currentSessionEpoch() = epoch
        override suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T? = gate.write(epochAtStart, block)
        override suspend fun <T> withWorkspaceWrite(epochAtStart: Int, block: suspend () -> T): T? = workspaceGate.write(epochAtStart, block)
        override suspend fun clearRoom(block: suspend () -> Unit) = gate.clear { workspaceGate.clear(block) }
    }
}
