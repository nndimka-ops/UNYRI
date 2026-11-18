package com.messenger.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.MessengerApp
import com.messenger.app.data.history.*
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.AuthUser
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryLocalStoreInstrumentedTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(java.util.Locale.ROOT,n)
    @Test fun confirmedAttachmentImportAndAckRepairPreserveDownloadState(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val db = Room.inMemoryDatabaseBuilder(app,MessengerDatabase::class.java).build()
        val gate = com.messenger.app.data.auth.SessionWriteGate { 1 }
        val auth = object : AuthRepository by app.authRepository {
            override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser(id(2),"test","Test")))
            override fun currentSessionEpoch() = 1
            override suspend fun <T> withSessionWrite(epochAtStart: Int,block:suspend () -> T): T? = gate.write(epochAtStart, block)
        }
        val base64 = java.util.Base64.getEncoder()
        val metadata = com.messenger.app.data.attachment.AttachmentMetadataDto(
            attachmentId=id(22),mediaType="file",objectKey="attachments/confirmed-fixture",size=3,encryptedSize=19,
            mimeType="application/zip",filename="fixture.zip",encryption=com.messenger.app.data.attachment.EncryptionInfoDto(
                version=1,key=base64.encodeToString(ByteArray(32)),nonce=base64.encodeToString(ByteArray(12)),
                sha256=base64.encodeToString(ByteArray(32))))
        val content = com.messenger.app.data.attachment.MessageContentCodec.encodeAttachment(metadata).toString(Charsets.UTF_8)
        val row = HistoryArchiveMessage(id(4),id(5),id(6),id(2),10,1000,content)
        try {
            HistoryLocalStore(db,auth).import(HistoryArchive(server=id(1),owner=id(2),snapshot=id(3),messages=listOf(row)),id(1),id(2),1)
            val imported = requireNotNull(db.attachmentDao().findByAttachmentId(id(22)))
            assertEquals("sent",imported.uploadState)
            assertEquals("not_downloaded",imported.downloadState)
            assertNull(imported.localPath)
            val downloading = imported.copy(uploadState="uploaded",downloadState="downloading",progress=42,localPath="/owned/preview")
            db.attachmentDao().upsert(downloading)
            assertEquals(1,db.attachmentDao().markUploadSent(id(22),row.client,row.chat))
            assertEquals(downloading.copy(uploadState="sent"),db.attachmentDao().findByAttachmentId(id(22)))
            assertEquals(0,db.attachmentDao().markUploadSent(id(22),row.client,row.chat))
            val cancelled = downloading.copy(uploadState="cancelled")
            db.attachmentDao().upsert(cancelled)
            assertEquals(0,db.attachmentDao().markUploadSent(id(22),row.client,row.chat))
            assertEquals(cancelled,db.attachmentDao().findByAttachmentId(id(22)))
        } finally { db.close() }
    }
    @Test fun sequenceCollisionPreservesUnreadableRowAndRetryEnvelope(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val db = Room.inMemoryDatabaseBuilder(app,MessengerDatabase::class.java).build()
        val gate = com.messenger.app.data.auth.SessionWriteGate { 1 }
        val auth = object : AuthRepository by app.authRepository {
            override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser(id(2),"test","Test")))
            override fun currentSessionEpoch() = 1
            override suspend fun <T> withSessionWrite(epochAtStart: Int,block:suspend () -> T): T? = gate.write(epochAtStart, block)
        }
        val native = HistoryLocalStore(db,auth)
        val original = MessageEntity(id(40),id(41),id(6),id(7),"unreadable",10,"1970-01-01T00:00:01Z","decrypt_failed")
        val envelope = com.messenger.app.data.local.message.PendingDecryptEntity(
            original.clientMessageId,requireNotNull(original.serverId),original.chatId,original.senderId,id(42),
            requireNotNull(original.sequence),original.createdAt,1,"retained encrypted envelope","content",null,
            retryCount=3,lastErrorClass="fixture-decrypt-failure")
        val replacement = HistoryArchiveMessage(id(43),id(44),original.chatId,id(2),10,1000,"{\"kind\":\"text\",\"text\":\"conflicting identity\"}")
        val earlier = replacement.copy(client=id(45),id=id(46),sequence=5)
        val archive = HistoryArchive(server=id(1),owner=id(2),snapshot=id(3),messages=listOf(earlier,replacement))
        try {
            db.messageDao().upsertDecryptFailed(original,envelope)
            var beforeCommitCalled = false
            val result = runCatching {
                native.import(archive,id(1),id(2),1,beforeCommit={beforeCommitCalled=true})
            }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertFalse(beforeCommitCalled)
            assertEquals(original,db.messageDao().findByClientMessageId(original.clientMessageId))
            assertEquals(listOf(envelope),db.messageDao().pendingDecryptsOrdered())
            assertNull(db.messageDao().findByClientMessageId(replacement.client))
            assertNull(db.messageDao().findByClientMessageId(earlier.client))
            assertNull(db.messageDao().historyCutoff())
        } finally { db.close() }
    }

    @Test fun fileProjectionAndRoomFailuresRollBackTogether(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val db = Room.inMemoryDatabaseBuilder(app,MessengerDatabase::class.java).build()
        val gate = com.messenger.app.data.auth.SessionWriteGate { 1 }
        val auth = object : AuthRepository by app.authRepository {
            override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser(id(2),"test","Test")))
            override fun currentSessionEpoch() = 1
            override suspend fun <T> withSessionWrite(epochAtStart: Int,block:suspend () -> T): T? = gate.write(epochAtStart, block)
        }
        val directory = java.nio.file.Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "history-room-rollback-").toFile()
        val native = HistoryLocalStore(db,auth)
        val row = HistoryArchiveMessage(id(4),id(5),id(6),id(2),10,1000,"{\"kind\":\"text\",\"text\":\"saved\"}")
        val archive = HistoryArchive(server=id(1),owner=id(2),snapshot=id(3),messages=listOf(row))
        try {
            native.import(archive,id(1),id(2),1)
            val extra = row.copy(client=id(7),id=id(8),sequence=30)
            val incoming = archive.copy(messages=listOf(extra))
            val calls = java.io.File(directory,"calls.json").apply { writeText("old calls") }
            val staged = HistoryRestoreFileTransaction(calls,"new calls".toByteArray())
            assertTrue(runCatching {
                staged.apply { commit ->
                    native.import(incoming,id(1),id(2),1,beforeCommit={
                        commit()
                        db.openHelper.writableDatabase.execSQL("INSERT INTO missing_restore_table VALUES (1)")
                    })
                }
            }.isFailure)
            assertEquals("old calls",calls.readText())
            assertNull(db.messageDao().findByClientMessageId(extra.client))
            assertEquals("saved",db.messageDao().findByClientMessageId(row.client)?.text)

            val blocked = java.io.File(directory,"blocked.json")
            val diskFailure = HistoryRestoreFileTransaction(blocked,"new calls".toByteArray())
            check(blocked.mkdir()); java.io.File(blocked,"blocker").writeText("untouched")
            assertTrue(runCatching {
                diskFailure.apply { commit -> native.import(incoming,id(1),id(2),1,beforeCommit=commit) }
            }.isFailure)
            assertNull(db.messageDao().findByClientMessageId(extra.client))
            assertEquals("old calls",calls.readText())
            assertEquals("untouched",java.io.File(blocked,"blocker").readText())

            HistoryRestoreFileTransaction(calls,"new calls".toByteArray()).apply { commit ->
                native.import(incoming,id(1),id(2),1,beforeCommit=commit)
            }
            native.import(incoming,id(1),id(2),1)
            assertEquals("new calls",calls.readText())
            assertEquals(listOf(10L,30L),db.messageDao().historyArchivePage(0,"").map { it.sequence })
        } finally { db.close(); directory.deleteRecursively() }
    }

    @Test fun transactionalRestoreAndAccountFence(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MessengerApp>()
        val db = Room.inMemoryDatabaseBuilder(app,MessengerDatabase::class.java).build()
        var checks = 0; var invalidateAt = Int.MAX_VALUE
        val auth = object : AuthRepository by app.authRepository {
            override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser(id(2),"test","Test")))
            override suspend fun currentAccessToken(): String? = error("History import must be local")
            override fun currentSessionEpoch(): Int = if (++checks >= invalidateAt) 2 else 1
            override suspend fun <T> withSessionWrite(epochAtStart: Int,block:suspend () -> T): T? = block()
        }
        val native = HistoryLocalStore(db,auth)
        val row = HistoryArchiveMessage(id(4),id(5),id(6),id(2),10,1000,"{\"kind\":\"text\",\"text\":\"saved\"}")
        val archive = HistoryArchive(server=id(1),owner=id(2),snapshot=id(3),messages=listOf(row))
        try {
            db.messageDao().upsert(MessageEntity(row.client,row.id,row.chat,row.sender,"unreadable",10,"1970-01-01T00:00:01Z","decrypt_failed"))
            native.import(archive,id(1),id(2),1)
            native.import(archive,id(1),id(2),1)
            val complete = archive.copy(format=2, media=emptyList(), chats=listOf(HistoryArchiveChat(row.chat,"direct",1000,
                HistoryArchivePeer(id(50),"other","Other"))))
            native.import(complete,id(1),id(2),1)
            assertEquals("direct",db.chatDao().cachedChat(row.chat)?.type)
            assertEquals("Other",db.chatDao().cachedParticipant(id(50))?.displayName)
            assertEquals("saved",db.messageDao().findByClientMessageId(row.client)?.text)
            assertEquals(1,db.messageDao().historyArchivePage(0,"").size)
            db.messageDao().applyEdit(row.id,"newer",12)
            native.import(archive,id(1),id(2),1)
            assertEquals("newer",db.messageDao().findByClientMessageId(row.client)?.text)
            checks=0; invalidateAt=2
            val extra = row.copy(client=id(7),id=id(8),sequence=30)
            assertTrue(runCatching { native.import(archive.copy(messages=listOf(extra)),id(1),id(2),1) }.isFailure)
            assertNull(db.messageDao().findByClientMessageId(extra.client))
            invalidateAt=Int.MAX_VALUE; checks=0
            db.messageDao().applyDelete(row.id,20)
            native.import(archive,id(1),id(2),1)
            assertEquals(20L,db.messageDao().findByClientMessageId(row.client)?.deleteSequence)
            assertNull(native.export(id(1),id(2),id(3),1).messages.single().content)
            val pending = MessageEntity(id(20),null,row.chat,row.sender,"unsent",null,"1970-01-01T00:00:02Z","pending",
                retryCount=3,nextRetryAtEpochMs=100000,lastErrorClass="network",payloadText="frozen",pendingEditText="newer",sendAttempted=true)
            val failed = pending.copy(clientMessageId=id(21),text="failed",status="failed")
            db.messageDao().upsert(pending); db.messageDao().upsert(failed)
            val attachment = com.messenger.app.data.local.attachment.AttachmentEntity(id(22),pending.clientMessageId,row.chat,"file","text/plain","draft.txt",3,null,null,null,null,"/private/draft", "not_downloaded","uploading",25,"fixture-object",31,1,"fixture-key","fixture-nonce",null,"fixture-hash")
            db.attachmentDao().upsert(attachment)
            db.messageDao().saveMediaJob(row.chat,"upload:${pending.clientMessageId}","pending-upload-fixture")
            native.import(archive,id(1),id(2),1)
            assertEquals(pending,db.messageDao().findByClientMessageId(pending.clientMessageId))
            assertEquals(failed,db.messageDao().findByClientMessageId(failed.clientMessageId))
            assertEquals(attachment,db.attachmentDao().findByAttachmentId(attachment.attachmentId))
            assertEquals("pending-upload-fixture",db.workspaceDao().get(row.chat,"upload:${pending.clientMessageId}"))
            // Export remains strict while ordinary sends are unsettled.
            assertTrue(runCatching { native.export(id(1),id(2),id(3),1) }.isFailure)
            val collision = row.copy(client=pending.clientMessageId,id=id(23),sequence=40)
            assertTrue(runCatching { native.import(archive.copy(messages=listOf(collision)),id(1),id(2),1) }.isFailure)
            assertEquals(pending,db.messageDao().findByClientMessageId(pending.clientMessageId))
            // An unresolved optimistic edit can change an imported row, so it
            // still blocks restoration until the normal outbox settles it.
            val lifecycle = pending.copy(clientMessageId=id(24),kind="edit",targetMessageId=row.id)
            db.messageDao().upsert(lifecycle)
            assertTrue(runCatching { native.import(archive,id(1),id(2),1) }.isFailure)
            assertEquals(lifecycle,db.messageDao().findByClientMessageId(lifecycle.clientMessageId))
            db.messageDao().hideLocally(lifecycle.clientMessageId)
            native.import(archive,id(1),id(2),1)
        } finally { db.close() }
    }
}
