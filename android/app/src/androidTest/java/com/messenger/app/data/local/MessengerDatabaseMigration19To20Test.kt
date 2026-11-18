package com.messenger.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.conference.ConferenceInvitation
import com.messenger.app.conference.ConferenceInvite
import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.history.HistoryLocalStore
import com.messenger.app.data.history.HistoryArchive
import com.messenger.app.data.history.HistoryArchiveMessage
import com.messenger.app.data.local.chat.ChatWorkspaceValue
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.AuthUser
import com.messenger.app.domain.repository.AuthRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real SQLite + generated Room validation, without any phone account or server.
 * The frozen v19 DDL below comes from the shipped pre-v2 APK (SHA256 253b77bd...
 * fa5e159eb), rather than deriving a "legacy" schema from the new implementation.
 */
@RunWith(AndroidJUnit4::class)
class MessengerDatabaseMigration19To20Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()
    private val opened = mutableListOf<MessengerDatabase>()

    @After fun cleanOwnedDatabases() {
        opened.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    @Test fun fullVersion19SchemaAndEveryExistingRowSurviveUpgrade() {
        val name = ownedName()
        val helper = legacy19(name)
        val before = helper.writableDatabase.let { db ->
            seedLegacyRows(db)
            tables.associateWith { snapshot(db, it) }
        }
        helper.close()

        val migrated = open20(name)
        // Opening through Room executes the actual migration and validates ALL
        // ten entity tables/indices against the generated current Room schema.
        val db = migrated.openHelper.writableDatabase
        assertEquals(21, db.version)
        db.query("SELECT count(*) FROM attachments WHERE videoNote != 0").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        tables.forEach { table -> assertEquals("Preserve $table", before.getValue(table), snapshot(db, table)) }
        db.query("SELECT count(*),sum(conferenceInviteJson IS NOT NULL) FROM messages").use {
            assertTrue(it.moveToFirst())
            assertEquals(3, it.getInt(0))
            assertEquals(0, it.getInt(1))
        }
        db.query("SELECT payloadText,pendingEditText,pendingEditRevision,sendAttempted,retryCount FROM messages WHERE clientMessageId='pending'").use {
            assertTrue(it.moveToFirst())
            assertEquals("frozen original", it.getString(0))
            assertEquals("newer unsent edit", it.getString(1))
            assertEquals(7, it.getInt(2))
            assertEquals(1, it.getInt(3))
            assertEquals(4, it.getInt(4))
        }
        migrated.close()
        val reopened = open20(name).openHelper.writableDatabase
        tables.forEach { table -> assertEquals("Reopen $table", before.getValue(table), snapshot(reopened, table)) }
    }

    @Test fun invitationSurvivesDaoReopenAndActualHistoryExportImportAndPurge() = runBlocking {
        val name = ownedName()
        legacy19(name).closeAfterOpening()
        var source = open20(name)
        val origin = "https://conference-fixture.example"
        // Archive.server is the server identity UUID, not its HTTPS origin.
        val serverIdentity = UUID.randomUUID().toString()
        val owner = UUID.randomUUID().toString()
        val chat = UUID.randomUUID().toString()
        val client = UUID.randomUUID().toString()
        val serverMessage = UUID.randomUUID().toString()
        val capability = ConferenceInvite.fresh(origin)
        val invitation = ConferenceInvitation(
            id = UUID.randomUUID().toString(), title = "Native fixture room", server = origin,
            roomId = capability.roomId, invite = capability.link(), expiresAt = 1793289600L,
        ).validated()
        source.chatDao().upsertChatMetadata(chat, "direct", "2026-10-29T10:00:00Z", null)
        source.messageDao().upsert(MessageEntity(
            clientMessageId = client, serverId = serverMessage, chatId = chat, senderId = owner,
            text = ConferenceInvitation.FALLBACK, sequence = 1, createdAt = "2026-10-29T10:00:00Z",
            status = "read", conferenceInviteJson = invitation.stored(),
        ))
        source.workspaceDao().put(ChatWorkspaceValue(chat, "conference_declined:$client", "1"))
        source.close()
        source = open20(name)
        assertEquals(invitation, ConferenceInvitation.decodeStored(source.messageDao().findByClientMessageId(client)?.conferenceInviteJson))
        assertEquals("1", source.workspaceDao().get(chat, "conference_declined:$client"))

        val auth = FixtureAuth(owner)
        val archive = HistoryLocalStore(source, auth).export(serverIdentity, owner, UUID.randomUUID().toString(), 1)
        val archived = requireNotNull(archive.messages.single().content)
        val content = MessageContentCodec.decode(archived.toByteArray(Charsets.UTF_8))
        assertEquals(ConferenceInvitation.FALLBACK, content.text)
        assertEquals(invitation, ConferenceInvitation.decode(content.conferenceInvite))

        val destination = open20(ownedName())
        HistoryLocalStore(destination, auth).import(archive, serverIdentity, owner, 1)
        val restored = requireNotNull(destination.messageDao().findByClientMessageId(client))
        assertEquals(ConferenceInvitation.FALLBACK, restored.text)
        assertEquals(invitation, ConferenceInvitation.decodeStored(restored.conferenceInviteJson))
        // Account cleanup already calls clearAllTables. Exercise the real Room
        // operation with BOTH the new column and private decline workspace data.
        source.clearAllTables()
        assertNull(source.messageDao().findByClientMessageId(client))
        assertNull(source.workspaceDao().get(chat, "conference_declined:$client"))
        assertTrue(source.workspaceDao().allValues().isEmpty())
    }

    @Test fun restoredNullOrMalformedInvitationNeverExposesRawCapabilityInTextOrExport() = runBlocking {
        val owner = UUID.randomUUID().toString()
        val serverIdentity = UUID.randomUUID().toString()
        val capability = ConferenceInvite.fresh("https://conference-fixture.example")
        val rawCapability = capability.link()
        val cases = listOf<JsonElement>(JsonNull, JsonPrimitive("invalid-metadata"), buildJsonObject {
            put("version", 1); put("id", "invalid-uuid"); put("invite", rawCapability)
        })
        val rows = cases.mapIndexed { index, extension ->
            HistoryArchiveMessage(client = UUID.randomUUID().toString(), id = UUID.randomUUID().toString(),
                chat = UUID.randomUUID().toString(), sender = owner, sequence = index + 1L,
                createdAtMs = 1793268000000L + index,
                content = buildJsonObject {
                    put("kind", "text"); put("text", rawCapability); put("conference_invite", extension)
                }.toString())
        }
        val archive = HistoryArchive(server = serverIdentity, owner = owner,
            snapshot = UUID.randomUUID().toString(), messages = rows)
        val database = open20(ownedName())
        val auth = FixtureAuth(owner)
        HistoryLocalStore(database, auth).import(archive, serverIdentity, owner, 1)
        rows.forEach { fixture ->
            val restored = requireNotNull(database.messageDao().findByClientMessageId(fixture.client))
            assertEquals(ConferenceInvitation.FALLBACK, restored.text)
            assertNull(restored.conferenceInviteJson)
            assertFalse(restored.text.contains(rawCapability))
        }
        // Exercise the actual archive writer after import as well: invalid
        // metadata must not re-export the hidden bearer from the original text.
        val exported = HistoryLocalStore(database, auth).export(serverIdentity, owner, UUID.randomUUID().toString(), 1)
        assertEquals(3, exported.messages.size)
        exported.messages.forEach {
            val safe = requireNotNull(it.content)
            assertFalse(safe.contains(rawCapability))
            val content = MessageContentCodec.decode(safe.toByteArray(Charsets.UTF_8))
            assertEquals(ConferenceInvitation.FALLBACK, content.text)
            assertNull(content.conferenceInvite)
        }
    }

    private fun ownedName() = "conference-migration-19-20-${UUID.randomUUID()}.db".also(names::add)
    private fun open20(name: String) = Room.databaseBuilder(context, MessengerDatabase::class.java, name)
        .addMigrations(MessengerDatabase.MIGRATION_19_20, MessengerDatabase.MIGRATION_20_21).build().also(opened::add)

    private fun legacy19(name: String): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                override fun onCreate(db: SupportSQLiteDatabase) { schema19.forEach(db::execSQL) }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected legacy upgrade")
            }).build(),
    )

    private fun SupportSQLiteOpenHelper.closeAfterOpening() { writableDatabase; close() }

    private fun snapshot(db: SupportSQLiteDatabase, table: String): List<List<String?>> =
        db.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            val columns = (0 until cursor.columnCount).filter { cursor.getColumnName(it) !in setOf("conferenceInviteJson", "videoNote") }
            buildList {
                while (cursor.moveToNext()) add(columns.map { column ->
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).joinToString("") { "%02x".format(it) }
                        else -> cursor.getString(column)
                    }
                })
            }
        }

    private fun seedLegacyRows(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO chats VALUES('chat','direct','2026-10-28T10:00:00Z','2026-10-28T10:02:00Z',7,8,7,1,'2026-10-30T00:00:00Z',0,NULL)")
        db.execSQL("INSERT INTO chat_members VALUES('chat','peer','member')")
        db.execSQL("INSERT INTO chat_participants VALUES('peer','fixture-peer','Fixture Peer',NULL,3,1)")
        db.execSQL("INSERT INTO chat_workspace VALUES('chat','draft','unsent fixture draft')")
        db.execSQL("INSERT INTO deleted_chats VALUES('already-deleted-chat')")
        db.execSQL("INSERT INTO peer_identities VALUES('peer',2,'verified','fixture-safety','fixture-public-key','2026-10-28T09:00:00Z')")
        db.execSQL("INSERT INTO pending_decrypts VALUES('decrypt-client','decrypt-server','chat','peer','device',9,'2026-10-28T10:03:00Z',3,'synthetic-ciphertext','content',NULL,2,'TransientFixture')")
        for ((id, status, sequence) in listOf(Triple("pending", "pending", null), Triple("sent", "read", 5L), Triple("hidden", "sent", 6L))) {
            val row = ContentValues().apply {
                put("clientMessageId", id); if (sequence == null) putNull("serverId") else put("serverId", "server-$id")
                put("chatId", "chat"); put("senderId", "me"); put("text", "original $id")
                if (sequence == null) putNull("sequence") else put("sequence", sequence)
                put("createdAt", "2026-10-28T10:00:00Z"); put("status", status); put("kind", "content")
                put("isEdited", 1); put("lastEditSequence", 3); put("isDeletedForEveryone", 0); put("deleteSequence", 0)
                put("hiddenLocally", if (id == "hidden") 1 else 0); put("forwarded", 1); put("retryCount", if (id == "pending") 4 else 0)
                put("nextRetryAtEpochMs", 1793289600000L); put("lastErrorClass", "FixtureRetry")
                put("caption", "caption $id"); put("albumId", "fixture-album"); put("payloadText", "frozen original")
                put("pendingEditText", "newer unsent edit"); put("pendingEditRevision", 7); put("sendAttempted", 1)
            }
            db.insert("messages", SQLiteDatabase.CONFLICT_ABORT, row)
        }
        db.execSQL("INSERT INTO reactions VALUES('server-sent','peer','👍',8)")
        db.execSQL("INSERT INTO attachments VALUES('fixture-file','sent','chat','file','application/pdf','fixture.pdf',12,NULL,NULL,NULL,NULL,'/private/fixture-only','ready','sent',100,'fixture-object',28,1,'synthetic-key','synthetic-nonce',NULL,'synthetic-sha')")
    }

    private class FixtureAuth(owner: String) : AuthRepository {
        override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser(owner, "fixture", "Fixture")))
        override suspend fun register(username: String, password: String, displayName: String) = Result.failure<Unit>(UnsupportedOperationException())
        override suspend fun login(username: String, password: String) = Result.failure<Unit>(UnsupportedOperationException())
        override suspend fun logout() { authState.value = AuthSessionState.LoggedOut }
        override suspend fun currentAccessToken(): String? = null
        override fun currentDeviceId() = "fixture-device"
        override fun currentSessionEpoch() = 1
        override suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T? = if (epochAtStart == 1) block() else null
        override suspend fun clearRoom(block: suspend () -> Unit) { block() }
    }

    companion object {
        private val tables = listOf("attachments", "chat_members", "chat_participants", "chat_workspace", "chats", "deleted_chats", "messages", "peer_identities", "pending_decrypts", "reactions")
        // Frozen CREATE TABLE/INDEX strings from the shipped Room-19 binary.
        private val schema19 = listOf(
            "CREATE TABLE IF NOT EXISTS `attachments` (`attachmentId` TEXT NOT NULL, `clientMessageId` TEXT NOT NULL, `chatId` TEXT NOT NULL, `mediaType` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `filename` TEXT, `size` INTEGER NOT NULL, `width` INTEGER, `height` INTEGER, `durationMs` INTEGER, `thumbnailBase64` TEXT, `localPath` TEXT, `downloadState` TEXT NOT NULL, `uploadState` TEXT NOT NULL, `progress` INTEGER NOT NULL, `objectKey` TEXT NOT NULL, `encryptedSize` INTEGER NOT NULL, `encryptionVersion` INTEGER NOT NULL, `keyBase64` TEXT, `nonceBase64` TEXT, `keysetBase64` TEXT, `sha256Base64` TEXT NOT NULL, PRIMARY KEY(`attachmentId`))",
            "CREATE TABLE IF NOT EXISTS `chat_members` (`chatId` TEXT NOT NULL, `userId` TEXT NOT NULL, `role` TEXT NOT NULL, PRIMARY KEY(`chatId`, `userId`))",
            "CREATE TABLE IF NOT EXISTS `chat_participants` (`userId` TEXT NOT NULL, `username` TEXT NOT NULL, `displayName` TEXT NOT NULL, `avatarUrl` TEXT, `avatarVersion` INTEGER NOT NULL DEFAULT 0, `hasAvatar` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`userId`))",
            "CREATE TABLE IF NOT EXISTS `chat_workspace` (`chatId` TEXT NOT NULL, `name` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`chatId`, `name`))",
            "CREATE TABLE IF NOT EXISTS `chats` (`chatId` TEXT NOT NULL, `type` TEXT NOT NULL, `createdAt` TEXT NOT NULL, `lastMessageAt` TEXT, `lastReadSequence` INTEGER NOT NULL, `peerDeliveredSequence` INTEGER NOT NULL DEFAULT 0, `peerReadSequence` INTEGER NOT NULL DEFAULT 0, `isMuted` INTEGER NOT NULL DEFAULT 0, `mutedUntil` TEXT, `isBlocked` INTEGER NOT NULL DEFAULT 0, `blockedUserId` TEXT, PRIMARY KEY(`chatId`))",
            "CREATE TABLE IF NOT EXISTS `deleted_chats` (`chatId` TEXT NOT NULL, PRIMARY KEY(`chatId`))",
            "CREATE TABLE IF NOT EXISTS `messages` (`clientMessageId` TEXT NOT NULL, `serverId` TEXT, `chatId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `text` TEXT NOT NULL, `sequence` INTEGER, `createdAt` TEXT NOT NULL, `status` TEXT NOT NULL, `kind` TEXT NOT NULL, `targetMessageId` TEXT, `replySnapshotJson` TEXT, `isEdited` INTEGER NOT NULL, `lastEditSequence` INTEGER NOT NULL, `isDeletedForEveryone` INTEGER NOT NULL, `deleteSequence` INTEGER NOT NULL, `hiddenLocally` INTEGER NOT NULL, `forwarded` INTEGER NOT NULL, `retryCount` INTEGER NOT NULL, `nextRetryAtEpochMs` INTEGER NOT NULL, `lastErrorClass` TEXT, `caption` TEXT, `albumId` TEXT, `payloadText` TEXT, `pendingEditText` TEXT, `pendingEditRevision` INTEGER NOT NULL, `sendAttempted` INTEGER NOT NULL, PRIMARY KEY(`clientMessageId`))",
            "CREATE TABLE IF NOT EXISTS `peer_identities` (`userId` TEXT NOT NULL, `signalDeviceId` INTEGER NOT NULL, `status` TEXT NOT NULL, `safetyNumber` TEXT, `identityKeyB64` TEXT, `verifiedAt` TEXT, PRIMARY KEY(`userId`, `signalDeviceId`))",
            "CREATE TABLE IF NOT EXISTS `pending_decrypts` (`clientMessageId` TEXT NOT NULL, `serverId` TEXT NOT NULL, `chatId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `senderDeviceId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, `messageType` INTEGER NOT NULL, `ciphertext` TEXT NOT NULL, `kind` TEXT NOT NULL, `targetMessageId` TEXT, `retryCount` INTEGER NOT NULL, `lastErrorClass` TEXT, PRIMARY KEY(`clientMessageId`))",
            "CREATE TABLE IF NOT EXISTS `reactions` (`targetMessageId` TEXT NOT NULL, `userId` TEXT NOT NULL, `emoji` TEXT, `lastReactionSequence` INTEGER NOT NULL, PRIMARY KEY(`targetMessageId`, `userId`))",
            "CREATE INDEX IF NOT EXISTS `index_attachments_chatId` ON `attachments` (`chatId`)",
            "CREATE INDEX IF NOT EXISTS `index_attachments_clientMessageId` ON `attachments` (`clientMessageId`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_chatId` ON `messages` (`chatId`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_targetMessageId` ON `messages` (`targetMessageId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_chatId_sequence` ON `messages` (`chatId`, `sequence`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_serverId` ON `messages` (`serverId`)",
        )
    }
}
