package com.messenger.app.data.local.chat

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.*
import org.junit.Test

/** Real SQLite executes the production triggers, including secondary plaintext copies and FTS. */
class MessageDeletionSqlTest {
    private fun fixture(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { s ->
                s.execute("CREATE TABLE messages(clientMessageId TEXT PRIMARY KEY,serverId TEXT,chatId TEXT,kind TEXT DEFAULT 'content',targetMessageId TEXT,text TEXT DEFAULT '',payloadText TEXT,caption TEXT,replySnapshotJson TEXT,pendingEditText TEXT,pendingEditRevision INTEGER DEFAULT 0,conferenceInviteJson TEXT,hiddenLocally INTEGER DEFAULT 0,isDeletedForEveryone INTEGER DEFAULT 0)")
                s.execute("CREATE TABLE attachments(attachmentId TEXT PRIMARY KEY,clientMessageId TEXT,localPath TEXT,thumbnailBase64 TEXT,keyBase64 TEXT)")
                s.execute("CREATE TABLE pending_decrypts(clientMessageId TEXT PRIMARY KEY,ciphertext TEXT)")
                s.execute("CREATE TABLE chat_workspace(chatId TEXT,name TEXT,value TEXT)")
                s.execute("CREATE VIRTUAL TABLE messages_fts USING fts4(text,client_message_id)")
                MessageDeletionSql.install.forEach(s::execute)
                s.execute("INSERT INTO messages VALUES('target','server','chat','content',NULL,'secret','original','caption','quote','pending',1,'invite',0,0)")
                s.execute("INSERT INTO messages(clientMessageId,chatId,targetMessageId,text,replySnapshotJson) VALUES('reply','chat','server','keep','secret quote')")
                s.execute("INSERT INTO messages(clientMessageId,chatId,kind,targetMessageId,text,payloadText) VALUES('edit','chat','edit','server','edit copy','original edit')")
                s.execute("INSERT INTO attachments VALUES('attachment','target','/private/file','thumbnail','key')")
                s.execute("INSERT INTO pending_decrypts VALUES('target','ciphertext')")
                s.execute("INSERT INTO messages_fts VALUES('secret','target')")
                s.execute("INSERT INTO chat_workspace VALUES('chat','direct_offer:target','private offer')")
            }
            block(db)
        }
    }
    private fun number(db: Connection, query: String): Int = db.createStatement().use { s -> s.executeQuery(query).use { it.next(); it.getInt(1) } }
    private fun assertErased(db: Connection, physical: Boolean = false) {
        assertEquals(0, number(db,"SELECT COUNT(*) FROM messages WHERE clientMessageId='target' AND (text<>'' OR payloadText IS NOT NULL OR caption IS NOT NULL OR replySnapshotJson IS NOT NULL OR pendingEditText IS NOT NULL OR conferenceInviteJson IS NOT NULL)"))
        assertEquals(if (physical) 0 else 1, number(db,"SELECT COUNT(*) FROM messages WHERE clientMessageId='target'"))
        assertEquals(0, number(db,"SELECT COUNT(*) FROM messages WHERE clientMessageId='reply' AND replySnapshotJson IS NOT NULL"))
        assertEquals(0, number(db,"SELECT COUNT(*) FROM attachments"))
        assertEquals(0, number(db,"SELECT COUNT(*) FROM pending_decrypts"))
        assertEquals(0, number(db,"SELECT COUNT(*) FROM messages_fts WHERE client_message_id='target'"))
        assertEquals(1, number(db,"SELECT COUNT(*) FROM privacy_deletions WHERE path='/private/file'"))
        assertEquals(1, number(db,"PRAGMA secure_delete"))
    }
    @Test fun deleteForMeAndEveryoneScrubTombstonesAndCopies() {
        for (flag in listOf("hiddenLocally", "isDeletedForEveryone")) fixture { db ->
            db.createStatement().use { it.execute("UPDATE messages SET $flag=1 WHERE clientMessageId='target'") }
            assertErased(db)
            assertEquals(0, number(db,"SELECT COUNT(*) FROM messages WHERE kind='edit' AND (text<>'' OR payloadText IS NOT NULL)"))
            db.createStatement().use { it.execute("UPDATE messages SET text='late original',payloadText='late payload' WHERE clientMessageId='target'") }
            assertErased(db)
        }
    }
    @Test fun chatAndHistoryPhysicalDeletionRetainFileCleanupIntent() = fixture { db ->
        db.createStatement().use { it.execute("DELETE FROM messages WHERE clientMessageId='target'") }
        assertErased(db, physical = true)
    }
    @Test fun upgradeErasesAlreadyHiddenPayloadAndAttachments() = fixture { db ->
        db.createStatement().use { s ->
            s.execute("DROP TRIGGER privacy_message_scrub")
            s.execute("UPDATE messages SET hiddenLocally=1 WHERE clientMessageId='target'")
            MessageDeletionSql.install.forEach(s::execute)
            MessageDeletionSql.backfill.forEach(s::execute)
        }
        assertErased(db)
    }
    @Test fun emptyImportedTombstoneErasesPreexistingOrphanMediaAndFts() = fixture { db ->
        db.createStatement().use { s ->
            s.execute("INSERT INTO attachments VALUES('orphan','hidden','/private/orphan','thumbnail','key')")
            s.execute("INSERT INTO messages_fts VALUES('orphan secret','hidden')")
            s.execute("INSERT INTO messages(clientMessageId,chatId,hiddenLocally) VALUES('hidden','chat',1)")
        }
        assertEquals(0, number(db,"SELECT COUNT(*) FROM attachments WHERE clientMessageId='hidden'"))
        assertEquals(0, number(db,"SELECT COUNT(*) FROM messages_fts WHERE client_message_id='hidden'"))
        assertEquals(1, number(db,"SELECT COUNT(*) FROM privacy_deletions WHERE path='/private/orphan'"))
    }
    @Test fun delayedReplyOrEditCannotRestoreDeletedContent() = fixture { db ->
        db.createStatement().use { s ->
            s.execute("UPDATE messages SET hiddenLocally=1 WHERE clientMessageId='target'")
            s.execute("INSERT INTO messages(clientMessageId,chatId,targetMessageId,text,replySnapshotJson) VALUES('late-reply','chat','server','new reply','erased quote')")
            s.execute("UPDATE messages SET replySnapshotJson='late changed quote' WHERE clientMessageId='reply'")
            s.execute("INSERT INTO messages(clientMessageId,chatId,kind,targetMessageId,text,payloadText) VALUES('late-edit','chat','edit','server','erased text','erased payload')")
        }
        assertEquals(0, number(db,"SELECT COUNT(*) FROM messages WHERE targetMessageId='server' AND replySnapshotJson IS NOT NULL"))
        assertEquals(0, number(db,"SELECT COUNT(*) FROM messages WHERE kind='edit' AND (text<>'' OR payloadText IS NOT NULL)"))
    }
}
