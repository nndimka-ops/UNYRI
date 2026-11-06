package com.messenger.app.data.local.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.Callback
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.messenger.app.data.local.attachment.AttachmentDao
import com.messenger.app.data.local.attachment.AttachmentEntity
import com.messenger.app.data.local.attachment.MediaDao
import com.messenger.app.data.local.message.MessageDao
import com.messenger.app.data.local.message.MessageEntity
import com.messenger.app.data.local.message.PendingDecryptEntity
import com.messenger.app.data.local.message.ReactionDao
import com.messenger.app.data.local.message.ReactionEntity
import com.messenger.app.data.local.signal.PeerIdentityDao
import com.messenger.app.data.local.signal.PeerIdentityEntity
import com.messenger.app.data.local.search.SearchDao

/** Local cache only - chat metadata and message history, never a
 *  replacement source of truth (Postgres is). Prepares the
 *  local-DB-then-sync-then-server flow for real offline messaging. */
@Database(
    entities = [
        ChatEntity::class, ChatMemberEntity::class, ChatParticipantEntity::class,
        MessageEntity::class, AttachmentEntity::class, ReactionEntity::class,
        PeerIdentityEntity::class, ChatWorkspaceValue::class, DeletedChat::class,
        PendingDecryptEntity::class,
    ],
    version = 20,
    exportSchema = false,
)
abstract class MessengerDatabase : RoomDatabase() {
    abstract fun deletedChatDao(): DeletedChatDao
    abstract fun workspaceDao(): ChatWorkspaceDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun reactionDao(): ReactionDao
    abstract fun peerIdentityDao(): PeerIdentityDao
    abstract fun searchDao(): SearchDao
    abstract fun mediaDao(): MediaDao

