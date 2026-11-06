package com.messenger.app.data.api

import android.content.Context
import com.messenger.app.BuildConfig
import java.net.URI
import java.security.MessageDigest
import java.io.File

object ServerEndpoint {
    private const val legacyLan = "http://192.168.0.106:8080"
    private const val secureLan = "https://192.168.0.106:8443"
    @Volatile private var identityOrigin: String? = null
    @Volatile private var selected: String? = null
    @Volatile var changes = 0; private set
    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences("server-endpoint", Context.MODE_PRIVATE)
        val recorded = preferences.getString("origin", null)?.let { value ->
            // A present but invalid choice is not an absent default. Guessing
            // another host here could send an existing account's bearer to it.
            runCatching { normalize(value, BuildConfig.ALLOW_LOCAL_HTTP) }
                .getOrElse { error("Saved server origin is invalid; authentication bootstrap stopped") }
        }
        // This runs before SecureTokenStore/AuthRepository are constructed. An
        // old installation may have used the bundled LAN default without an
        // explicit preference: never send its existing tokens to a new host.
        val oldLocalState = File(context.dataDir, "shared_prefs/messenger_secure_prefs.xml").exists() ||
            File(context.dataDir, "shared_prefs/messenger_secure_prefs.xml.bak").exists() ||
            context.getDatabasePath("messenger.db").exists() ||
            context.getDatabasePath("messenger.db-wal").exists()
        val initial = initialOrigin(recorded, BuildConfig.API_BASE_URL, BuildConfig.PUBLIC_DEFAULT_API_ORIGIN, oldLocalState)
        if (recorded == null) {
            // Persist even a fresh default before auth can create local state.
            // Future starts must not misclassify a new cloud session as legacy.
            check(preferences.edit().putString("origin", initial).commit()) { "Could not bind the server origin" }
        }
        selected = initial
        identityOrigin = preferences.getString("tls_identity_origin", null)
        if (migrationTarget(current(), BuildConfig.API_BASE_URL, BuildConfig.LAN_TLS_MIGRATION) != null) {
            // This build trusts the bundled CA for this exact address. Preserve the existing identity.
            check(preferences.edit().putString("origin", secureLan)
                .putString("tls_identity_origin", legacyLan).commit())
            identityOrigin = legacyLan
            selected = secureLan
        }
    }
    fun current(): String = selected ?: BuildConfig.API_BASE_URL
    fun resolve(fallback: String): String = selected ?: fallback
    fun websocket(fallback: String): String = selected?.let { it.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws" } ?: fallback
    fun save(context: Context, origin: String) {
        (context.applicationContext as? com.messenger.app.MessengerApp)?.clearConferenceForSessionChange()
        check(context.getSharedPreferences("server-endpoint", Context.MODE_PRIVATE).edit().putString("origin", origin).commit())
        selected = origin; changes++
    }
    internal fun initialOrigin(recorded: String?, bundled: String, publicDefault: String, hasLocalState: Boolean): String =
        recorded ?: if (bundled == publicDefault && hasLocalState) secureLan else bundled
    internal fun migrationTarget(configured: String, bundled: String, enabled: Boolean): String? =
        if (enabled && configured == legacyLan && bundled == legacyLan) secureLan else null
    internal fun storageIdentity(transport: String, recorded: String?): String =
        if (transport == secureLan && recorded == legacyLan) legacyLan else transport
    fun storageOrigin(): String = storageIdentity(current(), identityOrigin)
    fun signalPrefix(): String = signalNamespacePrefix(storageOrigin(), BuildConfig.SIGNAL_NAMESPACE_ORIGIN)
    // A bundled transport default can change independently of an existing
    // account's legacy key namespace. No records or keys are moved here.
    internal fun signalNamespacePrefix(storageOrigin: String, namespaceOrigin: String): String =
        if (storageOrigin == namespaceOrigin) "" else
            MessageDigest.getInstance("SHA-256").digest(storageOrigin.toByteArray()).joinToString("") { "%02x".format(it) } + ":"
    fun normalize(input: String, allowLocalHttp: Boolean): String {
        val raw = input.trim()
        require(raw.isNotEmpty() && raw.none { it.isWhitespace() })
        val uri = URI(if (raw.contains("://")) raw else "https://$raw")
        val scheme = uri.scheme?.lowercase(); val host = uri.host?.lowercase() ?: error("host")
        require(scheme in listOf("http", "https") && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in listOf("", "/") && (uri.port == -1 || uri.port in 1..65535))
        if (scheme == "http") require(allowLocalHttp && isLocal(host))
        val port = if (uri.port == -1 || (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) -1 else uri.port
        return URI(scheme, null, host, port, null, null, null).toASCIIString()
    }
    private fun isLocal(host: String): Boolean {
        if (host == "localhost" || host == "[::1]" || host == "::1" || host.endsWith(".local")) return true
        val p = host.split('.').map { it.toIntOrNull() ?: return false }
        return p.size == 4 && p.all { it in 0..255 } && (p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31))
    }
}
