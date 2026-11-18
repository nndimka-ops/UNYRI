package com.messenger.app.ui.call

import com.messenger.app.R
import com.messenger.app.data.call.*
import kotlin.test.*

class CallQualityPresentationTest {
    private fun sample(t: Int, rtt: Double = .1, sentStep: Long = 50, loss: Double = 0.0, jitter: Double = .01) = WebRtcCallStats(
        selectedCandidatePair = WebRtcCandidatePairStats(roundTripTimeSeconds = rtt),
        audioOutbound = WebRtcAudioOutboundStats(packetsSent = t*sentStep),
        audioInbound = WebRtcAudioInboundStats(packetsReceived = t*50L, packetsLost = 0, bytesReceived = t*1000L, jitter = jitter),
        audioRemoteInbound = WebRtcRemoteInboundStats(fractionLost = loss, packetsLost = (t*sentStep*loss).toLong()))
    private fun update(p: CallQualityPresentation, t: Int, rtt: Double = .1) = p.update(t*1000L,sample(t,rtt),false,false)
    @Test fun `measuring needs full five samples and resets after sampling gap`() {
        val p=CallQualityPresentation()
        for (t in 0..3) assertEquals(0,update(p,t).bars)
        assertEquals(4,update(p,4).bars)
        assertEquals(0,update(p,8).bars)
        for(t in 9..11) assertEquals(0,update(p,t).bars)
        assertEquals(4,update(p,12).bars)
    }
    @Test fun `unknown metrics never fabricate excellent grade`() {
        val p=CallQualityPresentation()
        for(t in 0..10) assertEquals(0,p.update(t*1000L,WebRtcCallStats(),false,false).bars)
    }
    @Test fun `degrade needs two worse readings and three second hold improve needs five`() {
        val p=CallQualityPresentation()
        for(t in 0..4) update(p,t)
        for(t in 5..10) update(p,t,1.0)
        assertEquals(1,p.result(10_000).bars)
        for(t in 11..14) update(p,t)
        assertEquals(1,p.result(14_000).bars)
        for(t in 15..20) update(p,t)
        assertEquals(4,p.result(20_000).bars)
    }
    @Test fun `weak words held four seconds then expire five seconds later poor stays`() {
        val p=CallQualityPresentation()
        for(t in 0..4) update(p,t,.5)
        assertNull(p.result(7_999).caption)
        assertEquals(R.string.call_quality_weak,p.result(8_000).caption)
        assertNull(p.result(13_000).caption)
        val poor=CallQualityPresentation()
        for(t in 0..4) update(poor,t,1.0)
        assertEquals(R.string.call_quality_poor,poor.result(80_000).caption)
    }
    @Test fun `DTX loss ignored below forty outgoing packets`() {
        val p=CallQualityPresentation()
        var r=CallQualityPresentation.Result()
        for(t in 0..4) r=p.update(t*1000L,sample(t,sentStep=2,loss=.5),false,false)
        assertEquals(4,r.bars)
    }
    @Test fun `repaired incoming audio graded by audible concealment not packet loss`() {
        val p=CallQualityPresentation()
        var r=CallQualityPresentation.Result()
        for(t in 0..4) r=p.update(t*1000L,sample(t).copy(audioInbound=WebRtcAudioInboundStats(
            bytesReceived=t*1000L,packetsReceived=t*50L,packetsLost=t*20L,concealedSamples=t*100L,
            silentConcealedSamples=t*100L,totalSamplesReceived=t*48000L)),false,false)
        assertEquals(4,r.bars)
    }
    @Test fun `stale video with no payload growth does not degrade audio`() {
        val p=CallQualityPresentation();var r=CallQualityPresentation.Result()
        for(t in 0..8) r=p.update(t*1000L,sample(t).copy(videoInbound=WebRtcVideoInboundStats(bytesReceived=1000,framesDecoded=0)),true,false)
        assertEquals(4,r.bars)
    }
    @Test fun `muting my microphone does not suppress evidence of missing peer audio`() {
        val p=CallQualityPresentation();var r=CallQualityPresentation.Result()
        for(t in 0..8) r=p.update(t*1000L,sample(t).copy(audioInbound=WebRtcAudioInboundStats(bytesReceived=1000,packetsReceived=50,jitter=.01)),
            false,false,localMuted=true)
        assertEquals(1,r.bars)
    }
    @Test fun `only bandwidth limited upload grades low bitrate`() {
        for(limited in listOf(false,true)) {
            val p=CallQualityPresentation();var r=CallQualityPresentation.Result()
            for(t in 0..6) r=p.update(t*1000L,sample(t).copy(
                selectedCandidatePair=WebRtcCandidatePairStats(roundTripTimeSeconds=.1,availableOutgoingBitrate=100_000.0),
                videoOutbound=WebRtcVideoOutboundStats(qualityLimitationReason=if(limited) "bandwidth" else "cpu")),false,true)
            assertEquals(if(limited) 1 else 4,r.bars)
        }
    }
}
