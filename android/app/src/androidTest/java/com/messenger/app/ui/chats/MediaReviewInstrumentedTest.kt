package com.messenger.app.ui.chats

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class MediaReviewInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun image(name: String): File = File(compose.activity.cacheDir, name).also { file ->
        val bitmap = Bitmap.createBitmap(1600, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(101, 88, 255))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun draftCanRemoveAndKeepCaptionWhileKeyboardIsOpen() {
        val selection = mutableStateOf((1..3).map { Uri.fromFile(image("draft-$it.png")) })
        val caption = mutableStateOf("")
        val kept = AtomicBoolean(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.4f)) {
                CocoTheme { PhotoBatchPreviewSheet(selection.value, caption.value, { caption.value = it }, false, false,
                    { selection.value = selection.value - it }, { kept.set(true) }, {}) }
            }
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performClick().performTextInput("Caption survives closing")
        compose.waitUntil(8000) {
            var visible = false
            compose.runOnIdle { visible = android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            } }
            visible
        }
        android.os.SystemClock.sleep(400) // IME visibility is reported before its window animation settles.
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.media_send_count, 3)).assertIsDisplayed()
        // Consume only the content offset; overscroll intentionally dismisses a modal sheet.
        val content = compose.onNodeWithTag("media-draft-content")
        val offset = content.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        content.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -offset) }
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.media_remove_draft))[0].assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Remove must reach its callback", 2, selection.value.size) }
        assertFalse("Removing a file must not dismiss its draft", kept.get())
        android.os.SystemClock.sleep(250) // Allow the system screenshot to include the latest rendered frame.
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(compose.activity.filesDir, "media-draft-keyboard.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(compose.activity.filesDir, "media-draft-tree.txt").writeText(compose.onAllNodes(isRoot()).printToString())
        compose.onNodeWithText(compose.activity.getString(R.string.media_send_count, 2)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.media_keep_draft)).performClick()
        assertTrue(kept.get()); assertEquals("Caption survives closing", caption.value); assertEquals(2, selection.value.size)
    }
    @Test fun missingAlbumNeighbourCanDownloadAndRetryWithoutSkipping() {
        val first = image("album-first.png"); val second = image("album-second.png")
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val items = listOf(MediaViewerItem(first, "image/png", "First"), MediaViewerItem(null, "image/png", "Second", "remote-second"))
        compose.setContent { CocoTheme { MediaViewerDialog(items, first, download = { id ->
            assertEquals("remote-second", id)
            if (attempts.incrementAndGet() == 1) throw java.io.IOException("Offline")
            second
        }, onDismiss = {}) } }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        assertEquals(0, attempts.get())
        compose.onNodeWithTag("media-pager").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { attempts.get() == 1 }
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.retry)).performClick()
        compose.waitUntil(5000) { attempts.get() == 2 }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_actions)).assertIsEnabled()
        compose.onNodeWithTag("media-pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        assertEquals(2, attempts.get())
    }
    @Test fun leavingRemotePageCancelsItsPendingLoad() {
        val first = image("cancel-first.png")
        val started = AtomicBoolean(false); val cancelled = AtomicBoolean(false)
        compose.setContent { CocoTheme { MediaViewerDialog(listOf(
            MediaViewerItem(first, "image/png", "First"), MediaViewerItem(null, "image/png", "Remote", "pending")), first,
            download = {
                started.set(true)
                try { kotlinx.coroutines.awaitCancellation() } finally { cancelled.set(true) }
            }, onDismiss = {}) } }
        compose.onNodeWithTag("media-pager").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { started.get() }
        compose.onNodeWithTag("media-pager").performTouchInput { swipeRight() }
        compose.waitUntil(5000) { cancelled.get() }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_actions)).assertIsEnabled()
    }
    @Test fun invalidPdfShowsExplanationInsteadOfPageOneOfZero() {
        val file = File(compose.activity.cacheDir, "broken.pdf").apply { writeText("not a pdf") }
        compose.setContent { CocoTheme { MediaViewerDialog(listOf(MediaViewerItem(file, "application/pdf", "Broken")), file) {} } }
        compose.waitUntil(5000) { compose.onAllNodesWithText(compose.activity.getString(R.string.media_preview_unavailable)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 / 0").assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_actions)).assertIsEnabled()
    }
    @Test fun doubleTapCanZoomInAndBackOut() {
        val file = image("zoom.png"); val zoomed = AtomicBoolean(false)
        compose.setContent { CocoTheme { ZoomImagePage(file, { zoomed.set(it) }, {}, {}) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("…").fetchSemanticsNodes().isEmpty() }
        compose.onRoot().performTouchInput { doubleClick() }
        compose.waitUntil(3000) { zoomed.get() }
        compose.onRoot().performTouchInput { doubleClick() }
        compose.waitUntil(3000) { !zoomed.get() }
    }
    @Test fun photoDragCancelsAndZoomedPhotoCannotDismiss() {
        val file = image("photo-gestures.png")
        val dismissed = AtomicBoolean(false)
        val zoomed = AtomicBoolean(false)
        compose.setContent { CocoTheme { ZoomImagePage(file, { zoomed.set(it) }, {}, { dismissed.set(true) }) } }
        compose.waitForIdle()
        val photo = compose.onNodeWithTag("photo-gestures")
        photo.performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(0f, height * .08f), 300) }
        compose.waitForIdle(); assertFalse(dismissed.get())
        photo.performTouchInput { doubleClick() }
        compose.waitUntil(3000) { zoomed.get() }
        photo.performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(0f, height * .3f), 400) }
        assertFalse(dismissed.get())
        photo.performTouchInput { doubleClick() }
        compose.waitUntil(3000) { !zoomed.get() }
        photo.performTouchInput {
            down(0, center + androidx.compose.ui.geometry.Offset(-width * .1f, 0f))
            down(1, center + androidx.compose.ui.geometry.Offset(width * .1f, 0f))
            moveTo(0, center + androidx.compose.ui.geometry.Offset(-width * .3f, 0f), 100)
            moveTo(1, center + androidx.compose.ui.geometry.Offset(width * .3f, 0f), 100)
            up(0); up(1)
        }
        compose.waitUntil(3000) { zoomed.get() }
        assertFalse(dismissed.get())
        photo.performTouchInput { doubleClick() }
        compose.waitUntil(3000) { !zoomed.get() }
        photo.performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, height * .3f), 400)
        }
        assertFalse("Do not close before the finger is released", dismissed.get())
        photo.performTouchInput { up() }
        compose.waitUntil(3000) { dismissed.get() }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun videoPausesInBackgroundAndYieldsAudioToCalls() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val file = createVideoTestFixture(File(compose.activity.cacheDir, "review-video.mp4"))
        assertTrue(file.length() > 1000)
        val audio = mutableStateOf(true)
        compose.setContent { CocoTheme { VideoPage(file, audio.value) } }
        fun playerView(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) playerView(view.getChildAt(i))?.let { return it }
            return null
        }
        var player: Player? = null
        compose.waitUntil(10000) {
            var ready = false
            compose.runOnIdle { player = playerView(compose.activity.window.decorView)?.player; ready = player?.playbackState == Player.STATE_READY }
            ready
        }
        compose.runOnIdle {
            val view = playerView(compose.activity.window.decorView)!!
            val surface = view.videoSurfaceView!!
            assertEquals(16f / 9f, surface.width.toFloat() / surface.height, 0.04f)
            player!!.seekTo(0); player!!.pause()
        }
        automation.takeScreenshot().let { bitmap -> File(compose.activity.filesDir, "media-video-player.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        // Scrubbing stays paused, uses a large touch target and changes the real player.
        val slider = compose.onNodeWithTag("video-timebar")
        slider.assertIsDisplayed()
        slider.performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width * .15f, height / 2f), androidx.compose.ui.geometry.Offset(width * .8f, height / 2f), 600) }
        compose.runOnIdle { assertFalse(player!!.playWhenReady); assertTrue("seek=${player!!.currentPosition}, duration=${player!!.duration}", player!!.currentPosition > player!!.duration / 2) }
        // Double-tap on the left clamps at zero, without dismissing the controls.
        compose.onNodeWithTag("video-tap-surface").performTouchInput {
            doubleClick(androidx.compose.ui.geometry.Offset(width * .1f, height * .25f))
        }
        compose.runOnIdle { assertTrue(player!!.currentPosition < 300); assertFalse(player!!.playWhenReady) }
        slider.assertIsDisplayed()
        val playButton = compose.onNodeWithContentDescription(compose.activity.getString(R.string.play))
        assertTrue("Transport must be near the bottom, not over the middle of the video", playButton.fetchSemanticsNode().boundsInRoot.top > compose.onRoot().fetchSemanticsNode().boundsInRoot.height * .65f)
        // Transport is centered in usable space, excluding asymmetric camera
        // cutout/navigation insets in landscape, rather than behind those insets.
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
        val safeCenter = compose.onRoot().fetchSemanticsNode().boundsInRoot.center.x +
            ((insets?.left ?: 0) - (insets?.right ?: 0)) / 2f
        assertEquals("Play must be horizontally centered in safe space", safeCenter, playButton.fetchSemanticsNode().boundsInRoot.center.x, 2f)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.video_mute)).performClick()
        compose.runOnIdle { assertEquals(0f, player!!.volume, .001f) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.video_unmute)).performClick()
        compose.runOnIdle { assertEquals(1f, player!!.volume, .001f) }
        playButton.performClick()
        compose.runOnIdle { assertTrue(player!!.playWhenReady) }
        automation.takeScreenshot().let { bitmap -> File(compose.activity.filesDir, "media-video-player.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { assertFalse(player!!.playWhenReady) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertFalse(player!!.playWhenReady); player!!.play(); audio.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0f, player!!.volume, 0.001f); assertFalse(player!!.playWhenReady) }
        compose.runOnIdle { audio.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1f, player!!.volume, 0.001f); assertFalse(player!!.playWhenReady) }
    }
    @Test fun unsupportedVideoOffersRetryInsteadOfSilentBlackScreen() {
        val file = File(compose.activity.cacheDir, "invalid-video.mp4").apply { writeText("not a media file") }
        compose.setContent { CocoTheme { VideoPage(file, true) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText(compose.activity.getString(R.string.retry)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.media_preview_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.retry)).performClick()
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun repeatedMediaOpenCloseReleasesPlayersAndReportsMemory() {
        val video = createVideoTestFixture(File(compose.activity.cacheDir, "memory-video.mp4"))
        assertTrue(video.length() > 1000)
        val photo = image("memory-photo.png")
        val opened = mutableStateOf(false)
        val selected = mutableStateOf(photo)
        val refs = mutableListOf<java.lang.ref.WeakReference<Player>>()
        val report = StringBuilder("cycle,pss_kb,java_used_bytes,native_bytes,live_players\n")
        fun sample(cycle: Int) {
            Runtime.getRuntime().gc(); System.runFinalization(); android.os.SystemClock.sleep(250)
            val info = android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(info)
            val runtime = Runtime.getRuntime()
            report.append("$cycle,${info.totalPss},${runtime.totalMemory()-runtime.freeMemory()},${android.os.Debug.getNativeHeapAllocatedSize()},${refs.count { it.get() != null }}\n")
        }
        fun findPlayer(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findPlayer(view.getChildAt(i))?.let { return it }
            return null
        }
        compose.setContent { CocoTheme {
            if (opened.value) MediaViewerDialog(listOf(MediaViewerItem(photo, "image/png", "QA photo"),
                MediaViewerItem(video, "video/mp4", "QA video")), selected.value, onDismiss = { opened.value = false })
        } }
        sample(0)
        repeat(30) { cycle ->
            val isVideo = cycle % 2 == 1
            compose.runOnIdle { selected.value = if (isVideo) video else photo; opened.value = true }
            var view: PlayerView? = null
            if (isVideo) {
                // Do not wait for all dialog/surface animations before observing
                // READY: the short autoplay clip may already be ENDED by then.
                compose.waitUntil(10000) {
                    var ready = false
                    compose.runOnUiThread {
                        view = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findPlayer)
                        ready = view?.player?.playbackState == Player.STATE_READY
                    }
                    ready
                }
                compose.runOnIdle { refs += java.lang.ref.WeakReference(view!!.player!!) }
            } else {
                compose.waitForIdle()
                compose.onNodeWithText("1 / 2").assertIsDisplayed()
                android.os.SystemClock.sleep(120)
            }
            compose.runOnIdle { opened.value = false }
            compose.waitForIdle()
            compose.runOnIdle { if (isVideo) assertNull("Closed PlayerView must detach its player", view!!.player); view = null }
            if ((cycle + 1) % 5 == 0) sample(cycle + 1)
        }
        compose.waitUntil(10000) { Runtime.getRuntime().gc(); refs.all { it.get() == null } }
        sample(31)
        File(compose.activity.filesDir, "media-memory-cycles.csv").writeText(report.toString())
        println("QA media memory cycles: " + report.toString())
        assertEquals(15, refs.size)
    }

}
