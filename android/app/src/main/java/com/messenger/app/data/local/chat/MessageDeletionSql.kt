package com.messenger.app.data.local.chat

/** Shared by the real database and JDBC contracts. Triggers cover every deletion entry point. */
internal object MessageDeletionSql {
    /** B-056 (POCO 19 Nov): Android's execSQL refuses a statement that returns a row, and
     *  `PRAGMA secure_delete=ON` returns the new value, so the app crashed at start. PRAGMAs run
     *  as queries inside a transaction (the writer connection); everything else via execSQL. */
    fun run(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String) {
        if (!sql.trimStart().startsWith("PRAGMA", ignoreCase = true)) { db.execSQL(sql); return }
        db.beginTransaction()
        try {
            db.query(sql).use { it.moveToFirst() }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
    const val SCRUB = "text='', payloadText=NULL, caption=NULL, replySnapshotJson=NULL, pendingEditText=NULL, pendingEditRevision=0, conferenceInviteJson=NULL"
    val install = listOf(
        "PRAGMA secure_delete=ON",
        "CREATE TABLE IF NOT EXISTS privacy_deletions(id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, attachmentId TEXT)",
        """CREATE TRIGGER IF NOT EXISTS privacy_attachment_delete BEFORE DELETE ON attachments BEGIN
          INSERT OR IGNORE INTO privacy_deletions(path,attachmentId) VALUES('',NULL);
          INSERT OR IGNORE INTO privacy_deletions(path,attachmentId) VALUES(COALESCE(OLD.localPath,'@attachment:'||OLD.attachmentId),OLD.attachmentId);
        END""",
        """CREATE TRIGGER IF NOT EXISTS privacy_attachment_clear BEFORE UPDATE OF localPath ON attachments WHEN OLD.localPath IS NOT NULL AND NEW.localPath IS NULL BEGIN
          INSERT OR IGNORE INTO privacy_deletions(path,attachmentId) VALUES(OLD.localPath,OLD.attachmentId);
        END""",
        """CREATE TRIGGER IF NOT EXISTS privacy_message_delete BEFORE DELETE ON messages BEGIN
          INSERT OR IGNORE INTO privacy_deletions(path,attachmentId) VALUES('',NULL);
          DELETE FROM attachments WHERE clientMessageId=OLD.clientMessageId;
          UPDATE messages SET replySnapshotJson=NULL WHERE kind='content' AND targetMessageId=OLD.serverId;
          UPDATE messages SET $SCRUB WHERE kind='edit' AND targetMessageId=OLD.serverId;
          DELETE FROM pending_decrypts WHERE clientMessageId=OLD.clientMessageId;
          DELETE FROM messages_fts WHERE client_message_id=OLD.clientMessageId;
          DELETE FROM chat_workspace WHERE chatId=OLD.chatId AND (name='direct_offer:'||OLD.clientMessageId OR name='direct_history:'||OLD.clientMessageId OR name='voice_transcript:'||OLD.clientMessageId);
        END""",
        """CREATE TRIGGER IF NOT EXISTS privacy_message_scrub AFTER UPDATE ON messages
        WHEN (NEW.hiddenLocally<>0 OR NEW.isDeletedForEveryone<>0) AND
          (NEW.text<>'' OR NEW.payloadText IS NOT NULL OR NEW.caption IS NOT NULL OR NEW.replySnapshotJson IS NOT NULL OR NEW.pendingEditText IS NOT NULL OR NEW.conferenceInviteJson IS NOT NULL OR OLD.hiddenLocally=0 AND NEW.hiddenLocally<>0 OR OLD.isDeletedForEveryone=0 AND NEW.isDeletedForEveryone<>0)
        BEGIN
          INSERT OR IGNORE INTO privacy_deletions(path,attachmentId) VALUES('',NULL);
          UPDATE messages SET $SCRUB WHERE clientMessageId=NEW.clientMessageId;
          UPDATE messages SET replySnapshotJson=NULL WHERE kind='content' AND targetMessageId=NEW.serverId AND replySnapshotJson IS NOT NULL;
          UPDATE messages SET $SCRUB WHERE kind='edit' AND targetMessageId=NEW.serverId;
          DELETE FROM attachments WHERE clientMessageId=NEW.clientMessageId;
          DELETE FROM pending_decrypts WHERE clientMessageId=NEW.clientMessageId;
          DELETE FROM chat_workspace WHERE chatId=NEW.chatId AND (name='direct_offer:'||NEW.clientMessageId OR name='direct_history:'||NEW.clientMessageId OR name='voice_transcript:'||NEW.clientMessageId);
          DELETE FROM messages_fts WHERE client_message_id=NEW.clientMessageId;
        END""",
        // Reject late writes of media belonging to a tombstone before they can restore keys/thumbnails.
        """CREATE TRIGGER IF NOT EXISTS privacy_attachment_insert BEFORE INSERT ON attachments WHEN EXISTS
          (SELECT 1 FROM messages WHERE clientMessageId=NEW.clientMessageId AND (hiddenLocally<>0 OR isDeletedForEveryone<>0)) BEGIN SELECT RAISE(IGNORE); END""",
        """CREATE TRIGGER IF NOT EXISTS privacy_message_insert AFTER INSERT ON messages WHEN NEW.hiddenLocally<>0 OR NEW.isDeletedForEveryone<>0 BEGIN
          INSERT OR IGNORE INTO privacy_deletions(path,attachmentId) VALUES('',NULL);
          UPDATE messages SET $SCRUB WHERE clientMessageId=NEW.clientMessageId;
          UPDATE messages SET replySnapshotJson=NULL WHERE kind='content' AND targetMessageId=NEW.serverId;
          UPDATE messages SET $SCRUB WHERE kind='edit' AND targetMessageId=NEW.serverId;
          DELETE FROM attachments WHERE clientMessageId=NEW.clientMessageId;
          DELETE FROM pending_decrypts WHERE clientMessageId=NEW.clientMessageId;
          DELETE FROM chat_workspace WHERE chatId=NEW.chatId AND (name='direct_offer:'||NEW.clientMessageId OR name='direct_history:'||NEW.clientMessageId OR name='voice_transcript:'||NEW.clientMessageId);
          DELETE FROM messages_fts WHERE client_message_id=NEW.clientMessageId;
        END""",
        """CREATE TRIGGER IF NOT EXISTS privacy_direct_delete BEFORE DELETE ON chat_workspace WHEN OLD.name LIKE 'direct_offer:%' OR OLD.name LIKE 'direct_history:%' BEGIN
          INSERT OR IGNORE INTO privacy_deletions(path,attachmentId) VALUES('@direct:'||OLD.value,NULL);
        END""",
    ).map(String::trimIndent) + listOf("INSERT", "UPDATE").flatMap { operation -> listOf(
        """CREATE TRIGGER IF NOT EXISTS privacy_reply_$operation AFTER $operation ON messages
          WHEN NEW.replySnapshotJson IS NOT NULL AND EXISTS(SELECT 1 FROM messages WHERE serverId=NEW.targetMessageId AND (hiddenLocally<>0 OR isDeletedForEveryone<>0))
          BEGIN UPDATE messages SET replySnapshotJson=NULL WHERE clientMessageId=NEW.clientMessageId; END""".trimIndent(),
        """CREATE TRIGGER IF NOT EXISTS privacy_edit_$operation AFTER $operation ON messages
          WHEN NEW.kind='edit' AND (NEW.text<>'' OR NEW.payloadText IS NOT NULL OR NEW.pendingEditText IS NOT NULL) AND EXISTS(SELECT 1 FROM messages WHERE serverId=NEW.targetMessageId AND (hiddenLocally<>0 OR isDeletedForEveryone<>0))
          BEGIN UPDATE messages SET $SCRUB WHERE clientMessageId=NEW.clientMessageId; END""".trimIndent(),
    ) }

    val backfill = listOf(
        "INSERT OR IGNORE INTO privacy_deletions(path,attachmentId) SELECT '',NULL WHERE EXISTS(SELECT 1 FROM messages WHERE hiddenLocally<>0 OR isDeletedForEveryone<>0)",
        "UPDATE messages SET $SCRUB WHERE (hiddenLocally<>0 OR isDeletedForEveryone<>0) AND (text<>'' OR payloadText IS NOT NULL OR caption IS NOT NULL OR replySnapshotJson IS NOT NULL OR pendingEditText IS NOT NULL OR conferenceInviteJson IS NOT NULL)",
        "DELETE FROM attachments WHERE clientMessageId IN (SELECT clientMessageId FROM messages WHERE hiddenLocally<>0 OR isDeletedForEveryone<>0)",
        "DELETE FROM pending_decrypts WHERE clientMessageId IN (SELECT clientMessageId FROM messages WHERE hiddenLocally<>0 OR isDeletedForEveryone<>0)",
        "UPDATE messages SET replySnapshotJson=NULL WHERE replySnapshotJson IS NOT NULL AND targetMessageId IN (SELECT serverId FROM messages WHERE hiddenLocally<>0 OR isDeletedForEveryone<>0)",
        "UPDATE messages SET $SCRUB WHERE kind='edit' AND targetMessageId IN (SELECT serverId FROM messages WHERE hiddenLocally<>0 OR isDeletedForEveryone<>0)",
        "DELETE FROM messages_fts WHERE client_message_id IN (SELECT clientMessageId FROM messages WHERE hiddenLocally<>0 OR isDeletedForEveryone<>0)",
        "DELETE FROM chat_workspace WHERE EXISTS(SELECT 1 FROM messages WHERE messages.chatId=chat_workspace.chatId AND (hiddenLocally<>0 OR isDeletedForEveryone<>0) AND (chat_workspace.name='direct_offer:'||clientMessageId OR chat_workspace.name='direct_history:'||clientMessageId OR chat_workspace.name='voice_transcript:'||clientMessageId))",
    )
}
