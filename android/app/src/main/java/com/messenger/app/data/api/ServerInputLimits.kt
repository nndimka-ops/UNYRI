package com.messenger.app.data.api

import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException

internal class ServerInputLimitException : IOException("Server input exceeds local limits")

/** Limits apply to the decoded body source, after OkHttp's transparent gzip expansion. */
internal object ServerInputLimits {
    const val API_BYTES = 16 * 1024 * 1024
    const val ERROR_BYTES = 64 * 1024
    const val RECOVERY_BYTES = 64 * 1024
    const val HEALTH_BYTES = 16 * 1024
    const val ADMIN_BYTES = 8 * 1024 * 1024
    const val WS_BYTES = 1024 * 1024
    const val DEPTH = 32
    const val COLLECTION_ITEMS = 4096

    fun read(body: ResponseBody?, maxBytes: Int): String {
        return readBytes(body, maxBytes).toString(body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
    }

    fun readBytes(body: ResponseBody?, maxBytes: Int): ByteArray {
        if (body == null) return ByteArray(0)
        if (body.contentLength() > maxBytes) throw ServerInputLimitException()
        val buffer = Buffer()
        val source = body.source()
        while (true) {
            if (Thread.currentThread().isInterrupted) throw IOException("Cancelled")
            val n = source.read(buffer, minOf(8192L, maxBytes + 1L - buffer.size))
            if (n == -1L) break
            if (buffer.size > maxBytes) throw ServerInputLimitException()
        }
        return buffer.readByteArray()
    }

    /** Iterative preflight: no JSON tree, recursion or collection allocation from remote lengths.
     * The real decoder still owns syntax/schema validation, including unknown fields. */
    fun validateJson(text: String, maxChars: Int = API_BYTES, maxString: Int = 12 * 1024 * 1024) {
        if (text.length > maxChars) throw ServerInputLimitException()
        val counts = IntArray(DEPTH)
        var depth = 0
        var quoted = false
        var escaped = false
        var length = 0
        var nodes = 0
        var primitiveLength = 0
        for (c in text) {
            if (quoted) {
                if (++length > maxString) throw ServerInputLimitException()
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
                continue
            }
            when (c) {
                '"' -> { quoted = true; length = 0; primitiveLength = 0 }
                '{', '[' -> {
                    if (depth == DEPTH || ++nodes > 65536) throw ServerInputLimitException()
                    counts[depth++] = 1
                    primitiveLength = 0
                }
                '}', ']' -> { if (depth > 0) depth--; primitiveLength = 0 }
                ',' -> {
                    if (depth > 0 && ++counts[depth - 1] > COLLECTION_ITEMS) throw ServerInputLimitException()
                    if (++nodes > 65536) throw ServerInputLimitException()
                    primitiveLength = 0
                }
                ':', ' ', '\r', '\n', '\t' -> primitiveLength = 0
                else -> if (++primitiveLength > 128) throw ServerInputLimitException()
            }
        }
    }
}

internal fun Response.readBoundedJson(maxBytes: Int = ServerInputLimits.API_BYTES): String {
    val endpointLimit = when {
        request.url.encodedPath == "/health" -> ServerInputLimits.HEALTH_BYTES
        request.url.encodedPath.startsWith("/admin/") -> ServerInputLimits.ADMIN_BYTES
        else -> maxBytes
    }
    val admitted = minOf(maxBytes, endpointLimit)
    val limit = if (isSuccessful) admitted else minOf(admitted, ServerInputLimits.ERROR_BYTES)
    return ServerInputLimits.read(body, limit).also { ServerInputLimits.validateJson(it, limit) }
}
