package com.messenger.app.data.attachment

/** Inspect bytes, never a MIME label or filename, before invoking a platform parser.
 * Android sends HEIF/AVIF sources as freshly encoded JPEG/PNG, not HEIF/AVIF. */
object PeerImageSignature {
    fun allowed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean {
        if (offset < 0 || length < 0 || offset > bytes.size - length) return false
        fun matches(vararg magic: Int): Boolean = length >= magic.size && magic.indices.all { (bytes[offset + it].toInt() and 255) == magic[it] }
        fun ascii(start: Int, value: String) = length >= start + value.length && value.indices.all { bytes[offset + start + it].toInt() == value[it].code }
        return matches(0xff, 0xd8, 0xff) || matches(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ||
            ascii(0, "GIF87a") || ascii(0, "GIF89a") || (ascii(0, "RIFF") && ascii(8, "WEBP"))
    }
}