    companion object {
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN conferenceInviteJson TEXT")
            }
        }
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN payloadText TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN pendingEditText TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN pendingEditRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN sendAttempted INTEGER NOT NULL DEFAULT 0")
                // A pre-upgrade pending row may already have been POSTed. Treat
                // it conservatively as attempted so an edit cannot silently
                // replace its payload under the same idempotency key.
                db.execSQL("UPDATE messages SET sendAttempted = 1, payloadText = text WHERE serverId IS NULL AND kind = 'content' AND status IN ('pending','failed')")
            }
        }
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_decrypts (
                        clientMessageId TEXT NOT NULL PRIMARY KEY,
                        serverId TEXT NOT NULL,
                        chatId TEXT NOT NULL,
                        senderId TEXT NOT NULL,
                        senderDeviceId TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        createdAt TEXT NOT NULL,
                        messageType INTEGER NOT NULL,
                        ciphertext TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        targetMessageId TEXT,
                        retryCount INTEGER NOT NULL DEFAULT 0,
                        lastErrorClass TEXT
                    )
                    """.trimIndent(),
                )
            }
        }
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN isMuted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chats ADD COLUMN mutedUntil TEXT")
                db.execSQL("ALTER TABLE chats ADD COLUMN isBlocked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chats ADD COLUMN blockedUserId TEXT")
            }
        }
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS deleted_chats (chatId TEXT NOT NULL PRIMARY KEY)")
                installDeletedChatGuards(db)
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE messages ADD COLUMN albumId TEXT") }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE messages ADD COLUMN caption TEXT") }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS chat_workspace (chatId TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(chatId, name))")
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_participants ADD COLUMN avatarVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chat_participants ADD COLUMN hasAvatar INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN peerDeliveredSequence INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chats ADD COLUMN peerReadSequence INTEGER NOT NULL DEFAULT 0")
            }
        }
        /** Этап E: adds the `messages` table. Mirrors MessageEntity's schema
         *  exactly (see indices there for why each one exists). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS messages (
                        clientMessageId TEXT NOT NULL PRIMARY KEY,
                        serverId TEXT,
                        chatId TEXT NOT NULL,
                        senderId TEXT NOT NULL,
                        text TEXT NOT NULL,
                        sequence INTEGER,
                        createdAt TEXT NOT NULL,
                        status TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_messages_serverId ON messages (serverId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_chatId_sequence ON messages (chatId, sequence)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId ON messages (chatId)")
            }
        }

        /** Этап G: adds the local-only unread-count watermark. Defaults to 0
         *  (existing chats show every already-cached message as "unread"
         *  until the chat is next opened and marks it read - a one-time,
         *  harmless cosmetic effect, never a data-loss/correctness issue). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN lastReadSequence INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Этап H: adds the local attachment cache table (see AttachmentEntity's
         *  doc comment for the crypto-material-in-plain-Room threat-model note). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attachments (
                        attachmentId TEXT NOT NULL PRIMARY KEY,
                        clientMessageId TEXT NOT NULL,
                        chatId TEXT NOT NULL,
                        mediaType TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        filename TEXT,
                        size INTEGER NOT NULL,
                        width INTEGER,
                        height INTEGER,
                        durationMs INTEGER,
                        thumbnailBase64 TEXT,
                        localPath TEXT,
                        downloadState TEXT NOT NULL,
                        uploadState TEXT NOT NULL,
                        progress INTEGER NOT NULL DEFAULT 0,
                        objectKey TEXT NOT NULL,
                        encryptedSize INTEGER NOT NULL,
                        keyBase64 TEXT NOT NULL,
                        nonceBase64 TEXT NOT NULL,
                        sha256Base64 TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_clientMessageId ON attachments (clientMessageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_chatId ON attachments (chatId)")
            }
        }

        /** Этап H.2: attachment encryption v2 (Tink Streaming AEAD) needs a
         *  `keysetBase64` column instead of key/nonce, plus a version tag
         *  to tell existing v1 rows (legacy AES-GCM) from new v2 rows
         *  apart. keyBase64/nonceBase64 become nullable (v2 rows have
         *  neither) - SQLite can't relax a NOT NULL constraint via ALTER
         *  TABLE, so the table is recreated (standard Room migration
         *  pattern), preserving every existing v1 row unchanged
         *  (encryptionVersion defaults to 1 for them). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attachments_new (
                        attachmentId TEXT NOT NULL PRIMARY KEY,
                        clientMessageId TEXT NOT NULL,
                        chatId TEXT NOT NULL,
                        mediaType TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        filename TEXT,
                        size INTEGER NOT NULL,
                        width INTEGER,
                        height INTEGER,
                        durationMs INTEGER,
                        thumbnailBase64 TEXT,
                        localPath TEXT,
                        downloadState TEXT NOT NULL,
                        uploadState TEXT NOT NULL,
                        progress INTEGER NOT NULL DEFAULT 0,
                        objectKey TEXT NOT NULL,
                        encryptedSize INTEGER NOT NULL,
                        encryptionVersion INTEGER NOT NULL DEFAULT 1,
                        keyBase64 TEXT,
                        nonceBase64 TEXT,
                        keysetBase64 TEXT,
                        sha256Base64 TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO attachments_new
                        (attachmentId, clientMessageId, chatId, mediaType, mimeType, filename,
                         size, width, height, durationMs, thumbnailBase64, localPath,
                         downloadState, uploadState, progress, objectKey, encryptedSize,
                         encryptionVersion, keyBase64, nonceBase64, keysetBase64, sha256Base64)
                    SELECT
                        attachmentId, clientMessageId, chatId, mediaType, mimeType, filename,
                        size, width, height, durationMs, thumbnailBase64, localPath,
                        downloadState, uploadState, progress, objectKey, encryptedSize,
                        1, keyBase64, nonceBase64, NULL, sha256Base64
                    FROM attachments
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE attachments")
                db.execSQL("ALTER TABLE attachments_new RENAME TO attachments")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_clientMessageId ON attachments (clientMessageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_chatId ON attachments (chatId)")
            }
        }

        /** Этап I: message lifecycle events (reply/edit/delete/reaction). All
         *  new `messages` columns are nullable-with-default, so (unlike
         *  Этап H.2's attachments migration) plain ADD COLUMN suffices - no
         *  table recreate needed. New `reactions` table mirrors
         *  ReactionEntity exactly. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'content'")
                db.execSQL("ALTER TABLE messages ADD COLUMN targetMessageId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replySnapshotJson TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN isEdited INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN lastEditSequence INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN isDeletedForEveryone INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN deleteSequence INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN hiddenLocally INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reactions (
                        targetMessageId TEXT NOT NULL,
                        userId TEXT NOT NULL,
                        emoji TEXT,
                        lastReactionSequence INTEGER NOT NULL,
                        PRIMARY KEY (targetMessageId, userId)
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Этап L: local Signal identity verification state (UNVERIFIED /
         *  VERIFIED / CHANGED) - see PeerIdentityEntity. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS peer_identities (
                        userId TEXT NOT NULL,
                        signalDeviceId INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        safetyNumber TEXT,
                        identityKeyB64 TEXT,
                        verifiedAt TEXT,
                        PRIMARY KEY (userId, signalDeviceId)
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Этап M: local-only FTS search. A standalone FTS4 virtual table
         *  (unicode61 tokenizer - case-insensitive, Unicode-aware) maintained
         *  by triggers directly off the `messages` table, so the index is
         *  always the deterministic projection of the same MessageEntity
         *  fields the UI renders (no separate, drifty indexing code). Only
         *  content rows that are actually searchable are indexed - lifecycle
         *  events (edit/delete/reaction), tombstoned, locally-hidden, and
         *  failed-decrypt rows are all excluded by the trigger WHERE clause.
         *  `text` is the only indexed column; the rest are UNINDEXED stored
         *  columns for result reconstruction without a join back. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSearchIndex(db)
                backfillFts(db)
            }
        }

        /** Idempotent FTS4 index + triggers setup. Called by the 7->8
         *  migration (upgrade), by the onOpen callback (fresh installs and
         *  every subsequent open), and directly by instrumentation tests.
         *  Every statement is IF NOT EXISTS, so calling it repeatedly is a
         *  no-op - this is the single source of truth for the search index
         *  existing regardless of how the DB was created. */
        fun ensureSearchIndex(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts4(
                    text,
                    chat_id UNINDEXED,
                    sender_id UNINDEXED,
                    sequence UNINDEXED,
                    created_at UNINDEXED,
                    client_message_id UNINDEXED,
                    tokenize=unicode61
                )
                """.trimIndent(),
            )
            // DROP + re-CREATE (not CREATE IF NOT EXISTS) so a condition change
            // like excluding 'identity_changed' takes effect on the next open
            // without a version bump - triggers hold no state.
            db.execSQL("DROP TRIGGER IF EXISTS messages_fts_insert")
            db.execSQL(
                """
                CREATE TRIGGER messages_fts_insert AFTER INSERT ON messages BEGIN
                  DELETE FROM messages_fts WHERE client_message_id = NEW.clientMessageId;
                  INSERT INTO messages_fts(text, chat_id, sender_id, sequence, created_at, client_message_id)
                    SELECT NEW.text, NEW.chatId, NEW.senderId, NEW.sequence, NEW.createdAt, NEW.clientMessageId
                    WHERE NEW.kind = 'content' AND NEW.hiddenLocally = 0 AND NEW.isDeletedForEveryone = 0
                      AND NEW.status <> 'decrypt_failed' AND NEW.status <> 'identity_changed' AND NEW.text <> '';
                END
                """.trimIndent(),
            )
            db.execSQL("DROP TRIGGER IF EXISTS messages_fts_update")
            db.execSQL(
                """
                CREATE TRIGGER messages_fts_update AFTER UPDATE ON messages BEGIN
                  DELETE FROM messages_fts WHERE client_message_id = OLD.clientMessageId;
                  INSERT INTO messages_fts(text, chat_id, sender_id, sequence, created_at, client_message_id)
                    SELECT NEW.text, NEW.chatId, NEW.senderId, NEW.sequence, NEW.createdAt, NEW.clientMessageId
                    WHERE NEW.kind = 'content' AND NEW.hiddenLocally = 0 AND NEW.isDeletedForEveryone = 0
                      AND NEW.status <> 'decrypt_failed' AND NEW.status <> 'identity_changed' AND NEW.text <> '';
                END
                """.trimIndent(),
            )
            db.execSQL("DROP TRIGGER IF EXISTS messages_fts_delete")
            db.execSQL(
                """
                CREATE TRIGGER messages_fts_delete AFTER DELETE ON messages BEGIN
                  DELETE FROM messages_fts WHERE client_message_id = OLD.clientMessageId;
                END
                """.trimIndent(),
            )
        }

        /** Rebuilds the index from existing rows (upgrade path only - a
         *  fresh install has no rows yet, and the triggers handle future
         *  writes). */
        private fun backfillFts(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                INSERT INTO messages_fts(text, chat_id, sender_id, sequence, created_at, client_message_id)
                SELECT text, chatId, senderId, sequence, createdAt, clientMessageId
                FROM messages
                WHERE kind = 'content' AND hiddenLocally = 0 AND isDeletedForEveryone = 0
                  AND status <> 'decrypt_failed' AND status <> 'identity_changed' AND text <> ''
                """.trimIndent(),
            )
        }

        /** N.2R durable outbox (Bug 4): retry bookkeeping for PENDING sends.
         *  All columns nullable-with-default so a plain ADD COLUMN suffices. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_targetMessageId ON messages (targetMessageId)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN nextRetryAtEpochMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN lastErrorClass TEXT")
            }
        }

        fun create(context: Context): MessengerDatabase =
            Room.databaseBuilder(context.applicationContext, MessengerDatabase::class.java, "messenger.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
                )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        ensureSearchIndex(db)
                        installDeletedChatGuards(db)
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Idempotent - also covers a fresh DB created without
                        // running onCreate (and any in-memory DB).
                        ensureSearchIndex(db)
                        installDeletedChatGuards(db)
                    }
                })
                .build()
    }
}
