package com.messenger.app.domain.model

/** Bounded presentation only. Never persist these projections. Thread-safe across UI/IO callers. */
object DisplayNameSanitizer {
    const val maximumScalars = 64
    const val maximumInputUnits = 1024
    @Volatile var unknownUser: String = "Unknown user"
    private data class Input(val text: String, val clipped: Boolean)
    private data class Key(val raw: Input, val fallback: Input)
    private val lock = Any()
    private val cache = object : LinkedHashMap<Key, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>?) = size > 256
    }
    internal var computations: Long = 0; private set

    private fun bounded(value: String): Input {
        var end = minOf(value.length, maximumInputUnits)
        if (end < value.length && end > 0 && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
        return Input(if (end == value.length) value else value.substring(0, end), end < value.length)
    }

    fun display(raw: String, fallback: String = ""): String {
        val key = Key(bounded(raw), bounded(fallback))
        synchronized(lock) { cache[key]?.let { return it } }
        val first = clean(key.raw.text)
        val source = if (first.isEmpty()) key.fallback else key.raw
        val name = if (first.isEmpty()) clean(source.text) else first
        val result = if (DisplayNamePolicy.scalarCount(name) > maximumScalars || (source.clipped && name.isNotEmpty())) {
            val prefix = name.codePoints().limit((maximumScalars - 1).toLong()).toArray()
            clean(String(prefix, 0, prefix.size)) + "…"
        } else name
        synchronized(lock) { computations++; cache[key] = result }
        return result
    }

    fun peer(raw: String): String = display(raw, unknownUser.ifBlank { "Unknown user" })

    private val spaces = Regex(" +")
    private fun clean(raw: String): String {
        if (raw == unknownUser) return raw // Trusted localized resource; safe before background warm-up.
        // Avoid Unicode table loading for the overwhelmingly common ASCII case.
        if (raw.all { it in ' '..'~' }) return raw.trim().replace(spaces, " ")
        return DisplayNamePolicy.sanitize(raw)
    }
}
