package com.messenger.app.ui.chats

import kotlinx.coroutines.CompletableDeferred

internal fun galleryNeedsPermission(api: Int, granted: Boolean) = api < 29 && !granted

/** Both original and edited images await the same permission result before any MediaStore write. */
internal class GalleryPermissionGate {
    private var pending: CompletableDeferred<Boolean>? = null
    suspend fun <T> save(needsPermission: Boolean, request: () -> Unit, write: suspend () -> T): T {
        if (needsPermission) {
            val result = CompletableDeferred<Boolean>()
            pending?.complete(false)
            pending = result
            try { request(); if (!result.await()) throw SecurityException("Gallery permission denied") }
            finally { if (pending === result) pending = null }
        }
        return write()
    }
    fun result(granted: Boolean) { pending?.complete(granted) }
}
