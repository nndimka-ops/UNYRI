package com.messenger.app.data.history

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Wire DTOs deliberately match the opaque server API. Never add recovery keys here. */
@Serializable data class HistoryBackupInfo(val backup_id: String, val format_version: Int, val sha256: String,
    val bytes: Int, val created_at: String, val verified_at: String? = null)
@Serializable data class HistoryBackupState(val server_id: String, val revision: Long, val desired_enabled: Boolean,
    val active: HistoryBackupInfo? = null, val staged: HistoryBackupInfo? = null)
@Serializable data class HistoryBackupObject(val backup_id: String, val format_version: Int, val sha256: String, val ciphertext: String)
@Serializable data class HistoryBackupUpload(val expected_revision: Long, val backup_id: String, val format_version: Int = 1,
    val ciphertext: String, val sha256: String, val media_ids: List<String> = emptyList())
@Serializable data class HistoryBackupConfirm(val expected_revision: Long, val backup_id: String, val sha256: String)
@Serializable data class HistoryBackupPreference(val expected_revision: Long, val desired_enabled: Boolean)
@Serializable data class HistoryBackupDelete(val expected_revision: Long)

interface HistoryBackupTransport {
    suspend fun restoreComplete(backupId: String) { error("Restore completion is not supported") }
    suspend fun status(): HistoryBackupState
    suspend fun download(id: String): HistoryBackupObject
    suspend fun stage(request: HistoryBackupUpload): HistoryBackupState
    suspend fun confirm(request: HistoryBackupConfirm): HistoryBackupState
    suspend fun preference(request: HistoryBackupPreference): HistoryBackupState
    suspend fun delete(request: HistoryBackupDelete): HistoryBackupState
}

/** Account-scoped port. The native owner must fence writes, settle optimistic jobs,
 * verify durable media, and reject an incomplete initial snapshot. Not a UI flag.
 */
interface HistoryBackupLocal {
    suspend fun assertCurrent()
    suspend fun export(snapshot: String): HistoryArchive
    /** Fill retained media and chat descriptors AFTER merging the previous copy. */
    suspend fun prepareBackup(archive: HistoryArchive): HistoryArchive = archive
    /** Recheck the captured source revision AND durable media. Called both before
     * upload and before confirmation; reject intervening deletion/account changes. */
    suspend fun verifyBackupReady(archive: HistoryArchive)
    suspend fun restoreVerified(archive: HistoryArchive)
}

/** Upload -> read back -> authenticate -> confirm. An incomplete upload is never
 * success. Always merge the previous authenticated copy before replacing it, even
 * on a new/empty device. A changed account, bad key or changed revision fails closed.
 * This coordinator must be retained once per account, not recreated per button tap.
 */
