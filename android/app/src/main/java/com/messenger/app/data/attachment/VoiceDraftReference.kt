package com.messenger.app.data.attachment

import java.io.File
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Existing voiceDraft JSON format; only a matching persisted take can enter the outbox. */
internal data class VoiceDraftReference(val id: String, val path: String, val duration: Long) {
    fun matches(other: VoiceDraftReference): Boolean = matches(other.id, other.path) && duration == other.duration
    fun matches(id: String, path: String): Boolean = this.id == id && runCatching {
        File(this.path).canonicalFile == File(path).canonicalFile
    }.getOrDefault(false)

    companion object {
        fun decode(raw: String?): VoiceDraftReference? = runCatching {
            val value = Json.parseToJsonElement(requireNotNull(raw)).jsonObject
            VoiceDraftReference(value.getValue("id").jsonPrimitive.content,
                value.getValue("path").jsonPrimitive.content, value.getValue("duration").jsonPrimitive.long)
                .also { require(it.id.isNotBlank() && File(it.path).isAbsolute && it.duration > 0) }
        }.getOrNull()

        fun fromJob(id: String, raw: String): VoiceDraftReference {
            val value = Json.parseToJsonElement(raw).jsonObject
            require(value.getValue("kind").jsonPrimitive.content == "voice")
            val file = File(URI(value.getValue("uri").jsonPrimitive.content))
            return VoiceDraftReference(id, file.path, value.getValue("duration").jsonPrimitive.long)
        }
    }
}
