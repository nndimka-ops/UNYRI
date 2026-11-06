package com.messenger.app.conference

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

/** Signal content metadata. Its debug description intentionally contains no bearer material. */
@Serializable
data class ConferenceInvitation(
    val version: Int = 1, val id: String, val title: String, val server: String,
    @SerialName("room_id") val roomId: String, val invite: String,
    @SerialName("expires_at") val expiresAt: Long,
) {
    override fun toString() = "ConferenceInvitation([redacted])"
    fun validated(): ConferenceInvitation {
        require(version == 1 && UUID.fromString(id).toString() == id && UUID.fromString(roomId).toString() == roomId)
        require(validTitle(title))
        require(server == ConferenceInvite.origin(server) && server.toByteArray(Charsets.UTF_8).size in 1..256 && expiresAt in 1..253402300799L && invite.length <= 2048)
        val parsed = ConferenceInvite.parse(invite)
        require(parsed.server == server && parsed.roomId == roomId)
        return this
    }
    fun json(): JsonElement = codec.encodeToJsonElement(serializer(), validated())
    fun stored(): String = json().toString()
    companion object {
        const val FALLBACK = "Приглашение в конференцию"
        fun validTitle(value: String): Boolean = value == value.trim() && value.codePointCount(0, value.length) in 1..80 && value.toByteArray(Charsets.UTF_8).size <= 320 && value.codePoints().noneMatch { Character.getType(it) == Character.CONTROL.toInt() || it == 0x2028 || it == 0x2029 || it in 0xd800..0xdfff }
        fun limitTitle(value: String): String = if (value.codePointCount(0, value.length) <= 80) value else value.substring(0, value.offsetByCodePoints(0, 80))
        fun safePreview(text: String): String = if (text.trimStart().let { it.startsWith("coco://conference?") || it.startsWith("coco://c#") }) FALLBACK else text
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(value: JsonElement?): ConferenceInvitation? = value?.let { runCatching {
            val obj = it.jsonObject
            require(listOf("id", "title", "server", "room_id", "invite").all { key -> (obj[key] as? JsonPrimitive)?.isString == true })
            require((obj["version"] as? JsonPrimitive)?.let { p -> !p.isString && p.intOrNull == 1 } == true)
            require((obj["expires_at"] as? JsonPrimitive)?.let { p -> !p.isString && p.longOrNull?.let { number -> number in 1..253402300799L } == true } == true)
            codec.decodeFromJsonElement(serializer(), it).validated()
        }.getOrNull() }
        fun decodeStored(value: String?): ConferenceInvitation? = value?.let { runCatching { codec.parseToJsonElement(it) }.getOrNull() }?.let(::decode)
    }
}
