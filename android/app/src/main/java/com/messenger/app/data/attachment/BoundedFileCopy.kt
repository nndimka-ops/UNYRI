package com.messenger.app.data.attachment

import java.io.InputStream
import java.io.OutputStream

internal fun copyBoundedFile(input: InputStream, output: OutputStream, maxBytes: Long, checkActive: () -> Unit = {}) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count < 0) return
        if (count == 0) continue
        total += count
        if (total > maxBytes) throw AttachmentTooLargeException("File exceeds the allowed size.")
        output.write(buffer, 0, count)
    }
}

/** An optional caller cap may only tighten the ordinary image/video source limit. */
internal fun boundedDraftSourceLimit(ordinaryLimit: Long, requestedLimit: Long?): Long {
    require(ordinaryLimit > 0 && (requestedLimit == null || requestedLimit > 0)) { "Source limit must be positive" }
    return requestedLimit?.let { minOf(ordinaryLimit, it) } ?: ordinaryLimit
}
