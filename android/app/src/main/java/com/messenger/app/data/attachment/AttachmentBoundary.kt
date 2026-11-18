package com.messenger.app.data.attachment

import java.io.File
import java.nio.file.Files

class AttachmentBoundaryException : IllegalArgumentException("Invalid attachment boundary")

/** Validate before constructing a path, including locally retained server IDs. */
object AttachmentBoundary {
    fun requireId(id: String): String {
        if (id.length !in 1..128 || !id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) throw AttachmentBoundaryException()
        return id
    }
    fun contained(root: File, file: File): Boolean = runCatching {
        val absolute = file.absoluteFile.toPath()
        if (absolute.any { it.toString() == ".." || it.toString() == "." }) return false
        var current: java.nio.file.Path? = absolute
        while (current != null) {
            if (Files.isSymbolicLink(current)) {
                val destination = Files.readSymbolicLink(current)
                val resolved = (if (destination.isAbsolute) destination else current.parent.resolve(destination)).normalize().toString().replace('\\', '/')
                if (!androidSystemAlias(current.toString().replace('\\', '/'), resolved)) return false
            }
            current = current.parent
        }
        val base = root.canonicalFile.toPath()
        val target = file.canonicalFile.toPath()
        target != base && target.startsWith(base)
    }.getOrDefault(false)
    internal fun androidSystemAlias(path: String, target: String): Boolean =
        (path == "/data/data" && target == "/data/user/0") || (path == "/data/user/0" && target == "/data/data")
    fun child(root: File, name: String): File = File(root, name).also { if (!contained(root, it)) throw AttachmentBoundaryException() }
}
