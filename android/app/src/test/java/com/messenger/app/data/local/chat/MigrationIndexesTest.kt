package com.messenger.app.data.local.chat

import androidx.sqlite.db.SupportSQLiteDatabase
import com.messenger.app.data.local.message.PendingDecryptRetrySql
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.*

/** 19 Nov, POCO: the first build with Room 23 -> 24 crashed at start on an upgraded device
 *  ("Migration didn't properly handle: pending_decrypts"): Room validates entity tables after
 *  migrating and found the partial decrypt-retry indexes that onOpen installs. Every registered
 *  migration must drop them first (onOpen re-creates them after validation). */
class MigrationIndexesTest {
    private fun adapter(db: Connection) = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
        check(method.name == "execSQL") { method.name }
        db.createStatement().use { it.execute(args!![0] as String) }; null
    } as SupportSQLiteDatabase

    // Explicit indexes only: SQLite's own primary-key index (sqlite_autoindex_*) is not ours and Room ignores it.
    private fun indexes(db: Connection): Set<String> = db.createStatement().use { sql ->
        sql.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'pending_decrypts' AND name NOT LIKE 'sqlite_%'").use { rows ->
            buildSet { while (rows.next()) add(rows.getString(1)) }
        }
    }

    @Test fun upgradeFrom23DropsThePartialRetryIndexesBeforeRoomValidates() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE pending_decrypts(clientMessageId TEXT NOT NULL PRIMARY KEY, senderId TEXT NOT NULL, sequence INTEGER NOT NULL, lastErrorClass TEXT)")
                sql.execute("CREATE TABLE chat_participants(userId TEXT PRIMARY KEY, username TEXT NOT NULL, displayName TEXT NOT NULL, publicNumber TEXT, publicUsername TEXT)")
                sql.execute(PendingDecryptRetrySql.INDEX_ALL)
                sql.execute(PendingDecryptRetrySql.INDEX_SENDER)
            }
            assertEquals(PendingDecryptRetrySql.INDEX_NAMES.toSet(), indexes(db), "the device state before the upgrade")
            val step = MessengerDatabase.registeredMigrations().single { it.startVersion == 23 }
            assertEquals(24, step.endVersion)
            step.migrate(adapter(db))
            assertTrue(indexes(db).isEmpty(), "Room must not see the unmodeled partial indexes: ${indexes(db)}")
        }
    }

    @Test fun everyVersionStepIsRegisteredOnceAndWrapped() {
        val steps = MessengerDatabase.registeredMigrations()
        assertEquals((1 until 24).toList(), steps.map { it.startVersion })
        steps.forEach { assertEquals(it.startVersion + 1, it.endVersion) }
        // A wrapped step drops the indexes even when its own work touches other tables only.
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE pending_decrypts(clientMessageId TEXT NOT NULL PRIMARY KEY, senderId TEXT NOT NULL, sequence INTEGER NOT NULL, lastErrorClass TEXT)")
                sql.execute("CREATE TABLE chat_participants(userId TEXT PRIMARY KEY, username TEXT NOT NULL, displayName TEXT NOT NULL, publicNumber TEXT, publicUsername TEXT)")
                sql.execute(PendingDecryptRetrySql.INDEX_ALL)
            }
            steps.last().migrate(adapter(db))
            assertTrue(indexes(db).isEmpty())
        }
    }

    @Test fun indexNamesMatchTheCreateStatements() {
        assertTrue(PendingDecryptRetrySql.INDEX_ALL.contains(" " + PendingDecryptRetrySql.INDEX_NAMES[0] + " "))
        assertTrue(PendingDecryptRetrySql.INDEX_SENDER.contains(" " + PendingDecryptRetrySql.INDEX_NAMES[1] + " "))
    }
}
