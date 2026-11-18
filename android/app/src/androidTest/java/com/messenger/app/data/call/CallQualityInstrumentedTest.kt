package com.messenger.app.data.call

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.domain.model.CallMediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import livekit.org.webrtc.PeerConnection

/** Exercises real native senders after SDP and an audio-to-video renegotiation. No server/accounts. */
class CallQualityInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun pc(engine: WebRtcEngineImpl): PeerConnection =
        WebRtcEngineImpl::class.java.getDeclaredField("peerConnection").apply { isAccessible = true }.get(engine) as PeerConnection

    @Test fun nativeCapsSurviveRenegotiationAndPreferencesApplyNextCall() = runBlocking {
        val prefs = context.getSharedPreferences(CallQualityPreferences.STORE, Context.MODE_PRIVATE)
        val saved = prefs.all
        val a = WebRtcEngineImpl(context)
        val b = WebRtcEngineImpl(context)
        suspend fun negotiate() {
            b.setRemoteDescription("offer", a.createOffer())
            a.setRemoteDescription("answer", b.createAnswer())
        }
        fun verify(engine: WebRtcEngineImpl, audio: Int, video: Int? = null, fps: Int? = null) {
            val senders = pc(engine).senders
            assertTrue(senders.any { it.track()?.kind() == "audio" })
            for (sender in senders) {
                val kind = sender.track()?.kind() ?: continue
                // Native Unified Plan retains the video transceiver after removeTrack.
                if (kind == "video" && video == null) continue
                val encodings = sender.parameters.encodings
                assertTrue("native encodings for $kind", encodings.isNotEmpty())
                for (encoding in encodings) {
                    assertEquals(if (kind == "audio") audio else video, encoding.maxBitrateBps)
                    if (kind == "video") assertEquals(fps, encoding.maxFramerate)
                }
            }
            if (video != null) assertTrue("video sender exists", senders.any { it.track()?.kind() == "video" })
        }
        try {
            prefs.edit().putString(CallQualityPreferences.AUDIO, "economy").putString(CallQualityPreferences.VIDEO, "economy").commit()
            a.initialize(emptyList(), CallMediaKind.AUDIO)
            b.initialize(emptyList(), CallMediaKind.AUDIO)
            negotiate()
            verify(a, 16_000); verify(b, 16_000)
            prefs.edit().putString(CallQualityPreferences.AUDIO, "high").putString(CallQualityPreferences.VIDEO, "high").commit()
            assertTrue(a.startLocalVideo(false))
            assertTrue(b.startLocalVideo(false))
            negotiate()
            verify(a, 16_000, 300_000, 15); verify(b, 16_000, 300_000, 15)
            a.stopLocalVideo(); b.stopLocalVideo(); negotiate()
            verify(a, 16_000)
            assertFalse(a.localVideoRunning()); assertFalse(b.localVideoRunning())
            a.dispose(); b.dispose()
            // These peers share one Android device. Caps require real native
            // video senders, not two simultaneous owners of its front camera.
            // Camera lifecycle is exercised separately; avoid driver contention
            // masking the next-call preference assertions in stopCapture().
            a.initialize(emptyList(), CallMediaKind.AUDIO)
            b.initialize(emptyList(), CallMediaKind.AUDIO)
            assertTrue(a.startLocalVideo(false))
            assertTrue(b.startLocalVideo(false))
            negotiate()
            verify(a, 48_000, 2_500_000, 30); verify(b, 48_000, 2_500_000, 30)
        } finally {
            a.dispose(); b.dispose()
            prefs.edit().clear().apply { saved.forEach { (key, value) -> if (value is String) putString(key, value) } }.commit()
        }
    }
}
