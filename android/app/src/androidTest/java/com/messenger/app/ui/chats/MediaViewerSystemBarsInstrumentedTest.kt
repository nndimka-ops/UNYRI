package com.messenger.app.ui.chats

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real dialog/window insets and native player; does not change the phone's navigation mode. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaViewerSystemBarsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val navType get() = WindowInsetsCompat.Type.navigationBars()
    private val screenshotRun = "media-player-bars-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"

    private data class HostBars(val status: Boolean?, val navigation: Boolean?, val behavior: Int,
        val lightStatus: Boolean, val lightNavigation: Boolean, val orientation: Int)

    private data class NavigationPixel(val x: Int, val y: Int, val visibleLevel: Int)
    private data class NavigationReference(val width: Int, val height: Int,
        val backgroundLevel: Int, val pixels: List<NavigationPixel>)
    private var navigationReference: NavigationReference? = null

    @Test fun photoControlsShowNavigationAndHiddenChromeAllowsTransientEdgeRevealThenBackRestoresHost() {
        val file = File(compose.activity.cacheDir, "bars-photo-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.rgb(35, 80, 100))
            file.outputStream().use { output -> it.compress(Bitmap.CompressFormat.PNG, 100, output) }
            it.recycle()
        }
        val shown = mutableStateOf(false)
        compose.setContent { CocoTheme { Box(Modifier.fillMaxSize()) {
            if (shown.value) MediaViewerDialog(listOf(MediaViewerItem(file, "image/png", "Own photo")), file,
                onDismiss = { shown.value = false })
        } } }
        val host = hostBars()
        try {
            compose.runOnIdle { shown.value = true }
            compose.onNodeWithText("1 / 1").assertIsDisplayed()
            awaitNavigation(true)
            captureNavigationReference()
            screenshot("photo-controls-navigation-visible.png")
            compose.onNodeWithTag("photo-gestures").performTouchInput { click(center) }
            // A photo also accepts double tap: wait for the real single-tap
            // recognizer and its window transition before the exact assertion.
            awaitNavigation(false)
            compose.onNodeWithText("1 / 1").assertDoesNotExist()
            // Insets can report the hidden request before SystemUI's hide animation ends.
            awaitRenderedNavigation(false, 0)
            screenshot("photo-controls-hidden-immersive.png")
            val hiddenNavigationPixels = navigationChromePixels()
            revealNavigationFromBottomEdge()
            screenshot("photo-after-raw-touchscreen-edge-injection.png")
            awaitRenderedNavigation(true, hiddenNavigationPixels)
            compose.onNodeWithText("1 / 1").assertDoesNotExist()
            screenshot("photo-edge-transient-navigation.png")
            // Transient bars are a SystemUI overlay. Some versions intentionally
            // keep the app's Insets visibility false throughout the real reveal.
            // Assert actual navigation pixels appear and then disappear instead.
            awaitRenderedNavigation(false, hiddenNavigationPixels)
            awaitNavigation(false, timeout = 12_000)
            compose.onNodeWithTag("photo-gestures").performTouchInput { click(center) }
            awaitNavigation(true)
            pressBack()
            compose.waitUntil(5_000) { !shown.value }
            assertEquals("Closing the dialog cannot leave the chat immersive", host, hostBars())
        } finally {
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            file.delete()
        }
    }

    @Test fun videoAutoHideAndMenusFollowNavigationWithoutResizingTheVideoAndResumeRestoresControls() {
        val file = createVideoTestFixture(File(compose.activity.cacheDir, "bars-video-${UUID.randomUUID()}.mp4"))
        val shown = mutableStateOf(false)
        compose.setContent { CocoTheme { Box(Modifier.fillMaxSize()) {
            if (shown.value) MediaViewerDialog(listOf(MediaViewerItem(file, "video/mp4", "Own silent video")), file,
                onDismiss = { shown.value = false })
        } } }
        val host = hostBars()
        try {
            compose.runOnIdle { shown.value = true }
            compose.waitUntil(10_000) { playerView() != null }
            compose.runOnUiThread { playerView()!!.player!!.repeatMode = Player.REPEAT_MODE_ONE }
            awaitNavigation(true)
            val surface = playerSize()
            screenshot("video-initial-physical-window.png")
            assertVideoFillsPhysicalScreen()
            assertDockClearsNavigation()
            compose.waitUntil(10_000) {
                var playing = false
                compose.runOnUiThread { playing = playerView()?.player?.isPlaying == true }
                playing
            }
            screenshot("video-controls-navigation-visible.png")
            awaitNavigation(false, timeout = 12_000)
            screenshot("video-hidden-navigation-hidden.png")
            assertEquals("Showing/hiding navigation must not resize the decoder surface", surface, playerSize())
            compose.onNodeWithTag("video-tap-surface").performTouchInput { click(center) }
            awaitNavigation(true)
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.pause)).performClick()
            assertDockClearsNavigation()
            val stableDock = compose.onNodeWithTag("video-controls-dock").fetchSemanticsNode().boundsInWindow
            repeat(3) {
                compose.onNodeWithTag("video-tap-surface").performTouchInput { click(center) }
                awaitNavigation(false)
                compose.onNodeWithTag("video-tap-surface").performTouchInput { click(center) }
                awaitNavigation(true)
                assertDockClearsNavigation()
                val restoredDock = compose.onNodeWithTag("video-controls-dock").fetchSemanticsNode().boundsInWindow
                assertEquals("Navigation visibility cannot move the controls", stableDock.bottom, restoredDock.bottom, 2f)
                compose.runOnUiThread { assertFalse(playerView()!!.player!!.playWhenReady) }
            }
            screenshot("video-repeated-controls-safe-navigation.png")
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_actions)).performClick()
            awaitNavigation(true)
            screenshot("video-actions-menu-navigation-visible.png")
            pressBack() // Dismiss the menu, not the viewer.
            compose.onNodeWithText("1 / 1").assertIsDisplayed()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            awaitNavigation(true)
            compose.runOnUiThread { assertFalse("Returning from background cannot resume paused media", playerView()!!.player!!.playWhenReady) }
            screenshot("video-resumed-controls-navigation-visible.png")
            pressBack()
            compose.waitUntil(5_000) { !shown.value }
            assertEquals("Media dialog lifecycle must preserve the host window's bars and orientation", host, hostBars())
            screenshot("host-after-player-dismiss.png")
        } finally {
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            file.delete()
        }
    }

    private fun hostBars(): HostBars {
        compose.waitUntil(5_000) {
            var ready = false
            compose.runOnUiThread { ready = ViewCompat.getRootWindowInsets(compose.activity.window.decorView) != null }
            ready
        }
        var result: HostBars? = null
        compose.runOnUiThread {
            val window = compose.activity.window
            val insets = ViewCompat.getRootWindowInsets(window.decorView)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            result = HostBars(insets?.isVisible(WindowInsetsCompat.Type.statusBars()), insets?.isVisible(navType),
                controller.systemBarsBehavior, controller.isAppearanceLightStatusBars,
                controller.isAppearanceLightNavigationBars, compose.activity.requestedOrientation)
        }
        return requireNotNull(result)
    }

    private fun dialogDecor(): View? = WindowInspector.getGlobalWindowViews().firstOrNull {
        it !== compose.activity.window.decorView && it.isShown && it.findViewById<View>(android.R.id.content) != null
    }

    private fun awaitNavigation(visible: Boolean, timeout: Long = 5_000) {
        compose.waitUntil(timeout) {
            var matched = false
            compose.runOnUiThread { matched = dialogDecor()?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(navType) == visible } == true }
            matched
        }
    }

    private fun assertDockClearsNavigation() {
        val dock = compose.onNodeWithTag("video-controls-dock").fetchSemanticsNode().boundsInWindow
        val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            compose.runOnUiThread {
                val decor = requireNotNull(dialogDecor())
                val safe = requireNotNull(ViewCompat.getRootWindowInsets(decor))
                    .getInsetsIgnoringVisibility(navType or WindowInsetsCompat.Type.displayCutout())
                val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
                val screenDock = dock.translate(androidx.compose.ui.geometry.Offset(origin[0].toFloat(), origin[1].toFloat()))
                assertTrue("Controls overlap bottom system navigation on the physical screen", screenDock.bottom <= screen.height - safe.bottom + 2f)
                assertTrue("Controls overlap the left system inset on the physical screen", screenDock.left >= safe.left - 2f)
                assertTrue("Controls overlap the right system inset on the physical screen", screenDock.right <= screen.width - safe.right + 2f)
                android.util.Log.i("COCO_MEDIA_BARS_QA", "screen=${screen.width}x${screen.height} dock=$screenDock navigation=$safe")
            }
        } finally { screen.recycle() }
    }

    private fun playerView(): PlayerView? {
        fun find(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        var found: PlayerView? = null
        compose.runOnUiThread { found = dialogDecor()?.let(::find) }
        return found
    }

    private fun assertVideoFillsPhysicalScreen() {
        val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val view = requireNotNull(playerView())
            compose.runOnUiThread {
                val origin = IntArray(2).also { view.getLocationOnScreen(it) }
                assertEquals("Decoder left edge must match the physical display", 0, origin[0])
                assertEquals("Decoder top edge must match the physical display", 0, origin[1])
                assertEquals("Decoder width must match the physical display", screen.width, view.width)
                assertEquals("Decoder height must match the physical display", screen.height, view.height)
            }
        } finally { screen.recycle() }
    }

    private fun playerSize(): Pair<Int, Int> {
        var size = 0 to 0
        compose.runOnUiThread { playerView()?.let { size = it.width to it.height } }
        return size
    }

    private fun pressBack() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }

    private fun awaitRenderedNavigation(visible: Boolean, hiddenPixels: Int) {
        var pixels = hiddenPixels
        compose.waitUntil(12_000) {
            pixels = navigationChromePixels()
            (pixels >= hiddenPixels + 32) == visible
        }
        var appReportedVisible: Boolean? = null
        compose.runOnUiThread { appReportedVisible = dialogDecor()?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(navType) } }
        android.util.Log.i("COCO_MEDIA_BARS_QA", "transientRendered=$visible brightPixels=$pixels hiddenBaseline=$hiddenPixels appInsetsVisible=$appReportedVisible")
    }

    /** Calibrate the real rendered glyphs in this fixture's black navigation
     * region. Samsung can render white system buttons at RGB178, so an absolute
     * RGB180 threshold would reject visibly present navigation. Keep the same
     * physical pixel mask for the hidden, transient and hidden-again checks. */
    private fun captureNavigationReference() {
        compose.waitForIdle()
        repeat(2) {
            val committed = java.util.concurrent.CountDownLatch(1)
            compose.runOnUiThread {
                val view = requireNotNull(dialogDecor())
                view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                view.postInvalidateOnAnimation()
            }
            check(committed.await(3, java.util.concurrent.TimeUnit.SECONDS))
        }
        var insets = androidx.core.graphics.Insets.NONE
        compose.runOnUiThread {
            insets = requireNotNull(ViewCompat.getRootWindowInsets(requireNotNull(dialogDecor())))
                .getInsetsIgnoringVisibility(navType)
        }
        compose.waitUntil(5_000) {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val left = insets.left.coerceIn(0, bitmap.width)
                val right = insets.right.coerceIn(0, bitmap.width - left)
                val bottom = if (left == 0 && right == 0) insets.bottom.coerceIn(0, bitmap.height) else 0
                val regions = mutableListOf<Pair<android.graphics.Rect, IntArray>>()
                fun sample(x: Int, y: Int, width: Int, height: Int) {
                    if (width <= 0 || height <= 0) return
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, x, y, width, height)
                    regions.add(android.graphics.Rect(x, y, x + width, y + height) to pixels)
                }
                sample(0, 0, left, bitmap.height)
                sample(bitmap.width - right, 0, right, bitmap.height)
                sample(0, bitmap.height - bottom, bitmap.width, bottom)
                fun level(pixel: Int) = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                var background = 255
                var foreground = 0
                regions.forEach { (_, pixels) -> pixels.forEach { pixel ->
                    val value = level(pixel)
                    background = minOf(background, value)
                    foreground = maxOf(foreground, value)
                } }
                val mask = mutableListOf<NavigationPixel>()
                if (foreground > background) {
                    val midpoint = background + (foreground - background) / 2
                    regions.forEach { (bounds, pixels) -> pixels.forEachIndexed { index, pixel ->
                        val value = level(pixel)
                        if (value > midpoint) mask.add(NavigationPixel(bounds.left + index % bounds.width(),
                            bounds.top + index / bounds.width(), value))
                    } }
                }
                // The existing reveal oracle requires at least 32 physical pixels.
                // Establish that signal while navigation is actually visible first.
                if (mask.size < 32) false else {
                    navigationReference = NavigationReference(bitmap.width, bitmap.height, background, mask)
                    android.util.Log.i("COCO_MEDIA_BARS_QA", "navigationReference=${bitmap.width}x${bitmap.height} insets=$insets background=$background foreground=$foreground maskPixels=${mask.size}")
                    true
                }
            } finally { bitmap.recycle() }
        }
    }

    private fun navigationChromePixels(): Int {
        val reference = requireNotNull(navigationReference) { "Capture visible navigation before checking its pixels" }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            check(bitmap.width == reference.width && bitmap.height == reference.height) {
                "Navigation pixel reference must remain on the same physical display and orientation"
            }
            return reference.pixels.count { point ->
                val pixel = bitmap.getPixel(point.x, point.y)
                val currentLevel = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                // Relative contrast at the identical glyph coordinates, calibrated
                // against this device's actual visible frame and black background.
                (currentLevel - reference.backgroundLevel) * 2 >= point.visibleLevel - reference.backgroundLevel
            }
        } finally { bitmap.recycle() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(requireNotNull(compose.activity.getExternalFilesDir(null)), screenshotRun)
            check(directory.mkdirs() || directory.isDirectory)
            val destination = File(directory, name)
            destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            android.util.Log.i("COCO_MEDIA_BARS_QA", "saved=$screenshotRun/$name")
        } finally { bitmap.recycle() }
    }

    private fun revealNavigationFromBottomEdge() {
        val bounds = instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            (bitmap.width to bitmap.height).also { bitmap.recycle() }
        }
        val start = SystemClock.uptimeMillis()
        val x = bounds.first / 2f
        val y = bounds.second - 2f
        for (step in 0..8) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 8 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y - step * 12f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (step < 8) SystemClock.sleep(30)
        }
    }
}
