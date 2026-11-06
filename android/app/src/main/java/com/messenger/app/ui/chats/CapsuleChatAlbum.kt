package com.messenger.app.ui.chats

import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.res.stringResource
import com.messenger.app.R
import com.messenger.app.ui.components.messageSelectionBodyCenter
import com.messenger.app.domain.model.*

internal val LocalChatAlbumCell = compositionLocalOf { false }
internal val LocalChatAlbumCellWidth = compositionLocalOf<Float?> { null }
internal val LocalChatAlbumOwnsSelectionMark = compositionLocalOf { false }

internal fun albumReceipt(messages: List<Message>): MessageStatus = when {
    messages.any { it.status==MessageStatus.FAILED } -> MessageStatus.FAILED
    messages.any { it.status==MessageStatus.PENDING } -> MessageStatus.PENDING
    messages.any { it.status==MessageStatus.SENT } -> MessageStatus.SENT
    messages.any { it.status==MessageStatus.DELIVERED } -> MessageStatus.DELIVERED
    else -> MessageStatus.READ
}

/** One visual object per send batch. Media runs form a single clipped mosaic;
 * documents keep their real rows and controls inside the SAME group. */
@OptIn(ExperimentalFoundationApi::class,ExperimentalLayoutApi::class)
@Composable
internal fun CapsuleChatAlbum(messages: List<Message>, outgoing: Boolean,
    attachments: Map<String,Attachment> = emptyMap(),
    replyAuthor:String="",onScrollToReply:(String)->Unit = {},
    replyAttachment:Attachment? = null,
    onCaptionTap: (Message)->Unit = {},
    onCaptionLongPress: (Message,Rect)->Unit = {_,_->},
    onReaction: (Message,String)->Unit = {_,_->},
    selectionMark: (@Composable () -> Unit)? = null,
    cell: @Composable (Message)->Unit) {
    val appearance = com.messenger.app.ui.theme.LocalChatAppearanceStyle.current
    require(messages.size in 1..10)
    fun attachment(message:Message)=attachments[message.clientMessageId]?:message.queuedAttachment
    fun visual(message:Message):Boolean = message.localMediaPresentation?.visual
        ?: attachment(message)?.let {it.mediaType==AttachmentMediaType.IMAGE||it.mimeType.startsWith("video/")}?:true
    val allDocuments = messages.none(::visual)
    val captionMessage=messages.firstOrNull { !it.caption.isNullOrBlank() }
    val density=LocalDensity.current
    val scaledEmoji=with(density){CapsuleChatStyle.emojiSize.sp.toDp()}
    val scaledReactionLine=with(density){CapsuleChatStyle.reactionHeight.sp.toDp()}
    val reactionWidth=if(allDocuments)maxOf(CapsuleChatStyle.reactionWidth.dp,scaledEmoji+10.dp)else CapsuleChatStyle.reactionWidth.dp
    val reactionHeight=if(allDocuments)maxOf(CapsuleChatStyle.reactionHeight.dp,scaledEmoji+6.dp,scaledReactionLine)else CapsuleChatStyle.reactionHeight.dp
    @Composable fun Metadata() {
        Row(Modifier.padding(start=8.dp,end=8.dp,top=CapsuleChatStyle.timeOffset.dp)
            .then(if(allDocuments)Modifier.testTag("document-album-metadata")else Modifier),verticalAlignment=Alignment.CenterVertically) {
            Text(formatTime(messages.last().createdAt),fontSize=CapsuleChatStyle.timeSize.sp,lineHeight=13.sp,color=CapsuleChatStyle.muted)
            if(outgoing){Spacer(Modifier.width(CapsuleChatStyle.metaGap.dp));MessageReceipt(albumReceipt(messages))}
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=if(outgoing)Alignment.CenterEnd else Alignment.CenterStart) {
        val width=minOf(maxWidth*CapsuleChatStyle.width,if(allDocuments)CapsuleChatStyle.fileWidth.dp else CapsuleChatStyle.mediaWidth.dp)
        CompositionLocalProvider(LocalChatAlbumOwnsSelectionMark provides (selectionMark != null)) {
        com.messenger.app.ui.components.SelectionOverlayLayout(outgoing = outgoing, selectionMark = selectionMark) {
        Column(Modifier.width(width).testTag("chat-album"),horizontalAlignment=if(outgoing)Alignment.End else Alignment.Start) {
            Column(Modifier.fillMaxWidth().messageSelectionBodyCenter().testTag("chat-album-body")
                .clip(if(allDocuments)appearance.documentAlbumShape else CapsuleChatStyle.mediaShape)
                .background(if(outgoing)appearance.outgoing else appearance.incoming)) {
                messages.firstNotNullOfOrNull {it.replyPreview}?.let {reply->
                    ReplyQuote(replyAuthor.ifBlank {stringResource(R.string.reply)},reply.preview,reply.contentType,
                        Modifier.fillMaxWidth().clickable {onScrollToReply(reply.targetServerId)}, attachment=replyAttachment)
                }
                if(messages.any {it.forwarded})ForwardedMessageLabel(Modifier.padding(8.dp))
                var index=0
                while(index<messages.size) {
                    val message=messages[index]
                    if(visual(message)) {
                        val end=(index until messages.size).firstOrNull { !visual(messages[it]) }?:messages.size
                        val run=messages.subList(index,end)
                        val ratios=run.map {it.localMediaPresentation?.let {layout->layout.aspectRatio?:1f}
                            ?: attachment(it)?.let {a->if(a.width!=null&&a.height!=null&&a.height>0)a.width.toFloat()/a.height else 1f}?:1f}
                        val mosaic=remember(run.map {it.clientMessageId},ratios,width.value,CapsuleChatStyle.albumGap){
                            albumMosaic(ratios,width.value,gap=CapsuleChatStyle.albumGap)
                        }
                        val density=LocalDensity.current
                        androidx.compose.ui.layout.Layout(content={
                            run.forEachIndexed { tileIndex,member ->
                                val tile=mosaic.tiles[tileIndex]
                                CompositionLocalProvider(LocalChatAlbumCell provides true,
                                    LocalChatAlbumCellWidth provides tile.width,
                                    LocalChatMediaWidth provides tile.width,LocalChatMediaHeight provides tile.height,
                                    LocalChatAlbumLastMember provides false,
                                    LocalChatMediaShape provides RoundedCornerShape(0.dp)) { cell(member) }
                            }
                        },modifier=Modifier.fillMaxWidth()) {measurables,_ ->
                            val positions=mosaic.tiles.map { tile->
                                val x=with(density){tile.x.dp.roundToPx()};val y=with(density){tile.y.dp.roundToPx()}
                                val right=with(density){(tile.x+tile.width).dp.roundToPx()};val bottom=with(density){(tile.y+tile.height).dp.roundToPx()}
                                Pair(Pair(x,y),Pair(right-x,bottom-y))
                            }
                            val placeables=measurables.mapIndexed {i,m->m.measure(Constraints.fixed(positions[i].second.first,positions[i].second.second))}
                            layout(with(density){mosaic.width.dp.roundToPx()},with(density){mosaic.height.dp.roundToPx()}) {
                                placeables.forEachIndexed {i,p->p.place(positions[i].first.first,positions[i].first.second)}
                            }
                        }
                        index=end
                    } else {
                        CompositionLocalProvider(LocalChatAlbumCell provides true,LocalChatAlbumCellWidth provides width.value) { cell(message) }
                        index++
                    }
                    if(index<messages.size) Spacer(Modifier.fillMaxWidth().height(CapsuleChatStyle.albumGap.dp))
                }
                captionMessage?.let { member ->
                    val bounds=remember { arrayOf(Rect.Zero) }
                    Text(cocoMessageRichText(member.caption.orEmpty()),fontSize=com.messenger.app.ui.theme.LocalChatTextSize.current.sp,
                        fontFamily=appearance.messageFontFamily,
                        lineHeight=(com.messenger.app.ui.theme.LocalChatTextSize.current*appearance.lineHeight).sp,
                        fontWeight=if(appearance.mediumText)FontWeight.Medium else FontWeight.Normal,
                        color=if(outgoing)CapsuleChatStyle.outText else CapsuleChatStyle.text,
                        modifier=Modifier.fillMaxWidth().onGloballyPositioned {bounds[0]=it.boundsInWindow()}
                            .combinedClickable(onClick={onCaptionTap(member)},onLongClick={onCaptionLongPress(member,bounds[0])})
                            .padding(horizontal=appearance.padX.dp,vertical=if(allDocuments)appearance.padY.dp
                                else (10f + appearance.padY - CapsuleChatStyle.padY).coerceAtLeast(0f).dp))
                }
            }
            if(allDocuments&&CapsuleChatStyle.showTimes)Metadata()
            if(messages.any {it.reactions.isNotEmpty()}) FlowRow(Modifier.padding(top=CapsuleChatStyle.reactionOffset.dp)
                .then(if(allDocuments)Modifier.testTag("document-album-reactions")else Modifier),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                messages.forEach {member->member.reactions.forEach {reaction->repeat(reaction.count.coerceAtLeast(0)) {
                    Box(Modifier.size(reactionWidth,reactionHeight).clickable {onReaction(member,reaction.emoji)},contentAlignment=Alignment.Center) {
                        if(allDocuments)Text(reaction.emoji,fontSize=CapsuleChatStyle.emojiSize.sp,lineHeight=CapsuleChatStyle.reactionHeight.sp,
                            modifier=Modifier.width(reactionWidth),maxLines=1,softWrap=false,
                            textAlign=androidx.compose.ui.text.style.TextAlign.Center,
                            style=MaterialTheme.typography.bodySmall.copy(platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false)))
                        else Text(reaction.emoji,fontSize=CapsuleChatStyle.emojiSize.sp)
                    }
                }}}
            }
            if(!allDocuments)Metadata()
        }
        }
        }
    }
}

