package com.messenger.app.ui.chats

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.ui.theme.CocoTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Assert real pixels during a held swipe, not just the final page index. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class MediaViewerPagingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val red = Color.rgb(220, 50, 64)
    private val blue = Color.rgb(25, 118, 210)

    @Test fun photosRemainVisibleOnBothSidesBeforeAndAfterThePageMidpoint() {
        val folder = fixtureDirectory()
        val first = photo(File(folder, "red.png"), red)
        val second = photo(File(folder, "blue.png"), blue)
        val shown = mutableStateOf(true)
        compose.setContent { CocoTheme { if (shown.value) MediaViewerDialog(listOf(
            MediaViewerItem(first, "image/png", "Red"), MediaViewerItem(second, "image/png", "Blue")), first,
            onDismiss = { shown.value = false }) } }
        try {
            compose.onNodeWithText("1 / 2").assertIsDisplayed()
            startPartialSwipe(forward = true)
            assertSwipePixels("media-photo-forward-before-midpoint.png", ::isRed, ::isBlue)
            compose.onNodeWithText("1 / 2").assertIsDisplayed()
            continuePartialSwipe(forward = true)
            assertSwipePixels("media-photo-forward-after-midpoint.png", ::isRed, ::isBlue)
            compose.onNodeWithText("2 / 2").assertIsDisplayed()
            releaseSwipe()
            startPartialSwipe(forward = false)
            assertSwipePixels("media-photo-reverse-before-midpoint.png", ::isRed, ::isBlue)
            compose.onNodeWithText("2 / 2").assertIsDisplayed()
            continuePartialSwipe(forward = false)
            assertSwipePixels("media-photo-reverse-after-midpoint.png", ::isRed, ::isBlue)
            compose.onNodeWithText("1 / 2").assertIsDisplayed()
            releaseSwipe()
        } finally {
            compose.runOnIdle { shown.value = false }; compose.waitForIdle()
            folder.listFiles()?.forEach { it.delete() }; folder.delete()
        }
    }

    @Test fun photoVideoSwipeUsesAPassivePosterAndOnlyTheCurrentPageOwnsAPlayer() {
        val folder = fixtureDirectory()
        val first = photo(File(folder, "red.png"), red)
        val video = createVideoTestFixture(File(folder, "clip.mp4"))
        val shown = mutableStateOf(true)
        compose.setContent { CocoTheme { if (shown.value) MediaViewerDialog(listOf(
            MediaViewerItem(first, "image/png", "Red"), MediaViewerItem(video, "video/mp4", "Video")), first,
            onDismiss = { shown.value = false }) } }
        try {
            compose.onNodeWithText("1 / 2").assertIsDisplayed()
            startPartialSwipe(forward = true)
            assertSwipePixels("media-mixed-forward-before-midpoint.png", ::isRed, ::isVideoPoster)
            assertEquals("An adjoining video page must not allocate a player", 0, playerStates().size)
            continuePartialSwipe(forward = true)
            assertSwipePixels("media-mixed-forward-after-midpoint.png", ::isRed, ::isVideoPoster)
            assertEquals("Crossing the midpoint must not prepare a decoder during a held gesture", 0, playerStates().size)
            releaseSwipe()
            compose.waitUntil(10_000) { playerStates().singleOrNull() == Player.STATE_READY }
            startPartialSwipe(forward = false)
            assertSwipePixels("media-mixed-reverse-before-midpoint.png", ::isRed, ::isVideoPoster)
            assertEquals(1, playerStates().size)
            continuePartialSwipe(forward = false)
            assertSwipePixels("media-mixed-reverse-after-midpoint.png", ::isRed, ::isVideoPoster)
            assertEquals("The outgoing decoder stays attached until the gesture settles", 1, playerStates().size)
            releaseSwipe()
            compose.waitUntil(5_000) { playerStates().isEmpty() }
        } finally {
            compose.runOnIdle { shown.value = false }; compose.waitForIdle()
            assertTrue("Closing the viewer detaches every video player", playerStates().isEmpty())
            folder.listFiles()?.forEach { it.delete() }; folder.delete()
        }
    }

    @Test fun cancelledSwipePastTheMidpointKeepsTheSamePausedDecoderAndPosition() {
        val folder = fixtureDirectory()
        val first = photo(File(folder, "red.png"), red)
        val video = createVideoTestFixture(File(folder, "clip.mp4"))
        val shown = mutableStateOf(true)
        compose.setContent { CocoTheme { if (shown.value) MediaViewerDialog(listOf(
            MediaViewerItem(first, "image/png", "Red"), MediaViewerItem(video, "video/mp4", "Video")), video,
            onDismiss = { shown.value = false }) } }
        try {
            compose.waitUntil(10_000) { playerStates().singleOrNull() == Player.STATE_READY }
            val original = attachedPlayers().single()
            compose.runOnUiThread { original.pause(); original.seekTo(1_000) }
            compose.waitUntil(5_000) {
                var ready = false
                compose.runOnUiThread { ready = original.currentPosition == 1_000L }
                ready
            }
            startPartialSwipe(forward = false)
            continuePartialSwipe(forward = false)
            compose.onNodeWithText("1 / 2").assertIsDisplayed()
            assertSame("Crossing the midpoint cannot detach the decoder", original, attachedPlayers().single())
            compose.onNodeWithTag("media-pager").performTouchInput {
                moveBy(Offset(width * -.65f, 0f), delayMillis = 300)
                advanceEventTime(200)
                up()
            }
            compose.onNodeWithText("2 / 2").assertIsDisplayed()
            assertSame("A cancelled swipe reuses the existing decoder", original, attachedPlayers().single())
            compose.runOnUiThread {
                assertFalse("Cancelling a swipe cannot resume a paused video", original.playWhenReady)
                assertEquals("Cancelling a swipe preserves its playback position", 1_000L, original.currentPosition)
            }
        } finally {
            compose.runOnIdle { shown.value = false }; compose.waitForIdle()
            assertTrue(attachedPlayers().isEmpty())
            folder.listFiles()?.forEach { it.delete() }; folder.delete()
        }
    }

    @Test fun remoteNeighbourUsesItsEmbeddedPreviewWithoutStartingADownloadBeforeSelection() {
        val folder = fixtureDirectory()
        val first = photo(File(folder, "red.png"), red)
        val second = photo(File(folder, "blue.png"), blue)
        val thumbnail = android.util.Base64.encodeToString(second.readBytes(), android.util.Base64.NO_WRAP)
        val downloads = java.util.concurrent.atomic.AtomicInteger(0)
        val shown = mutableStateOf(true)
        compose.setContent { CocoTheme { if (shown.value) MediaViewerDialog(listOf(
            MediaViewerItem(first, "image/png", "Red"),
            MediaViewerItem(null, "image/png", "Remote", "remote-blue", thumbnail)), first,
            download = { id -> assertEquals("remote-blue", id); downloads.incrementAndGet(); second },
            onDismiss = { shown.value = false }) } }
        try {
            compose.onNodeWithText("1 / 2").assertIsDisplayed()
            assertEquals(0, downloads.get())
            startPartialSwipe(forward = true)
            assertSwipePixels("media-remote-forward-before-midpoint.png", ::isRed, ::isBlue)
            assertEquals("Showing an encrypted message thumbnail must not download its original", 0, downloads.get())
            continuePartialSwipe(forward = true)
            assertEquals("A held midpoint must not start the original download", 0, downloads.get())
            assertSwipePixels("media-remote-forward-after-midpoint.png", ::isRed, ::isBlue)
            releaseSwipe()
            compose.waitUntil(5_000) { downloads.get() == 1 }
            compose.onNodeWithTag("media-pager").performTouchInput { swipeRight() }
            compose.onNodeWithText("1 / 2").assertIsDisplayed()
            assertEquals("The downloaded neighbour is reused", 1, downloads.get())
        } finally {
            compose.runOnIdle { shown.value = false }; compose.waitForIdle()
            folder.listFiles()?.forEach { it.delete() }; folder.delete()
        }
    }

    private fun startPartialSwipe(forward: Boolean) {
        compose.onNodeWithTag("media-pager").performTouchInput {
            val direction = if (forward) -1f else 1f
            down(Offset(width * if (forward) .9f else .1f, height * .5f))
            moveBy(Offset(width * direction * .02f, 0f), delayMillis = 32)
            moveBy(Offset(width * direction * .33f, 0f), delayMillis = 300)
        }
        compose.waitForIdle()
    }

    private fun continuePartialSwipe(forward: Boolean) {
        compose.onNodeWithTag("media-pager").performTouchInput {
            moveBy(Offset(width * (if (forward) -.3f else .3f), 0f), delayMillis = 300)
        }
        compose.waitForIdle()
    }

    private fun releaseSwipe() {
        compose.onNodeWithTag("media-pager").performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun assertSwipePixels(name: String, left: (Int) -> Boolean, right: (Int) -> Boolean) {
        var lastLeft = Color.BLACK
        var lastRight = Color.BLACK
        compose.waitUntil(5_000) {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (screenshot == null) false else try {
                lastLeft = screenshot.getPixel((screenshot.width * .12f).toInt(), screenshot.height / 2)
                lastRight = screenshot.getPixel((screenshot.width * .88f).toInt(), screenshot.height / 2)
                val matches = left(lastLeft) && right(lastRight)
                if (matches) File(compose.activity.filesDir, name).outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                matches
            } finally { screenshot.recycle() }
        }
        assertTrue("Left page pixel must remain visible: ${Integer.toHexString(lastLeft)}", left(lastLeft))
        assertTrue("Right page pixel must remain visible: ${Integer.toHexString(lastRight)}", right(lastRight))
    }

    private fun playerStates(): List<Int> {
        val players = attachedPlayers()
        var states = emptyList<Int>()
        compose.runOnUiThread { states = players.map { it.playbackState } }
        return states
    }

    private fun attachedPlayers(): List<Player> {
        val found = mutableListOf<Player>()
        fun visit(view: View) {
            if (view is PlayerView) view.player?.let { found.add(it) }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        compose.runOnUiThread { WindowInspector.getGlobalWindowViews().forEach(::visit) }
        return found
    }

    private fun isRed(value: Int) = matches(value, red)
    private fun isBlue(value: Int) = matches(value, blue)
    private fun isVideoPoster(value: Int): Boolean {
        val channels = listOf(Color.red(value), Color.green(value), Color.blue(value))
        return channels.minOrNull()!! > 20 && channels.maxOrNull()!! - channels.minOrNull()!! < 25
    }
    private fun matches(value: Int, expected: Int) =
        kotlin.math.abs(Color.red(value) - Color.red(expected)) <= 18 &&
            kotlin.math.abs(Color.green(value) - Color.green(expected)) <= 18 &&
            kotlin.math.abs(Color.blue(value) - Color.blue(expected)) <= 18

    private fun fixtureDirectory() = File(compose.activity.cacheDir, "pager-audit-${UUID.randomUUID()}").apply { mkdirs() }
    private fun photo(file: File, color: Int): File {
        Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return file
    }
}
