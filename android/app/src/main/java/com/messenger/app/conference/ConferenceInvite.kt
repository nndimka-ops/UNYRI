package com.messenger.app.conference

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import com.messenger.app.BuildConfig

/** Bearer capability and media key. Intentionally never Serializable or a data class. */
class ConferenceInvite(val server: String, val roomId: String, val secret: String, val mediaKey: String) {
    fun forServer(selected: String): ConferenceInvite { require(server == origin(selected)) { "Invitation belongs to a different server" }; return this }
    /** Room identifiers alone do not authorize reuse of an active capability. */
    internal fun sameCapability(other: ConferenceInvite): Boolean = server == other.server && roomId == other.roomId &&
        secret == other.secret && mediaKey == other.mediaKey
    override fun toString() = "ConferenceInvite([redacted])"
    fun legacyLink(): String = "coco://conference?server=${URLEncoder.encode(server, "UTF-8")}&room=$roomId#v=1&i=$secret&k=$mediaKey"
    fun link(): String {
        val a = decodeKey(secret); val b = decodeKey(mediaKey)
        if (a.all { it == 0.toByte() } || b.all { it == 0.toByte() }) return legacyLink()
        val publicOrigin = origin(BuildConfig.PUBLIC_DEFAULT_API_ORIGIN)
        val public = server == publicOrigin
        val originBytes = server.toByteArray(Charsets.UTF_8)
        require(originBytes.size in 1..256)
        val id = UUID.fromString(roomId)
        val buffer = ByteBuffer.allocate(2 + (if (public) 32 else 2 + originBytes.size) + 80)
        buffer.put(2.toByte()).put(if (public) 0.toByte() else 1.toByte())
        if (public) buffer.put(MessageDigest.getInstance("SHA-256").digest(publicOrigin.toByteArray(Charsets.US_ASCII)))
        else buffer.putShort(originBytes.size.toShort()).put(originBytes)
        buffer.putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
        require(a.any { it != 0.toByte() } && b.any { it != 0.toByte() } && !a.contentEquals(b))
        buffer.put(a).put(b)
        return "coco://c#" + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array())
    }
    companion object {
        fun parseForServer(input: String, selected: String): ConferenceInvite = parse(input).forServer(selected)
        fun fresh(server: String, roomId: String = UUID.randomUUID().toString()): ConferenceInvite =
            ConferenceInvite(origin(server), roomId, randomKey(), randomKey())
        fun secretHash(secret: String): String = MessageDigest.getInstance("SHA-256").digest(decodeKey(secret)).joinToString("") { "%02x".format(it) }
        fun parse(input: String): ConferenceInvite {
            require(input.length <= 2048)
            val uri = URI(input.trim())
            if (uri.scheme == "coco" && uri.host == "c") return parseCompact(uri)
            require(uri.scheme == "coco" && uri.host == "conference" && uri.path.orEmpty().isEmpty() && uri.port == -1 && uri.userInfo == null)
            val query = fields(requireNotNull(uri.rawQuery) { "Missing room" })
            val fragment = fields(requireNotNull(uri.rawFragment) { "Missing encryption key" })
            require(query.keys == setOf("server", "room") && fragment.keys == setOf("v", "i", "k") && fragment["v"] == "1")
            val room = query.getValue("room")
            require(UUID.fromString(room).toString() == room)
            val server = origin(query.getValue("server"))
            require(server == query.getValue("server"))
            decodeKey(fragment.getValue("i")); decodeKey(fragment.getValue("k"))
            require(fragment["i"] != fragment["k"])
            return ConferenceInvite(server, room, fragment.getValue("i"), fragment.getValue("k"))
        }
        private fun parseCompact(uri: URI): ConferenceInvite {
            require(uri.path.orEmpty().isEmpty() && uri.port == -1 && uri.userInfo == null && uri.rawQuery == null)
            val encoded = requireNotNull(uri.rawFragment)
            require(encoded.isNotEmpty() && encoded.matches(Regex("[A-Za-z0-9_-]+")))
            val bytes = Base64.getUrlDecoder().decode(encoded)
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
            val buffer = ByteBuffer.wrap(bytes)
            require(buffer.remaining() >= 2 && buffer.get().toInt() == 2)
            val server = when (buffer.get().toInt()) {
                0 -> {
                    require(buffer.remaining() == 112)
                    val known = origin(BuildConfig.PUBLIC_DEFAULT_API_ORIGIN)
                    val fingerprint = ByteArray(32).also(buffer::get)
                    require(MessageDigest.isEqual(fingerprint, MessageDigest.getInstance("SHA-256").digest(known.toByteArray(Charsets.US_ASCII))))
                    known
                }
                1 -> {
                    require(buffer.remaining() >= 2)
                    val count = buffer.short.toInt() and 0xffff
                    require(count in 1..256 && buffer.remaining() == count + 80)
                    val raw = ByteArray(count).also(buffer::get)
                    val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
                    require(value.all { it.code in 0x21..0x7e } && origin(value) == value)
                    value
                }
                else -> error("Unsupported invitation")
            }
            require(buffer.remaining() == 80)
            val id = UUID(buffer.long, buffer.long).toString()
            val a = ByteArray(32).also(buffer::get); val b = ByteArray(32).also(buffer::get)
            require(a.any { it != 0.toByte() } && b.any { it != 0.toByte() } && !a.contentEquals(b))
            return ConferenceInvite(server, id, Base64.getUrlEncoder().withoutPadding().encodeToString(a), Base64.getUrlEncoder().withoutPadding().encodeToString(b))
        }
        fun origin(raw: String): String {
            val uri = URI(raw)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in listOf("", "/") && (uri.port == -1 || uri.port in 1..65535))
            return URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port, null, null, null).toASCIIString()
        }
        private fun randomKey() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        private fun decodeKey(key: String): ByteArray {
            require(key.length == 43 && key.matches(Regex("[A-Za-z0-9_-]{43}")))
            val bytes = Base64.getUrlDecoder().decode(key)
            require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == key)
            return bytes
        }
        private fun fields(raw: String): Map<String, String> {
            val pairs = raw.split('&').map { part ->
                val pieces = part.split('=', limit = 2); require(pieces.size == 2)
                URLDecoder.decode(pieces[0], "UTF-8") to URLDecoder.decode(pieces[1], "UTF-8")
            }
            require(pairs.map { it.first }.distinct().size == pairs.size)
            return pairs.toMap()
        }
    }
}

object ConferenceLayoutPolicy {
    fun pageSize(landscape: Boolean, fontScale: Float = 1f) = if (fontScale >= 1.5f) {
        if (landscape) 3 else 2
    } else if (landscape) 9 else 6
    fun tileHeightDp(availableHeightDp: Float, visibleCount: Int, landscape: Boolean): Float {
        val columns = if (landscape) 3 else 2
        val rows = maxOf(1, (visibleCount + columns - 1) / columns)
        return ((availableHeightDp - 6f * (rows - 1)) / rows).coerceAtLeast(1f)
    }
    fun visible(ids: List<String>, page: Int, landscape: Boolean): List<String> = ids.drop(page.coerceAtLeast(0) * pageSize(landscape)).take(pageSize(landscape))
}
