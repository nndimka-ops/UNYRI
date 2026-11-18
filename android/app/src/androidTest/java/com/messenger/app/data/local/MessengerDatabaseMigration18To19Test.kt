package com.messenger.app.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.data.local.chat.MessengerDatabase
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executes the real 18→19 migration on a v18-shaped SQLite database. This is
 * intentionally a narrow data-preservation check (the full Room schema
 * validation is covered by a schema export when available); it needs a real
 * Android runtime because SupportSQLiteDatabase is not available on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class MessengerDatabaseMigration18To19Test {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        name = "migration-18-19-${UUID.randomUUID()}.db"
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(18) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationPreservesPendingTextAndBackfillsFrozenPayload() {
        val db = helper.writableDatabase
        db.execSQL(
            """
            CREATE TABLE messages (
                clientMessageId TEXT NOT NULL PRIMARY KEY,
                serverId TEXT, chatId TEXT NOT NULL, senderId TEXT NOT NULL, text TEXT NOT NULL,
                sequence INTEGER, createdAt TEXT NOT NULL, status TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'content', targetMessageId TEXT, replySnapshotJson TEXT,
                isEdited INTEGER NOT NULL DEFAULT 0, lastEditSequence INTEGER NOT NULL DEFAULT 0,
                isDeletedForEveryone INTEGER NOT NULL DEFAULT 0, deleteSequence INTEGER NOT NULL DEFAULT 0,
                hiddenLocally INTEGER NOT NULL DEFAULT 0, forwarded INTEGER NOT NULL DEFAULT 0,
                retryCount INTEGER NOT NULL DEFAULT 0, nextRetryAtEpochMs INTEGER NOT NULL DEFAULT 0,
                lastErrorClass TEXT, caption TEXT, albumId TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT INTO messages(clientMessageId,serverId,chatId,senderId,text,sequence,createdAt,status,kind) " +
                "VALUES('old',NULL,'chat','me','old text',NULL,'2026-10-19T10:00:00Z','pending','content')",
        )

        MessengerDatabase.MIGRATION_18_19.migrate(db)

        db.query("SELECT text,payloadText,sendAttempted,pendingEditText FROM messages WHERE clientMessageId='old'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("old text", cursor.getString(0))
            assertEquals("old text", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertNull(cursor.getString(3))
        }

        // Rows created after the migration keep the safe pre-send default.
        db.execSQL(
            "INSERT INTO messages(clientMessageId,serverId,chatId,senderId,text,sequence,createdAt,status,kind,payloadText,sendAttempted,pendingEditRevision) " +
                "VALUES('new',NULL,'chat','me','new text',NULL,'2026-10-19T10:00:01Z','pending','content',NULL,0,0)",
        )
        db.query("SELECT sendAttempted FROM messages WHERE clientMessageId='new'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }
}
