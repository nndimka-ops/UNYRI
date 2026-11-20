package com.messenger.app.ui.chats

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Base64
import java.util.Locale

internal interface ReactionHistoryStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
}

/** Persistence is deliberately separate from composer recents and encrypted reaction events. */
internal class ReactionPickerStore(
    private val storage: ReactionHistoryStorage,
    server: String,
    account: String,
) {
    constructor(context: Context, server: String, account: String) : this(preferences(context), server, account)
    private val key = storageKey(server, account)

    fun snapshot(): ReactionPickerHistory = synchronized(lock) {
        storage.read(key)?.let { runCatching { codec.decodeFromString<ReactionPickerHistory>(it) }.getOrNull() }
            ?: ReactionPickerHistory()
    }

    fun preferred(base: String): String = snapshot().preferred(base)

    fun recordSend(emoji: String, nowMillis: Long = System.currentTimeMillis()) = synchronized(lock) {
        storage.write(key, codec.encodeToString(snapshot().recordSend(emoji, nowMillis)))
    }

    fun setTone(base: String, variant: String) = synchronized(lock) {
        storage.write(key, codec.encodeToString(snapshot().setTone(base, variant)))
    }

    fun erase() = synchronized(lock) { storage.remove(key) }

    companion object {
        internal const val PREFS_NAME = "personal-reaction-history-v1"
        private val lock = Any()
        private val codec = Json { ignoreUnknownKeys = true }

        /** Length prefixes prevent different server/account pairs from sharing a key. */
        fun storageKey(server: String, account: String): String {
            val canonicalServer = runCatching {
                val uri = URI(server.trim().trimEnd('/'))
                val scheme = uri.scheme.lowercase(Locale.ROOT)
                val host = uri.host.lowercase(Locale.ROOT)
                val port = if (uri.port == -1 || uri.port == (if (scheme == "https") 443 else 80)) "" else ":${uri.port}"
                "$scheme://$host$port${uri.rawPath.orEmpty().trimEnd('/')}"
            }.getOrElse { server.trim().trimEnd('/') }
            val namespace = "${canonicalServer.length}:$canonicalServer${account.length}:$account"
            return "account." + Base64.getUrlEncoder().withoutPadding().encodeToString(namespace.toByteArray(Charsets.UTF_8))
        }

        fun erase(context: Context, server: String, account: String) = ReactionPickerStore(context, server, account).erase()

        /** The supplied Context is already scoped to the erased account workspace. */
        fun eraseWorkspace(context: Context) = synchronized(lock) {
            check(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()) { "Could not erase reaction history" }
        }

        private fun preferences(context: Context): ReactionHistoryStorage {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return object : ReactionHistoryStorage {
                override fun read(key: String): String? = prefs.getString(key, null)
                override fun write(key: String, value: String) { check(prefs.edit().putString(key, value).commit()) { "Could not save reaction history" } }
                override fun remove(key: String) { check(prefs.edit().remove(key).commit()) { "Could not erase reaction history" } }
            }
        }
    }
}
