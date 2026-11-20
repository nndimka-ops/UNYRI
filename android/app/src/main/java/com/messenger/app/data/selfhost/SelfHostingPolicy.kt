package com.messenger.app.data.selfhost

import com.messenger.app.data.api.ServerEndpoint
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Official, fixed destinations; server configuration never supplies a guide URL. */
object SelfHostingGuidePolicy {
    private const val guideBaseUrl = "https://github.com/nndimka-ops/UNYRI/blob/main/docs/"

    fun guideUrl(language: String): String {
        val filename = when (language.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')) {
            "ru" -> "SELF_HOSTING.md"
            "uk" -> "SELF_HOSTING_UK.md"
            else -> "SELF_HOSTING_EN.md"
        }
        return guideBaseUrl + filename
    }
}

@Serializable
data class RegistrationPolicy(
    @SerialName("invite_required") val inviteRequired: Boolean = false,
    @SerialName("registration_mode") val mode: String = "open",
    @SerialName("registration_open") val open: Boolean = true,
)

/** Memory only, bounded by origin, with no idle work. Failed refreshes never replace a policy. */
class RegistrationPolicyCache(private val now: () -> Long = System::nanoTime,
    private val ttl: Long = 60_000_000_000L) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Pair<Long, RegistrationPolicy>>()
    suspend fun get(origin: String, force: Boolean = false,
        load: suspend (String) -> RegistrationPolicy): RegistrationPolicy = mutex.withLock {
        val key = ServerEndpoint.normalize(origin, true)
        val cached = entries[key]
        if (!force && cached != null && now() - cached.first in 0 until ttl) return@withLock cached.second
        val value = load(key)
        if (entries.size >= 16) entries.remove(entries.keys.first())
        entries[key] = now() to value
        value
    }
}

/** Never use a data class here: its generated toString would disclose the credential. */
class ServerInvite(val origin: String, val code: String) {
    override fun toString() = "ServerInvite(redacted)"
}

object ServerInviteLink {
    const val maxCode = 256
    fun parse(raw: String?, allowLocalHttp: Boolean = false): ServerInvite? = runCatching {
        require(raw != null && raw.length <= 4096 && raw.none { it.isWhitespace() || it.isISOControl() })
        val uri = URI(raw)
        require(uri.isAbsolute && uri.rawQuery == null && uri.rawUserInfo == null)
        val carrier = ServerEndpoint.normalize(URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString(), allowLocalHttp)
        require(uri.path.orEmpty() in listOf("", "/"))
        val pairs = requireNotNull(uri.rawFragment).split('&').map {
            val parts = it.split('=', limit = 2)
            require(parts.size == 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }
        require(pairs.map { it.first }.toSet() == setOf("server", "invite_code") && pairs.size == 2)
        val values = pairs.toMap()
        val source = values.getValue("server")
        require(source.length <= 2048 && source.contains("://"))
        val origin = ServerEndpoint.normalize(source, allowLocalHttp)
        require(carrier == origin)
        val code = values.getValue("invite_code")
        require(code.length in 1..maxCode && code.all { it.code in 33..126 } && code.none { it in "&#?/%" })
        ServerInvite(origin, code)
    }.getOrNull()

    fun create(origin: String, code: String): String {
        val base = ServerEndpoint.normalize(origin, false)
        val link = "$base/#server=${URLEncoder.encode(base, "UTF-8")}&invite_code=${URLEncoder.encode(code, "UTF-8")}"
        require(parse(link) != null)
        return link
    }
}

enum class ClaimState { Checking, Unavailable, Available, Submitting, Invalid, RateLimited, Failed, RoleRefreshFailed, Success }
object OwnerClaimPolicy {
    fun normalize(raw: String): String? {
        if (raw.length > 64 || raw.any { it.code > 127 }) return null
        val code = raw.filterNot { it == '-' || it in " \t\r\n" }.uppercase(Locale.ROOT)
        return code.takeIf { it.length == 16 && it.all { c -> c in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" } }
    }
    fun availability(available: Boolean) = if (available) ClaimState.Available else ClaimState.Unavailable
    fun refusal(code: String?) = when (code) {
        "setup_invalid" -> ClaimState.Invalid
        "rate_limited" -> ClaimState.RateLimited
        "setup_not_available" -> ClaimState.Unavailable
        else -> ClaimState.Failed
    }
    fun canSubmit(state: ClaimState) = state in setOf(ClaimState.Available, ClaimState.Invalid, ClaimState.Failed)
    fun entryVisible(state: ClaimState) = canSubmit(state) || state in setOf(ClaimState.RateLimited, ClaimState.RoleRefreshFailed)
}
