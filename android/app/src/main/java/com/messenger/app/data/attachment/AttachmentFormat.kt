package com.messenger.app.data.attachment

/** Pure attachment presentation/limit helpers (Phase 7 §5/§18/§19/§52) — no
 *  Android framework types, directly unit-testable, mirroring iOS's
 *  `AttachmentFormat`. Limits mirror the server/encryption ceilings so the UI
 *  can pre-reject oversized media before any expensive work. */
object AttachmentFormat {

    /** Shared product selection cap (Phase 7 §5). */
    const val MAX_ITEMS = 10

    /** Encrypted-size ceilings (mirror `AttachmentManager` + iOS 25 MB). */
    const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    const val MAX_FILE_BYTES = 100L * 1024 * 1024
    const val MAX_VOICE_BYTES = 25L * 1024 * 1024

    /** Human-readable file size: B / KB / MB / GB, never raw bytes in UI. */
    fun fileSize(bytes: Long): String {
        if (bytes < 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${format(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${format(mb)} MB"
        return "${format(mb / 1024.0)} GB"
    }

    /** One decimal place when it adds information, none otherwise. Uses a
     *  fixed locale so the string is identical on every device. */
    private fun format(value: Double): String {
        val rounded = Math.round(value * 10) / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else String.format(java.util.Locale.ROOT, "%.1f", rounded)
    }

    fun isImageWithinLimit(bytes: Long): Boolean = bytes <= MAX_IMAGE_BYTES
    fun isFileWithinLimit(bytes: Long): Boolean = bytes <= MAX_FILE_BYTES
}
