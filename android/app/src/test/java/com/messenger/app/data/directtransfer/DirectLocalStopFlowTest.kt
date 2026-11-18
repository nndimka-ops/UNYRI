package com.messenger.app.data.directtransfer
import com.messenger.app.data.local.chat.ChatWorkspaceValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DirectLocalStopFlowTest {
    private fun id(n:Int)="00000000-0000-4000-8000-%012d".format(n)
    private val chat=id(90)
    private fun offer(n:Int)=DirectFileOffer(transferId=id(n),offerClientMessageId=id(n+10),senderUserId=id(80),senderDeviceId=id(81),recipientUserId=id(82),
        offerNonce="BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",filename="original-$n.bin",mimeType="application/octet-stream",size=1024,sha256="a".repeat(64),expiresAt=999999)
    private fun row(offer:DirectFileOffer):ChatWorkspaceValue {
        val record=DirectTransferRecord(offer,chat,"sender",state="transferring",durableOffset=256,reason="security_error",ackSampleId=7,speedBytesPerSecond=123)
        return ChatWorkspaceValue(chat,DirectTransferCoordinator.key(offer.transferId),DirectFileOffer.codec.encodeToString(DirectTransferRecord.serializer(),record))
    }
    @Test fun localPendingPublishesImmediatelyWithoutAnyNewDurableEmissionAndOnlyMatchingRowChanges()= runTest {
        val a=offer(1);val b=offer(2)
        val durable=MutableStateFlow(listOf(row(a),row(b)))
        val stops=MutableStateFlow<Map<String,DirectLocalTransferStop>>(emptyMap())
        val values=mutableListOf<Map<String,DirectFilePresentation>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { durable.directFilePresentations(chat,stops).collect { values+=it } }
        runCurrent();val initial=values.last();assertEquals(2,initial.size)
        stops.value=mapOf(a.transferId to DirectLocalTransferStop(a.transferId,true,true,false))
        runCurrent();val pending=values.last();assertEquals("manual_pause_saving",pending.getValue(a.offerClientMessageId).reason)
        assertEquals(0L,currentTime);assertSame(initial.getValue(b.offerClientMessageId),pending.getValue(b.offerClientMessageId))
        assertEquals(a.filename, pending.getValue(a.offerClientMessageId).offer.filename)
        assertEquals(256L,pending.getValue(a.offerClientMessageId).offset);assertEquals(7L,pending.getValue(a.offerClientMessageId).ackSampleId)
        assertEquals(initial.getValue(a.offerClientMessageId).localManualPaused,pending.getValue(a.offerClientMessageId).localManualPaused)
        stops.value=emptyMap();runCurrent();assertSame(initial.getValue(a.offerClientMessageId),values.last().getValue(a.offerClientMessageId))
        assertEquals("security_error",values.last().getValue(a.offerClientMessageId).reason)
    }
    @Test fun oldCallersDefaultEmitsAndHistoryNeverGetsAnActionableStop()=runTest {
        val a=offer(3);val legacy=flowOf(listOf(row(a))).directFilePresentations(chat).single();assertTrue(legacy.containsKey(a.offerClientMessageId))
        val descriptor=ChatWorkspaceValue(chat,DirectFileHistory.key(a.offerClientMessageId),a.stored())
        val value=flowOf(listOf(descriptor)).directFilePresentations(chat,flowOf(mapOf(a.transferId to DirectLocalTransferStop(a.transferId,true,true,false)))).single().getValue(a.offerClientMessageId)
        assertEquals("other",value.role);assertEquals("other_device",value.state);assertEquals("history_only",value.reason);assertFalse(value.manualControlsAvailable)
    }
}
