package com.messenger.app.ui.transfers
import com.messenger.app.data.directtransfer.*
import kotlin.test.*
class DirectLocalStopUiPolicyTest {
    private val offer=DirectFileOffer(transferId="10000000-0000-4000-8000-000000000001",offerClientMessageId="10000000-0000-4000-8000-000000000002",senderUserId="10000000-0000-4000-8000-000000000003",senderDeviceId="10000000-0000-4000-8000-000000000004",recipientUserId="10000000-0000-4000-8000-000000000005",offerNonce="BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",filename="original.bin",mimeType="application/octet-stream",size=1024,sha256="a".repeat(64),expiresAt=999999)
    private val base=DirectFilePresentation(offer,"paused","receiver",256,manualControlsAvailable=true,localManualPaused=true,peerManualPaused=true,manualSyncPending=true,manualSyncLimitReached=true)
    private val all=DirectTransferActionPermissions(true,true,true,true,true)
    @Test fun staleStopCannotHideUnconsentedOfferOrItsManualAccept() {
        val offered=base.copy(state="offered",offset=0,reason=null,localManualPaused=false,peerManualPaused=false,manualSyncPending=false,manualSyncLimitReached=false,manualControlsAvailable=false)
        for(stop in listOf(DirectLocalTransferStop(offer.transferId,true,true,false),DirectLocalTransferStop(offer.transferId,false,false,true))) {
            val shown=offered.withLocalStop(stop)
            assertSame(offered,shown)
            assertTrue(DirectTransferUiPolicy.canOfferActions(shown,DirectTransferUiPolicy.cardPermissions(shown)))
            assertEquals(DirectTransferStatus.OFFERED,DirectTransferUiPolicy.status(shown))
        }
    }
    @Test fun pendingDisablesEveryManualOrGenericRetryButRetainsCancel() {
        for(requested in listOf(false,true)) {
            val shown=base.withLocalStop(DirectLocalTransferStop(offer.transferId,requested,true,false))
            assertNull(DirectTransferUiPolicy.manualAction(shown));assertFalse(DirectTransferUiPolicy.canRetrySynchronization(shown))
            assertFalse(DirectTransferUiPolicy.canRetry(shown,all));assertFalse(DirectTransferUiPolicy.canRestart(shown,all));assertTrue(DirectTransferUiPolicy.canCancel(shown,all))
            assertEquals(if(requested)DirectTransferStatus.PAUSE_SAVING else DirectTransferStatus.CONTINUE_SAVING,DirectTransferUiPolicy.status(shown))
        }
    }
    @Test fun failedIntentForcesSameRouteEvenAfterAmbiguousDurableFalseContinue() {
        val paused=base.withLocalStop(DirectLocalTransferStop(offer.transferId,true,false,true))
        val continued=base.copy(localManualPaused=false).withLocalStop(DirectLocalTransferStop(offer.transferId,false,false,true))
        assertEquals(DirectTransferManualAction.PAUSE,DirectTransferUiPolicy.manualAction(paused));assertEquals(DirectTransferStatus.PAUSE_SAVE_FAILED,DirectTransferUiPolicy.status(paused))
        assertEquals(DirectTransferManualAction.CONTINUE,DirectTransferUiPolicy.manualAction(continued));assertEquals(DirectTransferStatus.CONTINUE_SAVE_FAILED,DirectTransferUiPolicy.status(continued))
        for(v in listOf(paused,continued)) { assertFalse(DirectTransferUiPolicy.canRetry(v,all));assertFalse(DirectTransferUiPolicy.canRestart(v,all));assertFalse(DirectTransferUiPolicy.canRetrySynchronization(v));assertTrue(DirectTransferUiPolicy.canCancel(v,all)) }
    }
    @Test fun unknownPermissionTerminalHistoryVerifyingAndFullOffsetNeverGainRetryIntent() {
        for(v in listOf(base.copy(manualControlsAvailable=false),base.copy(state="verifying"),base.copy(state="completed"),base.copy(state="other_device",reason="history_only"),base.copy(role="other"),base.copy(offset=1024))) {
            val shown=v.withLocalStop(DirectLocalTransferStop(offer.transferId,false,false,true));assertNull(DirectTransferUiPolicy.manualAction(shown))
        }
    }
}
