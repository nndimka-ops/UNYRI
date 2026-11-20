package com.messenger.app.data.identity

import android.content.SharedPreferences
import androidx.sqlite.db.SupportSQLiteDatabase
import com.messenger.app.data.local.chat.MessengerDatabase
import java.lang.reflect.Proxy
import java.sql.DriverManager
import kotlin.test.*

class PeerCacheUpgradeTest {
    @Test fun room23To24ClearsLoginsWithoutLosingPublicIdentityOrNames() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE chat_participants(userId TEXT PRIMARY KEY, username TEXT NOT NULL, displayName TEXT NOT NULL, publicNumber TEXT, publicUsername TEXT)")
                sql.execute("INSERT INTO chat_participants VALUES('peer','private_login','Alice','123456789','alice'),('empty','','Bob',NULL,NULL)")
            }
            val adapter = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
                check(method.name == "execSQL")
                db.createStatement().use { it.execute(args!![0] as String) }; null
            } as SupportSQLiteDatabase
            repeat(2) { MessengerDatabase.MIGRATION_23_24.migrate(adapter) }
            db.createStatement().use { sql ->
                sql.executeQuery("SELECT * FROM chat_participants ORDER BY userId").use { rows ->
                    assertTrue(rows.next()); assertEquals("", rows.getString("username")); assertEquals("Bob", rows.getString("displayName"))
                    assertTrue(rows.next()); assertEquals("", rows.getString("username")); assertEquals("Alice", rows.getString("displayName"))
                    assertEquals("123456789", rows.getString("publicNumber")); assertEquals("alice", rows.getString("publicUsername"))
                    assertFalse(rows.next())
                }
            }
            assertEquals(23, MessengerDatabase.MIGRATION_23_24.startVersion)
            assertEquals(24, MessengerDatabase.MIGRATION_23_24.endVersion)
        }
    }

    @Test fun encryptedDirectoryUpgradeRunsOnceAndKeepsOwnAuthenticationProfile() {
        val values = mutableMapOf<String, Any>("offline_directory" to "private_peer_login", "offline_profile" to "own_profile")
        lateinit var edit: SharedPreferences.Editor
        edit = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "remove" -> { values.remove(args!![0]); edit }
                "putBoolean" -> { values[args!![0] as String] = args[1] as Boolean; edit }
                "commit" -> true
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean" -> values[args!![0]] ?: args[1]
                "edit" -> edit
                else -> error(method.name)
            }
        } as SharedPreferences
        PeerCacheUpgrade.clearDirectoryOnce(prefs)
        assertNull(values["offline_directory"]); assertEquals("own_profile", values["offline_profile"])
        values["offline_directory"] = "new_public_only_cache"
        PeerCacheUpgrade.clearDirectoryOnce(prefs)
        assertEquals("new_public_only_cache", values["offline_directory"])
    }
}
