package com.messenger.app.data.message

import com.messenger.app.conference.ConferenceInvitation
import com.messenger.app.data.attachment.MessageContentCodec
import com.messenger.app.data.attachment.ReplySnapshotDto
import com.messenger.app.data.local.message.MessageEntity

/** The actual durable text outbox composer; frozen text and typed metadata remain independent. */
internal fun composeTextMessagePayload(entity: MessageEntity, reply: ReplySnapshotDto?, directFile: com.messenger.app.data.directtransfer.DirectFileOffer? = null): ByteArray =
    MessageContentCodec.encodeText(entity.payloadText ?: entity.text, replySnapshot = reply, forwarded = entity.forwarded,
        conferenceInvite = ConferenceInvitation.decodeStored(entity.conferenceInviteJson), directFile = directFile)
