package com.messenger.app.ui.chats

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoTheme
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compiled without devices in B06; executable regression fixtures for phase 2. */
class MediaViewerRegressionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pagingToADeferredFailedVideoPosterUsesItsEmbeddedThumbnailInsteadOfThePreviousPhoto() {
        val folder = File(compose.activity.cacheDir, "b06-preview-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val first = File(folder, "photo-a.png")
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
            first.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val corruptVideo = File(folder, "video-b.mp4").apply { writeText("unreadable video poster") }
        val thumbnail = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
            }
            android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
        }
        val items = listOf(MediaViewerItem(first, "image/png", "Photo A", "A"),
            MediaViewerItem(corruptVideo, "video/mp4", "Video B", "B", thumbnail))
        val shown = mutableStateOf(true)
        val workAllowed = mutableStateOf(true)
        val rendered = AtomicReference<Pair<String, Bitmap?>?>(null)
        compose.setContent { if (shown.value) CocoTheme {
            val pager = rememberPagerState(pageCount = { items.size })
            val item = items[pager.settledPage]
            // Same shared, settled-item preview host as the production dialog.
            val preview = rememberViewerItemPreview(item.id) {
                rememberMediaViewerPreview(item.file, item.mime, item.thumbnailBase64, workAllowed.value)
            }
            SideEffect { rendered.set(item.id to preview) }
            HorizontalPager(pager, key = { items[it].id }, modifier = Modifier.fillMaxSize().testTag("preview-pager")) { page ->
                Text(items[page].title)
            }
        } }
        try {
            compose.waitUntil(5_000) { rendered.get()?.let { it.first == "A" && it.second?.getPixel(0, 0) == android.graphics.Color.RED } == true }
            compose.runOnIdle { workAllowed.value = false }
            compose.onNodeWithTag("preview-pager").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { rendered.get()?.first == "B" }
            compose.runOnIdle { assertNull("A must not tint B or supply its closing image while poster work is deferred", rendered.get()!!.second) }
            compose.runOnIdle { workAllowed.value = true }
            compose.waitUntil(5_000) { rendered.get()?.let { it.first == "B" && it.second?.getPixel(0, 0) == android.graphics.Color.BLUE } == true }
        } finally {
            compose.runOnIdle { shown.value = false }; compose.waitForIdle()
            first.delete(); corruptVideo.delete(); folder.delete()
        }
    }

    @Test fun partialPhotoDragPublishesBackdropAndReturnGeometryWithoutARecompositionTrigger() {
        val (bitmap, file) = photo()
        val shown = mutableStateOf(true)
        val fractions = mutableListOf<Float>()
        val frames = mutableListOf<Rect?>()
        compose.setContent { if (shown.value) Box(Modifier.size(200.dp, 300.dp)) {
            ZoomImagePage(file, {}, {}, {}, bitmap, { fractions += it }, { frames += it })
        } }
        try {
            waitForDecodedPhoto()
            compose.onNodeWithTag("photo-gestures").performTouchInput {
                swipe(center, Offset(center.x, center.y + height * .07f), durationMillis = 600)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(fractions.any { it > 0f })
                assertEquals(0f, fractions.last(), .001f)
                assertTrue(frames.filterNotNull().any { it != frames.last() })
            }
        } finally { disposePhoto(shown, bitmap, file) }
    }

    @Test fun cancellingMarkupPreservesTheZoomPagingLock() {
        val (bitmap, file) = photo()
        val shown = mutableStateOf(true)
        val active = mutableStateOf(true)
        val zoomStates = mutableListOf<Boolean>()
        compose.setContent { if (shown.value) Box(Modifier.size(200.dp, 300.dp)) {
            ZoomImagePage(file, { zoomStates += it }, {}, {}, bitmap, active = active.value)
        } }
        try {
            waitForDecodedPhoto()
            val zoomIn = compose.onNodeWithTag("photo-gestures").fetchSemanticsNode().config[SemanticsActions.CustomActions]
                .first { it.label == compose.activity.getString(R.string.viewer_zoom_in) }
            compose.runOnIdle { assertTrue(zoomIn.action()) }
            compose.waitForIdle()
            compose.runOnIdle { assertTrue(zoomStates.last()); active.value = false }
            compose.waitForIdle()
            compose.runOnIdle { assertTrue("Markup covering the photo must not unlock paging", zoomStates.last()); active.value = true }
            compose.waitForIdle()
            compose.runOnIdle { assertTrue("Cancelling Markup must preserve the lock", zoomStates.last()) }
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            compose.runOnIdle { assertFalse("Leaving the actual page releases the lock", zoomStates.last()) }
        } finally { disposePhoto(shown, bitmap, file) }
    }

    @Test fun zoomedFlightKeepsTheCapturedSizeInsteadOfShrinkingToItsViewport() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val shown = mutableStateOf(true)
        compose.setContent { if (shown.value) BoxWithConstraints(Modifier.size(200.dp, 300.dp).testTag("flight-viewport")) {
            val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
            val fit = viewerFitRect(w, h, 32f, 32f)!!
            val captured = viewerTransformedRect(fit, w, h, 3f, 0f, 0f)
            MediaViewerFlight(bitmap, Rect(20f, 30f, 60f, 70f), captured, closing = true, progress = 1f, reduce = false)
        } }
        try {
            val viewport = compose.onNodeWithTag("flight-viewport").getUnclippedBoundsInRoot()
            val image = compose.onNodeWithTag("media-flight-image").getUnclippedBoundsInRoot()
            val viewportWidth = (viewport.right - viewport.left).value
            assertEquals(3f, (image.right - image.left).value / viewportWidth, .01f)
            assertEquals(3f, (image.bottom - image.top).value / viewportWidth, .01f)
            assertEquals(viewport.left.value - viewportWidth, image.left.value, .5f)
        } finally { compose.runOnIdle { shown.value = false }; compose.waitForIdle(); bitmap.recycle() }
    }

    @Test fun sourceFlightUsesTheSamePhysicalCoordinatesInLtrAndRtl() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val shown = mutableStateOf(true)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        compose.setContent { if (shown.value) CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
            Box(Modifier.size(200.dp, 300.dp).testTag("flight-viewport")) {
                val density = LocalDensity.current
                val source = with(density) { Rect(20.dp.toPx(), 80.dp.toPx(), 70.dp.toPx(), 130.dp.toPx()) }
                MediaViewerFlight(bitmap, source, null, closing = false, progress = 0f, reduce = false)
            }
        } }
        try {
            val ltr = compose.onNodeWithTag("media-flight-image").getUnclippedBoundsInRoot()
            val ltrViewport = compose.onNodeWithTag("flight-viewport").getUnclippedBoundsInRoot()
            compose.runOnIdle { direction.value = LayoutDirection.Rtl }
            compose.waitForIdle()
            val rtl = compose.onNodeWithTag("media-flight-image").getUnclippedBoundsInRoot()
            val rtlViewport = compose.onNodeWithTag("flight-viewport").getUnclippedBoundsInRoot()
            assertEquals((ltr.left - ltrViewport.left).value, (rtl.left - rtlViewport.left).value, .01f)
            assertEquals((ltr.top - ltrViewport.top).value, (rtl.top - rtlViewport.top).value, .01f)
            assertEquals(ltr.right - ltr.left, rtl.right - rtl.left)
            assertEquals(ltr.bottom - ltr.top, rtl.bottom - rtl.top)
        } finally { compose.runOnIdle { shown.value = false }; compose.waitForIdle(); bitmap.recycle() }
    }

    @Test fun unsupportedRecognitionHasNoScanAffordanceAndRetainsWorkingEditor() {
        var edits = 0
        compose.setContent { CocoTheme {
            MediaViewerPhotoToolbar(true, "Forward", {}, { edits++ }, onText = null, more = {})
        } }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.viewer_text_in_photo)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_markup)).performClick()
        compose.runOnIdle { assertEquals(1, edits) }
    }

    @Test fun ukrainianViewerMetadataAndActionLabelsDoNotFallBackToEnglish() {
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag("uk")) }
        val ukrainian = compose.activity.createConfigurationContext(configuration)
        compose.setContent { CompositionLocalProvider(LocalContext provides ukrainian, LocalConfiguration provides configuration) {
            CocoTheme { Column {
                MediaViewerHeader(stringResource(R.string.viewer_you), stringResource(R.string.viewer_today_at, "23:42"), 0, 2, {})
                MediaViewerPhotoToolbar(true, stringResource(R.string.viewer_forward_chat), {}, {}, onText = null, more = {
                    ViewerGlyphButton(MediaViewerIcons.Share, stringResource(R.string.viewer_share), onClick = {})
                })
            } }
        } }
        compose.onNodeWithTag("media-metadata").assertTextContains("Ви")
        compose.onNodeWithTag("media-metadata").assertTextContains("сьогодні о 23:42")
        compose.onNodeWithContentDescription("Переслати в чат").assertIsDisplayed()
        compose.onNodeWithContentDescription("Поділитися").assertIsDisplayed()
    }

    private fun photo(): Pair<Bitmap, File> {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        val file = File.createTempFile("b06-review-", ".png", compose.activity.cacheDir)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap to file
    }

    private fun waitForDecodedPhoto() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("photo-loaded-image").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun disposePhoto(shown: androidx.compose.runtime.MutableState<Boolean>, bitmap: Bitmap, file: File) {
        compose.runOnIdle { shown.value = false }; compose.waitForIdle(); bitmap.recycle(); file.delete()
    }
}
