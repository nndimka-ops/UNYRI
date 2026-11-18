package com.messenger.app.ui.chats

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.messenger.app.MessengerApp
import com.messenger.app.domain.repository.*
import com.messenger.app.domain.model.ChatSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ComposerBatchRecoveryInstrumentedTest {
    @Test fun failedAtomicAdmissionKeepsDraftAndRetryDoesNotDuplicateCaptionOrIds() = runBlocking {
        val app=ApplicationProvider.getApplicationContext<MessengerApp>()
        val workspace=mutableMapOf("mediaCaption" to "Batch caption")
        data class Queued(val id:String,val album:String?,val caption:String?)
        val accepted=mutableListOf<Queued>();val attempted=mutableListOf<String>();var failSecond=true
        val auth=object:AuthRepository by app.authRepository {
            override fun currentSessionEpoch()=100
            override suspend fun reconcileHistoryContext()=Unit
            override suspend fun<T> withWorkspaceWrite(epochAtStart:Int,block:suspend()->T):T?=if(epochAtStart==100)block()else null
        }
        val chats=object:ChatRepository by app.chatRepository {
            override fun observeChat(chatId:String)=flowOf<ChatSummary?>(null)
            override suspend fun getChat(chatId:String):Result<ChatSummary> = Result.failure(IllegalStateException("isolated"))
        }
        val messages=object:MessageRepository by app.messageRepository {
            override suspend fun syncLatest(chatId:String)=Result.success(Unit)
            override suspend fun chatValue(chatId:String,name:String)=workspace[name]
            override suspend fun saveChatValue(chatId:String,name:String,value:String?){if(value==null)workspace.remove(name)else workspace[name]=value}
            override suspend fun queueMediaBatch(chatId:String,media:List<QueuedMediaDraft>):Result<Unit> {
                attempted+=media.map {it.id}
                if(failSecond)return Result.failure(java.io.IOException("isolated admission error"))
                accepted+=media.map {Queued(it.id,it.albumId,it.caption)};return Result.success(Unit)
            }
            override suspend fun queueComposerMediaBatch(chatId:String,media:List<QueuedMediaDraft>,selectionUris:Set<String>,caption:String?,batchKey:String?,captionRevision:String?):Result<Unit> {
                val result=queueMediaBatch(chatId,media)
                if(result.isSuccess) { batchKey?.let {workspace.remove(it)};workspace.remove("mediaAlbum");workspace.remove("mediaCaption") }
                return result
            }
        }
        val sources=(0..2).map {i->File(app.cacheDir,"${UUID.randomUUID()}/${if(i==2)"file.pdf" else "photo.jpg"}").apply {parentFile.mkdirs();writeText("fixture")}}.toMutableList()
        val store=ViewModelStore()
        val model=withContext(Dispatchers.Main.immediate){ChatDetailViewModel(chats,messages,auth,app.attachmentManager,app.voicePlayerController,"isolated-batch").also{store.put("batch",it)}}
        try {
            val photos=sources.take(2).map(Uri::fromFile);val files=sources.drop(2).map(Uri::fromFile)
            val first=CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main.immediate){model.sendComposerAttachments(photos,files,"Batch caption","image",{},{} ){first.complete(it)}}
            assertFalse(withTimeout(5000){first.await()});assertEquals(0,accepted.size)
            val batchKey=workspace.keys.single {it.startsWith("composerAlbum:")}
            val album=workspace[batchKey];assertNotNull(album)
            assertEquals("Batch caption",workspace["mediaCaption"])
            val firstIds=attempted.toList();failSecond=false
            val retry=CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main.immediate){model.sendComposerAttachments(photos,files,"Batch caption","image",{},{} ){retry.complete(it)}}
            assertTrue(withTimeout(5000){retry.await()})
            assertEquals(3,accepted.size);assertEquals(3,accepted.map{it.id}.distinct().size)
            assertEquals(firstIds,accepted.map{it.id});assertTrue(accepted.all{it.album==album})
            assertEquals(1,accepted.count{!it.caption.isNullOrBlank()});assertNull(workspace[batchKey])
            assertNull(model.attachmentError.value)
            val nextFile=File(app.cacheDir,"${UUID.randomUUID()}/next.pdf").apply {parentFile.mkdirs();writeText("next batch")};sources+=nextFile
            val next=CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main.immediate){model.sendComposerAttachments(emptyList(),listOf(Uri.fromFile(nextFile)),null,"image",{},{} ){next.complete(it)}}
            assertTrue(withTimeout(5000){next.await()});assertNotEquals(album,accepted.last().album)
            assertEquals(4,accepted.map{it.id}.distinct().size)
        }finally{
            withContext(Dispatchers.Main.immediate){store.clear()}
            sources.forEach{it.delete();it.parentFile.delete()}
        }
    }
}
