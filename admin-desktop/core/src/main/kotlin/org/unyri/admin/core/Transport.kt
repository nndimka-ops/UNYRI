package org.unyri.admin.core

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.io.ByteArrayOutputStream

class Origin(value: String) {
    val value: String
    init {
        val uri = runCatching { URI(value.trim()) }.getOrElse { throw AdminFailure(FailureKind.INVALID) }
        val loopback = uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
        if (uri.scheme !in setOf("http", "https") || uri.scheme == "http" && !loopback || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.rawPath !in setOf("", "/") ||
            uri.port !in -1..65535 || uri.port == 0) throw AdminFailure(FailureKind.INVALID)
        this.value = "${uri.scheme}://${uri.rawAuthority}".lowercase(java.util.Locale.ROOT)
    }
    fun resolve(path: String): URI {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains('\\') || path.contains('#') ||
            path.any { it.code < 32 } || path.substringBefore('?').split('/').any { it == ".." || it == "." }) throw ProtocolFailure()
        val result = runCatching { URI(value + path) }.getOrElse { throw ProtocolFailure() }
        if (result.rawAuthority != URI(value).rawAuthority) throw ProtocolFailure()
        return result
    }
}
data class WireRequest(val method: String, val path: String, val body: String?, val bearer: String?, val admin: String?) {
    override fun toString() = "WireRequest($method, redacted)"
}
data class BinaryBody(val mime: String, val bytes: ByteArray) { override fun toString() = "BinaryBody(redacted)" }
fun interface Transport {
    fun send(request: WireRequest): String
    fun download(request: WireRequest, maximum: Int): BinaryBody = throw ProtocolFailure()
}

class JvmTransport(private val origin: Origin) : Transport {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    override fun download(request: WireRequest, maximum: Int): BinaryBody {
        val builder = HttpRequest.newBuilder(origin.resolve(request.path)).timeout(Duration.ofSeconds(20))
            .header("Accept", "image/png, image/jpeg").header("Cache-Control", "no-store")
        request.bearer?.let { builder.header("Authorization", "Bearer $it") }
        request.admin?.let { builder.header("x-coco-admin", it) }
        val response = try { client.send(builder.GET().build(), HttpResponse.BodyHandler { LimitedBody(maximum) }) }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
            catch (_: Exception) { throw AdminFailure(FailureKind.NETWORK) }
        if (response.statusCode() !in 200..299) throw AdminFailure(when (response.statusCode()) {
            401 -> FailureKind.AUTH; 403 -> FailureKind.FORBIDDEN; 404 -> FailureKind.MISSING; else -> FailureKind.SERVER
        }, response.statusCode())
        val mime = response.headers().firstValue("content-type").orElse("").substringBefore(';').lowercase()
        val bytes = response.body()
        val png = byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)
        if (bytes.isEmpty() || bytes.size > maximum || when (mime) {
            "image/png" -> !bytes.take(8).toByteArray().contentEquals(png)
            "image/jpeg" -> bytes.size < 3 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() || bytes[2] != 0xff.toByte()
            else -> true
        }) throw ProtocolFailure()
        return BinaryBody(mime, bytes)
    }
    override fun send(request: WireRequest): String {
        val builder = HttpRequest.newBuilder(origin.resolve(request.path)).timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json").header("Cache-Control", "no-store")
        request.bearer?.let { builder.header("Authorization", "Bearer $it") }
        request.admin?.let { builder.header("x-coco-admin", it) }
        if (request.body != null) builder.header("Content-Type", "application/json; charset=utf-8")
        builder.method(request.method, request.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        val response = try { client.send(builder.build(), HttpResponse.BodyHandler { info ->
            LimitedBody(if (info.statusCode() in 200..299) 2 * 1024 * 1024 else 65536)
        }) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (_: Exception) { throw AdminFailure(FailureKind.NETWORK) }
        val status = response.statusCode()
        return run {
            if (status !in 200..299) {
                // Never include untrusted bodies (potential credentials/PII) in errors or logs.
                throw AdminFailure(when (status) {
                    401 -> FailureKind.AUTH; 403 -> FailureKind.FORBIDDEN; 404 -> FailureKind.MISSING
                    409 -> FailureKind.CONFLICT; 429 -> FailureKind.RATE_LIMIT
                    in 300..399 -> FailureKind.PROTOCOL; else -> FailureKind.SERVER
                }, status)
            }
            if (status == 204) return@run "{}"
            if (!response.headers().firstValue("content-type").orElse("").lowercase().startsWith("application/json")) throw ProtocolFailure()
            val maximum = 2 * 1024 * 1024
            if (response.headers().firstValueAsLong("content-length").orElse(0) > maximum) throw ProtocolFailure()
            val bytes = response.body()
            if (bytes.size > maximum) throw ProtocolFailure()
            val result = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
                .getOrElse { throw ProtocolFailure() }
            JsonBudget.check(result); result
        }
    }
}

private class LimitedBody(private val maximum: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val output = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null
    override fun getBody(): CompletionStage<ByteArray> = result
    override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(1) }
    override fun onNext(items: List<ByteBuffer>) {
        for (buffer in items) {
            if (buffer.remaining() > maximum - output.size()) { subscription?.cancel(); result.completeExceptionally(ProtocolFailure()); return }
            val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); output.write(bytes)
        }
        subscription?.request(1)
    }
    override fun onError(error: Throwable) { result.completeExceptionally(error) }
    override fun onComplete() { result.complete(output.toByteArray()) }
}

/** Bound nesting and structural work before the JSON parser allocates a tree. */
object JsonBudget {
    fun check(text: String) {
        if (text.length > 2 * 1024 * 1024) throw ProtocolFailure()
        var depth = 0; var nodes = 0; var quoted = false; var escaped = false
        val counts = IntArray(33)
        for (ch in text) {
            if (quoted) { if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false; continue }
            when (ch) {
                '"' -> { quoted = true; nodes++ }
                '[', '{' -> { depth++; if (depth > 32) throw ProtocolFailure(); counts[depth] = 0; nodes++ }
                ']', '}' -> { depth--; if (depth < 0) throw ProtocolFailure() }
                ',' -> { nodes++; if (++counts[depth] > 4096) throw ProtocolFailure() }
            }
            if (nodes > 65536) throw ProtocolFailure()
        }
        if (depth != 0 || quoted) throw ProtocolFailure()
    }
}
