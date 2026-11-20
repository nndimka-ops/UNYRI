package com.messenger.app.data.history

import com.messenger.app.data.sync.FakeAuthRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.Base64
import java.security.MessageDigest

class HistoryKeySettingsSessionTest {
    private val server="11111111-1111-4111-8111-111111111111"
    private val owner="22222222-2222-4222-8222-222222222222"
    private val key=ByteArray(32){it.toByte()}
    private inner class Remote : HistoryBackupTransport, HistoryTransferTransport {
        var allowUploads=false
        var loseAckReply=true
        var statuses=0
        var creates=0
        var beforeStatus: suspend ()->Unit = {}
        var beforeCreate: suspend ()->Unit = {}
        var transferRecord:HistoryTransferRecord?=null
        override suspend fun createTransfer(publicKey:String):HistoryTransferRecord {
            creates++
            beforeCreate()
            return HistoryTransferRecord(UUID.randomUUID().toString(),state.server_id,owner,UUID.randomUUID().toString(),publicKey,
                System.currentTimeMillis()+600_000,"waiting").also { transferRecord=it }
        }
        override suspend fun transfer(id:String)=requireNotNull(transferRecord)
        override suspend fun publishTransfer(id:String,body:HistoryTransferPublish):HistoryTransferRecord {
            check(allowUploads && state.revision==body.expected_revision && state.active?.backup_id==body.backup_id)
            return requireNotNull(transferRecord).copy(status="ready",sender_public_key=body.sender_public_key,
                backup_id=body.backup_id,ciphertext=body.ciphertext).also { transferRecord=it }
        }
        override suspend fun ackTransfer(id:String,backup:String):HistoryTransferRecord {
            transferRecord=requireNotNull(transferRecord).copy(status="consumed",ciphertext=null)
            if(loseAckReply)error("ACK response lost")
            return requireNotNull(transferRecord)
        }
        override suspend fun cancelTransfer(id:String)=Unit
        var state=HistoryBackupState(server,0,true)
        var obj:HistoryBackupObject?=null
        override suspend fun status():HistoryBackupState {
            statuses++; beforeStatus(); return state
        }
        override suspend fun download(id:String)=requireNotNull(obj)
        override suspend fun stage(request:HistoryBackupUpload):HistoryBackupState {
            check(allowUploads && request.expected_revision==state.revision)
            obj=HistoryBackupObject(request.backup_id,request.format_version,request.sha256,request.ciphertext)
            state=state.copy(revision=state.revision+1,staged=HistoryBackupInfo(request.backup_id,request.format_version,
                request.sha256,Base64.getDecoder().decode(request.ciphertext).size,"prepared"))
            return state
        }
        override suspend fun confirm(request:HistoryBackupConfirm):HistoryBackupState {
            check(allowUploads && request.expected_revision==state.revision && request.backup_id==state.staged?.backup_id)
            state=state.copy(revision=state.revision+1,active=requireNotNull(state.staged).copy(verified_at="verified"),staged=null)
            return state
        }
        override suspend fun preference(request:HistoryBackupPreference):HistoryBackupState=error("Unused")
        override suspend fun delete(request:HistoryBackupDelete):HistoryBackupState=error("Unused")
        fun seed() {
            val id=UUID.randomUUID().toString()
            val archive=HistoryArchive(server=server,owner=owner,snapshot=id,messages=emptyList())
            val packet=HistoryBackupCrypto.encrypt(HistoryArchive.encode(archive),key,
                HistoryBackupCrypto.Context(UUID.fromString(server),UUID.fromString(owner),UUID.fromString(id)))
            val hash=MessageDigest.getInstance("SHA-256").digest(packet).joinToString(""){"%02x".format(it.toInt() and 255)}
            obj=HistoryBackupObject(id,1,hash,Base64.getEncoder().encodeToString(packet))
            state=state.copy(active=HistoryBackupInfo(id,1,hash,packet.size,"date","verified"))
        }
    }
    private class Storage : HistorySecretStorage {
        val rows=mutableMapOf<String,ByteArray>()
        override fun read(name:String)=rows[name]?.copyOf()
        override fun write(name:String,value:ByteArray){rows[name]=value.copyOf()}
        override fun compareAndSet(name:String,expected:ByteArray?,value:ByteArray):Boolean {
            if(!(rows[name]?.contentEquals(expected ?: byteArrayOf()) ?: (expected==null)))return false
            rows[name]=value.copyOf();return true
        }
    }
    @Test fun `direct source entry prepares confirmed history without opening backup settings first`() = runBlocking {
        val remote=Remote().apply { allowUploads=true }
        val storage=Storage()
        HistoryRecoveryKeys(storage,server,owner).saveConfirmed(key)
        val local=object:HistoryBackupLocal {
            override suspend fun assertCurrent()=Unit
            override suspend fun export(snapshot:String)=HistoryArchive(server=server,owner=owner,snapshot=snapshot,messages=emptyList())
            override suspend fun verifyBackupReady(archive:HistoryArchive)=Unit
            override suspend fun restoreVerified(archive:HistoryArchive)=Unit
        }
        val session=HistoryKeySettingsSession(FakeAuthRepository(owner),remote,local){storage}
        // The real Settings -> Transfer entry constructs a fresh session and
        // invokes this operation directly; there is no preceding status().
        assertTrue(session.prepareTransfer()>0)
        assertNotNull(remote.state.active?.verified_at)
        assertArrayEquals(key,storage.rows.values.single())
    }
    @Test fun `direct receiver entry returns valid QR without creating invisible request`() = runBlocking {
        val remote=Remote();val storage=Storage()
        val session=HistoryKeySettingsSession(FakeAuthRepository(owner),remote){storage}
        val secret=HistoryTransferCrypto.privateKey()
        try {
            val public=HistoryTransferCrypto.public(secret)
            val created=session.createTransfer(public)
            created.qr().validate(server,owner)
            assertEquals("waiting",created.status)
            assertEquals(public,created.recipient_public_key)
            assertEquals(1,remote.creates)
            assertTrue(remote.statuses>0)
            assertTrue(storage.rows.isEmpty())
        } finally { secret.fill(0) }
    }
    @Test fun `failed status validation cannot create a request or poison retry namespace`() = runBlocking {
        val remote=Remote().apply { state=state.copy(revision=-1) };val storage=Storage()
        val session=HistoryKeySettingsSession(FakeAuthRepository(owner),remote){storage}
        val secret=HistoryTransferCrypto.privateKey()
        try {
            val public=HistoryTransferCrypto.public(secret)
            assertTrue(runCatching { session.createTransfer(public) }.isFailure)
            assertEquals(0,remote.creates);assertNull(remote.transferRecord);assertTrue(storage.rows.isEmpty())
            val correctedServer=UUID.randomUUID().toString()
            remote.state=HistoryBackupState(correctedServer,0,true)
            val created=session.createTransfer(public)
            created.qr().validate(correctedServer,owner)
            assertEquals("$correctedServer:$owner",session.scopeId())
            assertEquals(1,remote.creates);assertTrue(storage.rows.isEmpty())
        } finally { secret.fill(0) }
    }
    @Test fun `failed confirmation status read leaves initialization retryable before any POST`() = runBlocking {
        val remote=Remote();val storage=Storage()
        remote.beforeStatus={if(remote.statuses==2)throw java.io.IOException("Synthetic status unavailable")}
        val session=HistoryKeySettingsSession(FakeAuthRepository(owner),remote){storage}
        val secret=HistoryTransferCrypto.privateKey()
        try {
            val public=HistoryTransferCrypto.public(secret)
            assertTrue(runCatching { session.createTransfer(public) }.exceptionOrNull() is java.io.IOException)
            assertEquals(0,remote.creates);assertNull(remote.transferRecord)
            remote.beforeStatus={};val correctedServer=UUID.randomUUID().toString()
            remote.state=HistoryBackupState(correctedServer,0,true)
            session.createTransfer(public).qr().validate(correctedServer,owner)
            assertEquals(1,remote.creates);assertTrue(storage.rows.isEmpty())
        } finally { secret.fill(0) }
    }
    @Test fun `concurrent first status and transfer share one complete initialization`() = runBlocking {
        withTimeout(5000) {
            val entered=kotlinx.coroutines.CompletableDeferred<Unit>()
            val release=kotlinx.coroutines.CompletableDeferred<Unit>()
            val remote=Remote();val storage=Storage()
            remote.beforeStatus={if(remote.statuses==1){entered.complete(Unit);release.await()}}
            val session=HistoryKeySettingsSession(FakeAuthRepository(owner),remote){storage}
            val secret=HistoryTransferCrypto.privateKey()
            try {
                val public=HistoryTransferCrypto.public(secret)
                val loading=async { session.status() }
                entered.await()
                val creating=async { session.createTransfer(public) }
                kotlinx.coroutines.yield()
                assertEquals(1,remote.statuses);assertEquals(0,remote.creates)
                release.complete(Unit)
                assertFalse(loading.await().keySaved)
                creating.await().qr().validate(server,owner)
                assertEquals(2,remote.statuses);assertEquals(1,remote.creates)
                assertTrue(storage.rows.isEmpty())
            } finally { release.complete(Unit);secret.fill(0) }
        }
    }
    @Test fun `account epoch change during initialization rejects POST and key writes`() = runBlocking {
        val auth=FakeAuthRepository(owner);val remote=Remote();val storage=Storage()
        remote.beforeStatus={auth.switchSession(owner)}
        val session=HistoryKeySettingsSession(auth,remote){storage}
        val secret=HistoryTransferCrypto.privateKey()
        try {
            val public=HistoryTransferCrypto.public(secret)
            assertTrue(runCatching { session.createTransfer(public) }.isFailure)
            assertEquals(1,remote.statuses);assertEquals(0,remote.creates);assertNull(remote.transferRecord)
            assertTrue(storage.rows.isEmpty())
            assertTrue(runCatching { session.createTransfer(public) }.isFailure)
            assertEquals(1,remote.statuses)
        } finally { secret.fill(0) }
    }
    @Test fun `cancelled late status response cannot publish cache or create transfer`() = runBlocking {
        withTimeout(5000) {
            val entered=kotlinx.coroutines.CompletableDeferred<Unit>()
            val release=kotlinx.coroutines.CompletableDeferred<Unit>()
            val remote=Remote();val storage=Storage()
            remote.beforeStatus={if(remote.statuses==1)kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable){entered.complete(Unit);release.await()}}
            val session=HistoryKeySettingsSession(FakeAuthRepository(owner),remote){storage}
            val secret=HistoryTransferCrypto.privateKey()
            try {
                val public=HistoryTransferCrypto.public(secret)
                val cancelled=launch { runCatching { session.createTransfer(public) } }
                entered.await();cancelled.cancel();release.complete(Unit);cancelled.join()
                assertEquals(0,remote.creates);assertNull(remote.transferRecord);assertTrue(storage.rows.isEmpty())
                remote.beforeStatus={}
                session.createTransfer(public).qr().validate(server,owner)
                assertEquals(3,remote.statuses);assertEquals(1,remote.creates)
            } finally { release.complete(Unit);secret.fill(0) }
        }
    }
    @Test fun `account change after transfer response never exposes QR to successor session`() = runBlocking {
        val auth=FakeAuthRepository(owner);val remote=Remote();val storage=Storage()
        remote.beforeCreate={auth.switchSession(owner)}
        val session=HistoryKeySettingsSession(auth,remote){storage}
        val secret=HistoryTransferCrypto.privateKey()
        try {
            assertTrue(runCatching { session.createTransfer(HistoryTransferCrypto.public(secret)) }.isFailure)
            assertEquals(1,remote.creates);assertTrue(storage.rows.isEmpty())
        } finally { secret.fill(0) }
    }
    @Test fun `malformed recipient key cannot create a waiting transfer`() = runBlocking {
        val remote=Remote();val storage=Storage()
        val session=HistoryKeySettingsSession(FakeAuthRepository(owner),remote){storage}
        assertTrue(runCatching { session.createTransfer("not-a-public-key") }.isFailure)
        assertEquals(0,remote.creates);assertNull(remote.transferRecord);assertTrue(storage.rows.isEmpty())
    }
    @Test fun `first confirmed source backup transfers real encrypted messages reactions and calls to second same-account device`() = runBlocking {
        val peer=HistoryArchivePeer(UUID.randomUUID().toString(),"synthetic_peer","Synthetic peer","123456789","public_peer")
        val chat=UUID.randomUUID().toString();val message=UUID.randomUUID().toString()
        val source=HistoryArchive(format=2,server=server,owner=owner,snapshot=UUID.randomUUID().toString(),
            messages=listOf(HistoryArchiveMessage(UUID.randomUUID().toString(),message,chat,owner,1,1000,"{\"text\":\"transfer fixture\"}")),
            reactions=listOf(HistoryArchiveReaction(message,peer.id,2,"👍")),
            chats=listOf(HistoryArchiveChat(chat,"direct",500,peer)),media=emptyList(),
            calls=listOf(HistoryArchiveCall(UUID.randomUUID().toString(),owner,peer.id,"video",1100,
                endedAtMs=1900,endReason="rejected",chat=chat,peer=peer)))
        class Local(var archive:HistoryArchive):HistoryBackupLocal {
            var restored=0
            override suspend fun assertCurrent()=Unit
            override suspend fun export(snapshot:String)=archive.copy(snapshot=snapshot)
            override suspend fun verifyBackupReady(archive:HistoryArchive)=Unit
            override suspend fun restoreVerified(archive:HistoryArchive) { this.archive=archive;restored++ }
        }
        val remote=Remote().apply {allowUploads=true;loseAckReply=false}
        val oldStorage=Storage();val oldLocal=Local(source)
        val old=HistoryKeySettingsSession(FakeAuthRepository(owner),remote,oldLocal){oldStorage}
        old.status();old.saveNewKey(key,0);old.prepareTransfer()
        assertNotNull(remote.state.active?.verified_at);assertNull(remote.state.staged)
        val packet=Base64.getDecoder().decode(remote.obj!!.ciphertext)
        val plain=HistoryBackupCrypto.decrypt(packet,key,HistoryBackupCrypto.Context(
            UUID.fromString(server),UUID.fromString(owner),UUID.fromString(remote.state.active!!.backup_id)))
        try {
            val text=plain.toString(Charsets.UTF_8)
            assertFalse(text.contains("synthetic_peer"))
            assertTrue(text.contains("public_peer"));assertTrue(text.contains("123456789"))
        } finally { plain.fill(0) }
        val newStorage=Storage();val newLocal=Local(source.copy(messages=emptyList(),reactions=emptyList(),calls=emptyList(),chats=emptyList()))
        val fresh=HistoryKeySettingsSession(FakeAuthRepository(owner),remote,newLocal){newStorage}
        fresh.status()
        val secret=HistoryTransferCrypto.privateKey()
        val waiting=fresh.createTransfer(HistoryTransferCrypto.public(secret))
        val qr=old.validateQR(waiting.qr().text())
        val sent=old.approveTransfer(qr);val received=fresh.readTransfer(qr)
        assertEquals(HistoryTransferCrypto.comparison(qr,sent),HistoryTransferCrypto.comparison(qr,received))
        var adopted=false
        fresh.acceptTransfer(qr,secret,received,true){adopted=true}
        assertTrue(adopted);assertTrue(secret.all { it==0.toByte() });assertEquals("consumed",remote.transferRecord!!.status)
        fresh.restore()
        assertEquals(1,newLocal.restored)
        assertEquals(source.messages,newLocal.archive.messages)
        assertEquals(source.reactions,newLocal.archive.reactions)
        val publicSource=source.withoutPeerLogins()
        assertEquals(publicSource.calls,newLocal.archive.calls)
        assertEquals(publicSource.chats,newLocal.archive.chats)
        assertArrayEquals(key,newStorage.rows.values.single())
        assertEquals(source.messages,oldLocal.archive.messages)
        assertEquals("synthetic_peer",oldLocal.archive.chats!!.single().peer!!.username)
    }

    @Test fun `source preparation names disabled upload prerequisite without uploading`() = runBlocking {
        val remote=Remote();val storage=Storage()
        val session=HistoryKeySettingsSession(FakeAuthRepository(owner),remote){storage}
        // A real native local source is required even after key setup; no protocol
        // request or key generation is allowed just because the source button was tapped.
        remote.state=remote.state.copy(desired_enabled=false)
        assertTrue(runCatching{session.prepareTransfer()}.exceptionOrNull() is HistoryUploadsDisabled)
        assertTrue(storage.rows.isEmpty());assertNull(remote.obj);assertNull(remote.transferRecord)
    }
    @Test fun `source without recovery key cannot create an invisible replacement key or upload`() = runBlocking {
        val remote=Remote();val storage=Storage()
        val local=object:HistoryBackupLocal {
            override suspend fun assertCurrent()=Unit
            override suspend fun export(snapshot:String):HistoryArchive=error("Key must be checked before export")
            override suspend fun verifyBackupReady(archive:HistoryArchive)=Unit
            override suspend fun restoreVerified(archive:HistoryArchive)=Unit
        }
        val session=HistoryKeySettingsSession(FakeAuthRepository(owner),remote,local){storage}
        assertTrue(runCatching{session.prepareTransfer()}.exceptionOrNull() is HistoryRecoveryKeyMissing)
        assertTrue(storage.rows.isEmpty());assertNull(remote.obj);assertNull(remote.transferRecord)
    }
    @Test fun `waiting automatic backup rechecks eligibility after manual operation releases lock`() = runBlocking {
        val entered=kotlinx.coroutines.CompletableDeferred<Unit>()
        val release=kotlinx.coroutines.CompletableDeferred<Unit>()
        var due=true
        val local=object:HistoryBackupLocal {
            override suspend fun assertCurrent()=Unit
            override suspend fun export(snapshot:String):HistoryArchive { entered.complete(Unit);release.await();error("Stop after source admission") }
            override suspend fun verifyBackupReady(archive:HistoryArchive)=Unit
            override suspend fun restoreVerified(archive:HistoryArchive)=Unit
        }
        val remote=Remote();val storage=Storage();val session=HistoryKeySettingsSession(FakeAuthRepository(owner),remote,local){storage}
        session.status();session.saveNewKey(key,0)
        val first=async { runCatching{session.backup()} }
        entered.await()
        val queued=async { runCatching{session.backup(beforeStart={due})} }
        kotlinx.coroutines.yield();due=false;release.complete(Unit)
        first.await()
        assertTrue(queued.await().exceptionOrNull() is HistoryAutoNotDue)
    }
    @Test fun `paper key setup persists without creating or importing a backup`() = runBlocking {
        val remote=Remote(); val storage=Storage(); val s=HistoryKeySettingsSession(FakeAuthRepository(owner),remote){storage}
        assertFalse(s.status().keySaved); s.saveNewKey(key,0)
        assertTrue(s.status().keySaved); assertNull(remote.state.active)
    }
    @Test fun `account change before durable write is rejected`() = runBlocking {
        val auth=FakeAuthRepository(owner); val remote=Remote(); val storage=Storage(); val s=HistoryKeySettingsSession(auth,remote){storage}
        s.status(); auth.switchEpochOnWrite=true
        assertTrue(runCatching{s.saveNewKey(key,0)}.isFailure); assertTrue(storage.rows.isEmpty())
    }
    @Test fun `new remote copy or revision invalidates draft confirmation`() = runBlocking {
        val remote=Remote(); val storage=Storage(); val s=HistoryKeySettingsSession(FakeAuthRepository(owner),remote){storage}
        s.status(); remote.seed()
        assertTrue(runCatching{s.saveNewKey(key,0)}.isFailure); assertTrue(storage.rows.isEmpty())
    }
    @Test fun `existing key must decrypt before it is stored`() = runBlocking {
        val remote=Remote(); remote.seed(); val storage=Storage(); val s=HistoryKeySettingsSession(FakeAuthRepository(owner),remote){storage}
        s.status()
        assertTrue(runCatching{s.saveExistingKey(ByteArray(32){9})}.isFailure); assertTrue(storage.rows.isEmpty())
        s.saveExistingKey(key); assertTrue(s.status().keySaved)
    }
    @Test fun `changed server cannot reuse original key namespace`() = runBlocking {
        val remote=Remote(); val storage=Storage(); val s=HistoryKeySettingsSession(FakeAuthRepository(owner),remote){storage}
        s.status(); remote.state=remote.state.copy(server_id=UUID.randomUUID().toString())
        assertTrue(runCatching{s.saveNewKey(key,0)}.isFailure); assertTrue(storage.rows.isEmpty())
    }
    @Test fun `replacement verifies current backup before replacing old saved key`() = runBlocking {
        val remote=Remote();val storage=Storage();val s=HistoryKeySettingsSession(FakeAuthRepository(owner),remote){storage}
        val old=ByteArray(32){9};s.status();s.saveNewKey(old,0);remote.seed()
        assertTrue(runCatching{s.replaceExistingKey(ByteArray(32){8})}.isFailure)
        assertArrayEquals(old,storage.rows.values.single())
        s.replaceExistingKey(key,remote.state.active!!.backup_id)
        assertArrayEquals(key,storage.rows.values.single())
    }
    @Test fun `lost ack response still exposes verified durable adoption for restore retry`() = runBlocking {
        val remote=Remote();remote.seed();val storage=Storage();val s=HistoryKeySettingsSession(FakeAuthRepository(owner),remote){storage};s.status()
        val recipient=HistoryTransferCrypto.privateKey();val sender=HistoryTransferCrypto.privateKey()
        val qr=HistoryTransferQR(server=server,owner=owner,transfer=UUID.randomUUID().toString(),recipient=HistoryTransferCrypto.public(recipient),expires=System.currentTimeMillis()+60_000)
        val backup=remote.state.active!!.backup_id
        val record=HistoryTransferRecord(qr.transfer,server,owner,owner,qr.recipient,qr.expires,"ready",HistoryTransferCrypto.public(sender),backup,HistoryTransferCrypto.encrypt(key,sender,qr,backup))
        remote.transferRecord=record;var adopted=false
        assertTrue(runCatching{s.acceptTransfer(qr,recipient,record,false){adopted=true}}.isFailure)
        assertFalse(adopted);assertTrue(storage.rows.isEmpty())
        assertTrue(runCatching{s.acceptTransfer(qr,recipient,record,true){adopted=true}}.isFailure)
        assertTrue(adopted);assertArrayEquals(key,storage.rows.values.single());assertTrue(recipient.all{it==0.toByte()})
        assertEquals("consumed",remote.transferRecord!!.status)
    }
}
