package com.messenger.app.ui.chats

import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.domain.model.*
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.theme.LocalChatTextSize
import com.messenger.app.ui.theme.LocalCocoColors
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual production bubbles; all files/data and callbacks are synthetic and account independent. */
@OptIn(ExperimentalTestApi::class)
class CompactChatSelectionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class Kind { TEXT, LONG_TEXT, FILE, VOICE, PHOTO, VIDEO, RETRY, TRANSFER }
    private data class Fixture(val kind: Kind, val outgoing: Boolean, val reactions: Boolean = false, val fontScale: Float = 1f) {
        val message = Message("selection-${kind.name}", "synthetic-selection-server", "synthetic-selection-chat", "synthetic-selection-user",
            when (kind) {
                Kind.TEXT, Kind.RETRY -> "Selection text"
                Kind.LONG_TEXT -> "A longer message that wraps across several lines and must keep exactly the same place when selection is opened and closed."
                else -> ""
            }, 1, "2026-10-26T16:00:00Z", if (kind == Kind.RETRY) MessageStatus.FAILED else MessageStatus.READ,
            reactions = if (reactions) listOf(ReactionSummary("❤️", 1, true)) else emptyList())
    }

    private class Counters {
        var selected = 0; var normalTaps = 0; var files = 0; var images = 0; var plays = 0
        var downloads = 0; var cancels = 0; var reactions = 0; var quick = 0; var retries = 0; var menus = 0
        fun reset() { selected=0;normalTaps=0;files=0;images=0;plays=0;downloads=0;cancels=0;reactions=0;quick=0;retries=0;menus=0 }
        fun assertNoContentActions() {
            assertEquals("No file is opened while selecting",0,files)
            assertEquals("No image is opened while selecting",0,images)
            assertEquals("No voice player is invoked while selecting",0,plays)
            assertEquals("No download is invoked while selecting",0,downloads)
            assertEquals("No transfer is cancelled while selecting",0,cancels)
            assertEquals("No reaction is changed while selecting",0,reactions)
            assertEquals("No quick reaction is sent while selecting",0,quick)
            assertEquals("No retry is sent while selecting",0,retries)
            assertEquals("No message menu is opened while selecting",0,menus)
        }
    }

    private var shown by mutableStateOf(Fixture(Kind.TEXT, false))
    private var selectionMode by mutableStateOf(false)
    private var selected by mutableStateOf(false)
    private var bodyBounds = Rect.Zero
    private var rowBounds = Rect.Zero
    private val counters = Counters()
    private lateinit var photo: File
    private lateinit var document: File
    private lateinit var voice: File
    private lateinit var thumbnail: String

    @Test fun selectionDoesNotMoveOrResizeAnyBodyAndCheckboxRemains48dpBothDirections() {
        prepareFiles()
        show()
        try {
            for (outgoing in listOf(false, true)) {
                for (kind in listOf(Kind.TEXT, Kind.LONG_TEXT, Kind.FILE, Kind.VOICE, Kind.PHOTO, Kind.VIDEO)) {
                    compose.runOnIdle {
                        counters.reset(); selectionMode=false; selected=false
                        shown=Fixture(kind,outgoing,fontScale=if(kind==Kind.LONG_TEXT||kind==Kind.FILE)1.6f else 1f)
                    }
                    compose.waitForIdle()
                    val originalBody=bodyBounds
                    val originalRow=rowBounds
                    assertTrue("Fixture body has been measured",originalBody.width>0f&&originalBody.height>0f)
                    compose.runOnIdle {selectionMode=true}
                    compose.waitForIdle()
                    assertSameRect("Unselected selection mode body ($kind/$outgoing)",originalBody,bodyBounds)
                    assertSameRect("Unselected selection mode row ($kind/$outgoing)",originalRow,rowBounds)
                    val mark=compose.onNode(isToggleable()).assertIsDisplayed().assertIsOff()
                    val markBounds=mark.fetchSemanticsNode().boundsInRoot
                    val density=compose.activity.resources.displayMetrics.density
                    assertTrue("Checkbox keeps a48dp touch width",markBounds.width>=48*density-1f)
                    assertTrue("Checkbox keeps a48dp touch height",markBounds.height>=48*density-1f)
                    val hostBounds=compose.onNodeWithTag("selection-host").fetchSemanticsNode().boundsInRoot
                    assertTrue("Checkbox starts inside the narrow real viewport ($kind/$outgoing)",markBounds.left>=hostBounds.left-1f)
                    assertTrue("Checkbox ends inside the narrow real viewport ($kind/$outgoing)",markBounds.right<=hostBounds.right+1f)
                    mark.performTouchInput {click()}
                    compose.onNode(isToggleable()).assertIsOn()
                    compose.runOnIdle {assertEquals("One checkbox touch selects exactly once",1,counters.selected);counters.assertNoContentActions()}
                    assertSameRect("Selected body ($kind/$outgoing)",originalBody,bodyBounds)
                    assertSameRect("Selected row ($kind/$outgoing)",originalRow,rowBounds)
                    capture("selection-${kind.name.lowercase()}-${if(outgoing) "out" else "in"}.png")
                    compose.runOnIdle {selectionMode=false;selected=false}
                    compose.waitForIdle()
                    assertSameRect("Body after leaving selection ($kind/$outgoing)",originalBody,bodyBounds)
                    assertSameRect("Row after leaving selection ($kind/$outgoing)",originalRow,rowBounds)
                }
            }
        } finally {deleteFiles()}
    }

    @Test fun physicalContentReactionRetryAndTransferTapsOnlySelectUntilSelectionEnds() {
        prepareFiles()
        show()
        try {
            for (outgoing in listOf(false,true)) {
                for (kind in listOf(Kind.TEXT,Kind.FILE,Kind.VOICE,Kind.PHOTO,Kind.VIDEO,Kind.RETRY,Kind.TRANSFER)) {
                    // Only an outgoing outbox entry can have a send-retry action.
                    if (kind == Kind.RETRY && !outgoing) continue
                    compose.runOnIdle {counters.reset();selectionMode=false;selected=false;shown=Fixture(kind,outgoing,reactions=true)}
                    compose.waitForIdle()
                    val target=contentAction(kind,outgoing).fetchSemanticsNode().boundsInRoot.center
                    val reaction=compose.onNodeWithText("❤️",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.center
                    compose.runOnIdle {selectionMode=true}
                    compose.waitForIdle()
                    touch(target)
                    compose.runOnIdle {assertEquals("Content touch selects once ($kind/$outgoing)",1,counters.selected);counters.assertNoContentActions()}
                    touch(reaction)
                    compose.runOnIdle {assertEquals("Reaction-area touch selects once more ($kind/$outgoing)",2,counters.selected);counters.assertNoContentActions()}
                    compose.runOnIdle {selectionMode=false;selected=false}
                    compose.waitForIdle()
                    // The same real control remains usable after selection is dismissed.
                    contentAction(kind,outgoing).performTouchInput {click()}
                    // combinedClickable waits for the double-tap window before dispatching
                    // an ordinary click when a quick reaction is available.
                    compose.waitUntil(3000) {
                        when(kind) {
                            Kind.FILE,Kind.VIDEO -> counters.files == 1
                            Kind.VOICE -> counters.plays == 1
                            Kind.PHOTO -> counters.images == 1
                            Kind.RETRY -> counters.retries == 1
                            Kind.TRANSFER -> if(outgoing)counters.cancels == 1 else counters.downloads == 1
                            else -> counters.normalTaps == 1
                        }
                    }
                    compose.runOnIdle {
                        assertEquals("Normal control touch does not select",2,counters.selected)
                        when(kind) {
                            Kind.FILE,Kind.VIDEO -> assertEquals(1,counters.files)
                            Kind.VOICE -> assertEquals(1,counters.plays)
                            Kind.PHOTO -> assertEquals(1,counters.images)
                            Kind.RETRY -> assertEquals(1,counters.retries)
                            Kind.TRANSFER -> if(outgoing)assertEquals(1,counters.cancels)else assertEquals(1,counters.downloads)
                            else -> assertEquals(1,counters.normalTaps)
                        }
                        assertEquals(0,counters.reactions);assertEquals(0,counters.quick);assertEquals(0,counters.menus)
                    }
                }
            }
        } finally {deleteFiles()}
    }

    private fun show() {
        compose.setContent {CocoTheme {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalCocoColors provides CapsuleChatStyle.colors,LocalChatTextSize provides 17,
                LocalDensity provides Density(density.density,shown.fontScale)) {
                Box(Modifier.width(320.dp).height(520.dp).background(CapsuleChatStyle.background)
                    .testTag("selection-host").padding(horizontal=CapsuleChatStyle.side.dp,vertical=24.dp)) {
                    MessageBubble(modifier=Modifier.testTag("selection-row").onGloballyPositioned {rowBounds=it.boundsInWindow()},
                        message=shown.message,attachment=attachment(shown),isOwnMessage=shown.outgoing,
                        selectionMode=selectionMode,selected=selected,onBoundsChanged={bodyBounds=it},playingAttachmentId=null,
                        onRetry={counters.retries++},onCancelPending={counters.cancels++},onDownload={counters.downloads++},
                        onTogglePlay={_,_->counters.plays++},onOpenFile={_,_->counters.files++},onOpenImage={counters.images++},
                        onLongPress={counters.menus++},onSelectionTap={if(selectionMode){counters.selected++;selected=!selected}else counters.normalTaps++},
                        onToggleReaction={counters.reactions++},onQuickReaction={counters.quick++},onScrollToReply={})
                }
            }
        }}
    }

    private fun attachment(fixture:Fixture):Attachment? {
        val kind=fixture.kind
        if(kind in listOf(Kind.TEXT,Kind.LONG_TEXT,Kind.RETRY))return null
        val file=when(kind){Kind.PHOTO->photo;Kind.VOICE->voice;else->document}
        return Attachment("selection-attachment",fixture.message.clientMessageId,
            when(kind){Kind.PHOTO->AttachmentMediaType.IMAGE;Kind.VOICE->AttachmentMediaType.VOICE;else->AttachmentMediaType.FILE},
            when(kind){Kind.PHOTO->"image/png";Kind.VIDEO->"video/mp4";Kind.VOICE->"audio/wav";else->"application/pdf"},
            "Selection-fixture.pdf",file.length(),if(kind==Kind.PHOTO||kind==Kind.VIDEO)4 else null,
            if(kind==Kind.PHOTO||kind==Kind.VIDEO)3 else null,if(kind==Kind.VOICE||kind==Kind.VIDEO)12000 else null,
            if(kind==Kind.VIDEO)thumbnail else null,file.path,
            if(kind==Kind.TRANSFER&&!fixture.outgoing)AttachmentDownloadState.DOWNLOADING else AttachmentDownloadState.DOWNLOADED,
            if(kind==Kind.TRANSFER&&fixture.outgoing)AttachmentUploadState.UPLOADING else AttachmentUploadState.SENT,50)
    }

    private fun contentAction(kind:Kind,outgoing:Boolean):SemanticsNodeInteraction = when(kind) {
        Kind.FILE -> compose.onNodeWithContentDescription("Selection-fixture.pdf",useUnmergedTree=true)
        Kind.VOICE -> compose.onNodeWithContentDescription(compose.activity.getString(R.string.play),useUnmergedTree=true)
        Kind.PHOTO -> {
            val description=compose.activity.getString(R.string.photo_preview)
            compose.waitUntil(5000){compose.onAllNodesWithContentDescription(description,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription(description,useUnmergedTree=true)
        }
        Kind.VIDEO -> compose.onNodeWithContentDescription(compose.activity.getString(R.string.open),useUnmergedTree=true)
        Kind.RETRY -> compose.onNodeWithText(compose.activity.getString(R.string.retry),useUnmergedTree=true)
        Kind.TRANSFER -> compose.onNodeWithContentDescription(compose.activity.getString(if(outgoing)R.string.cancel_sending else R.string.cancel))
        else -> compose.onNodeWithText(shown.message.text,useUnmergedTree=true)
    }

    private fun touch(positionInRoot:Offset) {
        val host=compose.onNodeWithTag("selection-host")
        val origin=host.fetchSemanticsNode().boundsInRoot.topLeft
        host.performTouchInput {click(positionInRoot-origin)}
    }
    private fun assertSameRect(label:String,expected:Rect,actual:Rect) {
        assertEquals("$label left",expected.left,actual.left,1f);assertEquals("$label top",expected.top,actual.top,1f)
        assertEquals("$label right",expected.right,actual.right,1f);assertEquals("$label bottom",expected.bottom,actual.bottom,1f)
    }
    private fun prepareFiles() {
        photo=File(compose.activity.cacheDir,"selection-uiqa-photo.png")
        document=File(compose.activity.cacheDir,"selection-uiqa-document.pdf").apply{writeText("Synthetic callback fixture; never opened by a real viewer")}
        voice=File(compose.activity.cacheDir,"selection-uiqa-voice.wav")
        val bitmap=Bitmap.createBitmap(4,3,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.DKGRAY)}
        try {
            val bytes=ByteArrayOutputStream().use {output->bitmap.compress(Bitmap.CompressFormat.PNG,100,output);output.toByteArray()}
            photo.writeBytes(bytes);thumbnail=Base64.encodeToString(bytes,Base64.NO_WRAP)
        } finally {bitmap.recycle()}
        val pcmBytes=800
        val wav=ByteBuffer.allocate(44+pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36+pcmBytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcmBytes)
        voice.writeBytes(wav.array())
    }
    private fun deleteFiles(){photo.delete();document.delete();voice.delete()}
    private fun capture(name:String) {
        val folder=File(compose.activity.getExternalFilesDir(null),"capsules-uiqa").apply{mkdirs()}
        val bitmap=compose.onNodeWithTag("selection-host").captureToImage().asAndroidBitmap()
        try {File(folder,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
}
