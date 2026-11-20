package com.messenger.app.data.attachment

import java.io.File
import java.net.URI
import kotlinx.serialization.json.*

/** Staged file URIs have no ContentProvider to answer metadata queries. */
internal fun localFileMetadata(scheme: String?, path: String?): Pair<String, Long>? =
    if (scheme == "file" && path != null) File(path).let { it.name to it.length() } else null

/** Startup reconciliation only considers directories allocated by the draft importer. */
internal fun ownedDraftCandidates(root: File): Set<String> = root.listFiles().orEmpty()
    .filter { it.isDirectory && runCatching { java.util.UUID.fromString(it.name) }.isSuccess }
    .flatMap { it.listFiles().orEmpty().filter(File::isFile) }.map { it.absolutePath }.toSet()

/** Lexical ownership check only: no provider or filesystem query during restoration. */
internal fun isOwnedDraftReference(root: File, raw: String): Boolean = runCatching {
    val uri = URI(raw)
    if (uri.scheme != "file") return false
    val file = File(uri).absoluteFile.toPath().normalize().toFile()
    file.parentFile?.parentFile == root.absoluteFile.toPath().normalize().toFile() &&
        runCatching { java.util.UUID.fromString(file.parentFile!!.name) }.isSuccess
}.getOrDefault(false)

/** Only these workspace entries own media; arbitrary draft text never owns files. */
internal fun mediaDraftPaths(name: String, value: String?): Set<String> {
    return mediaDraftPathsOrNull(name, value).orEmpty()
}

/** Null means ownership is uncertain: callers must skip deletion. */
internal fun mediaDraftPathsOrNull(name: String, value: String?): Set<String>? {
    if (value == null) return emptySet()
    fun path(raw: String): String? = runCatching {
        if (raw.startsWith("file:")) File(URI(raw)).absolutePath else raw.takeIf { File(it).isAbsolute }
    }.getOrNull()
    return runCatching {
        when {
            name == "fileDraftUri" -> listOfNotNull(path(value)).toSet()
            name == "mediaDrafts" || name == "fileDrafts" -> Json.parseToJsonElement(value).jsonArray.mapNotNull { path(it.jsonPrimitive.content) }.toSet()
            name == "cameraSendDraft" || name == "cameraCaptureIntent" -> Json.parseToJsonElement(value).jsonObject.let { draft -> listOfNotNull(draft.getValue("uri").jsonPrimitive.content.let(::path), draft["source"]?.jsonPrimitive?.content?.let(::path)).toSet() }
            name == "voiceDraft" -> listOfNotNull(Json.parseToJsonElement(value).jsonObject.getValue("path").jsonPrimitive.content.let(::path)).toSet()
            name.startsWith("upload:") -> listOfNotNull(Json.parseToJsonElement(value).jsonObject.getValue("uri").jsonPrimitive.content.let(::path)).toSet()
            else -> emptySet()
        }
    }.getOrNull()
}

/** Delete only owned, unreferenced regular files, never a supplied directory or symlink. */
internal fun discardUnreferencedDraft(root: File, candidate: File, references: Set<String>): Boolean {
    val canonicalRoot = root.canonicalFile
    val canonical = candidate.canonicalFile
    // B-073: the lexical /data/user/0 path never equals the canonical /data/data one, so every draft
    // was kept forever; compare the canonical path with the entry's physical location instead.
    if (com.messenger.app.data.auth.WorkspaceWipe.physicalEntry(candidate) != canonical.toPath() || !canonical.isFile ||
        canonical.parentFile?.parentFile != canonicalRoot ||
        references.any { File(it).canonicalFile == canonical }) return false
    if (!canonical.delete()) return false
    canonical.parentFile?.let { directory -> if (directory.listFiles()?.isEmpty() == true) directory.delete() }
    return true
}
