package com.messenger.app.data.api

import com.messenger.app.data.history.*
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException

class HistoryBackupUnsupportedServer : Exception()

/** Bounded and cancellable transport, permanently scoped to the captured session.
 * Redirects are rejected: never forward an encrypted archive/token to a new host.
 */
class HistoryBackupApi(client: OkHttpClient, private val auth: AuthRepository, private val baseUrl: String) : HistoryBackupTransport, HistoryMediaTransport, HistoryTransferTransport {
    override suspend fun createTransfer(publicKey:String):HistoryTransferRecord = json.decodeFromString(execute("/history-transfer",json.encodeToString(HistoryTransferCreate(publicKey))))
    override suspend fun transfer(id:String):HistoryTransferRecord { requireHistoryId(id); return json.decodeFromString(execute("/history-transfer/$id")) }
    override suspend fun publishTransfer(id:String,body:HistoryTransferPublish):HistoryTransferRecord { requireHistoryId(id); return json.decodeFromString(execute("/history-transfer/$id/publish",json.encodeToString(body))) }
    override suspend fun ackTransfer(id:String,backup:String):HistoryTransferRecord { requireHistoryId(id); return json.decodeFromString(execute("/history-transfer/$id/ack",json.encodeToString(HistoryTransferAck(backup)))) }
    override suspend fun cancelTransfer(id:String) { requireHistoryId(id); execute("/history-transfer/$id/cancel","{}") }
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val epoch = auth.currentSessionEpoch()
    private val owner = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id
    private val endpoint = ServerEndpoint.resolve(baseUrl)
    private fun fence() {
        check(owner != null && auth.currentSessionEpoch() == epoch &&
            (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id == owner &&
            ServerEndpoint.resolve(baseUrl) == endpoint) { "History account or server changed" }
    }
    override suspend fun restoreComplete(backupId: String) {
        requireHistoryId(backupId)
        execute("/history-backup/restore-complete", json.encodeToString(mapOf("backup_id" to backupId)))
    }
    override suspend fun status(): HistoryBackupState = json.decodeFromString(execute("/history-backup"))
    override suspend fun download(id: String): HistoryBackupObject {
        requireHistoryId(id)
        return json.decodeFromString(execute("/history-backup/objects/$id", maxBytes = 12 * 1024 * 1024))
    }
    override suspend fun stage(request: HistoryBackupUpload): HistoryBackupState =
        json.decodeFromString(execute("/history-backup/stage", json.encodeToString(request)))
    override suspend fun confirm(request: HistoryBackupConfirm): HistoryBackupState =
        json.decodeFromString(execute("/history-backup/confirm", json.encodeToString(request)))
    override suspend fun preference(request: HistoryBackupPreference): HistoryBackupState =
        json.decodeFromString(execute("/history-backup/preference", json.encodeToString(request)))
    override suspend fun delete(request: HistoryBackupDelete): HistoryBackupState =
        json.decodeFromString(execute("/history-backup/delete", json.encodeToString(request)))

    override suspend fun mediaStart(request: HistoryMediaStart): HistoryMediaLink = json.decodeFromString(execute("/history-backup/media/start", json.encodeToString(request)))
    override suspend fun mediaSeal(id: String, revision: Long): HistoryMediaLink {
        requireHistoryId(id)
        return json.decodeFromString(execute("/history-backup/media/$id/seal", json.encodeToString(HistoryMediaRevision(revision))))
    }
    override suspend fun mediaGet(id: String): HistoryMediaLink {
        requireHistoryId(id); return json.decodeFromString(execute("/history-backup/media/$id"))
    }
    override suspend fun liveAttachment(id: String): String {
        requireHistoryId(id)
        return json.decodeFromString<DownloadAttachmentResponseDto>(execute("/attachments/$id/download")).downloadUrl
    }
    override suspend fun restoreCheck(ids: List<String>): Map<String, Long> {
        require(ids.size <= 50_000 && ids.distinct().size == ids.size); ids.forEach(::requireHistoryId)
        val result = linkedMapOf<String,Long>()
        for (batch in ids.chunked(500)) {
            val checked = json.decodeFromString<HistoryRestoreChecked>(execute("/history-backup/restore-check", json.encodeToString(HistoryRestoreCheck(batch))))
            check(checked.deleted.size <= batch.size)
            for (deletion in checked.deleted) {
                check(deletion.id in batch && deletion.sequence > 0 && !result.containsKey(deletion.id))
                result[deletion.id] = deletion.sequence
            }
        }
        return result
    }
    override suspend fun sourceComplete(ids: List<String>): Boolean {
        return sourceComplete(ids, emptyList())
    }
    override suspend fun sourceComplete(ids: List<String>, callIds: List<String>): Boolean {
        require(ids.size <= 50_000 && ids.distinct().size == ids.size); ids.forEach(::requireHistoryId)
        require(callIds.size <= 50_000 && callIds.distinct().size == callIds.size); callIds.forEach(::requireHistoryId)
        return json.decodeFromString<HistorySourceChecked>(execute("/history-backup/source-check", json.encodeToString(HistoryRestoreCheck(ids, callIds)))).complete
    }
    override suspend fun restoreCallCheck(ids: List<String>): Set<String> {
        require(ids.size <= 50_000 && ids.distinct().size == ids.size); ids.forEach(::requireHistoryId)
        val result = linkedSetOf<String>()
        for (batch in ids.chunked(500)) {
            val response = json.decodeFromString<HistoryRestoreChecked>(execute("/history-backup/restore-check", json.encodeToString(HistoryRestoreCheck(emptyList(), batch))))
            val removed = requireNotNull(response.deleted_call_ids) { "Server does not validate call restoration" }
            check(removed.size <= batch.size && removed.distinct().size == removed.size && response.deleted.isEmpty())
            removed.forEach { check(it in batch && result.add(it)) }
        }
        return result
    }
    private fun mediaRequest(url: String): Request.Builder {
        val parsed = url.toHttpUrl()
        val base = endpoint.toHttpUrl()
        require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null)
        require(parsed.isHttps || (!base.isHttps && parsed.host == base.host)) { "Unsafe media URL" }
        return Request.Builder().url(parsed).header("Cache-Control", "no-store")
    }
    override suspend fun receiveMedia(url: String, entry: HistoryMediaEntry, directory: java.io.File): java.io.File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val job = kotlinx.coroutines.currentCoroutineContext()
        fence(); job.ensureActive()
        val call = client.newCall(mediaRequest(url).build())
        mediaCall(call) { response ->
                if (!response.isSuccessful) throw HistoryMediaUnavailable()
                val body = requireNotNull(response.body)
                require(body.contentLength() == -1L || body.contentLength() == entry.bytes)
                HistoryMediaSpool(directory).receive(entry, { body.byteStream() }) { fence(); job.ensureActive() }
        }
    }
    override suspend fun putMedia(url: String, file: java.io.File) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val job = kotlinx.coroutines.currentCoroutineContext(); fence(); job.ensureActive()
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: okio.BufferedSink) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { fence(); job.ensureActive(); val n = input.read(buffer); if (n < 0) break; sink.write(buffer, 0, n) }
                }
            }
        }
        val call = client.newCall(mediaRequest(url).put(body).build())
        mediaCall(call) { check(it.isSuccessful) { "Media upload failed" }; fence() }
    }
    private suspend fun <T> mediaCall(call: Call, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { check(continuation.isActive); consume(it) } }
                continuation.resumeWith(result)
            }
        })
    }

    private suspend fun execute(path: String, body: String? = null, maxBytes: Int = 64 * 1024): String {
        fence()
        val token = requireNotNull(auth.currentAccessToken())
        fence()
        val builder = Request.Builder().url(endpoint + path).header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-store")
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        val result = suspendCancellableCoroutine<String> { continuation ->
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(AuthApiException.Network("Backup connection failed")))
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching {
                        response.use {
                            val limit = if (it.isSuccessful) maxBytes else 64 * 1024
                            val stream = it.body ?: error("Missing backup response")
                            require(stream.contentLength() <= limit)
                            val bytes = ByteArrayOutputStream()
                            stream.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("Cancelled")
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(bytes.size() + count <= limit) { "Oversized backup response" }
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            val text = bytes.toString("UTF-8")
                            if (path == "/history-backup" && it.code in setOf(404, 405)) throw HistoryBackupUnsupportedServer()
                            if (!it.isSuccessful) throw parseApiException(json, it.code, text)
                            text
                        }
                    })
                }
            })
        }
        fence()
        return result
    }
}
