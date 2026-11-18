package com.messenger.app.ui.chats

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** No phones in B08. Compile this fixture here; the lead runs it in phase 2. */
class PdfReaderInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun accessibilityBridgeExposesPageTextWithoutAnOverridingDescription() {
        val sentence = "Unique readable freshwater PDF sentence"
        compose.setContent {
            Box(Modifier.size(300.dp).pdfPageAccessibility("Page 3 of 6", sentence, "Unavailable", true, emptyList()))
        }
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var found: android.view.accessibility.AccessibilityNodeInfo? = null
        try {
            compose.waitUntil(10000) {
                val root = automation.rootInActiveWindow
                val matches = root?.findAccessibilityNodeInfosByText(sentence).orEmpty()
                found = matches.firstOrNull { it.text?.contains(sentence) == true }
                matches.filter { it !== found }.forEach { it.recycle() }
                root?.recycle()
                found != null
            }
            val output = requireNotNull(found)
            assertTrue(output.text.contains("Page 3 of 6"))
            assertTrue(output.text.contains(sentence))
            assertTrue(output.contentDescription.isNullOrEmpty())
        } finally { found?.recycle() }
    }

    @Test fun signedZoomOffsetNormalizesAcrossPreviousPageAndClampsOnlyAtStart() {
        var scale by mutableFloatStateOf(1f)
        lateinit var list: LazyListState
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent {
            list = rememberLazyListState()
            val height = with(LocalDensity.current) { (600f * scale).toDp() }
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(with(LocalDensity.current) { 400.toDp() }).testTag("anchor-list")) {
                items(6) { index -> Box(Modifier.fillMaxWidth().height(height).testTag("anchor-page-$index")) }
            }
        }
        compose.onNodeWithTag("anchor-list").performScrollToIndex(3)
        compose.runOnIdle {
            scale = .5f
            list.requestScrollToItem(3, pdfZoomScrollOffset(0, 200f, .5f, 0f))
        }
        val top = compose.onNodeWithTag("anchor-page-3").getUnclippedBoundsInRoot().top -
            compose.onNodeWithTag("anchor-list").getUnclippedBoundsInRoot().top
        assertEquals(100f, top.value * density, 1f)
        compose.runOnIdle { assertEquals(2, list.firstVisibleItemIndex) }
        compose.runOnIdle { list.requestScrollToItem(0, -100) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, list.firstVisibleItemIndex); assertEquals(0, list.firstVisibleItemScrollOffset) }
    }

    @Test fun heldPinchUpdatesZoomOutBeforeEitherFingerIsReleased() {
        val file = document(6)
        var zoomed = false
        try {
            compose.setContent { CocoTheme { PdfReader(file, file.name, true, false, {}, { zoomed = it }, {}, {}) } }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("pdf-pages").fetchSemanticsNodes().isNotEmpty() }
            val page = compose.onNodeWithTag("pdf-pages")
            page.performTouchInput {
                val distance = width * .12f
                down(0, center - Offset(distance, 0f)); down(1, center + Offset(distance, 0f))
                moveTo(0, center - Offset(distance * 3, 0f))
                moveTo(1, center + Offset(distance * 3, 0f))
            }
            compose.runOnIdle { assertTrue(zoomed) }
            page.performTouchInput {
                val distance = width * .06f
                moveTo(0, center - Offset(distance, 0f)); moveTo(1, center + Offset(distance, 0f))
            }
            compose.runOnIdle { assertFalse(zoomed) } // both pointers remain down
            page.performTouchInput { up(0); up(1) }
        } finally { file.delete() }
    }

    private fun document(pages: Int): File {
        val file = File.createTempFile("b08-pdf-", ".pdf", compose.activity.cacheDir)
        val pdf = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(if (index % 2 == 0) 600 else 800, 900, index + 1).create())
                page.canvas.drawText("Freshwater Report — page ${index + 1} of $pages", 40f, 80f, Paint().apply { textSize = 22f })
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return file
    }

    @Test fun narrowChromeKeepsCloseReachableWhenShareIsUnavailable() {
        var closed = 0; var shared = 0
        compose.setContent { CocoTheme { Box(Modifier.width(320.dp).fillMaxSize()) {
            PdfReaderChrome("Contract signed with a very long document filename.pdf", 1, 6, true,
                { closed++ }, { shared++ }, shareEnabled = false)
        } } }
        val close = compose.onNodeWithContentDescription(compose.activity.getString(R.string.pdf_close))
        val share = compose.onNodeWithContentDescription(compose.activity.getString(R.string.pdf_share))
        share.assertIsNotEnabled()
        val filename = compose.onNodeWithTag("pdf-filename").getUnclippedBoundsInRoot()
        assertTrue(close.getUnclippedBoundsInRoot().right <= filename.left)
        assertTrue(filename.right <= share.getUnclippedBoundsInRoot().left)
        close.performClick()
        compose.runOnIdle { assertEquals(1, closed); assertEquals(0, shared) }
    }

    @Test fun continuousScrollUsesDocumentCounterAndChromeDoesNotResetPosition() {
        val file = document(6)
        var chrome by mutableStateOf(true)
        var closed = 0; var shared = 0
        try {
            compose.setContent { CocoTheme { Box(Modifier.fillMaxSize()) {
                PdfReader(file, "Contract signed.pdf", true, chrome, { chrome = it }, {}, { closed++ }, { shared++ })
            } } }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("pdf-pages").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pdf-pages").performScrollToIndex(3)
            compose.onNodeWithTag("pdf-page-3").assertExists()
            compose.onNodeWithTag("pdf-page-counter").assertTextEquals("4 / 6")
            val before = compose.onNodeWithTag("pdf-page-3").getUnclippedBoundsInRoot().top
            compose.runOnIdle { chrome = false }
            compose.onNodeWithTag("pdf-page-counter").assertDoesNotExist()
            compose.onNodeWithTag("pdf-page-3").assertExists()
            compose.runOnIdle { chrome = true }
            compose.onNodeWithTag("pdf-page-counter").assertTextEquals("4 / 6")
            assertEquals(before, compose.onNodeWithTag("pdf-page-3").getUnclippedBoundsInRoot().top)
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.pdf_share)).performClick()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.pdf_close)).performClick()
            compose.runOnIdle { assertEquals(1, shared); assertEquals(1, closed) }
        } finally { file.delete() }
    }

    @Test fun hiddenChromeRetainsAccessibleShowCloseShareAndZoomActions() {
        val file = document(1)
        var chrome by mutableStateOf(false)
        var shared = 0; var closed = 0
        try {
            compose.setContent { CocoTheme {
                PdfReader(file, "One page.pdf", true, chrome, { chrome = it }, {}, { closed++ }, { shared++ })
            } }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("pdf-page-0").fetchSemanticsNodes().isNotEmpty() }
            val labels = listOf(R.string.pdf_show_controls, R.string.pdf_share, R.string.pdf_close, R.string.pdf_zoom_in)
                .map { compose.activity.getString(it) }
            val node = compose.onNodeWithTag("pdf-page-0")
            val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            compose.runOnIdle {
                labels.forEach { label -> assertTrue(actions.any { it.label == label }) }
                actions.first { it.label == labels[3] }.action()
                actions.first { it.label == labels[1] }.action()
                actions.first { it.label == labels[2] }.action()
                actions.first { it.label == labels[0] }.action()
            }
            compose.onNodeWithTag("pdf-filename").assertExists()
            compose.onNodeWithTag("pdf-page-counter").assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, shared); assertEquals(1, closed) }
        } finally { file.delete() }
    }

    @Test fun corruptPdfHasStaticFailureAndWorkingShareAndClose() {
        val file = File.createTempFile("b08-corrupt-", ".pdf", compose.activity.cacheDir).apply { writeText("not a PDF") }
        var closed = 0; var shared = 0
        try {
            compose.setContent { CocoTheme { PdfReader(file, file.name, true, true, {}, {}, { closed++ }, { shared++ }) } }
            compose.waitUntil(10000) { compose.onAllNodesWithText(compose.activity.getString(R.string.pdf_open_error)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(compose.activity.getString(R.string.pdf_retry)).assertHasClickAction()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.pdf_share)).performClick()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.pdf_close)).performClick()
            compose.runOnIdle { assertEquals(1, shared); assertEquals(1, closed) }
        } finally { file.delete() }
    }

    @Test fun nativeTileRenderingIsBoundedAndClosingIsIdempotent() = runBlocking {
        val file = document(60)
        val cache = PdfPageCache.open(file)
        try {
            assertEquals(60, cache.pageCount)
            val size = cache.size(59)
            assertEquals(PdfPageSize(800, 900), size)
            val key = PdfTileKey(59, 4800, 4, 5)
            cache.setVisibleTiles(listOf(key))
            assertTrue(cache.render(key))
            var identity = 0
            cache.drawTiles(listOf(key)) { _, tile ->
                assertTrue(tile.allocationByteCount <= PDF_TILE_EDGE * PDF_TILE_EDGE * 4)
                identity = System.identityHashCode(tile)
            }
            assertTrue(cache.render(key))
            cache.drawTiles(listOf(key)) { _, tile -> assertEquals(identity, System.identityHashCode(tile)) }
            if (Build.VERSION.SDK_INT >= 35) assertTrue(cache.text(59).contains("Freshwater Report"))
        } finally { cache.close(); cache.close(); file.delete() }
    }

    @Test fun nativeResidentBytesStayBoundedWhileAHighResolutionPinchZoomsOut() = runBlocking {
        val file = document(6)
        val cache = PdfPageCache.open(file)
        try {
            val size = cache.firstSize
            for (scale in listOf(6f, 4f, 2f, 1f)) {
                val height = pdfRasterGeometry(1080, scale, 1080, size, 0f).height.toInt()
                val pages = List(2) { PdfPageViewport(it, size, it * (height + 24)) }
                val plan = pdfTilePlan(pages, 1080, 0, 2340, scale, 6f, 0f)
                cache.setVisibleTiles(plan.keys)
                for (key in plan.keys) {
                    assertTrue(cache.render(key))
                    assertTrue(cache.rasterBytes() <= PDF_BITMAP_BUDGET)
                }
                assertEquals(plan.bytes, cache.rasterBytes())
            }
        } finally { cache.close(); file.delete() }
    }
}
