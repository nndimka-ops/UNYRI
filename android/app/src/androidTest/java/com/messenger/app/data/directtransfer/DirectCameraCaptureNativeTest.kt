package com.messenger.app.data.directtransfer

import android.Manifest
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.messenger.app.BuildConfig
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.capture.DirectCameraCaptureActivity
import com.messenger.app.capture.NonCallCaptureKind
import com.messenger.app.capture.PendingExternalCapture
import com.messenger.app.capture.validDirectCaptureMedia
import com.messenger.app.data.api.ApiJson
import com.messenger.app.data.api.AuthApi
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.ui.theme.AppColorStyle
import com.messenger.app.ui.theme.AppColorStylePreference
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Own QA UID, real capture session/CameraX/activity and native accessibility taps. */
class DirectCameraCaptureNativeTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test fun dualCameraConfirmedReviewAndInterruptedCapture(): Unit = runBlocking(Dispatchers.IO) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        check(context.packageName == "com.messenger.app.directqa")
        val app = context.applicationContext as MessengerApp
        check(app.authRepository.authState.value is AuthSessionState.LoggedOut)
        val run = UUID.randomUUID().toString()
        val username = "dfcam" + run.replace("-", "").take(12)
        val password = UUID.randomUUID().toString()+UUID.randomUUID()+"Aa9!"
        val private = EncryptedSharedPreferences.create(context,"camera-native-$run-private",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        check(private.edit().putString("username",username).putString("password",password).commit())
        val folder = File(context.filesDir,"camera-native-$run").apply { mkdirs() }
        val outcomes = JSONArray()
        var ownedUser: String? = null
        var scenario: ActivityScenario<DirectCameraCaptureActivity>? = null
        var currentId: String? = null
        fun save() = File(folder,"proof.json").writeText(JSONObject().put("run",run)
            .put("scope","Actual physical CameraX capture via production private Activity; native taps, durable explicit review; no sent-chat attachment claim")
            .put("cases",outcomes).toString())
        fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if(node==null) emptyList() else
            listOf(node)+(0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        suspend fun tap(label: String) {
            withTimeout(10_000) {
                while(true) {
                    val found=nodes(inst.uiAutomation.rootInActiveWindow).firstOrNull {
                        (it.text?.toString()==label || it.contentDescription?.toString()==label) && it.isVisibleToUser && it.isEnabled
                    }
                    var click=found
                    while(click!=null && !click.isClickable) click=click.parent
                    if(click?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)break
                    delay(100)
                }
            }
        }
        suspend fun captureReady() {
            withTimeout(20_000) {
                while(true) {
                    var ready=false
                    scenario!!.onActivity {
                        ready=it.javaClass.getDeclaredField("ready\$delegate").apply { isAccessible=true }.get(it)
                            .let { state -> (state as androidx.compose.runtime.State<*>).value as Boolean }
                        val preview=it.javaClass.getDeclaredField("previewView").apply { isAccessible=true }.get(it) as? androidx.camera.view.PreviewView
                        ready=ready && preview?.previewStreamState?.value==androidx.camera.view.PreviewView.StreamState.STREAMING
                    }
                    if(ready)break
                    delay(100)
                }
            }
        }
        suspend fun snapshot(name: String) {
            delay(300);inst.waitForIdleSync()
            inst.uiAutomation.takeScreenshot()?.let { bitmap -> File(folder,"$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        }
        suspend fun closeOwned() {
            val id=currentId ?: return
            runCatching { scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() } }
            withTimeout(15_000) { while(app.nonCallCaptures.find(id)!=null) delay(50) }
            scenario?.close();scenario=null
            val record=app.externalCaptureStore.get(id)
            record?.directSource?.let { source -> File(source).delete();File(source.removeSuffix(".jpg")+".mp4").delete() }
            app.externalCaptureStore.remove(id);currentId=null
        }
        try {
            app.authRepository.register(username,password,"Owned Camera QA").getOrThrow()
            val logged=withTimeout(60_000) { app.authRepository.authState.filterIsInstance<AuthSessionState.LoggedIn>().first() }
            check(logged.user.username==username);ownedUser=logged.user.id
            check(private.edit().putString("user_id",ownedUser).commit())
            File(folder,"identity.json").writeText(JSONObject().put("run",run).put("user_id",ownedUser).put("username",username).toString())
            suspend fun launch(video: Boolean,front: Boolean) {
                val session=requireNotNull(app.captureSession())
                val ticket=requireNotNull(app.nonCallCaptures.acquire(NonCallCaptureKind.CAMERA,session) {})
                val file=File(File(context.cacheDir,"camera").apply { mkdirs() },"capture_${ticket.id}.jpg")
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
                check(app.externalCaptureStore.prepareDirect(app.nonCallCaptures,ticket,uri,file,UUID.randomUUID().toString(),video))
                currentId=ticket.id
                val original=requireNotNull(app.externalCaptureStore.get(ticket.id))
                if(front)check(app.externalCaptureStore.replaceDirect(original,original.copy(front=true)))
                scenario=ActivityScenario.launch(Intent(context,DirectCameraCaptureActivity::class.java).putExtra("capture_request",ticket.id))
                scenario!!.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                captureReady()
            }
            for(video in listOf(false,true)) for(front in listOf(false,true)) {
                launch(video,front)
                val id=requireNotNull(currentId)
                val name="${if(video)"video" else "photo"}-${if(front)"front" else "rear"}"
                snapshot("$name-preview")
                tap(context.getString(if(video)R.string.camera_record_video else R.string.camera_take_photo))
                if(video) { delay(1800);tap(context.getString(R.string.voice_stop)) }
                withTimeout(20_000) { while(app.externalCaptureStore.get(id)?.stage!="review")delay(50) }
                val reviewed=requireNotNull(app.externalCaptureStore.get(id))
                assertFalse(reviewed.confirmed);assertEquals(front,reviewed.front)
                val file=File(requireNotNull(reviewed.directSource).let { if(video)it.removeSuffix(".jpg")+".mp4" else it })
                assertTrue(validDirectCaptureMedia(file,video));val size=file.length()
                snapshot("$name-review")
                // Review, rather than capture or mode switch, owns confirmation.
                tap(context.getString(R.string.send))
                withTimeout(10_000) { while(app.externalCaptureStore.get(id)?.confirmed!=true)delay(50) }
                withTimeout(15_000) { while(app.nonCallCaptures.find(id)!=null)delay(50) }
                outcomes.put(JSONObject().put("case",name).put("valid_media",true).put("bytes",size)
                    .put("not_confirmed_before_send",!reviewed.confirmed).put("confirmed_after_native_send",true).put("lease_released",true))
                save();closeOwned()
            }
            // Background recording stops hardware and bytes, retaining only its
            // reservation so foreground can return to the same unconfirmed picker.
            launch(true,false);val interrupted=requireNotNull(currentId)
            tap(context.getString(R.string.camera_record_video));delay(500)
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            withTimeout(15_000) {
                while(true) {
                    var stopped=false
                    scenario!!.onActivity { activity ->
                        fun read(name:String)=activity.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(activity)
                        stopped=read("recording")==null && (read("ownedUseCases") as Collection<*>).isEmpty() &&
                            (read("cameraStates") as Map<*,*>).values.all { it==androidx.camera.core.CameraState.Type.CLOSED }
                    }
                    if(stopped && app.externalCaptureStore.get(interrupted)?.stage=="preview")break
                    delay(50)
                }
            }
            assertFalse(app.externalCaptureStore.get(interrupted)?.confirmed==true)
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);captureReady();snapshot("recording-background-restored")
            outcomes.put(JSONObject().put("case","recording_background").put("confirmed",false).put("hardware_stopped",true)
                .put("reservation_retained",app.nonCallCaptures.find(interrupted)!=null).put("foreground_preview_ready",true));save();closeOwned()
            // Back during preview never creates a reviewed or confirmed file.
            launch(false,false);val cancelled=requireNotNull(currentId)
            tap(context.getString(R.string.cancel))
            withTimeout(15_000) { while(app.nonCallCaptures.find(cancelled)!=null)delay(50) }
            assertFalse(app.externalCaptureStore.get(cancelled)?.confirmed==true)
            outcomes.put(JSONObject().put("case","preview_back").put("confirmed",false).put("lease_released",true));save();closeOwned()
            for(style in listOf(AppColorStyle.MONOCHROME,AppColorStyle.COLORFUL)) {
                AppColorStylePreference(context).set(style)
                check(context.getSharedPreferences("owned-qa-display",0).edit().putFloat("fontScale",1.5f).commit())
                launch(false,true)
                var actualScale=0f
                scenario!!.onActivity { actualScale=it.resources.configuration.fontScale }
                assertEquals(1.5f,actualScale,0.01f)
                snapshot("large-${style.preferenceValue}-preview")
                // Formerly invisible title and flip action must remain named
                // and actionable in both palettes and the larger text context.
                assertTrue(nodes(inst.uiAutomation.rootInActiveWindow).any { it.text?.toString()==context.getString(R.string.attach_camera) && it.isVisibleToUser })
                tap(context.getString(R.string.call_switch_camera));captureReady()
                assertFalse(requireNotNull(app.externalCaptureStore.get(requireNotNull(currentId))).front)
                snapshot("large-${style.preferenceValue}-flipped")
                outcomes.put(JSONObject().put("case","large-${style.preferenceValue}").put("actual_font_scale",actualScale).put("flip_action_works",true));save();closeOwned()
            }
            File(folder,"passed.json").writeText("{\"passed\":true}")
        } finally {
            runCatching { closeOwned() }
            val cleanup=JSONObject().put("user_id",ownedUser).put("username",username)
            try {
                val current=(app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user
                check(current?.id==ownedUser && current?.username==username)
                AuthApi(OkHttpClient(),ApiJson,BuildConfig.API_BASE_URL).deleteAccount(requireNotNull(app.authRepository.currentAccessToken()),password)
                cleanup.put("deleted_through_normal_api",true);app.authRepository.logout();private.edit().clear().commit()
            } catch(error:Exception) { cleanup.put("deleted_through_normal_api",false).put("failure_class",error.javaClass.simpleName) }
            File(folder,"cleanup.json").writeText(cleanup.toString())
            context.getSharedPreferences("owned-qa-display",0).edit().remove("fontScale").commit()
            AppColorStylePreference(context).set(AppColorStyle.MONOCHROME)
            android.util.Log.i("DirectNativeQA","camera-native-proof=camera-native-$run/proof.json")
        }
    }
}
