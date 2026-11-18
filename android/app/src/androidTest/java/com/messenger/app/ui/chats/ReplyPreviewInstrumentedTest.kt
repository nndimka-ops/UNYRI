package com.messenger.app.ui.chats

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.R
import com.messenger.app.data.local.ChatImageCache
import com.messenger.app.domain.model.*
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalTestApi::class)
class ReplyPreviewInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun everyMediaKindFitsNarrowComposerAndCancelPreservesDraft() {
        val chosen = mutableStateOf<Attachment?>(null)
        val shown = mutableStateOf(true)
        val draft = mutableStateOf("Keep my draft")
        compose.setContent {
            CocoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Box(Modifier.width(320.dp)) {
                        ComposerBar(replyHeader = if (shown.value) ({ ReplyComposerBanner("You", "Selected text, not the previous quote", "A caption", chosen.value, false) { shown.value = false } }) else null,
                            keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {}, input = draft.value, submitting = false,
                            onInputChange = { draft.value = it }, isRecording = false, onAttach = {}, onMicToggle = {}, onSend = {})
                    }
                }
            }
        }
        val variants = listOf(null, media("image/jpeg", AttachmentMediaType.IMAGE), media("video/mp4", AttachmentMediaType.FILE), media("audio/ogg", AttachmentMediaType.VOICE), media("application/pdf", AttachmentMediaType.FILE))
        variants.forEachIndexed { index, media ->
            compose.runOnIdle { chosen.value = media; shown.value = true }
            compose.onNodeWithText("You").assertIsDisplayed()
            val cancel = compose.onNodeWithContentDescription(compose.activity.getString(R.string.reply_cancel))
            cancel.assertIsDisplayed()
            val bounds = cancel.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 44 * compose.activity.resources.displayMetrics.density)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                File(compose.activity.filesDir, "reply-preview-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            cancel.performClick()
            compose.onNodeWithText("Keep my draft").assertIsDisplayed()
            compose.runOnIdle { assertFalse(shown.value); assertEquals("Keep my draft", draft.value) }
        }
    }

    @Test fun largeFontComposerKeepsNamesCaptionsDurationsAndIndependent48dpCancel() {
        val chosen = mutableStateOf<Attachment?>(null)
        val editing = mutableStateOf(false)
        var cancelled = 0
        compose.setContent {
            CocoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Box(Modifier.width(320.dp).testTag("reply-composer-frame")) {
                        ReplyComposerBanner("Alex", "Selected text", "A caption", chosen.value, editing.value) { cancelled++ }
                    }
                }
            }
        }
        val variants = listOf(null, media("image/jpeg", AttachmentMediaType.IMAGE), media("video/mp4", AttachmentMediaType.FILE),
            media("audio/ogg", AttachmentMediaType.VOICE), media("application/pdf", AttachmentMediaType.FILE))
        variants.forEachIndexed { index, attachment ->
            compose.runOnIdle { chosen.value = attachment }
            compose.onNodeWithText("Alex").assertIsDisplayed()
            if (attachment != null) {
                compose.onNodeWithText("A caption", substring = true).assertIsDisplayed()
                if (replyMediaKind(attachment) in listOf("voice", "video")) compose.onNodeWithText("0:24", substring = true).assertIsDisplayed()
                if (replyMediaKind(attachment) == "file") compose.onNodeWithText(attachment.filename!!, substring = true).assertIsDisplayed()
            } else compose.onNodeWithText("Selected text").assertIsDisplayed()
            val cancel = compose.onNodeWithContentDescription(compose.activity.getString(R.string.reply_cancel)).assertIsDisplayed()
            val bounds = cancel.fetchSemanticsNode().boundsInRoot
            val px = compose.activity.resources.displayMetrics.density
            assertTrue(bounds.width >= 48f * px - 1f && bounds.height >= 48f * px - 1f)
            assertInside("reply-composer-banner", "reply-composer-frame")
            val frame = compose.onNodeWithTag("reply-composer-frame").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= frame.left && bounds.right <= frame.right + 1f && bounds.bottom <= frame.bottom + 1f)
            cancel.performClick()
            compose.runOnIdle { assertEquals(index + 1, cancelled) }
        }
        compose.runOnIdle { editing.value = true }
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.dismiss)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(6, cancelled) }
        saveFrame("reply-composer-frame", "reply-composer-large-font.png")
    }

    @Test fun everyQuoteKindKeepsTheOriginalTapAtLargeFontAndShowsMediaBadge() {
        val chosen = mutableStateOf<Attachment?>(null)
        var quoteTaps = 0
        compose.setContent {
            CocoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Box(Modifier.width(280.dp).testTag("reply-quote-frame")) {
                        ReplyQuote("Original author", "Selected reply content over two readable lines", "text",
                            Modifier.clickable { quoteTaps++ }, attachment = chosen.value)
                    }
                }
            }
        }
        val variants = listOf(null, media("image/jpeg", AttachmentMediaType.IMAGE), media("video/mp4", AttachmentMediaType.FILE),
            media("audio/ogg", AttachmentMediaType.VOICE), media("application/pdf", AttachmentMediaType.FILE))
        variants.forEachIndexed { index, attachment ->
            compose.runOnIdle { chosen.value = attachment }
            compose.onNodeWithText("Original author").assertIsDisplayed()
            compose.onNodeWithText("Selected reply content over two readable lines").assertIsDisplayed()
            assertInside("reply-quote", "reply-quote-frame")
            if (attachment != null) {
                val badge = compose.onNodeWithTag("reply-media-${replyMediaKind(attachment)}", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals(36f * compose.activity.resources.displayMetrics.density, badge.width, 1f)
                assertEquals(badge.width, badge.height, 1f)
            }
            compose.onNodeWithTag("reply-quote").performClick()
            compose.runOnIdle { assertEquals(index + 1, quoteTaps) }
        }
        saveFrame("reply-quote-frame", "reply-quote-large-font.png")
    }

    @Test fun imageAndVideoUseRealCached128pxThumbnailWithoutDownloadOrPlayer() {
        val chosen = mutableStateOf(media("image/jpeg", AttachmentMediaType.IMAGE).copy(thumbnailBase64 = thumbnail(android.graphics.Color.BLUE)))
        compose.setContent {
            CocoTheme {
                Box(Modifier.width(280.dp).background(Color.Black).testTag("reply-thumbnail-frame")) {
                    ReplyQuote("Author", "Caption", "image", attachment = chosen.value)
                }
            }
        }
        listOf("image" to android.graphics.Color.BLUE, "video" to android.graphics.Color.RED).forEach { (kind, color) ->
            val encoded = thumbnail(color)
            compose.runOnIdle {
                chosen.value = media(if (kind == "image") "image/jpeg" else "video/mp4",
                    if (kind == "image") AttachmentMediaType.IMAGE else AttachmentMediaType.FILE).copy(thumbnailBase64 = encoded)
            }
            compose.waitUntil(5_000) { ChatImageCache.peek("reply-128:$kind:thumbnail:$encoded") != null }
            val cached = ChatImageCache.peek("reply-128:$kind:thumbnail:$encoded")!!
            assertTrue(cached.width <= 128 && cached.height <= 128)
            compose.waitForIdle()
            val image = compose.onNodeWithTag("reply-media-$kind", useUnmergedTree = true).captureToImage().toPixelMap()
            val sample = image[(image.width * .12f).toInt(), image.height / 2]
            if (kind == "image") assertTrue("Image thumbnail must be visible", sample.blue > .9f && sample.red < .1f)
            else assertTrue("Video thumbnail must replace the previous image", sample.red > .9f && sample.blue < .1f)
        }
        saveFrame("reply-thumbnail-frame", "reply-cached-video-thumbnail.png")
    }

    private fun assertInside(child: String, parent: String) {
        val childBounds = compose.onNodeWithTag(child).fetchSemanticsNode().boundsInRoot
        val parentBounds = compose.onNodeWithTag(parent).fetchSemanticsNode().boundsInRoot
        assertTrue("$childBounds must remain in $parentBounds", childBounds.left >= parentBounds.left - 1f &&
            childBounds.top >= parentBounds.top - 1f && childBounds.right <= parentBounds.right + 1f && childBounds.bottom <= parentBounds.bottom + 1f)
    }

    private fun thumbnail(color: Int): String {
        val bitmap = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try {
            val bytes = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.toByteArray() }
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }

    private fun saveFrame(tag: String, name: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        try {
            val folder = compose.activity.getExternalFilesDir("capsules-uiqa")!!
            folder.mkdirs()
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
    private fun media(mime: String, type: AttachmentMediaType) = Attachment("id-$mime", "message", type, mime, "A very long filename for the reply preview.pdf", 100, null, null, 24000, null, null, AttachmentDownloadState.NOT_DOWNLOADED, AttachmentUploadState.SENT, 0)
}
