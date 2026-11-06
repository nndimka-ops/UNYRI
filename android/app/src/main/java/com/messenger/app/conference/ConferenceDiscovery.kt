package com.messenger.app.conference

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Unknown modes never gain the privileges of an open room. */
enum class ConferenceAccess(val wire: String, val password: Boolean, val invitation: Boolean) {
    OPEN("open", false, false), PASSWORD("password", true, false), INVITE("invite", false, true), INVITE_PASSWORD("invite_password", true, true);
    companion object { fun parse(value: String): ConferenceAccess? = entries.firstOrNull { it.wire == value } }
}
@Serializable data class DirectoryRoom(val id: String, val title: String, val description: String = "",
    @SerialName("owner_user_id") val ownerUserId: String, @SerialName("owner_display_name") val ownerDisplayName: String,
    @SerialName("participant_count") val participantCount: Int, @SerialName("max_participants") val maxParticipants: Int,
    @SerialName("access_mode") val accessMode: String, @SerialName("admission_locked") val admissionLocked: Boolean = false,
    @SerialName("key_available") val keyAvailable: Boolean = false, @SerialName("created_at") val createdAt: String, @SerialName("expires_at") val expiresAt: String)
@Serializable data class ConferenceDirectory(val items: List<DirectoryRoom> = emptyList(), @SerialName("next_cursor") val nextCursor: String? = null)
@Serializable class ConferenceKeyEnvelope(@SerialName("recipient_device_id") val recipientDeviceId: String,
    @SerialName("message_type") val messageType: Int, val ciphertext: String) { override fun toString() = "ConferenceKeyEnvelope([redacted])" }
@Serializable class ConferenceKeyRequest(val id: String, @SerialName("room_id") val roomId: String,
    @SerialName("requester_user_id") val requesterUserId: String, @SerialName("requester_device_id") val requesterDeviceId: String,
    @SerialName("provider_user_id") val providerUserId: String? = null, @SerialName("provider_device_id") val providerDeviceId: String? = null,
    @SerialName("provider_identity") val providerIdentity: String? = null, @SerialName("assignment_revision") val assignmentRevision: Long,
    val nonce: String, @SerialName("access_revision") val accessRevision: Long, @SerialName("expires_at") val expiresAt: String,
    val status: String, val envelope: ConferenceKeyEnvelope? = null) { override fun toString() = "ConferenceKeyRequest([redacted])" }
@Serializable data class ConferenceKeyRequests(val items: List<ConferenceKeyRequest> = emptyList())

/** Deliberately not Serializable/SavedState: passwords and media keys are RAM only. */
class ConferenceCreateOptions(val title: String, val maxParticipants: Int = 30, val description: String = "", val listed: Boolean = false,
    val access: ConferenceAccess = ConferenceAccess.INVITE, val password: String? = null, val approvalRequired: Boolean = false) {
    fun validate() { require(ConferenceInvitation.validTitle(title.trim()) && maxParticipants in 2..30); ConferenceDiscoveryPolicy.description(description); if (access.password) ConferenceDiscoveryPolicy.password(requireNotNull(password)) else require(password == null) }
    override fun toString() = "ConferenceCreateOptions([redacted])"
}
class ConferenceSettingsEdit(val title: String, val description: String, val listed: Boolean, val access: ConferenceAccess,
    val locked: Boolean, val expectedRevision: Long, val passwordAction: String = "keep", val password: String? = null, val approvalRequired: Boolean? = null) {
    fun validate() { require(ConferenceInvitation.validTitle(title.trim()) && expectedRevision > 0); ConferenceDiscoveryPolicy.description(description)
        require(passwordAction in setOf("keep", "set", "remove")); if (passwordAction == "set") { require(access.password); ConferenceDiscoveryPolicy.password(requireNotNull(password)) } else require(password == null)
        require(!access.password || passwordAction != "remove") }
    override fun toString() = "ConferenceSettingsEdit([redacted])"
}
sealed class ConferenceAdmission(val server: String, val roomId: String, val mediaKey: String) {
    class Invitation(val value: ConferenceInvite, var password: String? = null) : ConferenceAdmission(value.server, value.roomId, value.mediaKey)
    class Catalog(server: String, roomId: String, val requestId: String, mediaKey: String) : ConferenceAdmission(server, roomId, mediaKey)
    final override fun toString() = "ConferenceAdmission([redacted])"
}
object ConferenceDiscoveryPolicy {
    fun uuid(value: String): String { require(UUID.fromString(value).toString() == value); return value }
    fun key(value: String): String { require(value.matches(Regex("[A-Za-z0-9_-]{43}"))); val bytes = Base64.getUrlDecoder().decode(value)
        require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value); return value }
    private fun scalars(value: String): Boolean { var i = 0; while (i < value.length) { val c = value[i++]; if (Character.isHighSurrogate(c)) { if (i >= value.length || !Character.isLowSurrogate(value[i++])) return false } else if (Character.isLowSurrogate(c)) return false }; return true }
    fun description(value: String) { require(scalars(value) && value.codePointCount(0, value.length) <= 240 && value.toByteArray(Charsets.UTF_8).size <= 960 && value.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }) }
    fun password(value: String) { require(scalars(value) && value.codePointCount(0, value.length) in 6..128 && value.toByteArray(Charsets.UTF_8).size <= 512 && value.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }) }
    fun envelope(value: ConferenceKeyEnvelope, target: String) { require(value.recipientDeviceId == uuid(target) && value.messageType in 1..2)
        val bytes = Base64.getDecoder().decode(value.ciphertext); require(bytes.isNotEmpty() && bytes.size <= 8192 && Base64.getEncoder().encodeToString(bytes) == value.ciphertext) }
}

