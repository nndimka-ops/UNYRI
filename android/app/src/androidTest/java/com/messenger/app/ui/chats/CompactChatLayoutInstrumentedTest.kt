package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.domain.model.*
import com.messenger.app.ui.theme.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual bubble geometry and interaction over synthetic data, never the operator's chat. */
@OptIn(ExperimentalTestApi::class)
class CompactChatLayoutInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private var fixtureWindow = Rect.Zero

    @Test fun shortTextUsesApprovedBelowMetadataAndKeepsBodyPaddingBeforeReactionsBothDirections() {
        val m=message("Да",reactions=listOf(ReactionSummary("👍",2,true)))
        var body=Rect.Zero
        var own by mutableStateOf(false)
        show {Bubble(m,null,{body=it},own=own)}
        val density=compose.activity.resources.displayMetrics.density
        for (direction in listOf(false,true)) {
            compose.runOnIdle {own=direction}
            compose.waitUntil(5000){body.width>0f&&body.height>0f}
            val text=compose.onNodeWithText(m.text,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val metadata=metadataBounds(m,direction)
            val time=metadata.first()
            val localBody=bodyInRoot(body)
            assertEquals("A short message keeps the approved default text size",15.sp,textLayout(m.text).layoutInput.style.fontSize)
            assertEquals("The approved text uses Regular emphasis",FontWeight.Normal,textLayout(m.text).layoutInput.style.fontWeight)
            assertFalse("Short text remains unclipped",textLayout(m.text).hasVisualOverflow)
            assertEquals("The approved metadata mode is below", "below", CapsuleChatStyle.metadataMode)
            assertEquals("Short-message metadata follows the complete body",CapsuleChatStyle.timeOffset*density,
                metadata.minOf{it.top}-localBody.bottom,1.5f)
            assertEquals("Body keeps the approved eight-dp top/bottom padding",16*density,
                localBody.height-text.height,2f)
            assertInside("Short-message text",localBody,text)
            assertReactionsBelowMetadata(metadata)
            capture("compact-short-text-${if(direction) "out" else "in"}.png")
        }
    }

    @Test fun multilineTextKeepsOneBelowFooterBeforeReactionsAtLargeFontBothDirections() {
        val m=message("First message line\nSecond message line",reactions=listOf(ReactionSummary("👍",2,true)))
        var body=Rect.Zero
        var own by mutableStateOf(false)
        show(textSize=19,fontScale=1.6f) {Bubble(m,null,{body=it},own=own)}
        val density=compose.activity.resources.displayMetrics.density
        for (direction in listOf(false,true)) {
            compose.runOnIdle {own=direction}
            compose.waitForIdle()
            val layout=textLayout(m.text)
            val text=compose.onNodeWithText(m.text,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val metadata=metadataBounds(m,direction)
            val localBody=bodyInRoot(body)
            assertEquals(19.sp,layout.layoutInput.style.fontSize)
            assertEquals(FontWeight.Normal,layout.layoutInput.style.fontWeight)
            assertTrue("Explicitly multiline text uses the footer fallback",layout.lineCount>=2)
            assertFalse("Multiline message is not clipped at enlarged system font",layout.hasVisualOverflow)
            assertEquals("Footer follows the complete body with the approved gap",CapsuleChatStyle.timeOffset*density,
                metadata.minOf{it.top}-localBody.bottom,2f)
            assertInside("Multiline message text",localBody,text)
            metadata.forEach {assertTrue("Multiline metadata stays within the message width",
                it.left>=localBody.left-1f && it.right<=localBody.right+1f)}
            assertReactionsBelowMetadata(metadata)
            capture("compact-multiline-large-font-${if(direction) "out" else "in"}.png")
        }
    }

    @Test fun longFileKeepsOneLineAndStableBodyHeightThroughUploadThenOpensAndLongPresses() {
        val file=File(compose.activity.cacheDir,"compact-file-layout.pdf").apply {writeText("Synthetic UI fixture")}
        val filename="20261021_163706-very-long-financial-report.pdf"
        var current by mutableStateOf(attachment(filename,file,AttachmentUploadState.PENDING))
        var own by mutableStateOf(false)
        var body=Rect.Zero
        var opened=0;var menus=0
        val m=message("")
        show {Bubble(m,current,{body=it},own=own,onOpen={opened++},onMenu={menus++})}
        for (direction in listOf(false,true)) {
            compose.runOnIdle {own=direction;current=attachment(filename,file,AttachmentUploadState.PENDING)}
            compose.waitForIdle()
            val initial=body
            for(state in listOf(AttachmentUploadState.UPLOADING,AttachmentUploadState.SENT)) {
                compose.runOnIdle {current=attachment(filename,file,state)}
                compose.waitForIdle()
                assertEquals("Transfer progress must not change the file body width",initial.width,body.width,1f)
                assertEquals("Transfer completion must not collapse the file row",initial.height,body.height,1f)
                metadataBounds(m,direction).forEach {assertInside("File metadata",bodyInRoot(body),it)}
            }
            val name=compose.onNodeWithContentDescription(filename,useUnmergedTree=true)
            val layout=textLayout(filename,accessibleName=true)
            assertEquals(1,layout.lineCount)
            assertFalse(layout.hasVisualOverflow)
                assertEquals("Filename keeps the approved default text size",15.sp,layout.layoutInput.style.fontSize)
            assertEquals("Filename keeps its Medium emphasis",FontWeight.Medium,layout.layoutInput.style.fontWeight)
            assertTrue(layout.layoutInput.text.text.endsWith(".pdf"))
            assertTrue(layout.layoutInput.text.text.contains('…'))
            name.performTouchInput{click()}
            compose.runOnIdle{assertEquals(if(direction) 2 else 1,opened);assertEquals(if(direction) 1 else 0,menus)}
            name.performTouchInput{longClick()}
            compose.runOnIdle{assertEquals(if(direction) 2 else 1,opened);assertEquals(if(direction) 2 else 1,menus)}
            capture("compact-file-upload-complete-${if(direction) "out" else "in"}.png")
        }
        file.delete()
    }

    @Test fun narrowFileFooterStaysInsideBodyAtLargeFontAndFullNameRemainsAccessibleBothDirections() {
        val file=File(compose.activity.cacheDir,"compact-large-font-layout.pdf").apply{writeText("Synthetic UI fixture")}
        val filename="Quarterly-accessible-financial-report.pdf"
        val m=message("")
        var body=Rect.Zero
        var own by mutableStateOf(false)
        val a=attachment(filename,file,AttachmentUploadState.SENT)
        show(textSize=19,fontScale=2f) {Bubble(m,a,{body=it},own=own)}
        for (direction in listOf(false,true)) {
            compose.runOnIdle {own=direction}
            compose.waitForIdle()
            compose.onNodeWithContentDescription(filename,useUnmergedTree=true).assertIsDisplayed()
            val layout=textLayout(filename,accessibleName=true)
            assertEquals(19.sp,layout.layoutInput.style.fontSize)
            assertEquals(FontWeight.Medium,layout.layoutInput.style.fontWeight)
            assertEquals(1,layout.lineCount)
            assertFalse("Accessible original filename must not require a clipped visible line",layout.hasVisualOverflow)
            assertTrue("Visible filename retains a measurable layout",layout.size.width>0&&layout.size.height>0)
            metadataBounds(m,direction).forEach {assertInside("Large-font file footer",bodyInRoot(body),it)}
            capture("compact-file-large-font-${if(direction) "out" else "in"}.png")
        }
        file.delete()
    }

    @Test fun leadingFileControlCancelsUploadAndDownloadOnceWithoutOpeningTheFile() {
        val file=File(compose.activity.cacheDir,"compact-cancel-slot.pdf").apply{writeText("Synthetic UI fixture")}
        val filename="File-transfer-cancellation-fixture.pdf"
        var current by mutableStateOf(attachment(filename,file,AttachmentUploadState.UPLOADING))
        var own by mutableStateOf(true)
        var body=Rect.Zero
        var uploadCancels=0;var downloadCancels=0;var opened=0;var menus=0
        var cancelledDownloadId:String?=null
        show {
            Bubble(message(""),current,{body=it},own=own,onOpen={opened++},onMenu={menus++},
                onCancel={uploadCancels++},onDownload={downloadCancels++;cancelledDownloadId=it.attachmentId})
        }
        try {
            compose.waitForIdle()
            val initialBody=body
            val initialName=compose.onNodeWithContentDescription(filename,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val uploadCancel=compose.onNodeWithContentDescription(compose.activity.getString(R.string.cancel_sending))
                .assertIsDisplayed().assertHasClickAction()
            val control=uploadCancel.fetchSemanticsNode().boundsInRoot
            val density=compose.activity.resources.displayMetrics.density
            assertTrue("The moved cancel control retains its 48dp touch width",control.width>=48*density-1f)
            assertTrue("The cancel control stays in the leading slot, clear of the filename",control.right<=initialName.left+1f)
            assertInside("Upload cancel target",bodyInRoot(body),control)
            uploadCancel.performTouchInput{click()}
            compose.runOnIdle {
                assertEquals("One physical tap cancels upload once",1,uploadCancels)
                assertEquals(0,downloadCancels);assertEquals(0,opened);assertEquals(0,menus)
                current=attachment(filename,file,AttachmentUploadState.SENT)
            }
            compose.waitForIdle()
            val completedName=compose.onNodeWithContentDescription(filename,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertEquals("Completion retains filename width",initialName.width,completedName.width,1f)
            assertEquals("Completion retains the file body height",initialBody.height,body.height,1f)
            assertEquals("Completion retains the file body width",initialBody.width,body.width,1f)
            compose.runOnIdle {
                own=false
                current=attachment(filename,file,AttachmentUploadState.SENT)
                    .copy(downloadState=AttachmentDownloadState.DOWNLOADING,localPath=null)
            }
            compose.waitForIdle()
            val downloadCancel=compose.onNodeWithContentDescription(compose.activity.getString(R.string.cancel))
                .assertIsDisplayed().assertHasClickAction()
            assertInside("Download cancel target",bodyInRoot(body),downloadCancel.fetchSemanticsNode().boundsInRoot)
            downloadCancel.performTouchInput{click()}
            compose.runOnIdle {
                assertEquals(1,uploadCancels)
                assertEquals("One physical tap routes download cancellation once",1,downloadCancels)
                assertEquals(current.attachmentId,cancelledDownloadId)
                assertEquals("Cancel must not open the parent file row",0,opened)
                assertEquals("Cancel must not open the message menu",0,menus)
            }
            capture("compact-file-cancel-leading-slot.png")
        } finally {file.delete()}
    }

    private fun show(textSize:Int=CapsuleChatStyle.fontSize.toInt(),fontScale:Float=1f,content:@Composable ()->Unit) {
        compose.setContent {CocoTheme {
            val realDensity=LocalDensity.current
            CompositionLocalProvider(LocalCocoColors provides CapsuleChatStyle.colors,LocalChatTextSize provides textSize,
                LocalDensity provides Density(realDensity.density,fontScale)) {
                Box(Modifier.width(320.dp).height(500.dp).background(CapsuleChatStyle.background)
                    .onGloballyPositioned {fixtureWindow=it.boundsInWindow()}.testTag("compact-chat-fixture")){content()}
            }
        }}
    }
    @Composable private fun Bubble(m:Message,a:Attachment?,bounds:(Rect)->Unit,own:Boolean=true,onOpen:()->Unit={},onMenu:()->Unit={},
        onCancel:()->Unit={},onDownload:(Attachment)->Unit={}) {
        MessageBubble(message=m,attachment=a,isOwnMessage=own,playingAttachmentId=null,onBoundsChanged=bounds,
            onRetry={},onCancelPending=onCancel,onDownload=onDownload,onTogglePlay={_,_->},onOpenFile={_,_->onOpen()},onOpenImage={},
            onLongPress=onMenu,onSelectionTap={},onToggleReaction={},onQuickReaction=null,onScrollToReply={})
    }
    private fun metadataBounds(m:Message,own:Boolean):List<Rect> {
        val time=formatTime(m.createdAt)
        compose.onAllNodesWithText(time,useUnmergedTree=true).assertCountEquals(1)
        val result=mutableListOf(compose.onNodeWithText(time,useUnmergedTree=true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        if(own) result+=compose.onNodeWithContentDescription(compose.activity.getString(R.string.msg_status_read),
            useUnmergedTree=true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        return result
    }
    private fun textLayout(text:String,accessibleName:Boolean=false):TextLayoutResult {
        val results=mutableListOf<TextLayoutResult>()
        val node=if(accessibleName) compose.onNodeWithContentDescription(text,useUnmergedTree=true)
            else compose.onNodeWithText(text,useUnmergedTree=true)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(results)}
        return results.single()
    }
    private fun bodyInRoot(body:Rect):Rect {
        val fixture=compose.onNodeWithTag("compact-chat-fixture").fetchSemanticsNode().boundsInRoot
        val dx=fixture.left-fixtureWindow.left
        val dy=fixture.top-fixtureWindow.top
        return Rect(body.left+dx,body.top+dy,body.right+dx,body.bottom+dy)
    }
    private fun assertInside(label:String,body:Rect,child:Rect) {
        assertTrue("$label stays inside body's horizontal padding: body=$body child=$child",child.left>=body.left-1f&&child.right<=body.right+1f)
        assertTrue("$label stays inside body's vertical padding: body=$body child=$child",child.top>=body.top-1f&&child.bottom<=body.bottom+1f)
    }
    private fun assertReactionsBelowMetadata(metadata:List<Rect>) {
        compose.onAllNodesWithText("👍",useUnmergedTree=true).assertCountEquals(2)
        val metadataBottom=metadata.maxOf{it.bottom}
        compose.onAllNodesWithText("👍",useUnmergedTree=true).fetchSemanticsNodes().forEach {
            assertTrue("Reactions follow the complete below footer",it.boundsInRoot.top>=metadataBottom-1f)
        }
    }
    private fun message(text:String,reactions:List<ReactionSummary> = emptyList()) = Message("compact-fixture","compact-server","compact-chat","me",text,1,
        "2026-10-26T16:00:00Z",MessageStatus.READ,reactions=reactions)
    private fun attachment(name:String,file:File,state:AttachmentUploadState)=Attachment("compact-attachment","compact-fixture",AttachmentMediaType.FILE,
        "application/pdf",name,1234,null,null,null,null,file.path,AttachmentDownloadState.DOWNLOADED,state,50)
    private fun capture(name:String) {
        val folder=File(compose.activity.getExternalFilesDir(null),"capsules-uiqa").apply{mkdirs()}
        val bitmap=compose.onNodeWithTag("compact-chat-fixture").captureToImage().asAndroidBitmap()
        try {File(folder,name).outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
        finally {bitmap.recycle()}
    }
}
