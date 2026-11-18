package com.messenger.app.ui.chats

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.messenger.app.data.attachment.PendingMediaPreview
import com.messenger.app.data.local.chat.MessengerDatabase
import com.messenger.app.data.local.message.*
import com.messenger.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaBatchAdmissionInstrumentedTest {
    @Test fun originalVideoKeepsItsExistingVideoCoverClassification() {
        val layout=PendingMediaPreview.presentation(JSONObject().put("kind","file").put("mime","video/mp4"),"2026-10-26T10:00:00Z",0)
        assertTrue(layout.getBoolean("visual"))
    }
    private fun row(index:Int,server:String?=null)=QueuedMediaRow(MessageEntity("id-$index",server,"chat","me","",null,
        "2026-10-26T10:00:00Z","pending",albumId="batch"),"{}",
        PendingMediaPreview.presentation(JSONObject().put("kind","image").put("width",4032).put("height",3024),"2026-10-26T10:00:00Z",index).toString())

    @Test fun roomPublishesWholeBatchAndKeepsLayoutAfterAcknowledgement() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,MessengerDatabase::class.java).build()
        try {
            val counts=java.util.Collections.synchronizedList(mutableListOf<Int>())
            val observer=launch(Dispatchers.Default){db.messageDao().observeMessages("chat").collect {counts+=it.size}}
            withTimeout(5000){while(counts.isEmpty())delay(10)}
            db.messageDao().enqueueMediaBatch((0..9).map {row(it)})
            withTimeout(5000){while(counts.last()!=10)delay(10)}
            assertEquals(listOf(0,10),counts.distinct())
            // Idempotent retries cannot replace the original position or duplicate the batch.
            db.messageDao().enqueueMediaBatch((0..9).map {row(it).copy(presentation="{}")})
            assertEquals(10,db.workspaceDao().mediaPresentations("chat").size)
            db.messageDao().upsert(row(0).message.copy(serverId="server",sequence=1,status="sent",createdAt="2026-10-26T10:01:00Z"))
            db.workspaceDao().remove("chat","upload:id-0")
            val message=Message("id-0","server","chat","me","",1,"2026-10-26T10:01:00Z",MessageStatus.SENT)
            val reserved=PendingMediaPreview.reserve(listOf(message),db.workspaceDao().mediaPresentations("chat")).single()
            assertEquals("2026-10-26T10:00:00Z",reserved.localMediaPresentation?.createdAt)
            assertEquals(4f/3f,reserved.localMediaPresentation!!.aspectRatio!!,.0001f)
            observer.cancelAndJoin()
        } finally {db.close()}
    }
    @Test fun failedTransactionPublishesNoPartialAlbumOrOrphanJobs() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),MessengerDatabase::class.java).build()
        try {
            // Inject a write error on the second row; the FIRST row must roll back too.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER qa_fail_second BEFORE INSERT ON messages WHEN NEW.clientMessageId='id-1' BEGIN SELECT RAISE(ABORT, 'synthetic write failure'); END")
            assertTrue(runCatching {db.messageDao().enqueueMediaBatch(listOf(row(0),row(1)))}.isFailure)
            assertTrue(db.messageDao().observeMessages("chat").first().isEmpty())
            assertTrue(db.workspaceDao().mediaJobs("chat").isEmpty())
            assertTrue(db.workspaceDao().mediaPresentations("chat").isEmpty())
        } finally {db.close()}
    }
}
