package com.messenger.app.data.history

import com.messenger.app.data.attachment.*
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import java.util.UUID

/** All byte work happens on IO. A changed source aborts replacement, never drops
 * the previous confirmed copy. Media remains opaque on the storage server. */
class HistoryNativeOwner(
    private val auth: AuthRepository,
    private val db: MessengerDatabase,
    private val remote: HistoryBackupTransport,
    private val media: HistoryMediaTransport,
    directory: File,
    planDirectory: File,
    private val prepareRestore: suspend () -> Unit,
    private val calls: com.messenger.app.data.call.CallHistoryRepository,
    private val prepareSource: suspend () -> Unit,
) : HistoryBackupLocal {
    // Resolve the OS-owned filesDir alias, but still reject symlinks below it.
    private val directory = File(requireNotNull(directory.parentFile).canonicalFile, directory.name)
    private val planDirectory = File(requireNotNull(planDirectory.parentFile).canonicalFile, planDirectory.name)
    private val epoch = auth.currentSessionEpoch()
    private val owner = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: error("Not signed in")
    private val store = HistoryLocalStore(db, auth)
    private var server: String? = null
    private var source: HistoryArchive? = null
    private var prepared: List<HistoryMediaEntry>? = null
    private var capturedChats: List<HistoryArchiveChat>? = null
    private var transferDirectory: File? = null
    private var cleanedOldTransfers = false
    companion object { private val activeTransfers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
    fun backupConfirmed() {
        // Nonessential local housekeeping must not turn an acknowledged copy into
        // a false failure. Never run pruning on cancellation or partial upload.
        runCatching { prepared?.let { HistoryUploadPlan(File(planDirectory, "${requireNotNull(server)}/$owner")).retain(it) } }
    }
    private suspend fun transfer(): File = transferDirectory ?: File(root(), "transfer-${UUID.randomUUID()}").also {
        activeTransfers.add(it.absolutePath)
        try { check(it.mkdir()); transferDirectory = it } catch (failure: Exception) { activeTransfers.remove(it.absolutePath); throw failure }
    }
    fun finish() {
        transferDirectory?.let { directory ->
            for (sub in directory.listFiles().orEmpty()) {
                if (sub.canonicalFile != sub.absoluteFile || !sub.isDirectory) continue
                for (file in sub.listFiles().orEmpty()) if (file.isFile && file.canonicalFile == file.absoluteFile) file.delete()
                sub.delete()
            }
            directory.delete()
            activeTransfers.remove(directory.absolutePath)
        }
        transferDirectory = null; source = null; prepared = null; capturedChats = null
    }
    private val json = Json { ignoreUnknownKeys = false }
    override suspend fun assertCurrent() {
        currentCoroutineContext().ensureActive()
        check(auth.currentSessionEpoch() == epoch && (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id == owner) { "History account changed" }
    }
    private suspend fun root(): File {
        assertCurrent()
        val id = server ?: remote.status().server_id.also { requireHistoryId(it); server = it }
        val base = File(directory, "$id/$owner").apply { check(isDirectory || mkdirs()); check(canonicalFile == absoluteFile) }
        if (!cleanedOldTransfers) {
            val threshold = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            for (stale in base.listFiles().orEmpty()) {
                if (!stale.name.matches(Regex("transfer-[0-9a-f-]{36}")) || !stale.isDirectory || stale.canonicalFile != stale.absoluteFile ||
                    stale.lastModified() >= threshold || stale.absolutePath in activeTransfers) continue
                for (sub in stale.listFiles().orEmpty()) {
                    if (sub.name !in setOf("cipher", "readback") || !sub.isDirectory || sub.canonicalFile != sub.absoluteFile) continue
                    for (file in sub.listFiles().orEmpty()) if (file.isFile && file.canonicalFile == file.absoluteFile) file.delete()
                    sub.delete()
                }
                stale.delete()
            }
            cleanedOldTransfers = true
        }
        return base
    }
    override suspend fun export(snapshot: String): HistoryArchive = withContext(Dispatchers.IO) {
        root(); prepareSource(); assertCurrent()
        capturedChats = readChats()
        calls.exportHistory(store.export(requireNotNull(server), owner, snapshot, epoch).copy(format = 2, media = emptyList(), chats = capturedChats), epoch).also { source = it; prepared = null }
    }
    private suspend fun readChats(): List<HistoryArchiveChat> = db.chatDao().observeChats(owner).first().map { row ->
        HistoryArchiveChat(row.chatId, row.type, java.time.Instant.parse(row.createdAt).toEpochMilli(), row.otherUserId?.let {
            HistoryArchivePeer(it, requireNotNull(row.otherUsername), row.otherDisplayName.orEmpty())
        })
    }
    private fun attachments(archive: HistoryArchive): Map<String, AttachmentMetadataDto> = buildMap {
        archive.messages.filter { !it.hidden && it.deletedThrough == 0L }.forEach { row ->
            row.content?.let { json.decodeFromString<MessageContentDto>(it).attachment }?.let {
                val old = put(it.attachmentId, it); require(old == null || old == it)
            }
        }
    }
    private fun descriptor(metadata: AttachmentMetadataDto, blob: String) = HistoryMediaEntry(metadata.attachmentId, blob,
        metadata.encryptedSize, Base64.getDecoder().decode(metadata.encryption.sha256).joinToString("") { "%02x".format(it) })
    private fun checkLink(entry: HistoryMediaEntry, link: HistoryMediaLink) {
        check(link.blob_id == entry.blob && link.bytes == entry.bytes && link.sha256 == entry.sha256)
    }
    override suspend fun prepareBackup(archive: HistoryArchive): HistoryArchive = withContext(Dispatchers.IO) {
        if (!media.sourceComplete(archive.messages.map { it.id }, archive.calls.map { it.id })) throw HistorySourceNotReady()
        val spool = File(transfer(), "cipher"); val state = remote.status(); assertCurrent()
        val required = attachments(archive)
        checkSpace(required.values, restore = false)
        val previous = archive.media.orEmpty().associateBy { it.attachment }
        val plan = HistoryUploadPlan(File(planDirectory, "${requireNotNull(server)}/$owner"))
        val entries = ArrayList<HistoryMediaEntry>()
        for ((id, metadata) in required) {
            assertCurrent()
            val existing = previous[id]
            val identity = descriptor(metadata, existing?.blob ?: UUID.randomUUID().toString())
            val entry = if (existing != null) identity else identity.copy(blob=plan.blob(id, identity.bytes, identity.sha256))
            entry.validate()
            val reusable = if (existing != null) media.mediaGet(entry.blob) else try { media.mediaGet(entry.blob) }
                catch (error: com.messenger.app.data.api.AuthApiException.Api) {
                    if (error.code in setOf("not_found", "media_not_found") || (error.code == "http_error" && error.message == "HTTP 404")) null else throw error
                }
            if (reusable != null) {
                if (existing != null) require(existing == entry)
                val link = reusable; checkLink(entry, link)
                val cipher = media.receiveMedia(requireNotNull(link.download_url), entry, spool)
                val probe = File(File(transfer(), "readback").apply { check(isDirectory || mkdirs()) }, "verify-${UUID.randomUUID()}")
                try { decrypt(metadata, cipher, probe) } finally { probe.delete() }
            } else {
                val cipher = media.receiveMedia(media.liveAttachment(id), entry, spool)
                // Authenticate before advertising a recoverable backup.
                val probe = File(File(transfer(), "readback").apply { check(isDirectory || mkdirs()) }, "verify-${UUID.randomUUID()}")
                try { decrypt(metadata, cipher, probe) } finally { probe.delete() }
                val start = media.mediaStart(HistoryMediaStart(state.revision, entry.blob, entry.bytes, entry.sha256)); checkLink(entry, start)
                media.putMedia(requireNotNull(start.upload_url), cipher)
                val sealed = media.mediaSeal(entry.blob, state.revision); checkLink(entry, sealed)
                // Separate directory forces actual server readback rather than cache reuse.
                val verification = File(transfer(), "readback")
                val readback = media.receiveMedia(requireNotNull(sealed.download_url), entry, verification)
                readback.delete()
            }
            entries.add(entry)
        }
        prepared = entries
        val chats = (archive.chats.orEmpty() + capturedChats.orEmpty()).associateBy { it.id }.values
            .filter { it.id !in archive.deletedChats }.sortedBy { it.id }
        archive.copy(format = 2, media = entries, chats = chats)
    }
    override suspend fun verifyBackupReady(archive: HistoryArchive) = withContext(Dispatchers.IO) {
        assertCurrent()
        val captured = requireNotNull(source)
        if (calls.exportHistory(store.export(requireNotNull(server), owner, captured.snapshot, epoch).copy(format = 2, media = emptyList(), chats = readChats()), epoch) != captured || readChats() != capturedChats) throw HistorySourceChanged()
        check(archive.media == prepared)
        validateHistoryMedia(archive.media.orEmpty(), attachments(archive).keys, 10L * 1024 * 1024 * 1024)
        if (!media.sourceComplete(archive.messages.map { it.id }, archive.calls.map { it.id })) throw HistorySourceNotReady()
        for (entry in archive.media.orEmpty()) { val link = media.mediaGet(entry.blob); checkLink(entry, link); assertCurrent() }
    }
    private suspend fun decrypt(metadata: AttachmentMetadataDto, cipher: File, target: File) {
        val job = currentCoroutineContext()
        val cancelled = { !job.isActive || auth.currentSessionEpoch() != epoch }
        val decode = Base64.getDecoder()
        val hash = decode.decode(metadata.encryption.sha256)
        when (metadata.encryption.version) {
            1 -> AttachmentCrypto.decryptFile(cipher, target, AttachmentKeyMaterial(decode.decode(metadata.encryption.key), decode.decode(metadata.encryption.nonce)), hash, isCancelled=cancelled)
            2 -> AttachmentCryptoV2.decryptFile(cipher, target, AttachmentCryptoV2.parseKeysetHandle(decode.decode(metadata.encryption.keyset)), hash, isCancelled=cancelled)
            else -> error("Unsupported media encryption")
        }
        assertCurrent(); require(target.length() == metadata.size)
    }
    private suspend fun checkSpace(metadata: Collection<AttachmentMetadataDto>, restore: Boolean) {
        var cipher = 0L; var plain = 0L; var largest = 0L
        for (item in metadata) {
            require(item.encryptedSize in 1..HistoryMediaEntry.MAX_FILE_BYTES && item.size in 0..HistoryMediaEntry.MAX_FILE_BYTES)
            cipher = Math.addExact(cipher, item.encryptedSize)
            plain = Math.addExact(plain, item.size)
            largest = maxOf(largest, item.encryptedSize + item.size)
        }
        require(cipher <= 10L * 1024 * 1024 * 1024)
        val reserve = 32L * 1024 * 1024
        if (root().usableSpace < cipher + (if (restore) plain else largest) + reserve) throw HistoryInsufficientSpace()
    }
    override suspend fun restoreVerified(archive: HistoryArchive) = withContext(Dispatchers.IO) {
        assertCurrent()
        val known = if (archive.format == 1) archive.messages.map { it.chat }.distinct().mapNotNull { id ->
            db.chatDao().observeChat(id,owner).first()?.let { row ->
                // A direct chat needs a known recipient; otherwise use the
                // generic local shell until normal metadata reconciliation.
                if (row.type == "direct" && row.otherUserId == null) null else
                    HistoryArchiveChat(row.chatId,row.type,java.time.Instant.parse(row.createdAt).toEpochMilli(),row.otherUserId?.let {
                        HistoryArchivePeer(it,requireNotNull(row.otherUsername),row.otherDisplayName.orEmpty())
                    })
            }
        } else emptyList()
        restoreArchive(archive.restoringTextChatDescriptors(known).restoringCallChatDescriptors())
    }
    private suspend fun restoreArchive(archive: HistoryArchive) = withContext(Dispatchers.IO) {
        val base = root(); archive.validate(requireNotNull(server), owner)
        val needed = attachments(archive)
        validateHistoryMedia(archive.media.orEmpty(), needed.keys, 10L * 1024 * 1024 * 1024)
        checkSpace(needed.values, restore = true)
        val before = remote.status()
        check(before.active?.backup_id == archive.snapshot) { "Backup changed; retry restore" }
        val files = linkedMapOf<String, File>()
        var imported = false
        try {
            for (entry in archive.media.orEmpty()) {
                assertCurrent()
                val metadata = requireNotNull(needed[entry.attachment]); require(entry == descriptor(metadata, entry.blob))
                val link = media.mediaGet(entry.blob); checkLink(entry, link)
                val cipher = media.receiveMedia(requireNotNull(link.download_url), entry, File(transfer(), "cipher"))
                val destination = File(base, "restored-${UUID.randomUUID()}")
                files[entry.attachment] = destination
                decrypt(metadata, cipher, destination)
            }
            assertCurrent(); prepareRestore(); assertCurrent()
            val deletedCalls = media.restoreCallCheck(archive.calls.map { it.id })
            val effective = archive.applyingServerDeletions(media.restoreCheck(archive.messages.map { it.id })).let {
                it.copy(calls = it.calls.filterNot { row -> row.id in deletedCalls }, callDeletedIds = it.callDeletedIds + deletedCalls)
            }
            assertCurrent(); val after = remote.status()
            check(before.revision == after.revision && before.active == after.active) { "Backup changed; retry restore" }
            assertCurrent()
            // Once the short atomic DB phase starts, complete its handoff before
            // cancellation can run file cleanup; never delete committed media.
            withContext(NonCancellable) {
                val previousFiles = auth.withSessionWrite(epoch) {
                    files.keys.mapNotNull { db.attachmentDao().findByAttachmentId(it)?.localPath }.map(::File)
                }.orEmpty()
                calls.restoreHistory(effective, epoch) { commitCalls ->
                    store.import(effective, requireNotNull(server), owner, epoch, files, beforeCommit = commitCalls)
                }
                imported = true
                val unused = auth.withSessionWrite(epoch) {
                    val retained = db.attachmentDao().allWithLocalPath().mapNotNull { it.localPath }.toSet()
                    (files.values + previousFiles).filter { it.absolutePath !in retained && it.parentFile == base &&
                        it.name.startsWith("restored-") && it.canonicalFile == it.absoluteFile }
                }.orEmpty()
                unused.forEach { it.delete() }
            }
            assertCurrent(); remote.restoreComplete(archive.snapshot); assertCurrent()
        } finally { if (!imported) files.values.forEach { it.delete(); File(it.parentFile, it.name + ".tmp").delete() } }
    }
}
