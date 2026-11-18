package com.messenger.app.data.directtransfer
import kotlin.test.*
class DirectLocalTransferStopTest {
    private val offer = DirectFileOffer(transferId="10000000-0000-4000-8000-000000000001", offerClientMessageId="10000000-0000-4000-8000-000000000002",
        senderUserId="10000000-0000-4000-8000-000000000003",senderDeviceId="10000000-0000-4000-8000-000000000004",recipientUserId="10000000-0000-4000-8000-000000000005",
        offerNonce="BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",filename="original.bin",mimeType="application/octet-stream",size=1024,sha256="a".repeat(64),expiresAt=999999)
    private val base=DirectFilePresentation(offer,"transferring","sender",256,"prefix_mismatch",ackSampleId=7,speedBytesPerSecond=123,
        localManualPaused=true,peerManualPaused=true,manualSyncPending=true,manualSyncLimitReached=true,manualControlsAvailable=true)
    @Test fun idMismatchAndNoObligationCannotProjectAnotherTransfer() {
        assertSame(base,base.withLocalStop(null))
        assertSame(base,base.withLocalStop(DirectLocalTransferStop("wrong",true,true,false)))
        assertSame(base,base.withLocalStop(DirectLocalTransferStop(offer.transferId,true,false,false)))
    }
    @Test fun temporaryCopyPreservesOriginalOfferOffsetDurableFlagsAndHardReason() {
        val shown=base.withLocalStop(DirectLocalTransferStop(offer.transferId,true,true,false))
        assertEquals("paused",shown.state);assertEquals("manual_pause_saving",shown.reason);assertEquals(0L,shown.speedBytesPerSecond)
        assertSame(base.offer,shown.offer);assertEquals(base.offset,shown.offset);assertEquals(base.ackSampleId,shown.ackSampleId)
        assertEquals(base.localManualPaused,shown.localManualPaused);assertEquals(base.peerManualPaused,shown.peerManualPaused)
        assertEquals(base.manualSyncPending,shown.manualSyncPending);assertEquals(base.manualSyncLimitReached,shown.manualSyncLimitReached)
        assertEquals("prefix_mismatch",base.reason);assertEquals(123L,base.speedBytesPerSecond);assertSame(base,base.withLocalStop(null))
    }
    @Test fun pendingWinsOverPriorErrorAndNeverManufacturesPermission() {
        for(paused in listOf(false,true)) {
            val stop=DirectLocalTransferStop(offer.transferId,paused,true,true)
            val shown=base.withLocalStop(stop)
            assertFalse(shown.manualControlsAvailable)
            assertEquals(if(paused)"manual_pause_saving" else "manual_continue_saving",shown.reason)
            assertFalse(base.copy(manualControlsAvailable=false).withLocalStop(stop.copy(pending=false)).manualControlsAvailable)
        }
    }
    @Test fun ambiguousFalseDurableContinueKeepsRequestedRetryCode() {
        val before=base.copy(localManualPaused=false)
        val shown=before.withLocalStop(DirectLocalTransferStop(offer.transferId,false,false,true))
        assertEquals("manual_continue_save_failed",shown.reason);assertFalse(shown.localManualPaused);assertTrue(shown.manualControlsAvailable)
    }
    @Test fun terminalInertVerifyingAndCompleteOffsetDominateStaleStop() {
        val stop=DirectLocalTransferStop(offer.transferId,true,true,true)
        for(state in listOf("completed","cancelled","rejected","expired","other_device","verifying")) {
            val value=base.copy(state=state);assertSame(value,value.withLocalStop(stop))
        }
        for(value in listOf(base.copy(completed=true),base.copy(role="other"),base.copy(reason="history_only"),base.copy(offset=offer.size))) assertSame(value,value.withLocalStop(stop))
    }
}
