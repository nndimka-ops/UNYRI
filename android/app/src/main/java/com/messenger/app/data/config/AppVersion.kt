package com.messenger.app.data.config

import java.net.URI
import java.util.Locale

/** Mirrors iOS AppVersion and server client_config.rs. Invalid input never gates. */
object AppVersion {
    private val pattern = Regex("[0-9]{1,6}(\\.[0-9]{1,6}){0,3}([-+][0-9A-Za-z.\\-]{1,32})?")

    fun components(value: String): List<Int>? {
        if (value.toByteArray(Charsets.UTF_8).size > 64 || !pattern.matches(value)) return null
        val core = value.substringBefore('-').substringBefore('+').split('.').map(String::toInt)
        return core + List(4 - core.size) { 0 }
    }

    fun compare(left: String, right: String): Int? {
        val a = components(left) ?: return null
        val b = components(right) ?: return null
        return a.zip(b).firstOrNull { (l, r) -> l != r }?.let { (l, r) -> l.compareTo(r) } ?: 0
    }

    /** A server cannot direct the update button to a look-alike or arbitrary download. */
    fun trustedUpdateUrl(raw: String?, applicationId: String = com.messenger.app.BuildConfig.APPLICATION_ID,
                         publisherUrl: String = com.messenger.app.BuildConfig.PUBLISHER_UPDATE_URL): String? =
        ownStoreUrl(raw, applicationId) ?: ownStoreUrl(publisherUrl, applicationId)

    private fun ownStoreUrl(raw: String?, applicationId: String): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 512 } ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return null
        val play = uri.scheme.equals("https", true) && uri.host.equals("play.google.com", true) && uri.rawPath == "/store/apps/details"
        val market = uri.scheme.equals("market", true) && uri.host.equals("details", true) && uri.rawPath.orEmpty().isEmpty()
        if (!play && !market) return null
        val fields = uri.rawQuery?.split('&') ?: return null
        if (fields.isEmpty() || fields.size > (if (play) 2 else 1)) return null
        val params = fields.map { it.split('=', limit = 2) }
        if (params.any { it.size != 2 } || params.map { it[0] }.distinct().size != params.size) return null
        if (params.any { (key, v) -> when (key) {
                "id" -> v != applicationId
                "hl" -> !play || !Regex("[A-Za-z0-9_-]{1,35}").matches(v)
                else -> true
            } }) return null
        return value.takeIf { params.any { it[0] == "id" } }
    }

    fun originKey(raw: String): String {
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        require(scheme in listOf("http", "https") && !host.isNullOrEmpty() && uri.rawUserInfo == null)
        return URI(scheme, null, host, uri.port, null, null, null).toASCIIString()
    }
}
