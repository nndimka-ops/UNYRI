package com.messenger.app.data.local.chat

import android.content.Context
import androidx.room.InvalidationTracker
import com.messenger.app.data.auth.WorkspaceWipe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One event-driven debounce for a workspace. No polling and no work while idle. */
internal class MessageDeletionMaintenance(private val context: Context, private val database: MessengerDatabase, private val scope: CoroutineScope) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    fun start() {
        database.invalidationTracker.addObserver(object : InvalidationTracker.Observer("messages", "attachments", "chat_workspace", "deleted_chats") {
            override fun onInvalidated(tables: Set<String>) { requests.trySend(Unit) }
        })
        requests.trySend(Unit) // Resume filesystem cleanup after a crash.
        scope.launch(Dispatchers.IO) {
            for (ignored in requests) {
                delay(300)
                while (requests.tryReceive().isSuccess) { /* Coalesce the deletion batch. */ }
                runCatching { sweep() }.onFailure { android.util.Log.w("DataDeletion", "cleanup pending: ${it.javaClass.simpleName}") }
            }
        }
    }

    private fun sweep() {
        val db = database.openHelper.writableDatabase
        val paths = db.query("SELECT id,path,attachmentId FROM privacy_deletions").use { cursor ->
            buildList { while (cursor.moveToNext()) add(Triple(cursor.getLong(0), cursor.getString(1), if (cursor.isNull(2)) null else cursor.getString(2))) }
        }
        if (paths.isEmpty()) return
        for ((_, path, id) in paths) {
            if (path.startsWith("@direct:")) {
                val offer = com.messenger.app.data.directtransfer.DirectFileOffer.decodeStored(path.removePrefix("@direct:"))
                if (offer != null) {
                    val root = File(context.filesDir, "direct-transfers")
                    root.listFiles().orEmpty().filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { owner ->
                        WorkspaceWipe.eraseWithin(root, File(owner, offer.transferId))
                    }
                }
                continue
            }
            if (path.isNotEmpty()) {
                val target = File(path)
                val retained = db.query("SELECT 1 FROM attachments WHERE localPath=? LIMIT 1", arrayOf<Any>(path)).use { it.moveToFirst() }
                if (!retained) {
                    // Never remove a user-picked original, a sibling workspace, or a symlink target.
                    val roots = listOf(File(context.cacheDir, "attachments"), File(context.filesDir, "history-restoration"),
                        File(context.noBackupFilesDir, "media-drafts"), File(context.noBackupFilesDir, "voice-drafts"))
                    val root = roots.firstOrNull {
                        target.absoluteFile.toPath().normalize().startsWith(it.canonicalFile.toPath()) && target.canonicalFile.toPath().startsWith(it.canonicalFile.toPath())
                    }
                    if (root != null) WorkspaceWipe.eraseWithin(root, target)
                }
                if (id != null && runCatching { java.util.UUID.fromString(id) }.isSuccess) {
                    val cache = File(context.cacheDir, "attachments")
                    cache.walkTopDown().maxDepth(2).filter { it.isFile && (it.name.startsWith(id) || it.name.startsWith("upload-$id")) }.forEach { WorkspaceWipe.eraseWithin(cache, it) }
                }
                val preview = java.security.MessageDigest.getInstance("SHA-256").digest(android.net.Uri.fromFile(target).toString().toByteArray()).joinToString("") { "%02x".format(it) }
                val previews = File(context.cacheDir, "attachments/previews")
                WorkspaceWipe.eraseWithin(context.cacheDir, File(previews, preview))
            }
        }
        // Replace only this processed snapshot with a content-free checkpoint intent. Monotonic
        // IDs preserve a concurrently queued deletion even when it has exactly the same path.
        db.beginTransaction()
        val checkpointId = try {
            for ((id, _, _) in paths) db.execSQL("DELETE FROM privacy_deletions WHERE id=?", arrayOf<Any>(id))
            db.execSQL("INSERT INTO privacy_deletions(path,attachmentId) VALUES('',NULL)")
            val id = db.query("SELECT last_insert_rowid()").use { it.moveToFirst(); it.getLong(0) }
            db.setTransactionSuccessful()
            id
        } finally { db.endTransaction() }
        com.messenger.app.data.local.ChatImageCache.clear()
        // FTS4 keeps removed terms in old segments. Rebuild/merge with secure_delete enabled.
        db.execSQL("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')")
        db.execSQL("INSERT INTO messages_fts(messages_fts) VALUES('optimize')")
        val complete = db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() && it.getInt(0) == 0 }
        // Busy checkpoints retain the durable retry intent for the next event/startup.
        if (complete) db.execSQL("DELETE FROM privacy_deletions WHERE id=?", arrayOf<Any>(checkpointId))
    }
}
