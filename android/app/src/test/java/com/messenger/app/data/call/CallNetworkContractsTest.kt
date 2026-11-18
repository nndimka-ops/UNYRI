package com.messenger.app.data.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors ios/Tests/CallNetworkContracts: the decisions that protect speech on a weak link. */
class CallNetworkContractsTest {
    private fun at(p: CallVideoPausePolicy, seconds: Int, kbps: Double? = 100.0, rtt: Double? = 450.0,
                   loss: Double? = 0.0, limited: Boolean = true, sending: Boolean = true, interrupted: Boolean = false) =
        p.update(seconds * 1000L, kbps, limited, rtt, loss, sending, interrupted)
    private fun through(p: CallVideoPausePolicy, range: IntRange, rtt: Double? = 450.0, loss: Double? = 0.0,
                        limited: Boolean = true) = range.mapNotNull { at(p, it, rtt = rtt, loss = loss, limited = limited) }

    @Test fun `ladder keeps every row above its initial size drop threshold`() {
        assertEquals(CallMediaProfile(CallNetworkTier.WIFI, 1280,720,30,2_000_000,800_000,16_000,32_000,false,true), CallMediaLadder.wifi)
        assertEquals(CallMediaProfile(CallNetworkTier.CELLULAR,960,540,30,1_000_000,650_000,16_000,32_000,true,true), CallMediaLadder.cellular)
        assertEquals(CallMediaProfile(CallNetworkTier.SAVING,640,360,15,350_000,400_000,16_000,20_000,true,false), CallMediaLadder.saving)
    }
    @Test fun `low estimate on an uncongested LAN never pauses`() {
        assertTrue(through(CallVideoPausePolicy(), 0..120, rtt = 5.0).isEmpty())
        assertTrue(through(CallVideoPausePolicy(), 0..120, rtt = null, loss = null).isEmpty())
        assertTrue(through(CallVideoPausePolicy(), 0..120, limited = false).isEmpty())
    }
    @Test fun `first camera and every retry get fifteen seconds plus low window`() {
        val p = CallVideoPausePolicy()
        assertTrue(through(p, 0..19).isEmpty())
        assertEquals(CallVideoPausePolicy.Decision.PAUSE, at(p,20))
        assertEquals(1,p.failedTries)
        assertTrue(through(p,21..29).isEmpty())
        assertEquals(CallVideoPausePolicy.Decision.RESUME,at(p,30))
        assertTrue(through(p,31..49).isEmpty())
        assertEquals(CallVideoPausePolicy.Decision.PAUSE,at(p,50))
        assertTrue(through(p,51..69).isEmpty())
        assertEquals(CallVideoPausePolicy.Decision.RESUME,at(p,70))
    }
    @Test fun `loss remains evidence across sparse receiver reports for eight seconds`() {
        val p = CallVideoPausePolicy()
        through(p,0..14,rtt=5.0)
        at(p,15,rtt=5.0,loss=0.06)
        assertTrue(through(p,16..19,rtt=5.0).isEmpty())
        assertEquals(CallVideoPausePolicy.Decision.PAUSE,at(p,20,rtt=5.0))
        val expired = CallVideoPausePolicy()
        through(expired,0..9,rtt=5.0)
        at(expired,10,rtt=5.0,loss=0.06)
        assertTrue(through(expired,11..40,rtt=5.0).isEmpty())
    }
    @Test fun `a good sample restarts the low window`() {
        val p=CallVideoPausePolicy(); through(p,0..17)
        at(p,18,kbps=400.0,limited=false)
        assertTrue(through(p,19..23).isEmpty())
        assertEquals(CallVideoPausePolicy.Decision.PAUSE,at(p,24))
    }
    @Test fun `network change resumes at once and clears old evidence`() {
        val p=CallVideoPausePolicy(); through(p,0..20)
        assertEquals(CallVideoPausePolicy.Decision.RESUME,p.networkChanged(21_000))
        assertEquals(0,p.failedTries)
        assertTrue(through(p,21..40).isEmpty())
        assertEquals(CallVideoPausePolicy.Decision.PAUSE,at(p,41))
    }
    @Test fun `sampling gap capture change and camera on each restart grace`() {
        val p=CallVideoPausePolicy(); through(p,0..17)
        assertTrue(through(p,30..49).isEmpty())
        assertEquals(CallVideoPausePolicy.Decision.PAUSE,at(p,50))
        assertEquals(CallVideoPausePolicy.Decision.RESUME,at(p,51,sending=false))
        assertTrue(through(p,52..71).isEmpty())
        p.captureChanged(72_000)
        assertTrue(through(p,72..91).isEmpty())
        assertEquals(CallVideoPausePolicy.Decision.PAUSE,at(p,92))
    }
    @Test fun `interruption keeps pause and retry backoff`() {
        val p=CallVideoPausePolicy(); through(p,0..20)
        for (s in 21..40) assertNull(at(p,s,interrupted=true))
        assertTrue(p.paused)
        assertEquals(CallVideoPausePolicy.Decision.RESUME,at(p,41))
        assertTrue(through(p,42..60).isEmpty())
        assertEquals(CallVideoPausePolicy.Decision.PAUSE,at(p,61))
        assertTrue(through(p,62..80).isEmpty())
        assertEquals(CallVideoPausePolicy.Decision.RESUME,at(p,81))
    }
    @Test fun `retries back off to forty and sixty seconds and healthy time resets them`() {
        val p=CallVideoPausePolicy()
        val events=(0..300).mapNotNull { seconds -> at(p,seconds)?.let { seconds to it } }
        assertEquals(listOf(20,50,90,150,230),events.filter { it.second == CallVideoPausePolicy.Decision.PAUSE }.map { it.first })
        assertEquals(listOf(30,70,130,210,290),events.filter { it.second == CallVideoPausePolicy.Decision.RESUME }.map { it.first })
        for (s in 301..331) at(p,s,kbps=200.0)
        assertEquals(0,p.failedTries)
        for (s in 332..336) assertNull(at(p,s))
        assertEquals(CallVideoPausePolicy.Decision.PAUSE,at(p,337))
        assertEquals(1,p.failedTries)
        assertEquals(CallVideoPausePolicy.Decision.RESUME,at(p,347))
    }
    @Test fun `camera seed requires recent clean link and never lowers estimate`() {
        val row=CallMediaLadder.wifi
        fun seed(rtt:Double?=40.0,loss:Double?=0.0,kbps:Double?=150.0)=CallBitrateSeedPolicy.seed(CallBitrateSeedPolicy.Cause.CAMERA_START,row,CallBitrateSeedPolicy.Link(rtt,loss,kbps))
        assertEquals(800_000,seed()); assertEquals(800_000,seed(399.0)); assertEquals(800_000,seed(loss=null))
        assertNull(seed(400.0)); assertNull(seed(null)); assertNull(seed(loss=.02)); assertNull(seed(kbps=null))
        assertNull(seed(kbps=800.0)); assertNull(seed(kbps=900.0))
        assertNull(CallBitrateSeedPolicy.seed(CallBitrateSeedPolicy.Cause.CAMERA_START,row,null))
        for (p in listOf(CallMediaLadder.wifi,CallMediaLadder.cellular,CallMediaLadder.saving)) {
            assertEquals(p.startBitrate,CallBitrateSeedPolicy.seed(CallBitrateSeedPolicy.Cause.NEW_PATH,p,null))
            assertNull(CallBitrateSeedPolicy.seed(CallBitrateSeedPolicy.Cause.RETRY,p,CallBitrateSeedPolicy.Link(30.0,0.0,100.0)))
        }
    }
    private fun pair(p:CallDeadPairPolicy,s:Int,id:String="one",received:Long=0,refused:Long=0,sent:Long?=0,voice:Boolean=true,dtls:Boolean?=true)=
        p.update(s*1000L,id,dtls,received,refused,sent,voice)
    @Test fun `dead first pair judged once after DTLS and at most twice a call`() {
        val p=CallDeadPairPolicy()
        assertFalse(pair(p,0,dtls=false)); assertFalse(pair(p,3)); assertFalse(pair(p,5))
        assertTrue(pair(p,6)); assertEquals(3_000L,p.watchedMs); assertFalse(pair(p,7))
        assertFalse(pair(p,8,"two")); assertTrue(pair(p,11,"two"))
        assertFalse(pair(p,12,"three")); assertFalse(pair(p,15,"three"))
        assertEquals(2,p.reported)
    }
    @Test fun `refused sends prove one way death only when no sends succeed`() {
        val p=CallDeadPairPolicy(); pair(p,0,sent=100)
        assertTrue(pair(p,3,received=150,refused=10,sent=100)); assertTrue(p.sendsRefused)
        for (sent in listOf(101L,null,50L)) {
            val q=CallDeadPairPolicy(); pair(q,0,sent=100)
            assertFalse(pair(q,3,received=150,refused=10,sent=sent))
        }
    }
    @Test fun `DTX exempts silence but cannot explain refused sends`() {
        val p=CallDeadPairPolicy(); pair(p,0,voice=false)
        assertFalse(pair(p,3,voice=false))
        val q=CallDeadPairPolicy(); pair(q,0,voice=false)
        assertTrue(pair(q,3,refused=10,voice=false))
    }
    @Test fun `hold exemption snapshots arrival when outage starts`() {
        val flowing=CallDeadPairPolicy(); pair(flowing,0); pair(flowing,3,received=150)
        flowing.noteOutage(4_000)
        pair(flowing,10,"new",received=150)
        assertTrue(pair(flowing,13,"new",received=150))
        val held=CallDeadPairPolicy(); pair(held,0); pair(held,3,received=150)
        held.noteOutage(8_000)
        pair(held,9,"new",received=150)
        assertFalse(pair(held,12,"new",received=150))
    }
    @Test fun `a long sampling gap never judges stale pair evidence`() {
        val p=CallDeadPairPolicy(); pair(p,0)
        assertFalse(pair(p,11)); assertFalse(pair(p,12,refused=100))
    }
    @Test fun `dead pair only initiates caller recovery toward glare capable peer`() {
        assertFalse(CallRecoveryPolicy(caller=false,peerResolvesGlare=true).mediaStalled(0))
        assertFalse(CallRecoveryPolicy(caller=true,peerResolvesGlare=false).mediaStalled(0))
        val r=CallRecoveryPolicy(caller=true,peerResolvesGlare=true)
        assertTrue(r.mediaStalled(0)); assertEquals(CallRecoveryPolicy.Action.RESTART,r.next(0))
        r.offerRequested(0,true); r.offerSent(0)
        assertFalse(r.earlierRoundFailed)
        assertEquals(CallRecoveryPolicy.Action.ABANDON_OFFER,r.next(30_000))
    }
    @Test fun `consent loss reanchors dead pair recovery and preserves pending offer`() {
        val r=CallRecoveryPolicy(caller=true,peerResolvesGlare=true)
        r.mediaStalled(0); r.offerRequested(0,true); r.offerSent(0)
        r.mediaDisconnected(20_000)
        assertEquals(20_000L,r.startedAt); assertEquals(0,r.negotiations); assertNull(r.lastNegotiationAt)
        assertEquals(0L,r.offerPendingSince); assertFalse(r.earlierRoundFailed)
        r.offerResolved(21_000)
        assertEquals(CallRecoveryPolicy.Action.WAIT,r.next(24_999))
        assertEquals(CallRecoveryPolicy.Action.RESTART,r.next(25_000))
        assertEquals(CallRecoveryPolicy.Action.FAIL,r.next(50_000))
    }
    @Test fun `Opus DTX receive preference is idempotent and preserves RED and explicit off`() {
        val sdp="v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 63 111\r\na=rtpmap:63 red/48000/2\r\na=fmtp:63 111/111\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;useinbandfec=1\r\n"
        val munged=CallSdpMunger.requestingOpusDtx(sdp)
        assertEquals(sdp,munged.replace(";usedtx=1",""))
        assertEquals(munged,CallSdpMunger.requestingOpusDtx(munged))
        val absent="v=0\na=rtpmap:111 opus/48000/2\n"
        assertEquals("v=0\na=rtpmap:111 opus/48000/2\na=fmtp:111 usedtx=1\n",CallSdpMunger.requestingOpusDtx(absent))
        val explicit="v=0\na=rtpmap:111 opus/48000/2\na=fmtp:111 usedtx=0\n"
        assertEquals(explicit,CallSdpMunger.requestingOpusDtx(explicit))
        assertEquals("v=0\n",CallSdpMunger.requestingOpusDtx("v=0\n"))
    }
    @Test fun `RED is preferred when available and absent entirely on saving row`() {
        val names=listOf("opus","red","G722","PCMU","telephone-event")
        assertEquals(listOf(1,0,2,3,4),CallAudioCodecPreference.order(names,true))
        assertEquals(listOf(0,2,3,4),CallAudioCodecPreference.order(names,false))
        assertEquals(listOf(0,1),CallAudioCodecPreference.order(listOf("opus","PCMU"),true))
    }
}