class HistoryBackupCoordinator(
    private val server: String, private val owner: String,
    private val remote: HistoryBackupTransport, private val local: HistoryBackupLocal,
) {
    private val mutex = Mutex()

    suspend fun status(): HistoryBackupState = mutex.withLock {
        fence(); remote.status().also { validateState(it); fence() }
    }

    /** Validate an entered recovery key without writing messages or replacing a copy. */
    suspend fun verifyRecoveryKey(recoveryKey: ByteArray): HistoryBackupInfo = mutex.withLock {
        val key = recoveryKey.copyOf()
        try {
            require(key.size == 32); fence()
            val state = remote.status().also(::validateState)
            val info = requireNotNull(state.active) { "No confirmed backup" }
            read(info, key)
            val latest = remote.status().also(::validateState); fence()
            check(latest.revision == state.revision && latest.active == info)
            info
        } finally { key.fill(0) }
    }

    /** Disabling never deletes the last confirmed copy. */
    suspend fun setEnabled(expectedRevision: Long, enabled: Boolean): HistoryBackupState = mutex.withLock {
        fence()
        val before = remote.status().also(::validateState)
        check(before.revision == expectedRevision)
        fence()
        remote.preference(HistoryBackupPreference(expectedRevision, enabled)).also {
            validateState(it); fence()
            check(it.revision > expectedRevision && it.desired_enabled == enabled && it.active == before.active)
            if (!enabled) check(it.staged == null)
        }
    }

    /** Caller passes the revision shown in the explicit deletion confirmation.
     * A newly replaced copy is never erased using a stale confirmation dialog. */
    suspend fun deleteConfirmed(expectedRevision: Long): HistoryBackupState = mutex.withLock {
        require(expectedRevision >= 0); fence()
        val before = remote.status().also(::validateState)
        check(before.revision == expectedRevision)
        fence()
        remote.delete(HistoryBackupDelete(expectedRevision)).also {
            validateState(it); fence()
            check(it.revision > expectedRevision && !it.desired_enabled && it.active == null && it.staged == null)
        }
    }

    suspend fun backup(recoveryKey: ByteArray): HistoryBackupInfo = mutex.withLock {
        val key = recoveryKey.copyOf()
        try {
            require(key.size == 32)
            fence()
            val state = remote.status().also(::validateState)
            fence(); check(state.desired_enabled) { "Backup uploads are disabled" }
            val previous = state.active?.let { read(it, key) }
            val id = UUID.randomUUID().toString()
            val current = local.export(id)
            fence(); current.validate(server, owner); check(current.snapshot == id)
            val merged = (previous?.let { HistoryArchiveMerge.merge(current, it) } ?: current).copy(snapshot = id)
            val archive = local.prepareBackup(merged)
            fence(); archive.validate(server, owner)
            check(archive.format >= merged.format && archive.withoutMedia() == merged.withoutMedia())
            check(archive.calls == merged.calls && archive.callDeletedIds == merged.callDeletedIds &&
                archive.callClearedBeforeMs == merged.callClearedBeforeMs && archive.callChatClears == merged.callChatClears)
            if (archive.format == 2) archive.validatePreparedChatDescriptors()
            // This must include missing attachments and source completeness; metadata
            // alone is NOT a durable media backup and must not pass this check.
            local.verifyBackupReady(archive)
            fence()
            val plain = HistoryArchive.encode(archive)
            val packet = try { HistoryBackupCrypto.encrypt(plain, key, context(id)) } finally { plain.fill(0) }
            val digest = sha256(packet)
            val staged = remote.stage(HistoryBackupUpload(state.revision, id, format_version = archive.format,
                ciphertext = Base64.getEncoder().encodeToString(packet), sha256 = digest,
                media_ids = archive.media?.map { it.blob } ?: emptyList())).also(::validateState)
            fence()
            check(staged.desired_enabled && staged.revision > state.revision)
            val info = requireNotNull(staged.staged?.takeIf { it.backup_id == id })
            check(info.sha256 == digest && info.bytes == packet.size)
            val readBack = read(info, key)
            // Compare parsed data, not JSON key order; read() already authenticated
            // the exact uploaded ciphertext hash and account/backup context.
            check(readBack == archive)
            local.verifyBackupReady(archive)
            fence()
            val confirmed = remote.confirm(HistoryBackupConfirm(staged.revision, id, digest)).also(::validateState)
            fence()
            val active = requireNotNull(confirmed.active)
            check(confirmed.desired_enabled && confirmed.revision > staged.revision &&
                active.backup_id == id && active.sha256 == digest && active.bytes == packet.size && active.verified_at != null)
            active
        } finally { key.fill(0) }
    }

    suspend fun restore(recoveryKey: ByteArray): HistoryBackupInfo = mutex.withLock {
        val key = recoveryKey.copyOf()
        try {
            require(key.size == 32); fence()
            val state = remote.status().also(::validateState)
            fence()
            val info = requireNotNull(state.active) { "No confirmed backup" }
            val archive = read(info, key)
            // Deletion/disable/replacement on another device must be observed before
            // entering the local transaction. No remote mutation during restoration.
            val latest = remote.status().also(::validateState)
            fence()
            check(latest.revision == state.revision && latest.active == info) { "Backup changed; retry restoration" }
            local.restoreVerified(archive)
            fence()
            info
        } finally { key.fill(0) }
    }

    private suspend fun read(info: HistoryBackupInfo, key: ByteArray): HistoryArchive {
        validateInfo(info)
        fence()
        val obj = remote.download(info.backup_id)
        fence()
        check(obj.backup_id == info.backup_id && obj.format_version == info.format_version && obj.sha256 == info.sha256)
        require(obj.ciphertext.length <= ((HistoryBackupCrypto.MAX_PACKET_BYTES + 2) / 3) * 4)
        val packet = Base64.getDecoder().decode(obj.ciphertext)
        check(packet.size == info.bytes && sha256(packet) == info.sha256)
        val plain = HistoryBackupCrypto.decrypt(packet, key, context(info.backup_id))
        return try {
            HistoryArchive.decode(plain, server, owner).also { check(it.snapshot == info.backup_id && it.format == info.format_version) }
        } finally { plain.fill(0) }
    }

    private fun context(id: String) = HistoryBackupCrypto.Context(UUID.fromString(server), UUID.fromString(owner), UUID.fromString(id))
    private suspend fun fence() { currentCoroutineContext().ensureActive(); local.assertCurrent() }
    private fun validateState(state: HistoryBackupState) {
        requireHistoryId(server); requireHistoryId(owner)
        check(state.server_id == server && state.revision >= 0)
        state.active?.let { validateInfo(it); check(it.verified_at != null) }
        state.staged?.let { validateInfo(it); check(it.verified_at == null) }
        check(state.active == null || state.active.backup_id != state.staged?.backup_id)
    }
    private fun validateInfo(info: HistoryBackupInfo) {
        requireHistoryId(info.backup_id)
        check(info.format_version in 1..2 && info.bytes in 29..HistoryBackupCrypto.MAX_PACKET_BYTES &&
            info.sha256.matches(Regex("[0-9a-f]{64}")))
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
