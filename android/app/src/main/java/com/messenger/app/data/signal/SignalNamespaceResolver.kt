package com.messenger.app.data.signal

import java.security.MessageDigest

/** Pins the existing account store once. Transport/build defaults never move it again.
 * No records are copied, deleted or combined: ratchet and identity stay together.
 */
class SignalNamespaceResolver(private val store: SecureKeyValueStore) {
    @Synchronized
    fun resolve(origin: String, owner: String, legacyPrefix: String): String {
        require(origin.isNotBlank() && owner.isNotBlank())
        val canonical = prefix(origin) + owner
        val pin = "namespace_binding:$canonical"
        val allowed = setOf(canonical, owner)
        store.getBytes(pin)?.let {
            val saved = it.toString(Charsets.UTF_8)
            check(saved in allowed) { "Invalid Signal namespace binding" }
            return saved
        }
        val legacy = legacyPrefix + owner
        val knownLegacyServer = origin in setOf("http://192.168.0.106:8080", "https://192.168.0.106:8443")
        // Bare stores were created for a bundled server. Only adopt one for
        // the restored server context, never just because another account exists.
        val selected = when {
            (legacy == canonical || (legacy == owner && knownLegacyServer)) && store.contains("$legacy:identity_key_pair") -> legacy
            store.contains("$canonical:identity_key_pair") -> canonical
            knownLegacyServer && store.contains("$owner:identity_key_pair") -> owner
            else -> canonical
        }
        store.putNamespaceBinding(pin, selected.toByteArray(Charsets.UTF_8))
        check(store.getBytes(pin)?.toString(Charsets.UTF_8) == selected) { "Could not persist Signal namespace" }
        return selected
    }

    companion object {
        fun prefix(origin: String): String = MessageDigest.getInstance("SHA-256")
            .digest(origin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ":"
    }
}
