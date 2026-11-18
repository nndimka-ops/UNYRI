package com.messenger.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.data.local.message.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptPersistenceInstrumentedTest {
    @Test fun unreadExcludesLifecycleAndReceiptPassesOldDecryptFailure() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MessengerDatabase::class.java).build()
        try {
            db.chatDao().upsertChatMetadata("chat", "direct", "2026-01-01", null)
            db.chatDao().upsertParticipant(com.messenger.app.data.local.chat.ChatParticipantEntity("peer", "peer", "Peer", null, 9, true))
            db.chatDao().upsertMember(com.messenger.app.data.local.chat.ChatMemberEntity("chat", "peer", "member"))
            val cachedPeer = db.chatDao().observeChat("chat", "me").first()!!
            assertEquals(9L, cachedPeer.otherAvatarVersion)
            assertEquals(true, cachedPeer.otherHasAvatar)
            for (i in 1..5) db.messageDao().upsert(MessageEntity(
                clientMessageId = "m$i", serverId = "m$i", chatId = "chat", senderId = "peer",
                text = "test", sequence = i.toLong(), createdAt = "2026-01-01", status = if (i == 2) "decrypt_failed" else "delivered",
                kind = if (i == 4) "reaction" else "content", hiddenLocally = i == 5,
            ))
            assertEquals(3, db.chatDao().observeChats("me").first().single().unreadCount)
            assertEquals(3L, db.messageDao().receiptSequence("chat", "me", 10))
            db.messageDao().hideLocally("m2")
            assertEquals(3L, db.messageDao().receiptSequence("chat", "me", 10))
            db.chatDao().updateLastReadSequence("chat", 1)
            db.chatDao().updateLastReadSequence("chat", 0)
            assertEquals(1L, db.chatDao().locallyReadChats().single().lastReadSequence)
            db.chatDao().recordPeerReceipt("chat", 8, 7)
            db.chatDao().recordPeerReceipt("chat", 3, 2)
            db.chatDao().upsertChatMetadata("chat", "direct", "2026-01-01", null)
            assertEquals(7L, db.chatDao().cachedChat("chat")!!.peerReadSequence)
            // A REST response started before a realtime arrival must not move
            // the chat back down the list when it finally reaches the cache.
            db.chatDao().touchLastMessageAt("chat", "2026-01-03T12:00:00Z")
            db.chatDao().upsertChatMetadata("chat", "direct", "2026-01-01", "2026-01-02T12:00:00Z")
            assertEquals("2026-01-03T12:00:00Z", db.chatDao().cachedChat("chat")!!.lastMessageAt)
            db.chatDao().upsertChatMetadata("chat", "direct", "2026-01-01", null)
            assertEquals("2026-01-03T12:00:00Z", db.chatDao().cachedChat("chat")!!.lastMessageAt)
        } finally { db.close() }
    }

    @Test fun migrationKeepsExistingLocalReadCursor() {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        val db = helper.writableDatabase
        try {
            db.execSQL("CREATE TABLE chats (chatId TEXT PRIMARY KEY, lastReadSequence INTEGER NOT NULL)")
            db.execSQL("INSERT INTO chats VALUES ('chat', 42)")
            MessengerDatabase.MIGRATION_10_11.migrate(db)
            db.execSQL("CREATE TABLE chat_participants (userId TEXT PRIMARY KEY, displayName TEXT NOT NULL)")
            db.execSQL("INSERT INTO chat_participants VALUES ('peer', 'Preserved')")
            MessengerDatabase.MIGRATION_11_12.migrate(db)
            db.query("SELECT displayName, avatarVersion, hasAvatar FROM chat_participants").use {
                it.moveToFirst(); assertEquals("Preserved", it.getString(0)); assertEquals(0L, it.getLong(1)); assertEquals(0, it.getInt(2))
            }
            db.query("SELECT lastReadSequence, peerReadSequence FROM chats").use {
                it.moveToFirst(); assertEquals(42L, it.getLong(0)); assertEquals(0L, it.getLong(1))
            }
        } finally { helper.close() }
    }
}
