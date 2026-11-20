package com.messenger.app.data.call

import com.messenger.app.data.api.CallDto
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun CallDto.withoutPrivatePeerLogin(): CallDto =
    if (peer?.username.isNullOrEmpty()) this else copy(peer = peer?.copy(username = ""))

/** One upgrade pass covers every server/account in the private call cache. */
internal object CallPeerPrivacy {
    private const val MARKER = ".b033-peer-logins-cleared"

    fun scrub(text: String): String {
        fun row(value: JsonElement): JsonElement {
            val objectValue = value.jsonObject
            val peer = objectValue["peer"] as? JsonObject ?: return value
            return JsonObject(objectValue + ("peer" to JsonObject(peer + ("username" to JsonPrimitive("")))))
        }
        return when (val root = Json.parseToJsonElement(text)) {
            is JsonArray -> JsonArray(root.map(::row))
            is JsonObject -> root["rows"]?.let { rows ->
                JsonObject(root + ("rows" to JsonArray(rows.jsonArray.map(::row))))
            } ?: root
            else -> error("Invalid call cache")
        }.toString()
    }

    @Synchronized
    fun migrate(root: File) {
        val marker = File(root, MARKER)
        if (marker.exists() || !root.isDirectory) return
        for (file in root.listFiles().orEmpty()) {
            if (!file.name.matches(Regex("[0-9a-f]{64}\\.json")) || !file.isFile || file.canonicalFile != file.absoluteFile) continue
            val sanitized = scrub(file.readText())
            val temp = File(file.path + ".b033-tmp")
            try {
                java.io.FileOutputStream(temp).use { output ->
                    output.write(sanitized.toByteArray(Charsets.UTF_8)); output.fd.sync()
                }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
        check(marker.createNewFile())
    }
}
