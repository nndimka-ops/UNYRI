package com.messenger.app.ui.chats

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.MessengerApp
import com.messenger.app.domain.repository.MessageRepository
import com.messenger.app.domain.model.MessageStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only: sends synthetic media to the operator's test chat. */
class GalleryLiveBatchInstrumentedTest {
    @Test fun requeueRejectedMessagesWithOriginalIds() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit live recovery authorization",args.getString("cocoRecover")=="true")
        val ids=args.getString("retryIds").orEmpty().split(',').filter{it.isNotBlank()}
        require(ids.size in 1..50)
        ids.forEach{UUID.fromString(it)}
        val app=ApplicationProvider.getApplicationContext<MessengerApp>()
        for(id in ids) app.messageRepository.retry(id).getOrThrow()
        File(app.getExternalFilesDir(null),"capsule-requeued-result.json").writeText(org.json.JSONObject().put("requeued",ids.size).put("sameIds",true).toString())
    }
    @Test fun mixedTenItemGalleryBatchReachesServerAndTextCanSendDuringUpload() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit live test authorization",args.getString("cocoLive")=="true")
        val chat=args.getString("cocoChat") ?: error("Missing test chat")
        val withDocuments=args.getString("cocoFiles")=="true"
        val app=ApplicationProvider.getApplicationContext<MessengerApp>()
        val tag="capsule-batch-${UUID.randomUUID()}"
        if(args.getString("cocoUi")=="true") {
            val owner=(app.authRepository.authState.value as com.messenger.app.domain.model.AuthSessionState.LoggedIn).user.id
            app.startActivity(com.messenger.app.push.NotificationIntents.open(app,
                com.messenger.app.push.NotificationTapTarget(app.accountWorkspaces.activeKey, owner,
                    app.authRepository.currentDeviceId()!!, chat, UUID.randomUUID().toString())))
            delay(1500)
            delay(1000)
        }
        val folder=File(app.cacheDir,tag).apply { mkdirs() }
        val video=createVideoTestFixture(File(folder,"$tag-video-1.mp4"))
        val inputs=(0..9).map { index ->
            val document=withDocuments&&(index==1||index==6)
            val source=File(folder,"$tag-$index.${if(document)"pdf" else if(index==3||index==8) "mp4" else "jpg"}")
            if(index==3||index==8) video.copyTo(source,true)
            else if(document) source.writeText("%PDF-1.4 COCO synthetic document $index")
            else {
                val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.rgb(30+index*15,75+index*8,140-index*7))
                try {source.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)}}finally{bitmap.recycle()}
            }
            source
        }
        data class Queued(val id:String,val kind:String,val album:String?)
        val queued=java.util.Collections.synchronizedList(mutableListOf<Queued>())
        val repository=object:MessageRepository by app.messageRepository {
            override suspend fun queueComposerMediaBatch(chatId:String,media:List<com.messenger.app.domain.repository.QueuedMediaDraft>,selectionUris:Set<String>,caption:String?,batchKey:String?,captionRevision:String?):Result<Unit> {
                val result=app.messageRepository.queueComposerMediaBatch(chatId,media,selectionUris,caption,batchKey,captionRevision)
                if(result.isSuccess)media.forEach {queued.add(Queued(it.id,it.kind,it.albumId))}
                return result
            }
            override suspend fun queueMediaBatch(chatId:String,media:List<com.messenger.app.domain.repository.QueuedMediaDraft>):Result<Unit> {
                val result=app.messageRepository.queueMediaBatch(chatId,media)
                if(result.isSuccess)media.forEach {queued.add(Queued(it.id,it.kind,it.albumId))}
                return result
            }
            override suspend fun queueMedia(chatId:String,id:String,uri:String,kind:String,caption:String?,duration:Long,albumId:String?):Result<Unit> {
                val result=app.messageRepository.queueMedia(chatId,id,uri,kind,caption,duration,albumId)
                if(result.isSuccess)queued.add(Queued(id,kind,albumId))
                return result
            }
        }
        val store=ViewModelStore()
        val model=withContext(Dispatchers.Main.immediate){ChatDetailViewModel(app.chatRepository,repository,app.authRepository,app.attachmentManager,app.voicePlayerController,chat).also{store.put("live-batch",it)}}
        try {
            val staged=inputs.map {model.stageMediaDraft(Uri.fromFile(it))}
            val wanted=staged.map {File(requireNotNull(it.path)).parentFile!!.name}.toSet()
            val snapshots=java.util.Collections.synchronizedList(mutableListOf<List<String>>())
            val observer=launch(Dispatchers.Default) {repository.observeMessages(chat).collect {list->
                val members=list.filter {it.clientMessageId in wanted}
                if(members.isNotEmpty()) {
                    val rows=com.messenger.app.ui.calls.chatTimeline(members,emptyList())
                    snapshots.add(rows.flatMap {it.messages}.map {it.clientMessageId})
                }
            }}
            if(args.getString("cocoObserve")=="true")delay(8000) // Operator opens the actual chat for screen capture.
            val completion=CompletableDeferred<Boolean>()
            val quality=gallerySendQuality("file",false) // Exact reported legacy preference.
            withContext(Dispatchers.Main.immediate){model.sendComposerAttachments(
                staged.filterIndexed {i,_->!withDocuments||(i!=1&&i!=6)},staged.filterIndexed {i,_->withDocuments&&(i==1||i==6)},
                "COCO mixed gallery QA",quality,onPhotoQueued={},onFileQueued={}){completion.complete(it)}}
            assertTrue(withTimeout(30_000){completion.await()})
            assertEquals(10,queued.size);assertEquals(10,queued.map{it.id}.distinct().size)
            assertEquals(if(withDocuments)6 else 8,queued.count{it.kind=="image"});assertEquals(2,queued.count{it.kind=="video"})
            assertEquals(if(withDocuments)2 else 0,queued.count{it.kind=="file"})
            assertEquals(1,queued.map{it.album}.distinct().size);assertNotNull(queued.first().album)
            val ids=queued.map{it.id}.toSet()
            val caption="COCO text while media upload $tag"
            assertTrue(model.sendComposerText(caption))
            val messages=withTimeout(90_000){repository.observeMessages(chat).first { list ->
                ids.all { id ->list.any{it.clientMessageId==id&&it.serverId!=null&&it.status in setOf(MessageStatus.SENT,MessageStatus.DELIVERED,MessageStatus.READ)}} &&
                    list.any {it.text==caption&&it.serverId!=null}
            }}
            val sent=messages.filter{it.clientMessageId in ids}
            assertEquals(10,sent.size)
            assertTrue(sent.all {it.albumId==queued.first().album})
            val timeline=com.messenger.app.ui.calls.chatTimeline(sent,emptyList())
            assertEquals(1,timeline.size);assertEquals(10,timeline.single().messages.size)
            val attachments=withTimeout(15_000){repository.observeAttachments(chat).first{list->ids.all{id->list.any{it.clientMessageId==id&&it.uploadState==com.messenger.app.domain.model.AttachmentUploadState.SENT}}}}
            assertEquals(if(withDocuments)6 else 8,attachments.count{it.clientMessageId in ids&&it.mimeType.startsWith("image/")})
            assertEquals(2,attachments.count{it.clientMessageId in ids&&it.mimeType.startsWith("video/")})
            observer.cancelAndJoin()
            val expected=queued.map {it.id}
            assertTrue("Live projection must be observed",snapshots.isNotEmpty())
            assertTrue("No partial album or reshuffling during real upload",snapshots.all {it==expected})
            if(args.getString("cocoUi")=="true") {
                delay(500)
                val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(app.getExternalFilesDir(null),"stable-live-${if(withDocuments)"mixed"else"media"}.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG,100,it)
                };screenshot.recycle()
            }
            File(app.getExternalFilesDir(null),"capsule-live-batch-result.json").writeText(org.json.JSONObject()
                .put("items",10).put("photos",if(withDocuments)6 else 8).put("videos",2).put("files",if(withDocuments)2 else 0).put("allServerAccepted",true)
                .put("timelineGroups",timeline.size)
                .put("oneAlbum",true).put("textAccepted",true).put("stableSnapshots",snapshots.size)
                .put("noPartialSnapshots",true).put("stableOrderThroughoutUpload",true).put("album",queued.first().album).toString())
        } finally {
            withContext(Dispatchers.Main.immediate){store.clear()}
            // Only source fixtures are removed; queued/accepted durable copies remain owned by the app.
            inputs.forEach {it.delete()};video.delete();folder.delete()
        }
    }
}
