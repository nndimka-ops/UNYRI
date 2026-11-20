package com.messenger.app.data.local.chat

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import kotlin.test.*

/** B-056 (POCO, 19 Nov): Android's execSQL throws for a statement that returns a row
 *  («Queries can be performed using SQLiteDatabase query or rawQuery methods only») and
 *  `PRAGMA secure_delete=ON` returns the new value - the app crashed at start. The JDBC
 *  contracts accept it, so this fake behaves like Android. */
class MessageDeletionSqlRunTest {
    private class AndroidLike {
        val executed = mutableListOf<String>()
        val queried = mutableListOf<String>()
        var transactions = 0
        val db = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args!![0] as String
                    if (sql.trimStart().startsWith("PRAGMA", ignoreCase = true)) {
                        throw IllegalStateException("Queries can be performed using SQLiteDatabase query or rawQuery methods only.")
                    }
                    executed += sql; null
                }
                "query" -> {
                    queried += args!![0] as String
                    // Plain JVM tests have no framework cursor implementation: a minimal Cursor proxy.
                    Proxy.newProxyInstance(android.database.Cursor::class.java.classLoader,
                        arrayOf(android.database.Cursor::class.java)) { _, m, _ -> if (m.name == "moveToFirst") true else null }
                }
                "beginTransaction" -> { transactions++; null }
                "setTransactionSuccessful", "endTransaction" -> null
                else -> error(method.name)
            }
        } as SupportSQLiteDatabase
    }

    @Test fun pragmasRunAsQueriesAndEverythingElseThroughExecSql() {
        val fake = AndroidLike()
        (MessageDeletionSql.install + MessageDeletionSql.backfill).forEach { MessageDeletionSql.run(fake.db, it) }
        assertEquals(listOf("PRAGMA secure_delete=ON"), fake.queried)
        assertEquals(1, fake.transactions, "the PRAGMA runs on the writer connection")
        assertTrue(fake.executed.isNotEmpty() && fake.executed.none { it.trimStart().startsWith("PRAGMA", ignoreCase = true) })
    }
}
