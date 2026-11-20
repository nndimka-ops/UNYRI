package org.unyri.admin.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant

val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

enum class Policy(val wire: String) {
    AUTHENTICATOR("authenticator"), PAPER("paper_key"), BOTH("both");
    val paper get() = this != AUTHENTICATOR
    val totp get() = this != PAPER
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } ?: throw ProtocolFailure() }
}

enum class FailureKind { NETWORK, AUTH, FORBIDDEN, MISSING, CONFLICT, RATE_LIMIT, PROTOCOL, STORAGE, INVALID, SERVER }
open class AdminFailure(val kind: FailureKind, val status: Int = 0) : RuntimeException(kind.name)
class ProtocolFailure : AdminFailure(FailureKind.PROTOCOL)

@Serializable data class AdminStatus(
    val configured: Boolean, val key_protocol: Int, val server_id: String,
    val auth_protocol: Int = 1, val auth_policy: String = "both", val auth_revision: Long = 0,
    val totp_configured: Boolean = false, val paper_key_configured: Boolean = false,
    val signing_public_key: String? = null
) {
    fun policy(): Policy {
        if (key_protocol != 1 || auth_protocol !in 1..2) throw ProtocolFailure()
        return if (auth_protocol == 1) Policy.BOTH else Policy.parse(auth_policy)
    }
}
@Serializable data class Challenge(val challenge_id: String, val server_id: String, val user_id: String,
    val auth_session: String, val purpose: String, val public_key: String, val nonce: String)
@Serializable data class KeyProof(val challenge_id: String, val signature: String)
@Serializable data class Setup(val auth_policy: String = "both", val secret: String? = null, val qr_png: String? = null) {
    override fun toString() = "Setup(redacted)"
}
@Serializable data class Unlock(val token: String, val recovery_codes: List<String> = emptyList(), val auth_policy: String = "both") {
    override fun toString() = "Unlock(redacted)"
}
@Serializable data class LoginUser(val id: String, val username: String)
@Serializable data class LoginResponse(val access_token: String, val access_token_expires_at: String,
    val device_id: String, val user: LoginUser) { override fun toString() = "LoginResponse(redacted)" }
@Serializable data class Me(val id: String, val admin_role: String? = null)
@Serializable data class Session(val origin: String, val access: String, val admin: String = "", val userId: String,
    val username: String, val deviceId: String, val role: String, val serverId: String = "",
    val accessExpires: String, val unlockedAt: String = "", val optedIn: Boolean = false) {
    fun valid(now: Instant = Instant.now()): Boolean = runCatching {
        role in setOf("owner", "moderator") && access.isNotBlank() && admin.isNotBlank() &&
            now.plusSeconds(30).isBefore(Instant.parse(accessExpires)) &&
            !Instant.parse(unlockedAt).isAfter(now.plusSeconds(30)) &&
            now.isBefore(Instant.parse(unlockedAt).plusSeconds(12 * 3600))
    }.getOrDefault(false)
    override fun toString() = "Session(redacted)"
}
@Serializable data class Page<T>(val items: List<T>, val next_cursor: String? = null, val has_more: Boolean = false) {
    fun checked(): Page<T> {
        if (items.size > 100 || has_more && (items.isEmpty() || next_cursor.isNullOrBlank()) ||
            !has_more && next_cursor != null || (next_cursor?.length ?: 0) > 4096) throw ProtocolFailure()
        return this
    }
}
@Serializable data class UserRow(val user_id: String, val reason: String, val description: String? = null,
    val status: String, val installation_blocks: Int = 0, val created_at: String? = null)
@Serializable data class QueueRow(val id: String, val user_id: String? = null, val description: String? = null,
    val status: String, val reason: String? = null, val reported_username: String? = null,
    val reporter_username: String? = null, val platform: String? = null, val app_version: String? = null,
    val created_at: String? = null, val review_note: String? = null, val decision_reason: String? = null)
@Serializable data class Profile(val user_id: String, val username: String, val display_name: String? = null,
    val bio: String? = null, val has_avatar: Boolean = false, val created_at: String? = null)
@Serializable data class Moderation(val status: String, val reason: String? = null, val read_only: Boolean = false,
    val reports_open: Long = 0, val reports_total: Long = 0)
@Serializable data class Device(val device_id: String, val device_name: String? = null,
    val platform: String? = null, val app_version: String? = null, val os_version: String? = null,
    val installation_id: String? = null, val last_seen: String? = null, val revoked_at: String? = null)
@Serializable data class UserDetail(val profile: Profile, val moderation: Moderation,
    val devices: List<JsonObject> = emptyList(), val security: JsonObject = JsonObject(emptyMap()),
    val is_administrator: Boolean = false, val suspend_allowed: Boolean = false, val moderation_allowed: Boolean = false)
@Serializable data class Health(val status: String, val database: Boolean, val redis: Boolean)
@Serializable data class Summary(val open_reports: Long, val in_progress_reports: Long,
    val unresolved_feedback: Long, val active_account_suspensions: Long,
    val active_installation_suspensions: Long, val reports_last_24h: Long, val storage: Storage? = null)
@Serializable data class Storage(val used_bytes: Long, val reserved_bytes: Long, val budget_bytes: Long)
@Serializable data class ServerPolicy(val name: String, val operator: String = "", val contact: String = "",
    val rules: String = "", val retention: String = "", val max_file_bytes: Long = 0,
    val user_quota_bytes: Long = 0, val storage_budget_bytes: Long = 0)
@Serializable data class Screenshot(val index: Int, val mime_type: String, val byte_size: Long)
@Serializable data class FeedbackDetail(val id: String, val description: String? = null,
    val screenshots: List<Screenshot> = emptyList(), val performance: JsonObject? = null)
@Serializable data class Performance(val items: List<JsonObject>, val retention_hours: Int)

/** Bounded page history. Cursors remain opaque and are never synthesized from a sort column. */
class Pager<T> {
    var page: Page<T> = Page(emptyList()); private set
    var cursor: String? = null; private set
    private val previous = ArrayDeque<String?>()
    private val visited = mutableSetOf<String>()
    val canBack get() = previous.isNotEmpty()
    fun accept(value: Page<T>) {
        value.checked()
        if (value.has_more && (value.next_cursor == cursor || value.next_cursor in visited)) throw ProtocolFailure()
        page = value
    }
    fun next() {
        val next = page.next_cursor ?: throw ProtocolFailure()
        if (previous.size >= 1000) throw ProtocolFailure()
        previous.addLast(cursor); cursor?.let(visited::add); cursor = next
    }
    fun back() { cursor?.let(visited::remove); cursor = previous.removeLast(); cursor?.let(visited::remove) }
    fun reset() { page = Page(emptyList()); cursor = null; previous.clear(); visited.clear() }
}

class SessionEpoch {
    var value: Long = 0; private set
    fun clear() { value++ }
    fun accepts(expected: Long) = expected == value
}
