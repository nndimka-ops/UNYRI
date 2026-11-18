package com.messenger.app.data.local

import android.content.Context
import com.messenger.app.data.attachment.AttachmentMetadataDto
import com.messenger.app.data.local.attachment.AttachmentDao
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Local media-cache accounting + cleanup (Phase 9.1). Represents ONLY the
 * device-local cache — never server/S3 bytes. File-system size scanning and
 * deletion run on [Dispatchers.IO]; the message/chat databases, outbox, Signal
 * keys and auth state are never touched.
 */
class MediaCacheManager internal constructor(
    private val cacheDir: File,
    private val dao: AttachmentDao,
    private val avatarCacheDir: File,
    private val dbFile: File,
    private val draftRoot: File? = null,
    private val workspaceDao: com.messenger.app.data.local.chat.ChatWorkspaceDao? = null,
    private val currentEpoch: () -> Int = { 0 },
    private val withWorkspaceWrite: suspend (Int, suspend () -> Unit) -> Unit? = { _, block -> block() },
    private val restoredRoot: File? = null,
    private val withMediaWrite: suspend (Int, suspend () -> Unit) -> Unit? = { _, block -> block() },
    private val policyPreferences: android.content.SharedPreferences? = null,
    private val policyOwner: () -> String? = { "default" },
    private val deleteCachedFile: (File, String) -> Boolean? = MediaCacheUse::deleteUnlessUsed,
    private val activeChatId: () -> String? = { null },
    private val attachmentPathAllowed: ((String?) -> Boolean)? = null,
) {
    var onAvatarsCleared: () -> Unit = {}
    constructor(context: Context, dao: AttachmentDao, avatarCacheDir: File, workspaceDao: com.messenger.app.data.local.chat.ChatWorkspaceDao? = null, authRepository: com.messenger.app.domain.repository.AuthRepository? = null, attachmentPathAllowed: ((String?) -> Boolean)? = null) :
        this(context.cacheDir, dao, avatarCacheDir, context.getDatabasePath("messenger.db"), File(context.noBackupFilesDir, "media-drafts"), workspaceDao,
            { authRepository?.currentSessionEpoch() ?: 0 },
            { epoch, block -> if (authRepository != null) authRepository.withWorkspaceWrite(epoch, block) else block() }, File(context.filesDir, "history-restoration"),
            { epoch, block -> if (authRepository != null) authRepository.withMediaWrite(epoch, block) else block() },
            context.getSharedPreferences("media-cache-policy", Context.MODE_PRIVATE),
            { (authRepository?.authState?.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id?.let { com.messenger.app.data.api.ServerEndpoint.current() + "|" + it } },
            activeChatId = { com.messenger.app.ActiveChatTracker.activeChatId.value }, attachmentPathAllowed = attachmentPathAllowed)
    fun policy(): CachePolicy {
        val owner = policyOwner() ?: return CachePolicy()
        return CachePolicy(policyPreferences?.getLong("$owner|max", 0L) ?: 0L, policyPreferences?.getInt("$owner|days", 0) ?: 0)
    }
    fun savePolicy(policy: CachePolicy) {
        val owner = policyOwner() ?: return
        policyPreferences?.edit()?.putLong("$owner|max", policy.maxBytes.coerceAtLeast(0))?.putInt("$owner|days", policy.retentionDays.coerceIn(0,365))?.apply()
    }
    internal fun observePolicy(): Flow<CachePolicy> = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(policy()) }
        policyPreferences?.registerOnSharedPreferenceChangeListener(listener)
        trySend(policy())
        awaitClose { policyPreferences?.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Observe the state that enforcement actually protects, across every DAO write path. */
    internal fun evictionChanges(activeChat: Flow<String?>, releases: Flow<Long>, writes: Flow<Unit>): Flow<Unit> = merge(
        dao.observeCacheState().map { rows -> rows.map { row ->
            CacheProtection(row.attachmentId, row.chatId, row.localPath,
                CacheClearPolicy.isProtectedUploadSource(row.uploadState), row.downloadState == "downloading")
        }.toSet() }.distinctUntilChanged().map { Unit },
        (workspaceDao?.observeCacheProtection() ?: flowOf(emptyList())).map { rows ->
            val paths = mutableSetOf<String>()
            var malformed = false
            rows.forEach { row ->
                val parsed = com.messenger.app.data.attachment.mediaDraftPathsOrNull(row.name, row.value)
                if (parsed == null) malformed = true else paths += parsed
            }
            paths.toSet() to malformed
        }.distinctUntilChanged().map { Unit },
        activeChat.distinctUntilChanged().map { Unit },
        releases.distinctUntilChanged().map { Unit },
        writes,
    )

    private data class CacheProtection(val id: String, val chat: String, val path: String, val upload: Boolean, val download: Boolean)

    suspend fun nextRetentionSweepDelay(requestedPolicy: CachePolicy = policy()): Long? {
        val days = requestedPolicy.retentionDays
        if (days <= 0) return null
        val epoch = currentEpoch()
        return withContext(Dispatchers.IO) {
            val modified = dao.allWithLocalPath().mapNotNull { it.localPath?.takeIf(::eligiblePath)?.let(::File)?.takeIf { it.isFile }?.lastModified() }
            if (epoch != currentEpoch()) throw kotlinx.coroutines.CancellationException("Cache account changed")
            CacheSweepSchedule.delayMillis(days, modified, System.currentTimeMillis())
        }
    }
    data class ChatUsage(val chatId: String, val bytes: Long)
    suspend fun chatUsage(): List<ChatUsage> = withContext(Dispatchers.IO) {
        val epoch = currentEpoch()
        dao.allWithLocalPath().filter { eligiblePath(it.localPath) }.groupBy { it.chatId }.map { (id, rows) ->
            ChatUsage(id, rows.mapNotNull { it.localPath }.distinct().sumOf { File(it).takeIf { it.isFile }?.length() ?: 0L })
        }.filter { it.bytes > 0 }.sortedByDescending { it.bytes }.also { if (currentEpoch() != epoch) throw kotlinx.coroutines.CancellationException("Account changed") }
    }
    private fun eligiblePath(path: String?): Boolean = attachmentPathAllowed?.invoke(path)
        ?: (path?.let { File(it).canonicalFile.parentFile == attachmentsDir().canonicalFile } == true)

    data class Usage(
        val photosBytes: Long,
        val voiceBytes: Long,
        val filesBytes: Long,
        val avatarsBytes: Long,
        val tempBytes: Long,
        val databaseBytes: Long,
    ) {
        val mediaBytes: Long get() = photosBytes + voiceBytes + filesBytes
        val totalCacheBytes: Long get() = mediaBytes + avatarsBytes + tempBytes
    }

    data class ClearResult(
        val deletedFiles: Int,
        val deletedBytes: Long,
        val protectedFiles: Int,
        val failedFiles: Int,
    )

    suspend fun scan(): Usage = withContext(Dispatchers.IO) {
        val epoch = currentEpoch()
        val attachments = dao.allWithLocalPath()
        val referencedPaths = attachments.mapNotNull { it.localPath }
            .map { File(it).canonicalPath }
            .toHashSet()

        var photos = 0L
        var voice = 0L
        var files = 0L
        for (entity in attachments) {
            val path = entity.localPath ?: continue
            if (!eligiblePath(path)) continue
            val file = File(path)
            if (!file.exists()) continue
            val size = file.length()
            when (entity.mediaType) {
                AttachmentMetadataDto.MEDIA_TYPE_IMAGE -> photos += size
                AttachmentMetadataDto.MEDIA_TYPE_VOICE -> voice += size
                else -> files += size
            }
        }

        val avatars = dirSize(avatarCacheDir)
        val temp = dirSize(File(cacheDir, "camera")) + dirSize(File(cacheDir, MediaExportStore.DIRECTORY)) + legacyFiles(cacheDir).sumOf { it.length() } +
            draftFiles().filterNot { it.canonicalPath in referencedPaths }.sumOf { it.length() } +
            previewFiles().sumOf { it.length() } +
            orphanPlaintextIn(attachmentsDir(), referencedPaths)
                .sumOf { runCatching { it.length() }.getOrDefault(0L) }

        if (epoch != currentEpoch()) throw kotlinx.coroutines.CancellationException("Account changed")
        Usage(
            photosBytes = photos,
            voiceBytes = voice,
            filesBytes = files,
            avatarsBytes = avatars,
            tempBytes = temp,
            databaseBytes = databaseSize(),
        )
    }

    /**
     * Deletes known re-downloadable media plaintext and avatars.
     * Outgoing attachments with a non-completed upload are PROTECTED (their
     * plaintext is the retry/re-send source) and skipped. After a successful
     * deletion the row's cached-path metadata is invalidated so the UI treats
     * it as a normal cache miss, never a broken/corrupt message.
     */
    suspend fun clear(chatId: String? = null): ClearResult = clearSelected(chatId, null)

    suspend fun enforcePolicy(requestedPolicy: CachePolicy = policy()): ClearResult {
        val policy = requestedPolicy
        if (policy.maxBytes == 0L && policy.retentionDays == 0) return ClearResult(0,0,0,0)
        val epoch = currentEpoch()
        var result: ClearResult? = null
        withWorkspaceWrite(epoch) { withMediaWrite(epoch) {
            result = withContext(Dispatchers.IO) {
                val rows = dao.allWithLocalPath()
                val references = workspaceDao?.allValues().orEmpty().flatMap {
                    com.messenger.app.data.attachment.mediaDraftPathsOrNull(it.name, it.value)
                        ?: return@withContext ClearResult(0,0,rows.size,1)
                }.map { File(it).canonicalPath }.toSet()
                val owned = rows.filter { eligiblePath(it.localPath) }.groupBy { File(it.localPath!!).canonicalPath }
                var protectedBytes = 0L
                val candidates = mutableListOf<CacheEvictionPolicy.Entry>()
                for ((path, aliases) in owned) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val file = File(path)
                    if (path in references || aliases.any { CacheClearPolicy.isProtectedUploadSource(it.uploadState) || it.downloadState == "downloading" || it.chatId == activeChatId() || MediaCacheUse.isUsed(file,it.attachmentId) }) {
                        protectedBytes += file.length(); continue
                    }
                    candidates.add(CacheEvictionPolicy.Entry(aliases.first().attachmentId,file.length(),file.lastModified()))
                }
                val selected = CacheEvictionPolicy.select(candidates,policy,System.currentTimeMillis(),protectedBytes=protectedBytes)
                // All aliases of a deleted cache file lose their cache reference together.
                val ids = owned.values.filter { it.first().attachmentId in selected }.flatten().map { it.attachmentId }.toSet()
                clearOwned(null,ids,automatic=true)
            }
        } }
        return result ?: throw kotlinx.coroutines.CancellationException("Cache account changed")
    }

    private suspend fun clearSelected(chatId: String?, selected: Set<String>?, epoch: Int = currentEpoch()): ClearResult {
        var result: ClearResult? = null
        // Logout drains workspace before media; maintain that lock order here.
        withWorkspaceWrite(epoch) { withMediaWrite(epoch) { result = clearOwned(chatId, selected) } }
        return result ?: throw kotlinx.coroutines.CancellationException("Cache account changed")
    }

    private suspend fun clearOwned(chatId: String?, selected: Set<String>?, automatic: Boolean = false): ClearResult = withContext(Dispatchers.IO) {
        workspaceDao?.removeCompletedMediaJobs()
        val attachments = dao.allWithLocalPath()
        val workspaceReferences = workspaceDao?.allValues().orEmpty().flatMap {
            com.messenger.app.data.attachment.mediaDraftPathsOrNull(it.name, it.value)
                ?: return@withContext ClearResult(0, 0L, attachments.size, 1)
        }.map { File(it).canonicalPath }.toSet()
        val protectedPaths = workspaceReferences + attachments.filter {
            CacheClearPolicy.isProtectedUploadSource(it.uploadState) || it.downloadState == "downloading" || chatId != null && it.chatId != chatId || automatic && it.chatId == activeChatId()
        }.mapNotNull { it.localPath }.map { File(it).canonicalPath }
        var deletedFiles = 0
        var deletedBytes = 0L
        var protectedFiles = 0
        var failedFiles = 0

        for (entity in attachments) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (chatId != null && entity.chatId != chatId || selected != null && entity.attachmentId !in selected) continue
            val path = entity.localPath ?: continue
            if (automatic && entity.chatId == activeChatId()) { protectedFiles++; continue }
            if (!eligiblePath(path) || entity.downloadState == "downloading") { protectedFiles++; continue }
            // Restored originals may no longer exist at the old attachment URL.
            // They are durable local history, not disposable network cache.
            if (restoredRoot?.let { File(path).canonicalFile.toPath().startsWith(it.canonicalFile.toPath()) } == true) {
                protectedFiles++
                continue
            }
            if (CacheClearPolicy.isProtectedUploadSource(entity.uploadState) || File(path).canonicalPath in protectedPaths) {
                protectedFiles++
                continue
            }
            val file = File(path)
            if (file.exists()) {
                val size = file.length()
                when (deleteCachedFile(file, entity.attachmentId)) {
                    true -> { deletedFiles++; deletedBytes += size }
                    null -> { protectedFiles++; continue }
                    false -> { failedFiles++; continue }
                }
            }
            // Invalidate the stale cached-path metadata whether or not the
            // file still existed (a missing file is already a cache miss).
            // Once the bytes are gone, finish invalidating their reference even
            // if the screen is dismissed at this exact suspension boundary.
            withContext(kotlinx.coroutines.NonCancellable) { dao.clearLocalCache(entity.attachmentId) }
        }

        if (chatId == null && selected == null && !automatic) {
            val exports = File(cacheDir, MediaExportStore.DIRECTORY).listFiles().orEmpty().filter { it.isFile } + legacyFiles(cacheDir)
            for (file in exports) {
                val bytes = file.length()
                when (MediaCacheUse.deleteUnlessUsed(file, "export")) {
                    true -> { deletedFiles++; deletedBytes += bytes }
                    false -> failedFiles++
                    null -> protectedFiles++
                }
            }
        }
        // Drafts, previews and avatars can be active outside this screen. Only
        // attachment plaintext proven re-downloadable is eligible for this operation.
        ClearResult(deletedFiles, deletedBytes, protectedFiles, failedFiles)
    }

    private fun attachmentsDir(): File = File(cacheDir, "attachments").apply { mkdirs() }
    private fun previewFiles(): List<File> = File(attachmentsDir(), "previews")
        .listFiles { file -> file.isFile && !isTransientTransfer(file.name) }?.toList().orEmpty()
    private fun draftFiles(): List<File> = draftRoot?.listFiles()?.filter { it.isDirectory }
        ?.flatMap { it.listFiles()?.filter { file -> file.isFile }.orEmpty() }.orEmpty()

    /** Orphan plaintext leftovers (crashed uploads/downloads) — never the
     *  in-flight .enc/.part/.building transfer files. */
    private fun orphanPlaintextIn(dir: File, referenced: Set<String>): List<File> =
        dir.listFiles { f -> f.isFile && !isTransientTransfer(f.name) }
            ?.filterNot { referenced.contains(it.canonicalPath) }
            ?: emptyList()

    private fun isTransientTransfer(name: String): Boolean =
        name.endsWith(".enc") || name.endsWith(".part") || name.endsWith(".building")

    private fun dirSize(dir: File): Long =
        dir.listFiles { f -> f.isFile }?.sumOf { runCatching { it.length() }.getOrDefault(0L) } ?: 0L

    private fun databaseSize(): Long {
        val parent = dbFile.parentFile ?: return 0L
        return parent.listFiles { f -> f.isFile && f.name.startsWith("messenger.db") }
            ?.sumOf { runCatching { it.length() }.getOrDefault(0L) } ?: 0L
    }
}
