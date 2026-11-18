package livekit.org.webrtc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Validate the resolved SDK, without loading JNI or opening a camera/network connection. */
class WebRtcDependencyTest {
    @Test fun `calls and camera helpers resolve only the shared M144 namespace`() {
        val loader = PeerConnection::class.java.classLoader
        assertFailsWith<ClassNotFoundException> { loader.loadClass("org.webrtc.PeerConnection") }
        // Read the shipped field rather than a compiler-inlined version constant.
        assertEquals("M144", WebrtcBuildVersion::class.java.getField("webrtc_branch").get(null))
        assertEquals<Class<*>>(Camera2Capturer::class.java, CallTorchCamera2Capturer::class.java.superclass)
        assertTrue(CameraSession::class.java.isAssignableFrom(CallTorchCamera2Session::class.java))
    }

    @Test fun `shared SDK defaults to the LiveKit native library without custom field trials`() {
        val options = PeerConnectionFactory.InitializationOptions.builder(null).createInitializationOptions()
        assertEquals("lkjingle_peerconnection_so", options.nativeLibraryName)
        assertEquals("", options.fieldTrials)
    }
}
