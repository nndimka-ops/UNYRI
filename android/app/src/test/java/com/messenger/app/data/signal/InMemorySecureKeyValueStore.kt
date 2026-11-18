package com.messenger.app.data.signal

/** Simple in-memory fake for [SecureKeyValueStore] - used only in JVM unit
 *  tests to exercise the real libsignal crypto + our real [SignalKeyStore]
 *  logic without needing Android Keystore/an emulator. Two instances
 *  sharing the same backing map simulate "the same device across an app
 *  restart" (see identity persistence tests); two independent instances
 *  simulate two distinct devices. */
class InMemorySecureKeyValueStore(
    private val backing: MutableMap<String, Any> = mutableMapOf(),
) : SecureKeyValueStore {
    override fun commitReceive(bytes: Map<String, ByteArray?>, ints: Map<String, Int>) {
        val updated = backing.toMutableMap()
        bytes.forEach { (key, value) -> if (value == null) updated.remove(key) else updated[key] = value.copyOf() }
        updated.putAll(ints); backing.clear(); backing.putAll(updated)
    }

    override fun getBytes(key: String): ByteArray? = backing[key] as? ByteArray

    override fun putBytes(key: String, value: ByteArray) {
        backing[key] = value
    }

    override fun getInt(key: String): Int? = backing[key] as? Int

    override fun putInt(key: String, value: Int) {
        backing[key] = value
    }

    override fun remove(key: String) {
        backing.remove(key)
    }

    override fun contains(key: String): Boolean = backing.containsKey(key)

    override fun keysWithPrefix(prefix: String): Set<String> = backing.keys.filter { it.startsWith(prefix) }.toSet()
}
