package com.messenger.app.data.history

import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Account-scoped key management and explicit copy/restore. Production supplies
 * the native media owner; metadata-only test callers cannot copy or restore. */
class HistoryKeySettingsSession(
    private val auth: AuthRepository,
    private val remote: HistoryBackupTransport,
    private val nativeLocal: HistoryBackupLocal? = null,
    private val storage: () -> HistorySecretStorage,
) {
    companion object { private val copyRestoreMutex = Mutex() }
    private val epoch = auth.currentSessionEpoch()
    private val owner = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id
    private var server: String? = null
    private var coordinator: HistoryBackupCoordinator? = null
    private val initializationMutex = Mutex()
    private data class Initialized(val coordinator: HistoryBackupCoordinator, val initialState: HistoryBackupState? = null)

    /** Every entry point owns its prerequisites; opening backup settings is not
     * required first. Publish the captured namespace only after the candidate's
     * complete status validates. A failed/cancelled read must leave a retryable
     * empty cache and must never create a transfer request on the server. */
    private suspend fun ensureInitialized(): Initialized = withContext(Dispatchers.IO) {
        initializationMutex.withLock {
            checkCurrent()
            coordinator?.let { return@withLock Initialized(it) }
            val first = remote.status()
            checkCurrent(); requireHistoryId(first.server_id)
            val candidate = HistoryBackupCoordinator(first.server_id, requireNotNull(owner), remote, nativeLocal ?: local)
            val validated = candidate.status()
            checkCurrent()
            server = first.server_id
            coordinator = candidate
            Initialized(candidate, validated)
        }
    }
    private var preparedTransferBackup:String? = null
    suspend fun prepareTransfer(onConfirmed:()->Unit={}):Long {
        val opened=ensureInitialized()
        val state=opened.initialState ?: opened.coordinator.status()
        checkCurrent()
        if(!state.desired_enabled)throw HistoryUploadsDisabled()
        val copied=backup(onConfirmed=onConfirmed); checkCurrent(); preparedTransferBackup=copied.backup_id
        return System.currentTimeMillis()
    }
    fun clearTransferPreparation() { preparedTransferBackup=null; pendingPublication=null }
    private var pendingPublication: Pair<String,HistoryTransferPublish>? = null
    private fun fence() {
        check(owner != null && auth.currentSessionEpoch() == epoch &&
            (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.id == owner)
    }
    private suspend fun checkCurrent() { currentCoroutineContext().ensureActive(); fence() }
    private val local = object : HistoryBackupLocal {
        override suspend fun assertCurrent() = fence()
        override suspend fun export(snapshot: String): HistoryArchive = error("Full media backup is not connected")
        override suspend fun verifyBackupReady(archive: HistoryArchive): Unit = error("Full media backup is not connected")
        override suspend fun restoreVerified(archive: HistoryArchive): Unit = error("Full media restore is not connected")
    }
    data class Status(val remote: HistoryBackupState, val keySaved: Boolean)
    suspend fun status(): Status = withContext(Dispatchers.IO) {
        val opened = ensureInitialized()
        val state = opened.initialState ?: opened.coordinator.status()
        val saved = auth.withSessionWrite(epoch) {
            fence()
            val key = keys().load()
            try { key != null } finally { key?.fill(0) }
        } ?: error("History account changed")
        fence(); Status(state, saved)
    }
    suspend fun saveNewKey(key: ByteArray, expectedRevision: Long) = withContext(Dispatchers.IO) {
        val opened = ensureInitialized()
        val state = opened.initialState ?: opened.coordinator.status()
        check(state.revision == expectedRevision && state.active == null && state.staged == null)
        save(key)
    }
    suspend fun saveExistingKey(key: ByteArray) = withContext(Dispatchers.IO) {
        val current=ensureInitialized().coordinator
        current.verifyRecoveryKey(key); checkCurrent(); save(key)
    }
    suspend fun replaceExistingKey(candidate:ByteArray, backupId:String?=null, beforeSave:()->Unit={}, afterSave:()->Unit={}) = withContext(Dispatchers.IO) {
      copyRestoreMutex.withLock {
        val current=ensureInitialized().coordinator; val old=keys().load()
        try {
            val verified=current.verifyRecoveryKey(candidate)
            check(backupId==null || verified.backup_id==backupId)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            auth.withSessionWrite(epoch) { fence(); beforeSave(); keys().replaceVerified(old,candidate); afterSave(); true } ?: error("Account changed")
            fence()
        } finally { old?.fill(0) }
      }
    }
    fun scopeId():String { fence(); return "${requireNotNull(server)}:${requireNotNull(owner)}" }
    suspend fun verifyAutoKey() = withContext(Dispatchers.IO) {
        val current=ensureInitialized().coordinator; val key=requireNotNull(keys().load())
        try { if(current.status().active!=null) current.verifyRecoveryKey(key); checkCurrent() }
        finally { key.fill(0) }
    }
    fun validateQR(text:String):HistoryTransferQR { fence(); return HistoryTransferCrypto.parse(text,requireNotNull(server),requireNotNull(owner)) }
    suspend fun createTransfer(publicKey:String):HistoryTransferRecord {
        ensureInitialized(); checkCurrent(); HistoryTransferCrypto.publicKey(publicKey)
        val record=(remote as HistoryTransferTransport).createTransfer(publicKey)
        checkCurrent()
        record.qr().validate(requireNotNull(server),requireNotNull(owner)); check(record.recipient_public_key==publicKey && record.status=="waiting"); checkCurrent(); return record
    }
    suspend fun readTransfer(qr:HistoryTransferQR):HistoryTransferRecord {
        ensureInitialized(); checkCurrent(); qr.validate(requireNotNull(server),requireNotNull(owner))
        return (remote as HistoryTransferTransport).transfer(qr.transfer).also { it.pin(qr); checkCurrent() }
    }
    suspend fun approveTransfer(qr:HistoryTransferQR):HistoryTransferRecord = withContext(Dispatchers.IO) {
        val currentCoordinator=ensureInitialized().coordinator
        pendingPublication?.takeIf { it.first==qr.transfer }?.second?.let { pending ->
            val current=readTransfer(qr)
            val result=if(current.status=="waiting") (remote as HistoryTransferTransport).publishTransfer(qr.transfer,pending) else current
            result.pin(qr)
            check(result.status=="ready" && result.backup_id==pending.backup_id && result.ciphertext==pending.ciphertext && result.sender_public_key==pending.sender_public_key)
            checkCurrent()
            return@withContext result
        }
        check(readTransfer(qr).status=="waiting")
        val prepared=preparedTransferBackup ?: throw HistorySourceNotReady(); val state=currentCoordinator.status()
        if(state.active?.backup_id!=prepared)throw HistorySourceChanged()
        if(!state.desired_enabled)throw HistoryUploadsDisabled()
        val key=keys().load() ?: throw HistoryRecoveryKeyMissing(); val private=HistoryTransferCrypto.privateKey()
        try {
            check(currentCoordinator.verifyRecoveryKey(key).backup_id==prepared)
            val body=HistoryTransferPublish(state.revision,prepared,HistoryTransferCrypto.public(private),HistoryTransferCrypto.encrypt(key,private,qr,prepared))
            pendingPublication=qr.transfer to body
            check(readTransfer(qr).status=="waiting")
            (remote as HistoryTransferTransport).publishTransfer(qr.transfer,body).also {
                it.pin(qr); check(it.status=="ready" && it.ciphertext==body.ciphertext && it.sender_public_key==body.sender_public_key && it.backup_id==body.backup_id); checkCurrent()
            }
        } finally { key.fill(0); private.fill(0) }
    }
    suspend fun acceptTransfer(qr:HistoryTransferQR,private:ByteArray,shown:HistoryTransferRecord,matched:Boolean,onKeySaved:()->Unit={}) {
        check(matched); ensureInitialized(); val record=readTransfer(qr); check(record==shown)
        val key=HistoryTransferCrypto.decrypt(private,qr,record)
        try { replaceExistingKey(key,requireNotNull(record.backup_id), beforeSave={ qr.validate(requireNotNull(server),requireNotNull(owner)) }, afterSave={ private.fill(0); onKeySaved() }) } finally { key.fill(0) }
        checkCurrent(); val ack=(remote as HistoryTransferTransport).ackTransfer(qr.transfer,record.backup_id)
        checkCurrent(); ack.pin(qr); check(ack.status=="consumed"); private.fill(0)
    }
    suspend fun cancelTransfer(qr:HistoryTransferQR) { ensureInitialized(); if(pendingPublication?.first==qr.transfer)pendingPublication=null;checkCurrent(); (remote as HistoryTransferTransport).cancelTransfer(qr.transfer);checkCurrent() }
    suspend fun pause(expectedRevision: Long) = withContext(Dispatchers.IO) {
        ensureInitialized().coordinator.setEnabled(expectedRevision, false)
    }
    suspend fun enable(expectedRevision: Long) = withContext(Dispatchers.IO) {
        ensureInitialized().coordinator.setEnabled(expectedRevision, true)
    }
    suspend fun delete(expectedRevision: Long) = withContext(Dispatchers.IO) {
        ensureInitialized().coordinator.deleteConfirmed(expectedRevision)
    }
    suspend fun backup(beforeStart:()->Boolean={true}, onConfirmed:()->Unit={}) = withContext(Dispatchers.IO) {
      copyRestoreMutex.withLock {
        checkCurrent(); if(!beforeStart()) throw HistoryAutoNotDue(); check(nativeLocal != null)
        val current=ensureInitialized().coordinator
        val key = keys().load() ?: throw HistoryRecoveryKeyMissing()
        try { current.backup(key).also {
            (nativeLocal as? HistoryNativeOwner)?.backupConfirmed()
            auth.withSessionWrite(epoch) { fence(); onConfirmed(); true } ?: error("History account changed")
        } } finally { key.fill(0); (nativeLocal as? HistoryNativeOwner)?.finish() }
      }
    }
    suspend fun restore() = withContext(Dispatchers.IO) {
      copyRestoreMutex.withLock {
        checkCurrent(); check(nativeLocal != null)
        val current=ensureInitialized().coordinator
        val key = keys().load() ?: throw HistoryRecoveryKeyMissing()
        try { current.restore(key) } finally { key.fill(0); (nativeLocal as? HistoryNativeOwner)?.finish() }
      }
    }
    private suspend fun save(key: ByteArray) {
        auth.withSessionWrite(epoch) { fence(); keys().saveConfirmed(key); true } ?: error("History account changed")
        fence()
    }
    private fun keys() = HistoryRecoveryKeys(storage(), requireNotNull(server), requireNotNull(owner))
}

class HistoryAutoNotDue : Exception()