/** Bindings originate from authenticated REST metadata and the selected local account, never from plaintext. */
class ConferenceKeyBinding(val origin: String, val request: ConferenceKeyRequest, val now: Long) {
    fun validate() {
        require(ConferenceInvite.origin(origin) == origin)
        with(request) {
            listOf(id, roomId, requesterUserId, requesterDeviceId, requireNotNull(providerUserId), requireNotNull(providerDeviceId)).forEach(ConferenceDiscoveryPolicy::uuid)
            require(assignmentRevision > 0 && accessRevision > 0); ConferenceDiscoveryPolicy.key(nonce)
            val parts = requireNotNull(providerIdentity).split(':'); require(parts.size == 3 && parts[0] == providerUserId && parts[1] == providerDeviceId); parts.forEach(ConferenceDiscoveryPolicy::uuid)
            require(Instant.parse(expiresAt).epochSecond in (now + 1)..(now + 90))
        }
    }
    override fun toString() = "ConferenceKeyBinding([redacted])"
}
object ConferenceKeyPayload {
    private val json = Json { isLenient = false; ignoreUnknownKeys = false }
    private val fields = setOf("version", "origin", "room_id", "request_id", "requester_user_id", "requester_device_id", "provider_user_id", "provider_device_id", "provider_identity", "assignment_revision", "nonce", "access_revision", "expires_at", "media_key")
    fun encode(binding: ConferenceKeyBinding, mediaKey: String): ByteArray {
        binding.validate(); ConferenceDiscoveryPolicy.key(mediaKey)
        val r = binding.request
        return buildJsonObject {
            put("version", 1); put("origin", binding.origin); put("room_id", r.roomId); put("request_id", r.id)
            put("requester_user_id", r.requesterUserId); put("requester_device_id", r.requesterDeviceId)
            put("provider_user_id", r.providerUserId!!); put("provider_device_id", r.providerDeviceId!!); put("provider_identity", r.providerIdentity!!)
            put("assignment_revision", r.assignmentRevision); put("nonce", r.nonce); put("access_revision", r.accessRevision)
            put("expires_at", Instant.parse(r.expiresAt).epochSecond); put("media_key", mediaKey)
        }.toString().toByteArray(Charsets.UTF_8)
    }
    fun decode(raw: String, binding: ConferenceKeyBinding): String {
        binding.validate(); require(raw.toByteArray(Charsets.UTF_8).size <= 4096)
        val value = strictFlatObject(raw); require(value.keys == fields)
        fun text(key: String): String = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid binding")
        fun number(key: String): Long { val p = value[key] as? JsonPrimitive ?: error("Invalid integer"); require(!p.isString && p.content.matches(Regex("0|[1-9][0-9]*"))); return p.content.toLong() }
        val r = binding.request
        require(number("version") == 1L && text("origin") == binding.origin && text("room_id") == r.roomId && text("request_id") == r.id)
        require(text("requester_user_id") == r.requesterUserId && text("requester_device_id") == r.requesterDeviceId)
        require(text("provider_user_id") == r.providerUserId && text("provider_device_id") == r.providerDeviceId && text("provider_identity") == r.providerIdentity)
        require(number("assignment_revision") == r.assignmentRevision && number("access_revision") == r.accessRevision && text("nonce") == r.nonce)
        require(number("expires_at") == Instant.parse(r.expiresAt).epochSecond)
        return ConferenceDiscoveryPolicy.key(text("media_key"))
    }
    /** JsonObject alone silently overwrites duplicates. Scan this flat protocol before decoding it. */
    private fun strictFlatObject(raw: String): JsonObject {
        var i = 0; fun space() { while (i < raw.length && raw[i].isWhitespace()) i++ }
        fun quoted(): String { require(i < raw.length && raw[i] == '"'); val start = i++; var escaped = false
            while (i < raw.length) { val c = raw[i++]; if (!escaped && c == '"') return raw.substring(start, i); if (!escaped && c == '\\') escaped = true else escaped = false }; error("Invalid JSON") }
        val seen = hashSetOf<String>(); space(); require(i < raw.length && raw[i++] == '{'); space()
        if (i < raw.length && raw[i] == '}') i++ else while (true) {
            space(); val key = json.decodeFromString<String>(quoted()); require(seen.add(key)); space(); require(i < raw.length && raw[i++] == ':'); space()
            if (i < raw.length && raw[i] == '"') quoted() else { val start = i; while (i < raw.length && raw[i] != ',' && raw[i] != '}') { require(raw[i] != '{' && raw[i] != '[' && raw[i] != '"'); i++ }; require(i > start) }
            space(); require(i < raw.length); val end = raw[i++]; if (end == '}') break; require(end == ',')
        }
        space(); require(i == raw.length)
        return json.parseToJsonElement(raw).jsonObject
    }
}
