package com.messenger.app.data.directtransfer

import android.content.Intent
import android.view.WindowManager
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.BuildConfig
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.data.api.ApiJson
import com.messenger.app.data.api.AuthApi
import com.messenger.app.domain.model.AuthSessionState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** QA only: real Main DI/Signal/outbox/chat card. Coordinator invocation is
 * explicit test orchestration, NOT a native Files-picker/consent-dialog claim. */
class MainDirectLifecycleNativeTest {
    @Test fun realMainOutboxAndIosReceiverLifecycle(): Unit = runBlocking(Dispatchers.IO) {
        val inst=InstrumentationRegistry.getInstrumentation()
        val context=inst.targetContext
        check(context.packageName=="com.messenger.app.directqa")
        val app=context.applicationContext as MessengerApp
        check(app.authRepository.authState.value is AuthSessionState.LoggedOut)
        val args=InstrumentationRegistry.getArguments()
        fun uuid(value:String)=value.also { require(UUID.fromString(it).toString()==it) }
        val run=uuid(requireNotNull(args.getString("run_id")))
        val peer=uuid(requireNotNull(args.getString("recipient_user_id")))
        val cancelOnly=args.getString("cancel_only")=="1"
        val smallCompleted=args.getString("small_completed")=="1"
        val name="mdlqa"+run.replace("-","").take(12)
        val password=UUID.randomUUID().toString()+UUID.randomUUID()+"Aa9!"
        val private=EncryptedSharedPreferences.create(context,"main-direct-$run-private",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        check(private.edit().putString("username",name).putString("password",password).commit())
        val folder=File(context.filesDir,"main-direct-$run").apply { mkdirs() }
        val seed=File(context.cacheDir,if(smallCompleted)"direct-owned-video-small-seed.mp4" else "direct-owned-video-seed.mp4")
        val input=File(File(context.cacheDir,"camera").apply { mkdirs() },"COCO-owned-original-$run.mp4")
        val expectedSize=if(smallCompleted)8388608L else 136315007L
        val expectedHash=if(smallCompleted)"48325a60e51fefac63d2db4ac4a9554b30138c82208d9b31664396757a55eef2" else "1f39cc74fe23b2efa2d228d9b95ce466ced07f88fc7fbb5a4c34209d83f0a930"
        check(seed.isFile && seed.length()==expectedSize)
        var owner:String?=null
        var scenario:ActivityScenario<MainActivity>?=null
        var observation:Job?=null
        val coordinator=app.directTransfers
        @Suppress("UNCHECKED_CAST")
        val live=coordinator.javaClass.getDeclaredField("records").apply { isAccessible=true }.get(coordinator) as StateFlow<Map<String,DirectTransferRecord>>
        suspend fun waitRecord(id:String,timeout:Long=300_000,predicate:(DirectTransferRecord)->Boolean):DirectTransferRecord =
            withTimeout(timeout) { live.mapNotNull { it[id] }.first(predicate) }
        suspend fun hostFile(name:String,timeout:Long=600_000):JSONObject {
            val file=File(folder,name)
            withTimeout(timeout) { while(!file.isFile)delay(100) }
            require(file.length() in 1..4096)
            return JSONObject(file.readText())
        }
        suspend fun screen(name:String) {
            delay(250)
            if(inst.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName)
                inst.uiAutomation.takeScreenshot()?.let { bitmap -> File(folder,"$name.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        }
        try {
            app.authRepository.register(name,password,"Own Main Direct QA").getOrThrow()
            val logged=withTimeout(60_000) { app.authRepository.authState.filterIsInstance<AuthSessionState.LoggedIn>().first() }
            check(logged.user.username==name);owner=logged.user.id
            check(private.edit().putString("user_id",owner).putString("device_id",app.authRepository.currentDeviceId()).commit())
            val epoch=app.authRepository.currentSessionEpoch()
            val chat=app.chatRepository.createOrOpenDirectChat(peer).getOrThrow().chatId
            scenario=ActivityScenario.launch<MainActivity>(com.messenger.app.push.NotificationIntents.open(context,
                com.messenger.app.push.NotificationTapTarget(app.accountWorkspaces.activeKey, owner!!,
                    app.authRepository.currentDeviceId()!!, chat, java.util.UUID.randomUUID().toString())).apply { data = null })
            scenario!!.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            withTimeout(60_000) { app.messageRepository.socketState.first { it==com.messenger.app.domain.repository.SocketConnectionState.CONNECTED } }
            File(folder,"identity.json").writeText(JSONObject().put("run",run).put("sender_user_id",owner)
                .put("sender_device_id",app.authRepository.currentDeviceId()).put("recipient_user_id",peer).put("chat_id",chat).put("username",name).toString())
            val readiness=hostFile("ready.json")
            check(readiness.getString("sender_user_id")==owner && readiness.getString("recipient_user_id")==peer)
            val seen=mutableSetOf<String>()
            observation=launch(Dispatchers.IO) {
                live.collect { records -> records.values.filter { it.chatId==chat }.forEach { record ->
                    if(record.ackSampleId>0) {
                        val key="${record.offer.transferId}:${record.attemptId}:${record.ackSampleId}"
                        if(seen.add(key))File(folder,"ack-samples.jsonl").appendText(JSONObject()
                            .put("transfer",record.offer.transferId).put("attempt",record.attemptId).put("sample",record.ackSampleId)
                            .put("offset",record.durableOffset).put("speed",record.speedBytesPerSecond).toString()+"\n")
                    }
                    File(folder,"latest-safe-state.json").writeText(JSONObject().put("transfer",record.offer.transferId).put("state",record.state)
                        .put("offset",record.durableOffset).put("reason",record.reason).put("attempt",record.attemptId)
                        .put("registered",record.registered).put("completed",record.completed).put("closed",record.mailboxClosed)
                        .put("completionReceived",record.completionReceived).put("completionAcknowledged",record.completionAcknowledged)
                        .put("closePending",record.closePending).put("pendingTypes",org.json.JSONArray(record.pending.map { it.control.type })).toString())
                } }
            }
            check(input.parentFile!!.mkdirs() || input.parentFile!!.isDirectory)
            seed.inputStream().use { source -> input.outputStream().use { target -> source.copyTo(target,64*1024) } }
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",input)
            check(context.contentResolver.getType(uri)=="video/mp4")
            val previous=live.value.keys.toSet()
            assertTrue(coordinator.offerOriginal(chat,uri,epoch))
            val first=withTimeout(60_000) { live.first { records -> records.values.any { it.chatId==chat && it.role=="sender" && it.offer.transferId !in previous } }
                .values.single { it.chatId==chat && it.role=="sender" && it.offer.transferId !in previous }.offer.transferId }
            val registered=waitRecord(first) { it.registered }
            val sent=withTimeout(60_000) { app.messageRepository.observeMessages(chat).mapNotNull { items -> items.firstOrNull {
                it.clientMessageId==registered.offer.offerClientMessageId && it.serverId!=null } }.first() }
            File(folder,"first-offer.json").writeText(JSONObject().put("transfer_id",first).put("client_message_id",sent.clientMessageId)
                .put("server_message_id",sent.serverId).put("actual_main_outbox_ack",true).put("actual_mailbox_registered",true).toString())
            screen("first-offer-card")
            // Manual inspection is separate from the transport-progress budget.
            // Host publishes this only when the real recipient is ready to Accept.
            check(hostFile("inspection-done.json",600_000).getString("transfer_id")==first)
            if(smallCompleted) {
                val full=waitRecord(first,600_000) { it.completed && it.completionReceived && it.mailboxClosed }
                assertEquals(expectedSize,full.durableOffset);assertEquals(expectedHash,full.offer.sha256)
                assertEquals("video/mp4",full.offer.mimeType)
                screen("completed-small-original-card")
                File(folder,"proof.json").writeText(JSONObject().put("passed",true).put("scope","ActualMainDI explicitlyforced8MiB directoriginal; completed nativeUI/feedback peer checks require external receipts; NOT100MiB threshold proof")
                    .put("sender_user_id",owner).put("chat_id",chat).put("full_transfer",first).put("bytes",full.durableOffset).put("sha256",full.offer.sha256)
                    .put("completionReceived",full.completionReceived).put("completionAcknowledged",full.completionAcknowledged).put("closed",full.mailboxClosed)
                    .put("sender_pause_or_cancel_injected",false).put("native_files_picker_proof",false).toString())
                check(hostFile("allow-cleanup.json",1_200_000).getString("sender_user_id")==owner)
                return@runBlocking
            }
            if(cancelOnly) {
                val cancelled=waitRecord(first,600_000) { it.state=="cancelled" && it.mailboxClosed }
                require(cancelled.durableOffset in 1 until expectedSize)
                screen("receiver-cancelled-card")
                File(folder,"proof.json").writeText(JSONObject().put("passed",true).put("scope","ActualAndroidMainDI/outbox/coordinator and nativeiOS recipientmidstreamCancel; externalpeerreceipt required")
                    .put("sender_user_id",owner).put("chat_id",chat).put("receiver_cancel_transfer",first).put("receiver_cancel_offset",cancelled.durableOffset)
                    .put("sender_pause_or_cancel_injected",false).put("closed",cancelled.mailboxClosed).put("native_files_picker_proof",false).toString())
                check(hostFile("allow-cleanup.json",1_200_000).getString("sender_user_id")==owner)
                return@runBlocking
            }
            waitRecord(first) { it.durableOffset>=4L*1024*1024 }
            val paused=waitRecord(first) { it.state=="paused" && it.reason=="background" }
            require(paused.durableOffset>=4L*1024*1024 && paused.durableOffset<expectedSize)
            File(folder,"background.json").writeText(JSONObject().put("transfer_id",first).put("offset",paused.durableOffset)
                .put("attempt",paused.attemptId).put("reason",paused.reason).toString())
            screen("background-paused-card")
            val full=waitRecord(first,900_000) { it.completed && it.completionReceived && it.mailboxClosed }
            assertEquals(expectedSize,full.durableOffset);assertEquals(expectedHash,full.offer.sha256)
            assertEquals("video/mp4",full.offer.mimeType);require(full.attemptId!=paused.attemptId)
            File(folder,"full.json").writeText(JSONObject().put("transfer_id",first).put("bytes",full.durableOffset).put("sha256",full.offer.sha256)
                .put("paused_attempt",paused.attemptId).put("resumed_attempt",full.attemptId).put("closed",full.mailboxClosed).toString())
            screen("completed-original-card")
            check(hostFile("allow-second.json",300_000).getString("first_transfer_id")==first)
            val beforeSecond=live.value.keys.toSet()
            assertTrue(coordinator.offerOriginal(chat,uri,epoch))
            val second=withTimeout(60_000) { live.first { records -> records.values.any { it.chatId==chat && it.role=="sender" && it.offer.transferId !in beforeSecond } }
                .values.single { it.chatId==chat && it.role=="sender" && it.offer.transferId !in beforeSecond }.offer.transferId }
            File(folder,"second-offer.json").writeText(JSONObject().put("transfer_id",second).toString())
            val cancelled=waitRecord(second) { it.state=="cancelled" && it.mailboxClosed }
            require(cancelled.durableOffset in 1 until expectedSize)
            screen("receiver-cancelled-card")
            File(folder,"proof.json").writeText(JSONObject().put("passed",true).put("scope","ActualAndroidMainDI/Auth/Signal/outbox/mailbox/coordinator/chatcard and iOSnativeAccept/BGResume/Open/receiverCancel; externalpeerreceipt required")
                .put("sender_user_id",owner).put("chat_id",chat).put("full_transfer",first).put("bytes",full.durableOffset).put("sha256",full.offer.sha256)
                .put("background_prefix",paused.durableOffset).put("resumed_attempt",full.attemptId).put("receiver_cancel_transfer",second)
                .put("receiver_cancel_offset",cancelled.durableOffset).put("sender_pause_or_cancel_injected",false)
                .put("native_files_picker_proof",false).toString())
            check(hostFile("allow-cleanup.json",1_200_000).getString("sender_user_id")==owner)
        } catch(error:Exception) {
            File(folder,"failure.json").writeText(JSONObject().put("sender_user_id",owner).put("failure_class",error.javaClass.simpleName).put("preserved_for_root",true).toString())
            withContext(NonCancellable) { withTimeoutOrNull(1_200_000) { check(hostFile("release-cleanup.json",1_200_000).getString("sender_user_id")==owner) } }
            throw error
        } finally {
            observation?.cancel()
            val result=JSONObject().put("user_id",owner).put("username",name)
            try {
                val current=(app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user
                check(current?.id==owner && current?.username==name && private.getString("user_id",null)==owner)
                AuthApi(OkHttpClient(),ApiJson,BuildConfig.API_BASE_URL).deleteAccount(requireNotNull(app.authRepository.currentAccessToken()),password)
                result.put("deleted_through_normal_api",true);app.authRepository.logout();private.edit().clear().commit()
            } catch(error:Exception) { result.put("deleted_through_normal_api",false).put("failure_class",error.javaClass.simpleName) }
            File(folder,"cleanup.json").writeText(result.toString());scenario?.close();input.delete()
        }
    }
}
