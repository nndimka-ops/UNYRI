package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.messenger.app.domain.model.*
import com.messenger.app.ui.theme.CocoTheme
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Saved material changes opacity/gap, never the actual 2–10-item album membership. */
@OptIn(ExperimentalTestApi::class)
class CapsuleTransparentAlbumInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val wallpaper=Color(0xFF284B3C)
    private val media=Color(0xFF6588A8)

    @Test fun twoThroughTenItemsKeepOneAlbumAndApprovedSurfaceCompositesOverWallpaper() {
        val count=mutableIntStateOf(2)
        val outgoing=mutableStateOf(true)
        val caption="One transparent caption"
        val timestamp="2026-10-26T10:00:00Z"
        var reactionTaps=0
        compose.setContent {CocoTheme {
            val messages=(0 until count.intValue).map {i->Message("transparent-$i","server-$i","chat","sender","",i.toLong(),
                timestamp,MessageStatus.READ,albumId="same-album",caption=if(i==0)caption else null,
                reactions=if(i==0)listOf(ReactionSummary("👍",2,false))else emptyList())}
            val attachments=messages.map {message->Attachment(message.clientMessageId,message.clientMessageId,
                AttachmentMediaType.IMAGE,"image/jpeg",null,1,120,80,null,null,null,
                AttachmentDownloadState.NOT_DOWNLOADED,AttachmentUploadState.SENT,100)}.associateBy {it.clientMessageId}
            Box(Modifier.width(320.dp).height(600.dp).background(wallpaper).testTag("transparent-host")) {
                CapsuleChatAlbum(messages,outgoing.value,attachments,onReaction={member,emoji->
                    assertEquals("transparent-0",member.clientMessageId);assertEquals("👍",emoji);reactionTaps++
                }) {member->Box(Modifier.fillMaxSize().background(media).testTag("media-${member.clientMessageId}"))}
            }
        }}
        for(n in 2..10) {
            compose.runOnIdle{count.intValue=n}
            compose.onAllNodesWithTag("chat-album").assertCountEquals(1)
            for(i in 0 until n)compose.onNodeWithTag("media-transparent-$i").assertIsDisplayed()
            compose.onAllNodesWithText(caption).assertCountEquals(1)
            compose.onAllNodesWithText(formatTime(timestamp)).assertCountEquals(1)
            compose.onAllNodesWithText("👍").assertCountEquals(2)
        }
        // The fourth preview example is not a maximum: all ten remain visible.
        compose.onNodeWithTag("media-transparent-9").assertIsDisplayed()
        compose.onAllNodesWithText("👍")[1].performTouchInput{click()}
        compose.runOnIdle{assertEquals(1,reactionTaps)}
        compose.runOnIdle{count.intValue=2}
        for(isOutgoing in listOf(true,false)) {
            compose.runOnIdle{outgoing.value=isOutgoing}
            val first=compose.onNodeWithTag("media-transparent-0").fetchSemanticsNode().boundsInRoot
            val second=compose.onNodeWithTag("media-transparent-1").fetchSemanticsNode().boundsInRoot
            val album=compose.onNodeWithTag("chat-album").fetchSemanticsNode().boundsInRoot
            val host=compose.onNodeWithTag("transparent-host").fetchSemanticsNode().boundsInRoot
            val captionBounds=compose.onNodeWithText(caption).fetchSemanticsNode().boundsInRoot
            val density=compose.activity.resources.displayMetrics.density
            assertEquals(CapsuleChatStyle.mediaWidth*density,album.width,1f)
            assertEquals(CapsuleChatStyle.albumGap*density,second.left-first.right,1f)
            val pixels=compose.onNodeWithTag("transparent-host").captureToImage().toPixelMap()
            val approvedSurface=(if(isOutgoing)CapsuleChatStyle.outgoing else CapsuleChatStyle.incoming).compositeOver(wallpaper)
            fun assertApprovedSurface(x:Float,y:Float,label:String) {
                val pixel=pixels[(x-host.left).roundToInt(),(y-host.top).roundToInt()]
                assertEquals("$label red",approvedSurface.red,pixel.red,.015f)
                assertEquals("$label green",approvedSurface.green,pixel.green,.015f)
                assertEquals("$label blue",approvedSurface.blue,pixel.blue,.015f)
            }
            assertApprovedSurface((first.right+second.left)/2f,first.center.y,"Mosaic gap")
            assertApprovedSurface(album.left+4f*density,captionBounds.center.y,"Caption inset")
        }
        val folder=File(compose.activity.getExternalFilesDir(null),"capsules-uiqa").apply{mkdirs()}
        File(folder,"capsule-transparent-album-theme16.png").outputStream().use {
            compose.onNodeWithTag("transparent-host").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }

    @Test fun documentOnlyAlbumKeepsFileWidthAndApprovedCaptionSurface() {
        val caption="Files remain one batch"
        compose.setContent {CocoTheme {
            val messages=(0..1).map {i->Message("doc-$i","doc-server-$i","chat","me","",i.toLong(),
                "2026-10-26T10:00:00Z",MessageStatus.READ,caption=if(i==0)caption else null,albumId="document-batch")}
            val attachments=messages.map {message->Attachment(message.clientMessageId,message.clientMessageId,
                AttachmentMediaType.FILE,"application/pdf","file.pdf",12,null,null,null,null,null,
                AttachmentDownloadState.NOT_DOWNLOADED,AttachmentUploadState.SENT,100)}.associateBy {it.clientMessageId}
            Box(Modifier.width(320.dp).height(400.dp).background(wallpaper).testTag("documents-host")) {
                CapsuleChatAlbum(messages,true,attachments) {member->Box(Modifier.fillMaxWidth().height(48.dp).testTag(member.clientMessageId))}
            }
        }}
        compose.onAllNodesWithTag("chat-album").assertCountEquals(1)
        compose.onNodeWithTag("doc-0").assertIsDisplayed();compose.onNodeWithTag("doc-1").assertIsDisplayed()
        val album=compose.onNodeWithTag("chat-album").fetchSemanticsNode().boundsInRoot
        val host=compose.onNodeWithTag("documents-host").fetchSemanticsNode().boundsInRoot
        val captionBounds=compose.onNodeWithText(caption).fetchSemanticsNode().boundsInRoot
        assertEquals(CapsuleChatStyle.fileWidth*compose.activity.resources.displayMetrics.density,album.width,1f)
        val pixels=compose.onNodeWithTag("documents-host").captureToImage().toPixelMap()
        val pixel=pixels[(album.left-host.left+4*compose.activity.resources.displayMetrics.density).roundToInt(),(captionBounds.center.y-host.top).roundToInt()]
        val approvedSurface=CapsuleChatStyle.outgoing.compositeOver(wallpaper)
        assertEquals(approvedSurface.red,pixel.red,.015f);assertEquals(approvedSurface.green,pixel.green,.015f);assertEquals(approvedSurface.blue,pixel.blue,.015f)
    }
}
