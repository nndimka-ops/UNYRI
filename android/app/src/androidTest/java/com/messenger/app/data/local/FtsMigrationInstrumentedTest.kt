package com.messenger.app.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.data.local.chat.MessengerDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-SQLite test of MIGRATION_7_8 against a v7 database seeded with real
 * old rows (M.1 / B4): the FTS table is created, the backfill indexes only
 * the searchable projection, and deleted/hidden/decrypt-failed rows are
 * excluded - with no destructive fallback. Uses a raw framework SQLite
 * helper (no Room schema-file dependency).
 */
@RunWith(AndroidJUnit4::class)
class FtsMigrationInstrumentedTest {

    private fun openV7Db(context: Context, name: String): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private fun v7MessagesSchema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                clientMessageId TEXT NOT NULL PRIMARY KEY,
                serverId TEXT,
                chatId TEXT NOT NULL,
                senderId TEXT NOT NULL,
                text TEXT NOT NULL,
                sequence INTEGER,
                createdAt TEXT NOT NULL,
                status TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'content',
                targetMessageId TEXT,
                replySnapshotJson TEXT,
                isEdited INTEGER NOT NULL DEFAULT 0,
                lastEditSequence INTEGER NOT NULL DEFAULT 0,
                isDeletedForEveryone INTEGER NOT NULL DEFAULT 0,
                deleteSequence INTEGER NOT NULL DEFAULT 0,
                hiddenLocally INTEGER NOT NULL DEFAULT 0,
                forwarded INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    private fun insertMessage(db: SupportSQLiteDatabase, id: String, text: String, seq: Int, status: String = "delivered", kind: String = "content", hidden: Int = 0, deleted: Int = 0) {
        db.execSQL(
            "INSERT INTO messages (clientMessageId, chatId, senderId, text, sequence, createdAt, status, kind, hiddenLocally, isDeletedForEveryone) VALUES (?, 'chatA', 'alice', ?, ?, '2026-09-06', ?, ?, ?, ?)",
            arrayOf(id, text, seq, status, kind, hidden, deleted),
        )
    }

    private fun searchMatches(db: SupportSQLiteDatabase, match: String): Int =
        db.query("SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH ?", arrayOf(match)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    @Test
    fun migrationBackfillsOnlySearchableRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("fts_mig_test.db")
        val db = openV7Db(context, "fts_mig_test.db")
        v7MessagesSchema(db)

        // Old v7 data: normal, edited-projection, Cyrillic, deleted, hidden, decrypt_failed, lifecycle.
        insertMessage(db, "m1", "hello world", seq = 1)
        insertMessage(db, "m2", "edited effective text", seq = 2)
        insertMessage(db, "m3", "Привет мир", seq = 3)
        insertMessage(db, "m4", "deleted secret", seq = 4, deleted = 1)
        insertMessage(db, "m5", "hidden note", seq = 5, hidden = 1)
        insertMessage(db, "m6", "[Unable to decrypt message]", seq = 6, status = "decrypt_failed")
        insertMessage(db, "m7", "[edit]", seq = 7, kind = "edit")

        MessengerDatabase.MIGRATION_7_8.migrate(db)

        assertEquals(1, searchMatches(db, "hello"))
        assertEquals(1, searchMatches(db, "edited"))
        assertEquals(1, searchMatches(db, "Привет"))
        assertEquals(0, searchMatches(db, "deleted"))
        assertEquals(0, searchMatches(db, "hidden"))
        assertEquals(0, searchMatches(db, "Unable"))
        assertEquals(0, searchMatches(db, "edit"))

        db.query("SELECT COUNT(*) FROM messages_fts", arrayOf<Any>()).use {
            it.moveToFirst()
            assertEquals(3, it.getInt(0))
        }
        db.close()
        context.deleteDatabase("fts_mig_test.db")
    }
}
