package com.messenger.app.data.sync

import com.messenger.app.data.local.message.*
import com.messenger.app.data.signal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.signal.libsignal.protocol.*
import java.sql.DriverManager
import kotlin.test.*

class RetainedDecryptRetryParityTest {
    private fun entry(id: String, sequence: Long, sender: String = "other", count: Int = 0, error: String? = null) =
        PendingDecryptEntity(id, id, "chat-1", sender, "device", sequence, "2026-01-01T00:00:00Z",
            2, "plaintext-for:$id", "content", null, count, error)

    private fun pipeline(dao: MessageDao, decryptor: MessageDecryptor, auth: FakeAuthRepository = FakeAuthRepository()) =
        MessageIngestionPipeline(dao, InMemoryChatDao(), InMemoryAttachmentDao(), InMemoryReactionDao(), decryptor,
            auth, FakeDeliveredReporter(), InMemorySyncCursorStore())

    /** Simulate closing/reopening only durable rows, with no pipeline/selection state carried over. */
    private suspend fun reopen(old: InMemoryMessageDao, scopes: List<String> = listOf("all")): InMemoryMessageDao {
        val next = InMemoryMessageDao()
        old.pendingDecrypts().forEach { next.insertPendingDecrypt(it) }
        old.all().forEach { next.upsert(it) }
        for (scope in scopes) old.composerValue("__decrypt_retry__", scope)?.let { next.saveMediaJob("__decrypt_retry__", scope, it) }
        return next
    }

    @Test fun `128 failing saturated rows cannot starve recoverable row after reopening`() = runTest {
        val dao = InMemoryMessageDao(); val crypto = FakeMessageDecryptor()
        for (i in 1..129) {
            dao.insertPendingDecrypt(entry("id-$i", i.toLong(), count = Int.MAX_VALUE))
            if (i <= 128) crypto.failingMessageIds += "id-$i"
        }
        pipeline(dao, crypto).recoverRetainedAfterSync()
        assertEquals(128, crypto.decryptOrder.size)
        assertFalse("id-129" in crypto.decryptOrder)
        assertTrue(dao.pendingDecrypts().all { it.retryCount == Int.MAX_VALUE })
        val reopened = reopen(dao)
        pipeline(reopened, crypto).recoverRetainedAfterSync()
        assertEquals("id-129", crypto.decryptOrder[128])
        assertEquals("decoded:id-129", reopened.findByClientMessageId("id-129")?.text)
        assertFalse(reopened.pendingDecrypts().any { it.clientMessageId == "id-129" })
        assertTrue(reopened.pendingDecrypts().all { it.retryCount == Int.MAX_VALUE })
        reopened.insertPendingDecrypt(entry("id-1", 1))
        assertEquals(Int.MAX_VALUE, reopened.pendingDecryptRetryCount("id-1"))
    }

    @Test fun `ties wrap once and sender cursors stay independent from global scope`() = runTest {
        val dao = InMemoryMessageDao()
        for (id in listOf("c", "a", "b")) dao.insertPendingDecrypt(entry(id, 5, "one"))
        dao.insertPendingDecrypt(entry("z", 5, "two"))
        assertEquals(listOf("a", "b", "c", "z"), dao.pendingDecryptRetryBatch(null).map { it.clientMessageId })
        dao.advancePendingDecryptRetryCursor(entry("b", 5), "one")
        assertEquals(listOf("c", "a", "b"), dao.pendingDecryptRetryBatch("one").map { it.clientMessageId })
        assertEquals(listOf("a", "b", "c", "z"), dao.pendingDecryptRetryBatch(null).map { it.clientMessageId })
        dao.advancePendingDecryptRetryCursor(entry("c", 5), null)
        val reopened = reopen(dao, listOf("all", "sender:one"))
        assertEquals(listOf("z", "a", "b", "c"), reopened.pendingDecryptRetryBatch(null).map { it.clientMessageId })
        assertEquals(listOf("c", "a"), reopened.pendingDecryptRetryBatch("one", 2).map { it.clientMessageId })
        assertEquals(listOf("z"), reopened.pendingDecryptRetryBatch("two").map { it.clientMessageId })
        reopened.saveMediaJob("__decrypt_retry__", "all", "broken JSON")
        assertEquals("a", reopened.pendingDecryptRetryBatch(null).first().clientMessageId)
        assertFailsWith<IllegalArgumentException> { reopened.pendingDecryptRetryBatch(null, 129) }
    }

