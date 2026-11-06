package com.messenger.app.data.local

import java.io.File
import java.io.IOException

/** Call under the account write gate, after invalidating the session epoch. */
object PrivateCachePurger {
    fun erase(cacheRoot: File, callHistoryRoot: File, vararg draftRoots: File) {
        ChatImageCache.clear()
        val targets = draftRoots.toList() + listOf(File(cacheRoot, "attachments"), File(cacheRoot, "camera"), File(cacheRoot, "avatar_camera"), callHistoryRoot) +
            cacheRoot.listFiles().orEmpty().filter { it.name.startsWith("voice_") && it.extension == "m4a" }
        for (target in targets) {
            if (target.exists() && !target.deleteRecursively()) throw IOException("Could not erase private cache")
        }
    }
}
