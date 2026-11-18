package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.LayoutCoordinates
import com.messenger.app.ui.theme.CocoTheme
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production inset owner + production list modifier, using the device's real IME. */
@OptIn(ExperimentalLayoutApi::class)
class ChatKeyboardLiftInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class LiveRows {
        private val rows = ConcurrentHashMap<Int, LiveChatBounds>()
        operator fun set(id: Int, coordinates: LayoutCoordinates) {
            rows.getOrPut(id) { LiveChatBounds() }.coordinates = coordinates
        }
        fun getValue(id: Int): Rect = rows.getValue(id).bounds ?: Rect.Zero
        val entries get() = rows.mapValues { it.value.bounds ?: Rect.Zero }.entries
    }
    private val LiveChatBounds.top get() = bounds?.top ?: 0f
    private val LiveChatBounds.bottom get() = bounds?.bottom ?: 0f

    private fun waitForSurface(lift: ChatKeyboardLift, ready: () -> Boolean) {
        var previous = Int.MIN_VALUE
        var changedAt = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            val current = lift.pixels
            val now = android.os.SystemClock.uptimeMillis()
            if (current != previous || !ready()) { previous = current; changedAt = now }
            ready() && now - changedAt >= 100
        }
        compose.waitForIdle()
    }

    @Composable private fun memoryStore(): EmojiRecentsStore {
        val scope = rememberCoroutineScope()
        return remember(scope) { EmojiRecentsStore(null, null, scope) }
    }

    @Test fun everyInsetFramePreservesRowsAndDragOwnsTheReadingPosition() {
        val frame = mutableIntStateOf(0)
        val ids = mutableStateOf((0..99).toList())
        val targetFrame = mutableIntStateOf(0)
        val ime = object : WindowInsets {
            override fun getBottom(density: Density) = frame.intValue
            override fun getTop(density: Density) = 0
            override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
        }
        val target = object : WindowInsets {
            override fun getBottom(density: Density) = targetFrame.intValue
            override fun getTop(density: Density) = 0
            override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
        }
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        lateinit var scope: CoroutineScope
        val lift = ChatKeyboardLift()
        val rows = LiveRows()
        val composer = LiveChatBounds()
        var rowPasses = 0
        lateinit var store: EmojiRecentsStore
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        // Keep app preference hydration out of the synthetic frame/measurement probe.
        compose.setContent { androidx.compose.material3.MaterialTheme {
            list = rememberLazyListState(25)
            scope = rememberCoroutineScope()
            store = memoryStore()
            EmojiKeyboardHost(remember { EmojiInputState() }, false, {}, store = store, ime = ime, target = target,
                nav = WindowInsets(bottom = 24), lift = lift) {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize().chatTimelineLift(lift, list).testTag("frame-list"),
                        state = list, contentPadding = PaddingValues(bottom = 100.dp)) {
                        items(ids.value, key = { it }) { id ->
                            SideEffect { rowPasses++ }
                            Text("Message $id", Modifier.height(64.dp).fillMaxWidth()
                                .onGloballyPositioned { rows[id] = it })
                        }
                    }
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(60.dp)
                        .chatInputLift(lift)
                        .onGloballyPositioned { composer.coordinates = it })
                }
            }
        } }
        compose.waitUntil(5000) { store.state.value.loaded }
        waitForSurface(lift) { lift.pixels == 0 }
        val row = list.layoutInfo.visibleItemsInfo.last { rows.getValue(it.index).bottom < composer.top }.index
        val original = rows.getValue(row)
        val originalComposer = composer.bounds!!
        val index = list.firstVisibleItemIndex
        val offset = list.firstVisibleItemScrollOffset
        val composed = rowPasses
        val originalViewport = list.layoutInfo.viewportSize
        compose.runOnIdle { targetFrame.intValue = 700 }
        for (bottom in listOf(24, 80, 200, 400, 700, 400, 200, 80, 24, 0)) {
            compose.runOnIdle {
                if (bottom == 400 && frame.intValue == 700) targetFrame.intValue = 0
                frame.intValue = bottom
            }
            compose.waitForIdle()
            val expected = chatKeyboardLift(bottom, 24, 0).toFloat()
            assertEquals(original.top - expected, rows.getValue(row).top, .5f)
            assertEquals(originalComposer.top - expected, composer.top, .5f)
            assertEquals(index, list.firstVisibleItemIndex)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
            assertEquals("Viewport at IME bottom $bottom", originalViewport, list.layoutInfo.viewportSize)
            assertEquals("Row compositions at IME bottom $bottom", composed, rowPasses)
        }
        compose.runOnIdle { frame.intValue = 700 }
        compose.onNodeWithTag("frame-list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue(list.firstVisibleItemIndex != index || list.firstVisibleItemScrollOffset != offset)
        val draggedIndex = list.firstVisibleItemIndex
        val draggedOffset = list.firstVisibleItemScrollOffset
        compose.runOnIdle { frame.intValue = 0 }
        compose.waitForIdle()
        assertEquals(draggedIndex, list.firstVisibleItemIndex)
        assertEquals(draggedOffset, list.firstVisibleItemScrollOffset)
        // The extra visual top inset must also let a drag reveal the oldest row.
        compose.runOnIdle { scope.launch { list.scrollToItem(0) } }
        compose.waitUntil(5000) { !list.canScrollBackward }
        compose.waitForIdle()
        val oldestTop = rows.getValue(0).top
        compose.runOnIdle { frame.intValue = 700 }
        compose.onNodeWithTag("frame-list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(oldestTop, rows.getValue(0).top, .5f)
        // Prepending older history keeps the same key and visual reading position.
        compose.runOnIdle { ids.value = (-10..99).toList() }
        compose.waitUntil(5000) { list.firstVisibleItemIndex == 10 }
        compose.waitForIdle()
        assertEquals(oldestTop, rows.getValue(0).top, .5f)
        compose.runOnIdle { frame.intValue = 0 }
        compose.waitForIdle()
        assertEquals(oldestTop, rows.getValue(0).top, .5f)
    }

    @Test fun midHistoryMessageKeepsItsComposerGapAndReturnsAfterRealImeAndEmoji() {
        lateinit var lift: ChatKeyboardLift
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        val rows = LiveRows()
        val compositions = ConcurrentHashMap<Int, Int>()
        val composer = LiveChatBounds()
        var density = 1f
        lateinit var close: () -> Unit
        lateinit var imeSettled: () -> Boolean
        lateinit var imeHidden: () -> Boolean
        val emoji = EmojiInputState()
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.setContent { CocoTheme {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            val localDensity = androidx.compose.ui.platform.LocalDensity.current
            val ime = WindowInsets.ime
            val target = WindowInsets.imeAnimationTarget
            val nav = WindowInsets.navigationBars
            imeSettled = { ime.getBottom(localDensity) > nav.getBottom(localDensity) &&
                ime.getBottom(localDensity) == target.getBottom(localDensity) }
            imeHidden = { ime.getBottom(localDensity) == 0 && target.getBottom(localDensity) == 0 }
            lift = remember { ChatKeyboardLift() }
            list = rememberLazyListState(25)
            var focused by remember { mutableStateOf(false) }
            var text by remember { mutableStateOf("") }
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            close = { emoji.dismiss(); focus.clearFocus(force = true); keyboard?.hide() }
            EmojiKeyboardHost(emoji, focused, { close() }, store = memoryStore(), lift = lift) {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize().chatTimelineLift(lift, list).testTag("lift-list"), state = list,
                        contentPadding = PaddingValues(top = 80.dp, bottom = 100.dp)) {
                        items((0..99).toList(), key = { it }) { id ->
                            SideEffect { compositions.merge(id, 1, Int::plus) }
                            Text("Message $id", Modifier.fillMaxWidth().height(64.dp)
                                .onGloballyPositioned { rows[id] = it })
                        }
                    }
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(80.dp).background(Color.Gray))
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().chatInputLift(lift).navigationBarsPadding()
                        .onGloballyPositioned { composer.coordinates = it }) {
                        ComposerBar(input = text, submitting = false, keyboardVisible = focused, onInputChange = { text = it },
                            onFocusChanged = { focused = it }, onHideKeyboard = { close() }, isRecording = false,
                            onAttach = {}, onMicToggle = {}, onSend = {}, emojiInput = emoji)
                    }
                }
            }
        } }
        waitForSurface(lift) { lift.pixels == 0 && imeHidden() }
        val index = rows.entries.filter { it.value.bottom < composer.top - 4 * density }.maxBy { it.value.bottom }.key
        val before = rows.getValue(index)
        val gap = composer.top - before.bottom
        val scrollIndex = list.firstVisibleItemIndex
        val scrollOffset = list.firstVisibleItemScrollOffset
        val viewport = list.layoutInfo.viewportSize
        fun checkGap() = assertEquals(gap, composer.top - rows.getValue(index).bottom, 2 * density)
        fun checkWindow() {
            assertEquals(scrollIndex, list.firstVisibleItemIndex)
            assertEquals(scrollOffset, list.firstVisibleItemScrollOffset)
            assertEquals(viewport, list.layoutInfo.viewportSize)
        }
        compose.onNode(hasSetTextAction()).performClick()
        waitForSurface(lift) { imeSettled() }
        checkGap(); checkWindow()
        compose.onNodeWithTag("lift-list").performTouchInput { click(androidx.compose.ui.geometry.Offset(20f, height / 2f)) }
        waitForSurface(lift) { lift.pixels == 0 && imeHidden() }
        assertEquals(before.top, rows.getValue(index).top, 2 * density)
        checkGap(); checkWindow()
        // Open emoji directly from the closed composer, then hand the same space to IME.
        compose.onNodeWithTag("composer-emoji").performClick()
        waitForSurface(lift) { lift.pixels > 100 * density && emoji.showingEmoji && imeHidden() }
        compose.onNodeWithTag("emoji-keyboard").assertIsDisplayed()
        checkGap(); checkWindow()
        val emojiTop = rows.getValue(index).top
        compose.onNodeWithTag("composer-emoji").performClick()
        waitForSurface(lift) { emoji.mode == EmojiInputMode.System && imeSettled() }
        assertEquals(emojiTop, rows.getValue(index).top, 2 * density)
        checkGap(); checkWindow()
        val stableCompositions = compositions.getValue(index)
        compose.runOnIdle { close() }
        waitForSurface(lift) { lift.pixels == 0 && imeHidden() }
        assertEquals(before.top, rows.getValue(index).top, 2 * density)
        assertEquals(stableCompositions, compositions.getValue(index))
        checkGap(); checkWindow()
    }
}
