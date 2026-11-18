package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.R
import com.messenger.app.domain.model.*
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.theme.LocalCocoColors
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CapsuleChatInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun progressPreparationAndAckKeepEveryAlbumTileAtExactlyTheSameBounds() {
        val phase=androidx.compose.runtime.mutableIntStateOf(0)
        val messages=(0..9).map {i->Message("stable-$i",null,"chat","me","",null,"2026-10-26T10:00:00Z",MessageStatus.PENDING,
            albumId="stable",caption=if(i==0)"Stable batch" else null,
            localMediaPresentation=LocalMediaPresentation("2026-10-26T10:00:00Z",i,if(i%3==0).7f else 1.5f,true))}
        compose.setContent {CocoTheme {CompositionLocalProvider(LocalCocoColors provides CapsuleChatStyle.colors) {
            val attachments=messages.mapIndexed {i,m->Attachment("a-$i",m.clientMessageId,AttachmentMediaType.IMAGE,"image/jpeg",null,1,
                if(phase.intValue==0)700 else 640,if(phase.intValue==0)1000 else 480,null,null,null,
                AttachmentDownloadState.NOT_DOWNLOADED,if(phase.intValue==3)AttachmentUploadState.SENT else AttachmentUploadState.UPLOADING,phase.intValue*30)}
            Column(Modifier.width(320.dp)) {CapsuleChatAlbum(messages.map {if(phase.intValue==3)it.copy(status=MessageStatus.SENT)else it},true,attachments.associateBy{it.clientMessageId}) {m->
                Box(Modifier.fillMaxSize().testTag("tile-${m.clientMessageId}"))
            }}
        }}}
        fun bounds()=(0..9).map {compose.onNodeWithTag("tile-stable-$it").fetchSemanticsNode().boundsInRoot}
        val before=bounds();val group=compose.onNodeWithTag("chat-album").fetchSemanticsNode().boundsInRoot
        for(value in 1..3){compose.runOnIdle{phase.intValue=value};assertEquals(before,bounds())
            assertEquals(group,compose.onNodeWithTag("chat-album").fetchSemanticsNode().boundsInRoot)}
    }

    @Test fun actualContentAndReactionTouchesAtNormalSize() = checkContent(1f)
    @Test fun actualContentRemainsUsableWithLargeSystemText() = checkContent(1.5f)

    @Test fun twoThroughTenMediaAreOneMosaicWithOneCaptionAndReceipt() {
        val file=File(compose.activity.cacheDir,"unified-album.png")
        val bitmap=android.graphics.Bitmap.createBitmap(120,80,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(80,110,130))
        val bytes=java.io.ByteArrayOutputStream().also {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        file.writeBytes(bytes);bitmap.recycle()
        val count=androidx.compose.runtime.mutableIntStateOf(2)
        val failed=androidx.compose.runtime.mutableStateOf(false)
        var photoTaps=0;var videoTaps=0;var captionLongPresses=0;var retries=0
        compose.setContent { CocoTheme {CompositionLocalProvider(LocalCocoColors provides CapsuleChatStyle.colors) {
            val messages=(0 until count.intValue).map {i->Message("m-$i","s-$i","chat","me","",i.toLong(),"2026-10-26T14:28:00Z",if(i==0&&failed.value)MessageStatus.FAILED else MessageStatus.READ,caption=if(i==0)"One caption for the entire album" else null,albumId="whole-album")}
            val media=messages.mapIndexed {i,m->Attachment("a-$i",m.clientMessageId,if(i==1)AttachmentMediaType.FILE else AttachmentMediaType.IMAGE,
                if(i==1)"video/mp4" else "image/png",file.name,bytes.size.toLong(),120,80,if(i==1)3000 else null,
                android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP),file.path,AttachmentDownloadState.DOWNLOADED,AttachmentUploadState.SENT,100)}
            Column(Modifier.width(320.dp).fillMaxHeight().background(CapsuleChatStyle.background).padding(14.dp)) {
                CapsuleChatAlbum(messages,true,media.associateBy {it.clientMessageId},onCaptionLongPress={_,_->captionLongPresses++}) {m->
                    MessageBubble(message=m,attachment=media.first{it.clientMessageId==m.clientMessageId},isOwnMessage=true,
                        playingAttachmentId=null,onRetry={retries++},onDownload={},onTogglePlay={_,_->},onOpenFile={_,_->videoTaps++},onOpenImage={photoTaps++},
                        onLongPress={},onSelectionTap={},onToggleReaction={},onQuickReaction=null,onScrollToReply={})
                }
            }
        } } }
        for(n in 2..10) {
            compose.runOnIdle {count.intValue=n}
            compose.onAllNodesWithTag("chat-album").assertCountEquals(1)
            compose.onAllNodesWithText("One caption for the entire album").assertCountEquals(1)
            compose.onAllNodesWithText("16:28").assertCountEquals(1)
            compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.photo_preview)).assertCountEquals(n-1)
        }
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.photo_preview)).onFirst().performTouchInput {click()}
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.open)).performTouchInput {click()}
        compose.onNodeWithText("One caption for the entire album",useUnmergedTree=true).performTouchInput {longClick()}
        compose.runOnIdle {assertEquals(1,photoTaps);assertEquals(1,videoTaps);assertEquals(1,captionLongPresses)}
        compose.runOnIdle {failed.value=true}
        // A failed media tile now keeps a compact error control; its popup
        // offers both retry and cancel without resizing the mosaic.
        compose.onNode(hasContentDescription(compose.activity.getString(R.string.msg_status_failed)) and hasClickAction())
            .performTouchInput {click()}
        compose.onNodeWithText(compose.activity.getString(R.string.retry)).performTouchInput {click()}
        compose.runOnIdle {assertEquals(1,retries);failed.value=false}
        val output=File(compose.activity.getExternalFilesDir(null),"capsules-uiqa").apply {mkdirs()}
        File(output,"unified-album-ten.png").outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun documentsAndMediaStayOneGroupWithoutLosingFileActions() {
        val photo=File(compose.activity.cacheDir,"mixed-group.png")
        val bitmap=android.graphics.Bitmap.createBitmap(120,80,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.DKGRAY)
        val bytes=java.io.ByteArrayOutputStream().also {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        photo.writeBytes(bytes);bitmap.recycle()
        val pdf=File(compose.activity.cacheDir,"mixed-group.pdf").apply {writeText("Synthetic file")}
        val messages=(0..4).map {i->Message("mix-$i","server-$i","chat","me","",i.toLong(),"2026-10-26T14:28:00Z",MessageStatus.SENT,albumId="mixed",caption=if(i==0)"One mixed group" else null)}
        val attachments=messages.mapIndexed {i,m->Attachment("a-$i",m.clientMessageId,if(i==0)AttachmentMediaType.IMAGE else AttachmentMediaType.FILE,
            when(i){0->"image/png";1->"video/mp4";else->"application/pdf"},if(i<2)photo.name else "Route-$i.pdf",240000,120,80,if(i==1)3000 else null,
            if(i<2)android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)else null,if(i<2)photo.path else pdf.path,
            AttachmentDownloadState.DOWNLOADED,AttachmentUploadState.SENT,100)}
        var filesOpened=0
        compose.setContent {CocoTheme {CompositionLocalProvider(LocalCocoColors provides CapsuleChatStyle.colors) {
            Column(Modifier.width(320.dp).fillMaxHeight().background(CapsuleChatStyle.background).padding(14.dp)) {
                CapsuleChatAlbum(messages,true,attachments.associateBy {it.clientMessageId}) {m->
                    MessageBubble(message=m,attachment=attachments.first{it.clientMessageId==m.clientMessageId},isOwnMessage=true,
                        playingAttachmentId=null,onRetry={},onDownload={},onTogglePlay={_,_->},onOpenFile={_,mime->if(mime=="application/pdf")filesOpened++},
                        onOpenImage={},onLongPress={},onSelectionTap={},onToggleReaction={},onQuickReaction=null,onScrollToReply={})
                }
            }
        }}}
        compose.onAllNodesWithTag("chat-album").assertCountEquals(1)
        compose.onAllNodesWithText("16:28").assertCountEquals(1)
        compose.onAllNodesWithText("One mixed group").assertCountEquals(1)
        for(i in 2..4)compose.onNodeWithText("Route-$i.pdf",useUnmergedTree=true).performTouchInput{click()}
        compose.runOnIdle{assertEquals(3,filesOpened)}
        val output=File(compose.activity.getExternalFilesDir(null),"capsules-uiqa").apply{mkdirs()}
        File(output,"unified-mixed-group.png").outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun photoAndVideoShareOneCompactAlbumAndKeepTapTargets() {
        val file = File(compose.activity.cacheDir, "capsule-album.png")
        val bitmap = android.graphics.Bitmap.createBitmap(96, 72, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(84, 113, 132))
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        file.writeBytes(bytes); bitmap.recycle()
        val messages = (0..1).map { index -> Message("album-$index", "server-$index", "chat", "peer", "", index.toLong(), "2026-10-26T14:28:00Z", MessageStatus.SENT, albumId = "album") }
        val media = messages.mapIndexed { index, message -> Attachment("a-$index", message.clientMessageId,
            if (index == 0) AttachmentMediaType.IMAGE else AttachmentMediaType.FILE,
            if (index == 0) "image/png" else "video/mp4", file.name, bytes.size.toLong(), 96, 72,
            if (index == 1) 18000 else null, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), file.path,
            AttachmentDownloadState.DOWNLOADED, AttachmentUploadState.SENT, 100) }
        val bounds = mutableMapOf<String, Rect>(); var photoTaps = 0; var videoTaps = 0
        compose.setContent { CocoTheme { CompositionLocalProvider(LocalCocoColors provides CapsuleChatStyle.colors) {
            Column(Modifier.width(320.dp).fillMaxHeight().background(CapsuleChatStyle.background).padding(14.dp)) {
                CapsuleMediaAlbumRow(messages, false) { message ->
                    MessageBubble(message = message, attachment = media[messages.indexOf(message)], isOwnMessage = false,
                        onBoundsChanged = { bounds[message.clientMessageId] = it }, playingAttachmentId = null,
                        onRetry = {}, onDownload = {}, onTogglePlay = { _, _ -> }, onOpenFile = { _, _ -> videoTaps++ },
                        onOpenImage = { photoTaps++ }, onLongPress = {}, onSelectionTap = {}, onToggleReaction = {},
                        onQuickReaction = null, onScrollToReply = {})
                }
            }
        } } }
        compose.waitUntilAtLeastOneExists(hasContentDescription(compose.activity.getString(R.string.photo_preview)), timeoutMillis = 5000)
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnIdle {
            for (box in bounds.values) {
                assertEquals((CapsuleChatStyle.mediaWidth - CapsuleChatStyle.albumGap) / 2 * density, box.width, 1f)
                assertEquals(CapsuleChatStyle.mediaWidth * 153f / 274f * density, box.height, 1f)
            }
            assertEquals(2, bounds.size)
        }
        compose.onAllNodesWithText("16:28").assertCountEquals(1)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.photo_preview)).performTouchInput { click() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.open)).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, photoTaps); assertEquals(1, videoTaps) }
        val output = File(compose.activity.getExternalFilesDir(null), "capsules-uiqa").apply { mkdirs() }
        File(output, "album.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun checkContent(scale: Float) {
        val folder = File(compose.activity.cacheDir, "capsules-layout-fixtures").apply { mkdirs() }
        val document = File(folder, "Маршрут.pdf").apply { writeText("Synthetic COCO layout fixture") }
        val photo = File(folder, "photo.png")
        val bitmap = android.graphics.Bitmap.createBitmap(96, 72, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(84, 113, 132))
        photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val bounds = mutableMapOf<String, Rect>()
        var reactionTaps = 0; var quickTaps = 0; var longPresses = 0; var playTaps = 0
        fun message(id: String, text: String, mine: Boolean = false, reactions: List<ReactionSummary> = emptyList()) =
            Message(id, "server-$id", "chat", if (mine) "me" else "peer", text, 1L, "2026-10-26T14:28:00Z", MessageStatus.READ, reactions = reactions)
        fun attachment(id: String, type: AttachmentMediaType, mime: String, file: File) = Attachment(id, id, type, mime,
            file.name, 240_000, if (type == AttachmentMediaType.IMAGE) 96 else null,
            if (type == AttachmentMediaType.IMAGE) 72 else null, if (type == AttachmentMediaType.VOICE) 12000 else null,
            null, file.path, AttachmentDownloadState.DOWNLOADED, AttachmentUploadState.SENT, 100)
        val rows = listOf(
            message("short", "f", true, listOf(ReactionSummary("👍", 1, true))) to null,
            message("text", "Смотри, какое место нашла 🌿") to null,
            message("voice", "") to attachment("voice", AttachmentMediaType.VOICE, "audio/mp4", document),
            message("file", "") to attachment("file", AttachmentMediaType.FILE, "application/pdf", document),
            message("photo", "") to attachment("photo", AttachmentMediaType.IMAGE, "image/png", photo),
        )
        compose.setContent {
            CocoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale), LocalCocoColors provides CapsuleChatStyle.colors) {
                    Column(Modifier.width(320.dp).fillMaxHeight().background(CapsuleChatStyle.background)
                        .verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((message, attachment) in rows) MessageBubble(
                            message = message, attachment = attachment, isOwnMessage = message.senderId == "me",
                            onBoundsChanged = { bounds[message.clientMessageId] = it },
                            playingAttachmentId = null, onRetry = {}, onDownload = {},
                            onTogglePlay = { _, _ -> playTaps++ }, onOpenFile = { _, _ -> }, onOpenImage = {},
                            onLongPress = { longPresses++ }, onSelectionTap = {}, onToggleReaction = { reactionTaps++ },
                            onQuickReaction = { quickTaps++ }, onScrollToReply = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val density = compose.activity.resources.displayMetrics.density
        val shortText = compose.onNodeWithText("f", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val shortTime = compose.onAllNodesWithText(formatTime(rows.first().first.createdAt), useUnmergedTree = true)[0]
            .fetchSemanticsNode().boundsInRoot
        val shortReceipt = compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.msg_status_read), useUnmergedTree = true)[0]
            .fetchSemanticsNode().boundsInRoot
        val expectedShortWidth = shortText.width + 2 * CapsuleChatStyle.padX * density
        compose.runOnIdle {
            assertEquals(195f * density, bounds.getValue("voice").width, 1f)
            assertEquals(195f * density, bounds.getValue("file").width, 1f)
            assertEquals("Below metadata does not stretch the short message body",
                expectedShortWidth, bounds.getValue("short").width, 3f)
            assertEquals("Time follows the text's bottom padding and the approved detached gap",
                (CapsuleChatStyle.padY + CapsuleChatStyle.timeOffset) * density,
                minOf(shortTime.top, shortReceipt.top) - shortText.bottom, 2f)
        }
        compose.onNodeWithText("f", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("Маршрут.pdf") or hasContentDescription("Маршрут.pdf")).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.play)).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, playTaps) }
        compose.onNodeWithText("👍").performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, reactionTaps); assertEquals(0, quickTaps) }
        // Hit the actual glyph, not the center of the merged message subtree
        // (which also contains the separate reaction and timestamp).
        compose.onNodeWithText("f", useUnmergedTree = true).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, longPresses); assertEquals(0, quickTaps) }
        val output = File(compose.activity.getExternalFilesDir(null), "capsules-uiqa").apply { mkdirs() }
        File(output, "content-$scale.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
