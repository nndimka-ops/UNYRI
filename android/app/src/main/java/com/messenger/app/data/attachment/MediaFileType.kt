package com.messenger.app.data.attachment

/** A filename is a hint; known signatures take precedence. Does not execute/parse the file. */
internal object MediaFileType {
    fun detect(header: ByteArray, fallback: String): String {
        fun starts(vararg bytes: Int) = header.size >= bytes.size && bytes.indices.all { (header[it].toInt() and 255) == bytes[it] }
        val ascii = header.toString(Charsets.ISO_8859_1)
        return when {
            starts(255,216,255) -> "image/jpeg"
            starts(137,80,78,71,13,10,26,10) -> "image/png"
            ascii.startsWith("GIF87a") || ascii.startsWith("GIF89a") -> "image/gif"
            ascii.startsWith("%PDF-") -> "application/pdf"
            ascii.startsWith("RIFF") && ascii.substring(8.coerceAtMost(ascii.length)).startsWith("WEBP") -> "image/webp"
            ascii.length >= 12 && ascii.substring(4,8) == "ftyp" -> when (ascii.substring(8,12)) {
                "heic", "heix", "hevc", "hevx", "mif1" -> "image/heic"
                "avif", "avis" -> "image/avif"
                "M4A " -> "audio/mp4"
                "qt  " -> "video/quicktime"
                else -> "video/mp4"
            }
            else -> fallback
        }
    }
}
