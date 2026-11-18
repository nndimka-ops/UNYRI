package com.messenger.app.ui.chats

import com.messenger.app.domain.model.*
import kotlin.test.*

class QueuedMediaPresentationTest {
    private val preview=Attachment("id","id",AttachmentMediaType.IMAGE,"image/jpeg","photo.jpg",1,4032,3024,null,null,
        "/local/source.jpg",AttachmentDownloadState.DOWNLOADED,AttachmentUploadState.PENDING,0)
    private val message=Message("id",null,"chat","me","",null,"2026-10-26T10:00:00Z",MessageStatus.PENDING,
        queuedAttachment=preview,localMediaPresentation=LocalMediaPresentation("2026-10-26T10:00:00Z",0,4f/3f,true))

    @Test fun `preparation keeps already available preview and progress controls`() {
        val preparing=preview.copy(localPath=null,downloadState=AttachmentDownloadState.NOT_DOWNLOADED,
            width=null,height=null,uploadState=AttachmentUploadState.ENCRYPTING,progress=12)
        val result=queuedMediaPresentation(message,preparing)!!
        assertEquals(preview.localPath,result.localPath)
        assertEquals(AttachmentDownloadState.DOWNLOADED,result.downloadState)
        assertEquals(AttachmentUploadState.ENCRYPTING,result.uploadState)
        assertEquals(12,result.progress)
    }
    @Test fun `transcode dimensions do not resize visible media on acknowledgement`() {
        val pending=queuedMediaPresentation(message,null)!!
        val sent=queuedMediaPresentation(message.copy(queuedAttachment=null,status=MessageStatus.SENT),
            preview.copy(width=853,height=640,uploadState=AttachmentUploadState.SENT))!!
        assertEquals(pending.width,sent.width);assertEquals(pending.height,sent.height)
    }
    @Test fun `incoming and document geometry is not changed`() {
        assertEquals(preview,queuedMediaPresentation(message.copy(queuedAttachment=null,localMediaPresentation=null),preview))
        assertEquals(preview,queuedMediaPresentation(message.copy(localMediaPresentation=message.localMediaPresentation!!.copy(visual=false)),preview))
    }

    @Test fun `confirmed restored attachment never looks like an active upload`() {
        for (status in listOf(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ)) {
            for (old in listOf(AttachmentUploadState.PENDING, AttachmentUploadState.ENCRYPTING,
                AttachmentUploadState.UPLOADING, AttachmentUploadState.UPLOADED, AttachmentUploadState.SENDING, AttachmentUploadState.FAILED)) {
                val confirmed = message.copy(serverId="server",sequence=483,status=status,queuedAttachment=null,localMediaPresentation=null)
                val stored = preview.copy(mediaType=AttachmentMediaType.FILE,mimeType="video/mp4",uploadState=old,progress=100)
                val result = queuedMediaPresentation(confirmed,stored)!!
                assertEquals(stored.copy(uploadState=AttachmentUploadState.SENT),result)
                val uploading = result.uploadState != AttachmentUploadState.SENT && result.uploadState != AttachmentUploadState.CANCELLED
                assertFalse(uploading,"A confirmed video must be playable, without upload/X overlay")
            }
        }
    }

    @Test fun `ack projection leaves active download and cached presentation completely unchanged`() {
        val confirmed=message.copy(serverId="server",sequence=484,status=MessageStatus.READ,queuedAttachment=null,localMediaPresentation=null)
        val downloading=preview.copy(downloadState=AttachmentDownloadState.DOWNLOADING,uploadState=AttachmentUploadState.UPLOADED,
            progress=42,thumbnailBase64="existing-thumbnail",localPath="/owned/previous-preview")
        assertEquals(downloading.copy(uploadState=AttachmentUploadState.SENT),queuedMediaPresentation(confirmed,downloading))
    }

    @Test fun `pending failed incomplete deleted cancelled or mismatched rows are never promoted`() {
        val confirmed=message.copy(serverId="server",sequence=483,status=MessageStatus.READ,queuedAttachment=null,localMediaPresentation=null)
        val stored=preview.copy(uploadState=AttachmentUploadState.UPLOADED)
        val invalid=listOf(confirmed.copy(serverId=null),confirmed.copy(serverId=" "),confirmed.copy(sequence=null),
            confirmed.copy(sequence=0),confirmed.copy(sequence=-1),confirmed.copy(status=MessageStatus.PENDING),
            confirmed.copy(status=MessageStatus.FAILED),confirmed.copy(status=MessageStatus.DECRYPT_FAILED),
            confirmed.copy(status=MessageStatus.IDENTITY_CHANGED),confirmed.copy(isDeletedForEveryone=true))
        for(row in invalid) assertEquals(stored,queuedMediaPresentation(row,stored))
        val cancelled=stored.copy(uploadState=AttachmentUploadState.CANCELLED)
        assertEquals(cancelled,queuedMediaPresentation(confirmed,cancelled))
        val foreign=stored.copy(clientMessageId="another-message")
        assertEquals(foreign,queuedMediaPresentation(confirmed,foreign))
    }

    @Test fun `acknowledged queued preview keeps reserved geometry while dropping upload controls`() {
        val pending=queuedMediaPresentation(message,null)!!
        val confirmed=queuedMediaPresentation(message.copy(serverId="server",sequence=483,status=MessageStatus.SENT),null)!!
        assertEquals(AttachmentUploadState.SENT,confirmed.uploadState)
        assertEquals(pending.copy(uploadState=AttachmentUploadState.SENT),confirmed)
    }
}
