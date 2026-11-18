package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.messenger.app.domain.model.*
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.theme.LocalChatTextSize
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual document MessageBubble rows; no file IO, network, media or account operations. */
@OptIn(ExperimentalTestApi::class)
class CapsuleDocumentAlbumInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun documentCaptionMetadataAndEmojiStayUsableAtFontScale150()=check(1.5f)
    @Test fun documentCaptionMetadataAndEmojiStayUsableAtFontScale200()=check(2f)

    private fun check(fontScale:Float) {
        val caption="Shared document caption"
        val timestamp="2026-10-26T10:00:00Z"
        val outgoing=mutableStateOf(true)
        val reactions=mutableListOf<Pair<String,String>>()
        var captionPresses=0
        val messages=listOf(
            Message("document-a","server-a","chat","sender","",1,timestamp,MessageStatus.READ,
                caption=caption,albumId="document-group",reactions=listOf(ReactionSummary("❤️",1,false))),
            Message("document-b","server-b","chat","sender","",2,timestamp,MessageStatus.READ,
                albumId="document-group",reactions=listOf(ReactionSummary("👍",1,false))))
        val attachments=messages.associate {message->message.clientMessageId to Attachment(message.clientMessageId,message.clientMessageId,
            AttachmentMediaType.FILE,"application/pdf",if(message.clientMessageId=="document-a")"first-report.pdf"else"second.pdf",
            512,null,null,null,null,null,AttachmentDownloadState.NOT_DOWNLOADED,AttachmentUploadState.SENT,100)}
        compose.setContent {CocoTheme {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale),LocalChatTextSize provides 15) {
                Box(Modifier.width(320.dp).height(640.dp).background(Color(0xFF18362B)).testTag("document-large-host")) {
                    CapsuleChatAlbum(messages,outgoing.value,attachments,
                        onCaptionLongPress={message,_->assertEquals("document-a",message.clientMessageId);captionPresses++},
                        onReaction={message,emoji->reactions+=message.clientMessageId to emoji}) {message->
                        Box(Modifier.fillMaxWidth().testTag(message.clientMessageId)) {
                            MessageBubble(message=message,attachment=attachments.getValue(message.clientMessageId),
                                isOwnMessage=outgoing.value,playingAttachmentId=null,onRetry={},onDownload={},
                                onTogglePlay={_,_->},onOpenFile={_,_->},onOpenImage={},onLongPress={},onSelectionTap={},
                                onToggleReaction={},onQuickReaction=null,onScrollToReply={})
                        }
                    }
                }
            }
        }}
        for(isOutgoing in listOf(true,false)) {
            compose.runOnIdle{outgoing.value=isOutgoing}
            compose.onAllNodesWithTag("chat-album").assertCountEquals(1)
            compose.onNodeWithTag("document-a").assertIsDisplayed()
            val lastDocument=compose.onNodeWithTag("document-b").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val captionBounds=compose.onNodeWithText(caption).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val metadata=compose.onNodeWithTag("document-album-metadata").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val reactionRow=compose.onNodeWithTag("document-album-reactions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Shared caption follows both documents",captionBounds.top>=lastDocument.bottom-.5f)
            assertTrue("Time follows the document caption",metadata.top>=captionBounds.bottom-.5f)
            assertTrue("Time is above reactions",metadata.bottom<=reactionRow.top+.5f)
            compose.onAllNodesWithText(formatTime(timestamp)).assertCountEquals(1)
            for(emoji in listOf("❤️","👍")) {
                val target=compose.onNode(hasText(emoji) and hasClickAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val results=mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(emoji,useUnmergedTree=true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(results)}
                val layout=results.single()
                assertEquals("Emoji size remains16sp",16f,layout.layoutInput.style.fontSize.value,.001f)
                assertFalse("Emoji width is clipped at fontScale$fontScale",layout.didOverflowWidth)
                assertFalse("Emoji height is clipped at fontScale$fontScale",layout.didOverflowHeight)
                assertTrue("Emoji paragraph fits its reaction target",target.height>=layout.size.height-1f)
                compose.onNodeWithText(emoji,useUnmergedTree=true).performTouchInput{click()}
            }
            compose.onNodeWithText(caption).performTouchInput{longClick()}
            val folder=File(compose.activity.getExternalFilesDir(null),"capsules-uiqa").apply{mkdirs()}
            File(folder,"document-album-${fontScale.toString().replace('.','_')}-${if(isOutgoing)"outgoing"else"incoming"}.png").outputStream().use {
                compose.onNodeWithTag("document-large-host").captureToImage().asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        }
        compose.runOnIdle {
            assertEquals(listOf("document-a" to "❤️","document-b" to "👍","document-a" to "❤️","document-b" to "👍"),reactions)
            assertEquals(2,captionPresses)
        }
    }
}
