package com.messenger.app.data.directtransfer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.messenger.app.BuildConfig
import com.messenger.app.MainActivity
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.api.ApiJson
import com.messenger.app.data.api.AuthApi
import com.messenger.app.data.api.ChatsApi
import com.messenger.app.data.call.LocalTextureRenderer
import com.messenger.app.data.call.CallApi
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.CallDto
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.CallMediaKind
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Host-controlled real normal app/Controller/UI session in only the owned QA UID. */
class OrdinaryCallUiNativeTest {
    @get:Rule val permissions = GrantPermissionRule.grant(android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS)

    @Test fun ownedOrdinaryCallAndPresentation(): Unit = runBlocking(Dispatchers.IO) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        check(context.packageName == "com.messenger.app.directqa")
        val app = context.applicationContext as MessengerApp
        check(app.authRepository.authState.value is AuthSessionState.LoggedOut) { "Never replace any existing account" }
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("run_id")).also { require(canonicalUuid(it)) }
        val peer = requireNotNull(args.getString("peer_user_id")).also { require(canonicalUuid(it)) }
        val incomingMode = args.getString("incoming_mode") == "true"
        val username = "dfcqa" + run.replace("-", "").take(12)
        val password = UUID.randomUUID().toString() + UUID.randomUUID().toString() + "Aa9!"
        val prefs = EncryptedSharedPreferences.create(context, "call-ui-$run-private",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        check(prefs.edit().putString("run", run).putString("username", username).putString("password", password).commit())
        val folder = File(context.filesDir, "call-ui-$run").apply { mkdirs() }
        val http = OkHttpClient()
        var ownedUser: String? = null
        var lifecycleScenario: ActivityScenario<MainActivity>? = null
        var lastCommand = 0L
        var finished = false
        val traceScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val traces = Channel<String>(512)
        traceScope.launch { for (entry in traces) File(folder,"transitions.jsonl").appendText(entry+"\n") }
        fun trace(value: JSONObject) { check(traces.trySend(value.put("at",System.currentTimeMillis()).toString()).isSuccess) }
        val callApiField = app.callController.javaClass.getDeclaredField("callApi").apply { isAccessible=true }
        val actualApi = callApiField.get(app.callController) as CallApi
        check(actualApi.javaClass.name=="com.messenger.app.data.call.CallApiImpl")
        val tracedApi = object: CallApi by actualApi {
            override suspend fun createCall(calleeUserId: String, mediaKind: String): CallDto {
                trace(JSONObject().put("kind","create_start").put("peer",calleeUserId).put("media",mediaKind))
                try { return actualApi.createCall(calleeUserId,mediaKind).also { trace(JSONObject().put("kind","create_result").put("call_id",it.id).put("server_state",it.state)) } }
                catch (error:Exception) {
                    val row=JSONObject().put("kind","create_failure").put("class",error.javaClass.simpleName)
                    if(error is AuthApiException.Api) row.put("status",error.httpStatus).put("code",error.code)
                    trace(row);throw error
                }
            }
        }
        callApiField.set(app.callController,tracedApi)
        check(callApiField.get(app.callController)===tracedApi)
        traceScope.launch(Dispatchers.Main.immediate,start=CoroutineStart.UNDISPATCHED) {
            app.callController.state.collect { state ->
                val call=when(state) {
                    is CallUiState.OutgoingRinging->state.call;is CallUiState.IncomingRinging->state.call;is CallUiState.Connecting->state.call
                    is CallUiState.Connected->state.call;is CallUiState.Reconnecting->state.call;is CallUiState.Ended->state.call;is CallUiState.Failed->state.call;else->null
                }
                val row=JSONObject().put("kind","controller_state").put("state",state.javaClass.simpleName).put("call_id",call?.callId)
                if(state is CallUiState.Ended) row.put("reason",state.reason.name)
                if(state is CallUiState.Failed) row.put("reason",state.reason.name)
                trace(row)
            }
        }
        traceScope.launch(Dispatchers.Main.immediate,start=CoroutineStart.UNDISPATCHED) {
            app.messageRepository.socketState.collect { state -> trace(JSONObject().put("kind","socket_state").put("value",state.toString())) }
        }
        try {
            app.authRepository.register(username, password, "Owned Call UI QA").getOrThrow()
            val logged = withTimeout(60_000) { app.authRepository.authState.filterIsInstance<AuthSessionState.LoggedIn>().first() }
            check(logged.user.username == username)
            ownedUser = logged.user.id
            check(prefs.edit().putString("user_id", ownedUser).putString("device_id", app.authRepository.currentDeviceId()).commit())
            val token = requireNotNull(app.authRepository.currentAccessToken())
            val chat = ChatsApi(http, ApiJson, BuildConfig.API_BASE_URL).createDirect(token, peer).chatId
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            lifecycleScenario = scenario
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            File(folder, "identity.json").writeText(JSONObject().put("run", run).put("user_id", ownedUser)
                .put("device_id", app.authRepository.currentDeviceId()).put("username", username).put("chat_id", chat).put("peer_user_id", peer).put("incoming_mode",incomingMode).toString())
            val ready = File(folder, "ready.json")
            withTimeout(600_000) { while (!ready.isFile) delay(100); require(ready.length()<=2048); check(JSONObject(ready.readText()).getString("user_id")==ownedUser) }
            if(incomingMode) {
                val expectedCaller = JSONObject(ready.readText()).getString("expected_peer_user_id").also { require(canonicalUuid(it)) }
                withTimeout(180_000) { while(app.callController.state.value !is CallUiState.IncomingRinging)delay(100) }
                check((app.callController.state.value as CallUiState.IncomingRinging).call.otherUserId == expectedCaller) { "Only the explicit owned host-barrier caller may be accepted" }
                trace(JSONObject().put("kind", "owned_expected_incoming").put("other_user_id", expectedCaller))
                // Optional actual incoming native Answer also enters normal expanded UI.
                if(args.getString("native_answer")=="1") {
                    fun allAnswer(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null)emptyList() else listOf(node)+(0 until node.childCount).flatMap { allAnswer(node.getChild(it)) }
                    withTimeout(20_000) { while(true) {
                        val root=inst.uiAutomation.rootInActiveWindow
                        var answer=if(root?.packageName?.toString()==context.packageName)allAnswer(root).firstOrNull {
                            (it.text?.toString()==context.getString(R.string.call_accept) || it.contentDescription?.toString()==context.getString(R.string.call_accept)) && it.isVisibleToUser && it.isEnabled
                        }else null
                        while(answer!=null && !answer.isClickable)answer=answer.parent
                        if(answer?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)break
                        delay(100)
                    } }
                    trace(JSONObject().put("kind","native_owned_answer").put("expected_peer",expectedCaller))
                } else scenario.onActivity { app.callController.acceptIncoming() }
            } else scenario.onActivity { app.callController.startCall(peer, "Owned QA Peer", CallMediaKind.AUDIO) }
            withTimeout(120_000) { while (app.callController.state.value !is CallUiState.Connected) {
                val state=app.callController.state.value
                if(state is CallUiState.Failed || state is CallUiState.Ended) error("Actual call ended before connection: ${state.javaClass.simpleName}")
                delay(100)
            } }
            fun tree(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()
            suspend fun snapshot(marker: String) {
                require(marker.matches(Regex("[A-Za-z0-9_-]{1,60}")))
                val state = app.callController.state.value
                val connected = (state as? CallUiState.Connected) ?: ((state as? CallUiState.Reconnecting)?.since as? CallUiState.Connected)
                val safe = JSONObject().put("marker", marker).put("at", System.currentTimeMillis()).put("state", state.javaClass.simpleName)
                connected?.let { safe.put("call_id", it.call.callId).put("video_mode", it.videoMode.name).put("front", it.localCameraIsFrontFacing)
                    .put("camera", it.localCameraState.name).put("local_video", it.localVideoEnabled).put("remote_video", it.remoteVideoState.name)
                    .put("muted", it.isMuted).put("upgrade", it.videoUpgrade.javaClass.simpleName) }
                val views = JSONArray()
                scenario.onActivity { activity ->
                    safe.put("activity_font_scale", activity.resources.configuration.fontScale)
                        .put("orientation", activity.resources.configuration.orientation).put("in_pip", activity.isInPictureInPictureMode)
                    tree(activity.window.decorView).filterIsInstance<LocalTextureRenderer>().forEach { view ->
                        val pos = IntArray(2); view.getLocationOnScreen(pos)
                        views.put(JSONObject().put("identity", System.identityHashCode(view)).put("available", view.isAvailable)
                            .put("width", view.width).put("height", view.height).put("x",pos[0]).put("y",pos[1])
                            .put("surface_timestamp", runCatching { view.surfaceTexture?.timestamp }.getOrNull()))
                        view.bitmap?.let { bitmap -> File(folder,"$marker-self.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
                    }
                }
                safe.put("local_views",views)
                val controllerEngine = app.callController.javaClass.getDeclaredField("webRtcEngine").apply { isAccessible=true }.get(app.callController)
                val sink = controllerEngine.javaClass.getDeclaredField("localSink").apply { isAccessible=true }.get(controllerEngine)
                safe.put("sink_identity",sink?.let(System::identityHashCode))
                (controllerEngine as com.messenger.app.data.call.WebRtcEngine).getStats()?.let { stats ->
                    safe.put("rtc",JSONObject().put("rtt_seconds",stats.selectedCandidatePair?.roundTripTimeSeconds)
                        .put("local_candidate_type",stats.selectedCandidatePair?.localCandidateType)
                        .put("remote_candidate_type",stats.selectedCandidatePair?.remoteCandidateType)
                        .put("video_frames_encoded",stats.videoOutbound?.framesEncoded)
                        .put("video_frames_decoded",stats.videoInbound?.framesDecoded)
                        .put("video_bytes_sent",stats.videoOutbound?.bytesSent)
                        .put("video_bytes_received",stats.videoInbound?.bytesReceived))
                }
                safe.put("safe_media", SafeOrdinaryMediaDiagnostics.collect(controllerEngine, run))
                val root = inst.uiAutomation.rootInActiveWindow
                if (root?.packageName?.toString()==context.packageName) inst.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(folder,"$marker-screen.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
                File(folder,"snapshots.jsonl").appendText(safe.toString()+"\n")
            }
            suspend fun nativePinch(scale: Float) {
                require(scale in .5f..2f)
                var box=android.graphics.Rect()
                scenario.onActivity { activity ->
                    val view=tree(activity.window.decorView).filterIsInstance<LocalTextureRenderer>().single()
                    val xy=IntArray(2);view.getLocationOnScreen(xy)
                    box=android.graphics.Rect(xy[0],xy[1],xy[0]+view.width,xy[1]+view.height)
                }
                require(box.width()>40 && box.height()>40 && box.left>=0 && box.top>=0)
                val cx=box.exactCenterX();val cy=box.exactCenterY();val span=minOf(box.width(),box.height())*.18f
                val began=android.os.SystemClock.uptimeMillis()
                val properties=Array(2) { n -> android.view.MotionEvent.PointerProperties().apply { id=n;toolType=android.view.MotionEvent.TOOL_TYPE_FINGER } }
                fun emit(action:Int,count:Int,distance:Float) {
                    val coords=Array(count) { n -> android.view.MotionEvent.PointerCoords().apply { x=cx+(if(n==0)-distance else distance);y=cy;pressure=1f;size=.1f } }
                    val event=android.view.MotionEvent.obtain(began,android.os.SystemClock.uptimeMillis(),action,count,properties,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                    try { check(inst.uiAutomation.injectInputEvent(event,true)) } finally { event.recycle() }
                }
                emit(android.view.MotionEvent.ACTION_DOWN,1,span)
                emit(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,span)
                repeat(18) { n -> emit(android.view.MotionEvent.ACTION_MOVE,2,span*(1f+(scale-1f)*(n+1)/18));delay(16) }
                emit(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,span*scale)
                emit(android.view.MotionEvent.ACTION_UP,1,span*scale)
            }
            suspend fun nativeLabel(label:String) {
                require(label.length in 1..120)
                fun all(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null)emptyList() else listOf(node)+(0 until node.childCount).flatMap { all(node.getChild(it)) }
                withTimeout(10_000) {
                    while(true) {
                        val root=inst.uiAutomation.rootInActiveWindow
                        val node=if(root?.packageName?.toString()==context.packageName)all(root).firstOrNull {
                            (it.text?.toString()==label || it.contentDescription?.toString()==label) && it.isVisibleToUser && it.isEnabled
                        }else null
                        var click=node;while(click!=null && !click.isClickable)click=click.parent
                        if(click?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)break
                        delay(100)
                    }
                }
            }
            snapshot("audio_connected")
            val command = File(folder,"command.json")
            withTimeout(1_200_000) {
                while (!finished) {
                    if (command.isFile && command.length() in 1..4096) {
                        val value = runCatching { JSONObject(command.readText()) }.getOrNull()
                        val sequence = value?.optLong("sequence",0) ?: 0
                        if (sequence>lastCommand && value!=null) {
                            lastCommand=sequence
                            val action=value.getString("action")
                            when(action) {
                                "video" -> scenario.onActivity { app.callController.requestVideoUpgrade() }
                                "camera_on" -> scenario.onActivity { app.callController.setCameraEnabled(true) }
                                "camera_off" -> scenario.onActivity { app.callController.setCameraEnabled(false) }
                                "flip" -> scenario.onActivity { app.callController.switchCamera() }
                                "audio" -> scenario.onActivity { app.callController.switchToAudio() }
                                "landscape" -> scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                                "portrait" -> scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                                "font_large", "font_normal" -> {
                                    context.getSharedPreferences("owned-qa-display",0).edit().putFloat("fontScale",if(action=="font_large")1.5f else 1f).commit()
                                    scenario.recreate()
                                }
                                "foreground" -> context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                                "finish" -> finished=true
                                "capture" -> Unit
                                "ice_disconnected" -> {
                                    val actualEngine=app.callController.javaClass.getDeclaredField("webRtcEngine").apply { isAccessible=true }.get(app.callController)
                                    @Suppress("UNCHECKED_CAST")
                                    val actualEvents=actualEngine.javaClass.getDeclaredField("_events").apply { isAccessible=true }.get(actualEngine) as kotlinx.coroutines.flow.MutableSharedFlow<com.messenger.app.data.call.WebRtcEvent>
                                    actualEvents.emit(com.messenger.app.data.call.WebRtcEvent.ConnectionStateChanged(com.messenger.app.data.call.PeerConnectionState.DISCONNECTED))
                                    trace(JSONObject().put("kind","qa_injected_native_disconnect_event").put("transport_not_disabled",true))
                                }
                                "native_pinch" -> nativePinch(value.optDouble("scale",1.5).toFloat())
                                "native_label" -> nativeLabel(value.getString("label"))
                                else -> error("Unknown owned QA command")
                            }
                            delay(250); snapshot(value.optString("marker","command-$sequence"))
                            File(folder,"last-command.json").writeText(JSONObject().put("sequence",sequence).put("action",action).toString())
                        }
                    }
                    delay(100)
                }
            }
        } catch(error:Exception) {
            File(folder,"failure.json").writeText(JSONObject().put("failure_class",error.javaClass.simpleName).put("preserved_for_root",true).toString())
            // Keep exact owned identity and private credentials available for
            // a bounded safe-state/server-row diagnosis before normal deletion.
            withContext(NonCancellable) { withTimeoutOrNull(1_200_000) {
                val release=File(folder,"release-cleanup.json")
                while(!release.isFile)delay(100)
                check(release.length()<=2048 && JSONObject(release.readText()).getString("user_id")==ownedUser)
            } }
            throw error
        } finally {
            val ticket=JSONObject().put("username",username).put("user_id",ownedUser)
            runCatching { lifecycleScenario?.onActivity { app.callController.hangup() };delay(500) }
            try {
                val current=(app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user
                check(current?.id==ownedUser && current?.username==username && prefs.getString("user_id",null)==ownedUser)
                AuthApi(http,ApiJson,BuildConfig.API_BASE_URL).deleteAccount(requireNotNull(app.authRepository.currentAccessToken()),password)
                ticket.put("deleted_through_normal_api",true);app.authRepository.logout();prefs.edit().clear().commit()
            } catch (error:Exception) { ticket.put("deleted_through_normal_api",false).put("failure_class",error.javaClass.simpleName) }
            File(folder,"cleanup.json").writeText(ticket.toString());lifecycleScenario?.close()
            callApiField.set(app.callController,actualApi)
            traces.close();delay(100);traceScope.cancel()
            context.getSharedPreferences("owned-qa-display",0).edit().remove("fontScale").commit()
        }
    }
}
