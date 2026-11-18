package com.messenger.app.data.call

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.messenger.app.MainActivity
import com.messenger.app.data.api.IceServerDto
import com.messenger.app.domain.model.CallMediaKind
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit owned fixtures only. Real phone engine/camera -> native iOS peer.
 *  RTP/decoded-frame evidence is not an acoustic volume/echo measurement. */
class CallCrossPlatformMediaInstrumentedTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)

    @Test fun ringbackStartsOnActualPrivateRouteWithoutOpeningMedia() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Explicit owned QA session required",File(context.filesDir,"owned-media-fixture.json").isFile)
        val manager=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val before=manager.mode
        val activity=ActivityScenario.launch(MainActivity::class.java)
        val engine=WebRtcEngineImpl(context)
        var tone:ToneGenerator?=null
        try {
            engine.prepareCallAudio()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while((manager.mode!=AudioManager.MODE_IN_COMMUNICATION || engine.currentAudioRoute().kind==AudioRouteKind.SPEAKER) && System.nanoTime()<deadline)Thread.sleep(50)
            assertEquals(AudioManager.MODE_IN_COMMUNICATION,manager.mode)
            val actual=engine.currentAudioRoute().kind
            assertTrue("Phone supports a private output",engine.availableAudioRoutes().any {it.kind!=AudioRouteKind.SPEAKER})
            assertNotEquals("Audio ringback must not inherit loudspeaker",AudioRouteKind.SPEAKER,actual)
            assertFalse("Preparing ringback must not open camera",engine.localVideoRunning())
            tone=ToneGenerator(AudioManager.STREAM_VOICE_CALL,com.messenger.app.call.CallRingbackPlayer.RINGBACK_VOLUME)
            assertTrue(tone.startTone(ToneGenerator.TONE_SUP_RINGTONE,180))
            Thread.sleep(230)
            android.util.Log.i("COCO_MEDIA_QA","RINGBACK_PRIVATE_ROUTE_PASS route=$actual voiceVolume=${manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)} voiceVolumeMax=${manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)} cameraOpened=false acousticVolumeMeasured=false")
        } finally {tone?.stopTone();tone?.release();engine.dispose();activity.close()}
        assertEquals("Audio mode restored",before,manager.mode)
    }

    @Test fun realPhoneAndIOSPeerExchangeMedia() { exerciseMedia(requireDuplexAudio=true) }
    @Test fun realPhoneAndIOSPeerExchangeVideo() { exerciseMedia(requireDuplexAudio=false) }
    private fun exerciseMedia(requireDuplexAudio:Boolean): Unit = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureFile=File(context.filesDir,"owned-media-fixture.json")
        assumeTrue("Explicit private owned fixture required",fixtureFile.isFile)
        val fixture=JSONObject(fixtureFile.readText())
        assertTrue(fixture.getString("username").startsWith("qa_media_"))
        assertEquals("caller",fixture.getString("role"))
        val client=OkHttpClient.Builder().callTimeout(12,TimeUnit.SECONDS).build()
        val inbox=Channel<JSONObject>(Channel.UNLIMITED)
        val engine=WebRtcEngineImpl(context)
        var callId:String?=null
        var remoteSet=false
        var connected=false
        var firstRemoteFrame=false
        var failure:String?=null
        val pendingIce=mutableListOf<JSONObject>()
        fun authorized(path:String)=Request.Builder().url(fixture.getString("api")+path)
            .header("Authorization","Bearer "+fixture.getString("access_token"))
        suspend fun post(path:String,body:JSONObject=JSONObject()):JSONObject=withContext(Dispatchers.IO) {
            client.newCall(authorized(path).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response->
                check(response.isSuccessful){"Owned fixture HTTP "+response.code}
                JSONObject(response.body!!.string())
            }
        }
        val socket=client.newWebSocket(Request.Builder().url(fixture.getString("ws"))
            .header("Authorization","Bearer "+fixture.getString("access_token")).build(),object:WebSocketListener(){
                override fun onOpen(webSocket:WebSocket,response:Response){inbox.trySend(JSONObject().put("type","qa.open"))}
                override fun onMessage(webSocket:WebSocket,text:String){inbox.trySend(JSONObject(text))}
                override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){failure=t.javaClass.simpleName}
            })
        fun send(row:JSONObject){check(socket.send(row.toString())){"Owned fixture WS send rejected"}}
        fun applyIce(row:JSONObject){engine.addIceCandidate(row.getString("candidate"),row.optString("sdp_mid").takeUnless{it=="null"||it.isEmpty()},if(row.isNull("sdp_m_line_index"))null else row.getInt("sdp_m_line_index"))}
        val activity=ActivityScenario.launch(MainActivity::class.java)
        val collector=launch {
            engine.events.collect {event->when(event){
                is WebRtcEvent.LocalIceCandidate->callId?.let { id->send(JSONObject().put("type","call.ice").put("call_id",id).put("candidate",event.candidate).put("sdp_mid",event.sdpMid?:JSONObject.NULL).put("sdp_m_line_index",event.sdpMLineIndex?:JSONObject.NULL)) }
                is WebRtcEvent.ConnectionStateChanged->if(event.state==PeerConnectionState.CONNECTED)connected=true
                WebRtcEvent.RemoteFirstFrame->firstRemoteFrame=true
                else->Unit
            }}
        }
        val keeper=launch {while(isActive){callId?.let {send(JSONObject().put("type","call.keep_alive").put("call_id",it))};delay(5000)}}
        try {
            withTimeout(15000){while(inbox.receive().optString("type")!="qa.open")Unit}
            val call=post("/calls",JSONObject().put("callee_user_id",fixture.getString("peer_user_id")).put("media_kind","video"))
            callId=call.getString("id")
            android.util.Log.i("COCO_MEDIA_QA","READY caller=true realPhoneCamera=true")
            withTimeout(70000){while(!connected){
                check(failure==null){"Owned fixture socket failed: $failure"}
                val row=withTimeoutOrNull(500){inbox.receive()}?:continue
                if(row.optString("call_id")!=callId)continue
                when(row.optString("type")){
                    "call.accepted"->{
                        val array=fixture.getJSONArray("ice_servers")
                        val ice=(0 until array.length()).map {i->val item=array.getJSONObject(i);val urls=item.getJSONArray("urls");IceServerDto((0 until urls.length()).map{urls.getString(it)},item.optString("username").takeUnless{it.isEmpty()||it=="null"},item.optString("credential").takeUnless{it.isEmpty()||it=="null"})}
                        engine.initialize(ice,CallMediaKind.VIDEO)
                        assertTrue("Phone camera must actually start",engine.localVideoRunning())
                        send(JSONObject().put("type","call.offer").put("call_id",callId).put("sdp",engine.createOffer()))
                    }
                    "call.answer"->{engine.setRemoteDescription("answer",row.getString("sdp"));remoteSet=true;pendingIce.forEach(::applyIce);pendingIce.clear()}
                    "call.ice"->if(remoteSet)applyIce(row)else pendingIce.add(row)
                    "call.ended"->error("Owned media call ended before transport connected")
                }
            }}
            post("/calls/$callId/connected")
            delay(4000)
            val before=requireNotNull(engine.getStats())
            delay(8000)
            val after=requireNotNull(engine.getStats())
            android.util.Log.i("COCO_MEDIA_QA","RESULT before=$before after=$after firstFrame=$firstRemoteFrame route=${engine.currentAudioRoute().kind} requiresDuplexAudio=$requireDuplexAudio")
            assertTrue("Audio RTP sent grows",(after.audioOutbound?.bytesSent?:0)>(before.audioOutbound?.bytesSent?:0))
            if(requireDuplexAudio)assertTrue("Audio RTP received grows",(after.audioInbound?.bytesReceived?:0)>(before.audioInbound?.bytesReceived?:0))
            assertTrue("Camera frames encoded grow",(after.videoOutbound?.framesEncoded?:0)>(before.videoOutbound?.framesEncoded?:0))
            assertTrue("Remote native video frames decoded grow",(after.videoInbound?.framesDecoded?:0)>(before.videoInbound?.framesDecoded?:0))
            assertTrue("Actual remote first frame",firstRemoteFrame)
            val original=engine.currentAudioRoute()
            engine.setMuted(true);delay(500);engine.setMuted(false)
            if(engine.availableAudioRoutes().any{it.kind==AudioRouteKind.SPEAKER}){
                engine.setSpeakerOn(true);delay(700);assertEquals(AudioRouteKind.SPEAKER,engine.currentAudioRoute().kind)
                engine.setAudioRoute(original);delay(700);assertEquals(original.kind,engine.currentAudioRoute().kind)
            }
            engine.setVideoEnabled(false);delay(700);engine.setVideoEnabled(true);delay(2000)
            assertTrue("Camera resumes encoding",(engine.getStats()?.videoOutbound?.framesEncoded?:0)>(after.videoOutbound?.framesEncoded?:0))
            android.util.Log.i("COCO_MEDIA_QA","COMPLETE actualRtpAndVideoDecode=true duplexAudioRequired=$requireDuplexAudio acousticVolumeMeasured=false")
        } finally {
            callId?.let {try{post("/calls/$it/hangup")}catch(_:Exception){}}
            keeper.cancelAndJoin();collector.cancelAndJoin();engine.dispose();socket.close(1000,"owned test complete")
            activity.close();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
        }
    }
}
