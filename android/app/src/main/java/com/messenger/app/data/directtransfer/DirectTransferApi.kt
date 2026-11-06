package com.messenger.app.data.directtransfer

import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class DirectTransferApiError(val status: Int, val retryAfterSeconds: Long = 0) : Exception("Direct transfer request failed ($status)")

/** Only small metadata/Signal envelopes go through this client; it has no file-upload API. */
internal class DirectTransferApi(private val client: OkHttpClient, private val auth: AuthRepository, private val baseUrl: String,
    private val requests: DirectHttpGate = DirectHttpGate()) {
    @Volatile var executionAllowed: (String?, Int) -> Boolean = { _, _ -> true }
    private val json = DirectFileOffer.codec
    private val media = "application/json".toMediaType()
    @Serializable private data class Capability(val version: Int, @kotlinx.serialization.SerialName("max_file_bytes") val maxFileBytes: Long, @kotlinx.serialization.SerialName("direct_only") val directOnly: Boolean)

    suspend fun requireSupported(epoch: Int) {
        val capability = json.decodeFromString(Capability.serializer(), request("/capabilities", "GET", epoch))
        require(capability.version == 1 && capability.directOnly && capability.maxFileBytes >= DirectFileWire.MAX_FILE_BYTES) { "Unsupported direct transfer service" }
    }
    suspend fun register(record: DirectTransferRecord, epoch: Int): DirectTransferMetadata {
        val body = buildJsonObject {
            put("chat_id", record.chatId); put("offer_client_message_id", record.offer.offerClientMessageId)
            put("recipient_user_id", record.offer.recipientUserId); put("expires_at", record.offer.expiresAt)
        }.toString()
        return metadata(request("/${record.offer.transferId}", "PUT", epoch, body))
    }
    suspend fun get(id: String, epoch: Int): DirectTransferMetadata = metadata(request("/$id", "GET", epoch))
    suspend fun claim(id: String, epoch: Int, beforeStart: () -> Unit = {}): DirectTransferMetadata =
        metadata(request("/$id/claim", "POST", epoch, "{}", beforeStart))
    suspend fun signals(id: String, cursor: Long, epoch: Int): DirectSignalsPage {
        require(cursor >= 0)
        val page = json.decodeFromString(DirectSignalsPage.serializer(), request("/$id/signals?after=$cursor&limit=32", "GET", epoch))
        require(page.signals.size <= 32 && page.signals.all { it.ciphertext.length <= 90_000 && it.messageType in 1..2 })
        return page
    }
    suspend fun post(id: String, signal: DirectSignalEnvelope, epoch: Int, beforeStart: () -> Unit = {}) {
        require(signal.messageType in 1..2 && signal.ciphertext.length <= 90_000 && canonicalUuid(signal.clientSignalId) && canonicalUuid(signal.recipientDeviceId))
        val body = buildJsonObject {
            put("client_signal_id", signal.clientSignalId); put("recipient_device_id", signal.recipientDeviceId)
            put("message_type", signal.messageType); put("ciphertext", signal.ciphertext)
        }.toString()
        require(body.toByteArray(Charsets.UTF_8).size <= 96 * 1024)
        request("/$id/signals", "POST", epoch, body, beforeStart)
    }
    suspend fun close(id: String, epoch: Int) { request("/$id/close", "POST", epoch, "{}") }
    private fun metadata(raw: String) = json.decodeFromString(DirectTransferMetadata.serializer(), raw)

    suspend fun <T> prioritizing(id: String, epoch: Int, operation: suspend () -> T): T {
        checkOwner(epoch)
        return requests.promoting("$epoch:$id") { checkOwner(epoch); operation() }
    }

    private suspend fun request(path: String, method: String, epoch: Int, body: String? = null,
        beforeStart: () -> Unit = {}): String = withContext(Dispatchers.IO) {
        val ownerContext = currentCoroutineContext()
        ownerContext.ensureActive()
        checkOwner(epoch)
        val transferKey = path.removePrefix("/").substringBefore('/').substringBefore('?')
            .takeIf(::canonicalUuid)?.let { "$epoch:$it" }
        val transferId = path.removePrefix("/").substringBefore('/').substringBefore('?').takeIf(::canonicalUuid)
        val result = requests.withPermit(transferKey, method == "GET", prepare = {
            // This work may suspend and is outside the gate lock. A capacity
            // notification is not a quota debit; a lost final admission repeats it.
            ownerContext.ensureActive()
            checkOwner(epoch)
            val token = auth.currentAccessToken() ?: throw AuthApiException.Api("unauthorized", "Not authenticated")
            ownerContext.ensureActive()
            checkOwner(epoch)
            val endpoint = ServerEndpoint.resolve(baseUrl).trimEnd('/') + "/direct-transfers" + path
            Request.Builder().url(endpoint).header("Authorization", "Bearer $token")
                .method(method, body?.toRequestBody(media)).build()
        }, beforeStart = {
            // Bounded, non-suspending producer predicate/process-local attempt
            // marker only. Durable journal/Signal work belongs before this API.
            ownerContext.ensureActive()
            checkOwner(epoch)
            if (!executionAllowed(transferId, epoch)) throw DirectOperationRetired()
            beforeStart()
            checkOwner(epoch)
            ownerContext.ensureActive()
        }, start = { prepared ->
            val response = CompletableDeferred<String>()
            val call = client.newCall(prepared)
            try {
                ownerContext.ensureActive()
                checkOwner(epoch)
                if (!executionAllowed(transferId, epoch)) throw DirectOperationRetired()
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                        response.completeExceptionally(AuthApiException.Network("Direct transfer network unavailable"))
                    }
                    override fun onResponse(call: okhttp3.Call, received: okhttp3.Response) {
                        val value = runCatching {
                            received.use {
                                val retry = it.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 3600) ?: 0
                                if (it.code == 429) {
                                    // Synchronous and unconditional, including a cancelled
                                    // caller/old epoch: no unstarted request may escape via
                                    // response completion, permit release or a late catch.
                                    requests.backOff(retry * 1000)
                                }
                                if (response.isCancelled) throw CancellationException("Direct transfer request cancelled")
                                checkOwner(epoch)
                                if (!it.isSuccessful) throw DirectTransferApiError(it.code, retry)
                                val source = it.body ?: error("Empty transfer response")
                                require(source.contentLength() <= 4 * 1024 * 1024) { "Transfer response too large" }
                                val bytes = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(64 * 1024)
                                val input = source.byteStream()
                                while (true) {
                                    val count = input.read(buffer, 0, minOf(buffer.size, 4 * 1024 * 1024 + 1 - bytes.size()))
                                    if (count < 0) break
                                    if (count == 0) continue
                                    bytes.write(buffer, 0, count)
                                    require(bytes.size() <= 4 * 1024 * 1024) { "Transfer response too large" }
                                }
                                bytes.toByteArray().toString(Charsets.UTF_8)
                            }
                        }
                        value.fold(response::complete, response::completeExceptionally)
                    }
                })
            } catch (error: Throwable) {
                call.cancel()
                throw error
            }
            DirectHttpStarted(awaitResult = { response.await() }, cancel = {
                response.cancel()
                call.cancel()
            })
        })
        ownerContext.ensureActive()
        checkOwner(epoch)
        result
    }
    private fun checkOwner(epoch: Int) { if (auth.currentSessionEpoch() != epoch) throw CancellationException("Transfer account changed") }
}