    @Test fun `typed permanent errors retain ciphertext and explicit retry can recover them`() = runTest {
        val failures = listOf(DuplicateMessageException("duplicate"), InvalidMessageException("malformed"),
            UnsupportedSignalMessageTypeException(), MalformedSignalCiphertextException(IllegalArgumentException()),
            InvalidVersionException("version"), LegacyMessageException("legacy"))
        val expected = listOf("permanent_duplicate", "permanent_malformed_ciphertext", "permanent_unsupported_type",
            "permanent_malformed_ciphertext", "permanent_unsupported_type", "permanent_unsupported_type")
        failures.zip(expected).forEach { (error, name) -> assertEquals(name, retainedDecryptErrorClass(error)) }
        assertEquals("IdentityChangedException", retainedDecryptErrorClass(IdentityChangedException("other", listOf(1))))
        assertEquals("IllegalStateException", retainedDecryptErrorClass(IllegalStateException()))
        val dao = InMemoryMessageDao(); val normal = FakeMessageDecryptor()
        var fail = true
        val crypto = object : MessageDecryptor by normal {
            override suspend fun decryptRetained(dto: com.messenger.app.data.api.MessageDto, signalDeviceId: Int): ByteArray {
                if (fail) throw failures[dto.sequence.toInt() - 1]
                return normal.decryptRetained(dto, signalDeviceId)
            }
        }
        failures.indices.forEach { dao.insertPendingDecrypt(entry("p-$it", it + 1L)) }
        pipeline(dao, crypto).recoverRetainedAfterSync()
        assertEquals(expected.toSet(), dao.pendingDecrypts().map { it.lastErrorClass }.toSet())
        assertEquals(failures.size, dao.pendingDecrypts().size)
        assertTrue(dao.pendingDecrypts().all { it.ciphertext == "plaintext-for:${it.clientMessageId}" })
        assertTrue(dao.pendingDecryptRetryBatch(null).isEmpty())
        assertEquals(failures.size, dao.pendingDecryptsForSender("other").size)
        fail = false
        pipeline(dao, crypto).retryPendingDecrypts("other", automatic = false)
        assertTrue(dao.pendingDecrypts().isEmpty())
    }

    @Test fun `cancelled attempt saves rotation and stale session does no work`() = runTest {
        val dao = InMemoryMessageDao(); val normal = FakeMessageDecryptor()
        dao.insertPendingDecrypt(entry("a", 1)); dao.insertPendingDecrypt(entry("b", 2))
        val entered = CompletableDeferred<Unit>()
        val held = object : MessageDecryptor by normal {
            override suspend fun decryptRetained(dto: com.messenger.app.data.api.MessageDto, signalDeviceId: Int): ByteArray {
                entered.complete(Unit); awaitCancellation()
            }
        }
        val job = launch { pipeline(dao, held).recoverRetainedAfterSync() }
        entered.await(); job.cancelAndJoin()
        assertEquals("b", reopen(dao).pendingDecryptRetryBatch(null).first().clientMessageId)
        val auth = FakeAuthRepository().apply { switchEpochOnWrite = true }
        pipeline(dao, normal, auth).recoverRetainedAfterSync()
        assertTrue(normal.decryptOrder.isEmpty())
    }

    @Test fun `Room SQL ranges use partial indexes and enforce limit and eligibility on SQLite`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use {
                it.execute("CREATE TABLE pending_decrypts(clientMessageId TEXT PRIMARY KEY,sequence INTEGER,senderId TEXT,lastErrorClass TEXT)")
                it.execute(PendingDecryptRetrySql.INDEX_ALL); it.execute(PendingDecryptRetrySql.INDEX_SENDER)
                // Idempotent index-only upgrade also applies to an existing database.
                it.execute(PendingDecryptRetrySql.INDEX_ALL); it.execute(PendingDecryptRetrySql.INDEX_SENDER)
                for (i in 1..129) it.execute("INSERT INTO pending_decrypts VALUES('id-${i.toString().padStart(3,'0')}',$i,'one',NULL)")
                it.execute("INSERT INTO pending_decrypts VALUES('permanent',0,'one','permanent_duplicate')")
            }
            for (base in listOf(PendingDecryptRetrySql.ALL, PendingDecryptRetrySql.SENDER)) {
                for (range in listOf("", PendingDecryptRetrySql.AFTER, PendingDecryptRetrySql.THROUGH)) {
                    val sql = (base + range + PendingDecryptRetrySql.ORDER)
                        .replace(":senderId", "'one'").replace(":sequence", "128").replace(":client", "'id-128'").replace(":limit", "128")
                    db.createStatement().use { stmt ->
                        val rows = mutableListOf<String>()
                        stmt.executeQuery(sql).use { while (it.next()) rows += it.getString("clientMessageId") }
                        assertTrue(rows.size <= 128); assertFalse("permanent" in rows)
                        if (range == PendingDecryptRetrySql.AFTER) assertEquals(listOf("id-129"), rows)
                        else assertEquals(128, rows.size)
                        val plan = mutableListOf<String>()
                        stmt.executeQuery("EXPLAIN QUERY PLAN $sql").use { while (it.next()) plan += it.getString("detail") }
                        assertTrue(plan.any { it.contains(if (base == PendingDecryptRetrySql.ALL) "index_pending_decrypts_retry" else "index_pending_decrypts_sender_retry") }, plan.toString())
                        assertFalse(plan.any { it.contains("TEMP B-TREE") }, plan.toString())
                    }
                }
            }
        }
    }
}
