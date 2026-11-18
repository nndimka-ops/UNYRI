package com.messenger.app.ui.chats

import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

/** Persisted before leaving the app: process-local epoch numbers are not an identity. */
@Serializable
internal data class CameraLaunchIntent(
    val nonce: String,
    val owner: String,
    val server: String,
    val device: String,
    val chat: String,
    val uri: String,
    val source: String,
    val confirmed: Boolean = false,
) {
    fun belongsTo(owner: String?, server: String, device: String?, chat: String): Boolean =
        owner != null && device != null && this.owner == owner && this.server == server && this.device == device && this.chat == chat

    /** Canonical equivalence keeps Android's private-data aliases valid, even after Cancel deleted the file. */
    fun matchesSource(file: File): Boolean {
        if (source.isBlank()) return false
        return try {
            val registered = File(source)
            registered.isAbsolute && file.isAbsolute && registered.canonicalFile == file.canonicalFile
        } catch (_: IOException) { false }
          catch (_: SecurityException) { false }
    }

}
