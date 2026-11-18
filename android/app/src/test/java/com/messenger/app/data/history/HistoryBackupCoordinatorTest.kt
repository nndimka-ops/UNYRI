package com.messenger.app.data.history

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class HistoryBackupCoordinatorTest {
    private val server = "11111111-1111-4111-8111-111111111111"
    private val owner = "22222222-2222-4222-8222-222222222222"
    private val key = ByteArray(32) { it.toByte() }
    private fun id() = UUID.randomUUID().toString()
    private fun archive() = HistoryArchive(server=server, owner=owner, snapshot=id(), messages=listOf(
        HistoryArchiveMessage(id(), id(), id(), owner, 1, 1000, "{\"text\":\"hello\"}")))
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private inner class Local(var data: HistoryArchive = archive()) : HistoryBackupLocal {
        var current = true; var ready = true; var imports = 0
        var prepare: (HistoryArchive) -> HistoryArchive = { it }
        override suspend fun assertCurrent() { check(current) }
        override suspend fun export(snapshot: String) = data.copy(snapshot=snapshot)
        override suspend fun prepareBackup(archive: HistoryArchive) = prepare(archive)
        override suspend fun verifyBackupReady(archive: HistoryArchive) { check(ready) }
        override suspend fun restoreVerified(archive: HistoryArchive) { check(current); imports++; data=archive }
    }
    private inner class Remote : HistoryBackupTransport {
        var state = HistoryBackupState(server, 0, true)
        val objects = mutableMapOf<String, HistoryBackupObject>()
        var stages = 0; var confirms = 0; var statuses = 0
        var lastStage: HistoryBackupUpload? = null
        var onDownload: (() -> Unit)? = null
        var onStage: (() -> Unit)? = null
        var onStatus: (() -> Unit)? = null
        var corrupt = false; var loseStageReply = false; var loseConfirmReply = false
        fun seed(archive: HistoryArchive) {
            val packet = HistoryBackupCrypto.encrypt(HistoryArchive.encode(archive), key,
                HistoryBackupCrypto.Context(UUID.fromString(server), UUID.fromString(owner), UUID.fromString(archive.snapshot)))
            val hash = digest(packet)
            objects[archive.snapshot] = HistoryBackupObject(archive.snapshot,archive.format,hash,Base64.getEncoder().encodeToString(packet))
            state=state.copy(active=HistoryBackupInfo(archive.snapshot,archive.format,hash,packet.size,"date","verified"))
        }
        override suspend fun status(): HistoryBackupState { statuses++; onStatus?.invoke(); return state }
        override suspend fun download(id: String): HistoryBackupObject {
            onDownload?.invoke()
            val obj=objects.getValue(id)
            return if (corrupt) obj.copy(ciphertext="AAAA") else obj
        }
        override suspend fun stage(request: HistoryBackupUpload): HistoryBackupState {
            check(state.revision==request.expected_revision); check(state.desired_enabled); stages++
            lastStage = request
            val obj=HistoryBackupObject(request.backup_id,request.format_version,request.sha256,request.ciphertext)
            objects[obj.backup_id]=obj
            state=state.copy(revision=state.revision+1,staged=HistoryBackupInfo(obj.backup_id,obj.format_version,obj.sha256,
                Base64.getDecoder().decode(obj.ciphertext).size,"date"))
            onStage?.invoke()
            if (loseStageReply) error("Connection lost after stage")
            return state
        }
        override suspend fun confirm(request: HistoryBackupConfirm): HistoryBackupState {
            check(state.revision==request.expected_revision); check(state.staged?.backup_id==request.backup_id)
            check(state.desired_enabled); confirms++
            state=state.copy(revision=state.revision+1,active=state.staged!!.copy(verified_at="verified"),staged=null)
            if (loseConfirmReply) error("Connection lost after confirmation")
            return state
        }
        override suspend fun preference(request: HistoryBackupPreference): HistoryBackupState {
            check(state.revision==request.expected_revision)
            state=state.copy(revision=state.revision+1,desired_enabled=request.desired_enabled,
                staged=if(request.desired_enabled) state.staged else null)
            return state
        }
        override suspend fun delete(request: HistoryBackupDelete): HistoryBackupState {
            check(state.revision==request.expected_revision)
            state=state.copy(revision=state.revision+1,desired_enabled=false,active=null,staged=null)
            objects.clear(); return state
        }
    }
    private fun coordinator(remote: Remote, local: Local) = HistoryBackupCoordinator(server,owner,remote,local)
    private suspend fun fails(block: suspend () -> Unit) { assertTrue(runCatching { block() }.isFailure) }

    @Test fun `prepared full format binds media set to stage and restores complete copy`() = runBlocking {
        val remote = Remote(); val local = Local()
        local.prepare = { a -> a.copy(format=2,media=emptyList(),chats=a.messages.map {
            HistoryArchiveChat(it.chat,"saved",1000)
        }.distinctBy { it.id }) }
        coordinator(remote,local).backup(key)
        assertEquals(2,remote.lastStage!!.format_version)
        assertEquals(emptyList<String>(),remote.lastStage!!.media_ids)
        val receiver = Local(local.data.copy(messages=emptyList()))
        coordinator(remote,receiver).restore(key)
        assertEquals(2,receiver.data.format); assertEquals(local.data.messages,receiver.data.messages)
        assertFalse(receiver.data.chats!!.isEmpty())
    }
    @Test fun `media preparation may not alter messages or downgrade merged backup`() = runBlocking {
        val remote = Remote(); val local = Local()
        local.prepare = { it.copy(messages=emptyList()) }
        fails { coordinator(remote,local).backup(key) }
        assertEquals(0,remote.stages)
        val v2 = local.data.copy(format=2,media=emptyList(),chats=local.data.messages.map { HistoryArchiveChat(it.chat,"saved",1000) })
        remote.seed(v2); local.prepare = { it.withoutMedia() }
        fails { coordinator(remote,local).backup(key) }
        assertEquals(0,remote.stages)
    }

    @Test fun `readback confirms only verified upload and preserves caller key`() = runBlocking {
        val remote=Remote(); val local=Local()
        val result=coordinator(remote,local).backup(key)
        assertEquals(result,remote.state.active); assertEquals(1,remote.confirms); assertArrayEquals(ByteArray(32){it.toByte()},key)
        val empty=Local(local.data.copy(messages=emptyList()))
        coordinator(remote,empty).restore(key)
        assertEquals(local.data.messages,empty.data.messages); assertEquals(1,empty.imports)
    }
    @Test fun `empty new phone cannot erase previous history`() = runBlocking {
        val remote=Remote(); val old=archive(); remote.seed(old)
        val local=Local(old.copy(messages=emptyList()))
        coordinator(remote,local).backup(key)
        coordinator(remote,local).restore(key)
        assertEquals(old.messages,local.data.messages)
    }
    @Test fun `wrong key prevents replacing existing copy`() = runBlocking {
        val remote=Remote(); remote.seed(archive()); val previous=remote.state.active
        fails { coordinator(remote,Local()).backup(ByteArray(32){9}) }
        assertEquals(previous,remote.state.active); assertEquals(0,remote.stages)
    }
    @Test fun `unverified media or incomplete source prevents upload`() = runBlocking {
        val remote=Remote(); val local=Local(); local.ready=false
        fails { coordinator(remote,local).backup(key) }
        assertEquals(0,remote.stages)
    }
    @Test fun `corrupted readback never confirms or replaces old copy`() = runBlocking {
        val remote=Remote(); remote.seed(archive()); val previous=remote.state.active
        remote.onStage={remote.corrupt=true}
        fails { coordinator(remote,Local()).backup(key) }
        assertEquals(0,remote.confirms); assertEquals(previous,remote.state.active)
    }
    @Test fun `logout after download cannot import or confirm`() = runBlocking {
        val remote=Remote(); remote.seed(archive()); val local=Local()
        remote.onDownload={local.current=false}
        fails { coordinator(remote,local).restore(key) }
        assertEquals(0,local.imports); assertEquals(0,remote.confirms)
    }
    @Test fun `logout after stage leaves previous active copy intact`() = runBlocking {
        val remote=Remote(); remote.seed(archive()); val previous=remote.state.active; val local=Local()
        remote.onStage={local.current=false}
        fails { coordinator(remote,local).backup(key) }
        assertEquals(previous,remote.state.active); assertEquals(0,remote.confirms)
    }
    @Test fun `lost stage response is retryable without deleting active copy`() = runBlocking {
        val remote=Remote(); remote.seed(archive()); val previous=remote.state.active; val local=Local()
        remote.loseStageReply=true
        fails { coordinator(remote,local).backup(key) }
        assertEquals(previous,remote.state.active)
        remote.loseStageReply=false
        coordinator(remote,local).backup(key)
        assertEquals(1,remote.confirms)
    }
    @Test fun `lost confirm response preserves committed copy for retry`() = runBlocking {
        val remote=Remote(); val local=Local(); remote.loseConfirmReply=true
        fails { coordinator(remote,local).backup(key) }
        assertNotNull(remote.state.active)
        remote.loseConfirmReply=false
        coordinator(remote,local).backup(key)
        coordinator(remote,local).restore(key)
        assertEquals(1,local.data.messages.size)
    }
    @Test fun `revision change before local restore rejects stale snapshot`() = runBlocking {
        val remote=Remote(); remote.seed(archive()); val local=Local()
        remote.onStatus={if(remote.statuses==2) remote.state=remote.state.copy(revision=1)}
        fails { coordinator(remote,local).restore(key) }; assertEquals(0,local.imports)
    }
    @Test fun `cancellation before upload is propagated and no copy created`() = runBlocking {
        val remote=Remote(); remote.onStatus={throw CancellationException("cancel")}
        try { coordinator(remote,Local()).backup(key); fail() } catch (_: CancellationException) {}
        assertEquals(0,remote.stages)
    }
    @Test fun `disabled backups cannot be silently reenabled`() = runBlocking {
        val remote=Remote(); remote.state=remote.state.copy(desired_enabled=false)
        fails { coordinator(remote,Local()).backup(key) }; assertEquals(0,remote.stages)
    }
    @Test fun `local history change during upload prevents confirmation`() = runBlocking {
        val remote=Remote(); remote.seed(archive()); val previous=remote.state.active; val local=Local()
        remote.onStage={local.ready=false}
        fails { coordinator(remote,local).backup(key) }
        assertEquals(0,remote.confirms); assertEquals(previous,remote.state.active)
    }
    @Test fun `wrong server and malformed metadata fail before downloads`() = runBlocking {
        val remote=Remote(); remote.state=remote.state.copy(server_id=id())
        fails { coordinator(remote,Local()).backup(key) }; assertEquals(0,remote.stages)
    }
    @Test fun `disable preserves restoration while explicit delete stops uploads`() = runBlocking {
        val remote=Remote(); remote.seed(archive()); val previous=remote.state.active; val local=Local()
        val coordinator=coordinator(remote,local)
        coordinator.setEnabled(0,false)
        assertEquals(previous,remote.state.active)
        coordinator.restore(key); assertEquals(1,local.imports)
        fails { coordinator.backup(key) }
        coordinator.deleteConfirmed(1)
        assertNull(remote.state.active); assertFalse(remote.state.desired_enabled)
    }
    @Test fun `stale delete confirmation cannot erase newer copy`() = runBlocking {
        val remote=Remote(); val local=Local(); val coordinator=coordinator(remote,local)
        val revision=coordinator.status().revision
        coordinator.backup(key); val active=remote.state.active
        fails { coordinator.deleteConfirmed(revision) }
        assertEquals(active,remote.state.active)
    }
}
