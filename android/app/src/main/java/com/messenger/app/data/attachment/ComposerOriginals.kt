package com.messenger.app.data.attachment

import com.messenger.app.data.directtransfer.DIRECT_NORMAL_SOURCE_LIMIT
import com.messenger.app.data.directtransfer.DirectFileWire
import java.security.MessageDigest

/** Metadata only at Add. The admitted source is copied by the direct journal at Send. */
data class ComposerOriginals(val selected: Set<String>, val unavailable: Set<String> = emptySet(), val metadata: Map<String, ComposerSourceInfo> = emptyMap()) {
    companion object {
        const val MAX_DIRECT = 8
        fun needsDirect(bytes: Long): Boolean {
            if (bytes > DirectFileWire.MAX_FILE_BYTES) throw AttachmentTooLargeException("Original exceeds 64 GiB")
            return bytes <= 0 || bytes > DIRECT_NORMAL_SOURCE_LIMIT
        }
    }
}

/** Retry identity belongs to exactly one frozen selection/caption/quality, never to the tray. */
object ComposerBatchIdentity {
    fun key(uris: List<String>, caption: String?, quality: String): String {
        val input = ComposerDrafts.encode(uris + listOf(caption.orEmpty(), quality))
        return "composerAlbum:" + MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

data class ComposerCaption(val text: String, val revision: String = java.util.UUID.randomUUID().toString())
