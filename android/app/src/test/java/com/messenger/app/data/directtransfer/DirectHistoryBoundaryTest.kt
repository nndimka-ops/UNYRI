package com.messenger.app.data.directtransfer

import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.attachment.MessageContentDto
import com.messenger.app.data.history.*
import com.messenger.app.data.local.chat.ChatWorkspaceValue
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

class DirectHistoryBoundaryTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private val offer = DirectFileOffer(transferId=id(7),offerClientMessageId=id(3),senderUserId=id(1),senderDeviceId=id(8),
        recipientUserId=id(2),offerNonce=java.util.Base64.getEncoder().encodeToString(ByteArray(32)),filename="original.mp4",
        mimeType="video/mp4",size=136315007,sha256="a".repeat(64),expiresAt=1)
    private val text = "Direct file transfer\n\nCaption **literal** Привет 👋"
    private val historyJson=Json { ignoreUnknownKeys=false }
    private fun archive(payload: String) = HistoryArchive(server=id(9),owner=id(2),snapshot=id(10),
        messages=listOf(HistoryArchiveMessage(id(3),id(4),id(5),id(1),10,1000,payload)))
    @Test fun actualCodecRetainsCanonicalWireCaptionAndMetadata() {
        val encoded=MessageContentCodec.encodeText(text,directFile=offer)
        val decoded=MessageContentCodec.decode(encoded)
        assertEquals(text,decoded.text);assertEquals(offer,DirectFileOffer.decode(decoded.directFile))
        assertEquals("Caption **literal** Привет 👋",DirectFileOffer.caption(decoded.text))
        assertNull(decoded.authentication) // A codec fixture is not Signal authority.
        File("build/direct-history-android-wire.json").writeBytes(encoded)
        File("build/direct-history-android-portable.json").writeText(historyJson.encodeToString(decoded))
    }
    @Test fun independentlyEmittedSwiftWireUsesActualKotlinDecoder() {
        val filename=System.getenv("COCO_DIRECT_HISTORY_SWIFT_WIRE")
        org.junit.Assume.assumeTrue("Actual Swift wire fixture required",filename != null)
        val incoming=MessageContentCodec.decode(File(requireNotNull(filename)).readBytes())
        assertEquals(text,incoming.text);assertEquals(offer,DirectFileOffer.decode(incoming.directFile))
    }
    @Test fun rawSwiftPortablePayloadMergesWithoutForeignDecodeReserialization() {
        val filename=System.getenv("COCO_DIRECT_HISTORY_SWIFT_PORTABLE")
        org.junit.Assume.assumeTrue("Actual Swift MessageStore export required",filename != null)
        val rawSwift=File(requireNotNull(filename)).readText()
        val own=historyJson.encodeToString(MessageContentCodec.decode(MessageContentCodec.encodeText(text,directFile=offer)))
        assertEquals(archive(own),HistoryArchiveMerge.merge(archive(own),archive(rawSwift)))
    }
    @Test fun legacyKnownPrefixDisplaysWithoutRewritingStoredText() {
        val legacy="Передача файла напрямую\n\nLegacy caption"
        assertEquals(legacy,MessageContentCodec.decode(MessageContentCodec.encodeText(legacy,directFile=offer)).text)
        assertEquals("Legacy caption",DirectFileOffer.caption(legacy))
        assertNull(DirectFileOffer.caption("unrelated"))
    }
    @Test fun inertDescriptorCannotBecomeConsentOrCompletedTransfer() {
        val value=ChatWorkspaceValue(id(5),DirectFileHistory.key(id(3)),offer.stored())
        val display=assertNotNull(DirectFileHistory.presentation(value,id(5)))
        assertEquals("other",display.role);assertEquals("other_device",display.state)
        assertEquals("history_only",display.reason);assertFalse(display.completed);assertEquals(0L,display.offset)
        assertNull(DirectFileHistory.presentation(value.copy(name="direct_transfer:"+offer.transferId),id(5)))
        assertNull(DirectFileHistory.presentation(value.copy(chatId=id(99)),id(5)))
        assertFails { DirectFileHistory.validate(offer,id(99),id(1),id(2)) }
        assertFails { DirectFileHistory.validate(offer,id(3),id(99),id(2)) }
        assertFails { DirectFileHistory.validate(offer,id(3),id(1),id(99)) }
    }
    @Test fun trustedCompletedJournalAlwaysWinsReadonlyDescriptorRegardlessRowOrder() = runTest {
        val inert=ChatWorkspaceValue(id(5),DirectFileHistory.key(id(3)),offer.stored())
        val complete=DirectTransferRecord(offer,id(5),"receiver",state="completed",completed=true,durableOffset=offer.size)
        val trusted=ChatWorkspaceValue(id(5),DirectTransferCoordinator.key(offer.transferId),
            DirectFileOffer.codec.encodeToString(DirectTransferRecord.serializer(),complete))
        for(rows in listOf(listOf(inert,trusted),listOf(trusted,inert))) {
            val value=flowOf(rows).directFilePresentations(id(5)).single().getValue(id(3))
            assertTrue(value.completed);assertEquals("receiver",value.role);assertEquals(offer.size,value.offset)
        }
    }
    @Test fun matchingPortableMetadataMergesButActualMetadataConflictStillFails() {
        val payload=historyJson.encodeToString(MessageContentDto(kind="text",text=text,directFile=offer.json()))
        val a=archive(payload);assertEquals(a,HistoryArchiveMerge.merge(a,a))
        val changed=historyJson.encodeToString(MessageContentDto(kind="text",text=text,directFile=offer.copy(filename="changed.mp4").json()))
        assertFails { HistoryArchiveMerge.merge(a,archive(changed)) }
    }
}
