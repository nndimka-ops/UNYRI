package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before

/** Compile in phase 1; run with the keyboard/device matrix in phase 2. No account is needed. */
@OptIn(ExperimentalTestApi::class, ExperimentalLayoutApi::class)
class EmojiInputPanelInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun useProductionWindowInsets() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    }

    @Test fun selectionReplacementDeleteAndUndoUseTheRetainedEditor() {
        val state = EmojiInputState()
        val overlays = ComposerOverlayState()
        var draft = "Left tail"
        lateinit var textToolbar: androidx.compose.ui.platform.TextToolbar
        lateinit var imeSettled: () -> Boolean
        compose.setContent { CocoTheme {
            textToolbar = androidx.compose.ui.platform.LocalTextToolbar.current
            val density = androidx.compose.ui.platform.LocalDensity.current
            val ime = WindowInsets.ime
            val target = WindowInsets.imeAnimationTarget
            imeSettled = { ime.getBottom(density) > 0 && ime.getBottom(density) == target.getBottom(density) }
            var input by remember { mutableStateOf(draft) }
            var focused by remember { mutableStateOf(false) }
            EmojiKeyboardHost(state, focused, onDismiss = { focused = false }) {
                Box(Modifier.fillMaxSize().chatInputLift().navigationBarsPadding()) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        ComposerBar(input = input, submitting = false, keyboardVisible = focused,
                            onInputChange = { input = it; draft = it }, onFocusChanged = { focused = it },
                            onHideKeyboard = { state.dismiss() }, isRecording = false,
                            onAttach = {}, onMicToggle = {}, onSend = {}, emojiInput = state, overlayState = overlays)
                    }
                }
            }
        } }
        val editor = compose.onNode(hasSetTextAction())
        editor.performClick().performTextInputSelection(TextRange(5, 9))
        // This test exercises the retained edit session, independently of the
        // platform selection toolbar consuming an outside pointer tap.
        compose.runOnIdle { textToolbar.hide() }
        compose.waitUntil(10_000) { imeSettled() }
        compose.waitForIdle()
        compose.runOnIdle { textToolbar.hide() }
        // Invoke the same toggle action as accessibility while a native
        // selection toolbar may still be removing its popup window.
        compose.onNodeWithTag("composer-emoji").performSemanticsAction(SemanticsActions.OnClick) { it() }
        editor.assertIsFocused()
        compose.runOnIdle { assertEquals(EmojiInputMode.Emoji, state.mode) }
        compose.waitUntil(5000) { state.connection != null }
        compose.runOnIdle {
            assertTrue(composerCoveredForRead(overlays, state))
            assertFalse(ChatReadPolicy.readable(true, true, composerCoveredForRead(overlays, state)))
            assertFalse(mediaWorkAllowed(true, composerCoveredForRead(overlays, state), true))
        }
        editor.assertIsFocused()
        compose.onAllNodesWithText("😀").onLast().performClick()
        compose.runOnIdle { assertEquals("Left 😀", draft) }
        // Compose 1.7's legacy editor coalesces IME edits for five seconds.
        // Establish a separate undo snapshot; two immediate commands can
        // correctly undo as one group all the way back to "Left tail".
        val nextUndoGroup = System.currentTimeMillis() + 5_100
        compose.waitUntil(6_000) { System.currentTimeMillis() >= nextUndoGroup }
        editor.performTextInputSelection(TextRange(5, 7))
        compose.runOnIdle { textToolbar.hide() }
        compose.onNodeWithTag("emoji-keyboard-backspace").performClick()
        compose.runOnIdle { assertEquals("Left ", draft) }
        editor.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Z); keyUp(Key.CtrlLeft) }
        compose.runOnIdle { assertEquals("Left 😀", draft) }
        editor.performTextInputSelection(TextRange(7))
        compose.onNodeWithTag("composer-emoji").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("emoji-keyboard").assertDoesNotExist()
        editor.assertIsFocused().performTextInput("!")
        compose.runOnIdle { assertEquals("Left 😀!", draft) }
    }

    @Test fun markedTextCommitsThroughTheEditorAndBlurResetsThePanel() {
        val state = EmojiInputState()
        var draft = ""
        var playbacks = 0
        compose.setContent { CocoTheme {
            var input by remember { mutableStateOf("") }
            var focused by remember { mutableStateOf(false) }
            val focus = androidx.compose.ui.platform.LocalFocusManager.current
            EmojiKeyboardHost(state, focused, onDismiss = { focus.clearFocus() }) {
                Column(Modifier.fillMaxSize().chatInputLift().navigationBarsPadding(), verticalArrangement = Arrangement.Bottom) {
                    androidx.compose.material3.TextButton(onClick = {
                        state.chatAction({ focus.clearFocus() }, { playbacks++ })
                    }, Modifier.testTag("blur-input")) {
                        androidx.compose.material3.Text("Play voice")
                    }
                    ComposerBar(input = input, submitting = false, keyboardVisible = focused,
                        onInputChange = { input = it; draft = it }, onFocusChanged = { focused = it },
                        onHideKeyboard = { focus.clearFocus() }, isRecording = false,
                        onAttach = {}, onMicToggle = {}, onSend = {}, emojiInput = state)
                }
            }
        } }
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNodeWithTag("composer-emoji").performClick()
        compose.waitUntil(5000) { state.connection != null }
        compose.runOnIdle { state.connection!!.setComposingText("при", 1); state.insert("🇺🇦") }
        compose.runOnIdle { assertEquals("🇺🇦", draft) }
        // TalkBack calls semantics directly; no pointer event may be required to dismiss.
        compose.onNodeWithTag("blur-input").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("emoji-keyboard").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, playbacks) }
        compose.onNode(hasSetTextAction()).performClick()
        compose.runOnIdle { assertEquals(EmojiInputMode.System, state.mode); assertEquals("🇺🇦", draft) }
        compose.onNodeWithTag("composer-emoji").performClick()
        compose.onNodeWithTag("blur-input").performTouchInput { click(center) }
        compose.onNodeWithTag("emoji-keyboard").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, playbacks); assertEquals("🇺🇦", draft) }
    }

    @Test fun flagsInsertCompoundEmojiAndHeldDeleteStopsOnReleaseOrRemoval() {
        var chosen = ""
        var deletes = 0
        val visible = mutableStateOf(true)
        compose.setContent { CocoTheme {
            if (visible.value) EmojiInputPanel(onInsert = { chosen = it }, onBackspace = { deletes++ },
                modifier = Modifier.fillMaxWidth().height(336.dp))
        } }
        compose.onNodeWithTag("emoji-tab-flags").performClick()
        compose.onNodeWithText("🏳️‍🌈").performClick()
        compose.runOnIdle { assertEquals("🏳️‍🌈", chosen) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("emoji-keyboard-backspace").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("emoji-keyboard-backspace").performTouchInput { up() }
        compose.runOnIdle { assertTrue(deletes > 1) }
        val released = deletes
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(released, deletes) }
        compose.onNodeWithTag("emoji-keyboard-backspace").performTouchInput { down(center) }
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeBy(1000)
        val removed = deletes
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(removed, deletes) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun shortDockedImeKeepsComposerAndHistoryGeometryThroughBothTransitions() {
        val state = EmojiInputState().apply { focusChanged(true) }
        val overlays = ComposerOverlayState()
        val ime = mutableIntStateOf(180)
        val target = mutableIntStateOf(180)
        compose.setContent { CocoTheme {
            val density = androidx.compose.ui.platform.LocalDensity.current
            Box(Modifier.fillMaxWidth().height(640.dp)) {
                EmojiKeyboardHost(state, true, {},
                    ime = WindowInsets(bottom = with(density) { ime.intValue.dp.roundToPx() }),
                    target = WindowInsets(bottom = with(density) { target.intValue.dp.roundToPx() }),
                    nav = WindowInsets(bottom = with(density) { 24.dp.roundToPx() })) {
                    Box(Modifier.fillMaxSize().chatInputLift().navigationBarsPadding()) {
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(60.dp).testTag("composer-geometry"))
                        Box(Modifier.align(Alignment.BottomCenter).offset(y = (-113).dp).height(30.dp)
                            .fillMaxWidth().testTag("history-geometry"))
                    }
                }
            }
        } }
        val composer = compose.onNodeWithTag("composer-geometry").fetchSemanticsNode().boundsInRoot
        val history = compose.onNodeWithTag("history-geometry").fetchSemanticsNode().boundsInRoot
        fun check() {
            assertEquals(composer, compose.onNodeWithTag("composer-geometry").fetchSemanticsNode().boundsInRoot)
            assertEquals(history, compose.onNodeWithTag("history-geometry").fetchSemanticsNode().boundsInRoot)
        }
        compose.runOnIdle { overlays.toggleEmoji(state) }
        check()
        compose.runOnIdle { target.intValue = 0 }
        for (height in listOf(120, 60, 0)) { compose.runOnIdle { ime.intValue = height }; check() }
        compose.runOnIdle { overlays.toggleEmoji(state); target.intValue = 180 }
        for (height in listOf(0, 60, 120, 180)) { compose.runOnIdle { ime.intValue = height }; check() }
        compose.runOnIdle { assertEquals(EmojiInputMode.System, state.mode) }
    }

    @Test fun coldSavedHeightIsLoadedBeforeEmojiFirstReservesItsFootprint() {
        val state = EmojiInputState().apply { focusChanged(true) }
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        lateinit var store: EmojiRecentsStore
        var heightPixels = 0f
        compose.setContent { CocoTheme {
            val scope = rememberCoroutineScope()
            val density = androidx.compose.ui.platform.LocalDensity.current
            heightPixels = with(density) { 180.dp.toPx() }
            store = remember {
                EmojiRecentsStore(EmojiOwner("test", "https://one.example", "alice", 1),
                    object : EmojiRecentsStorage {
                        override suspend fun read(owner: EmojiOwner): EmojiSaved {
                            release.await(); return EmojiSaved(listOf("🇺🇦"), 180f)
                        }
                        override suspend fun write(owner: EmojiOwner, saved: EmojiSaved) = Unit
                    }, scope)
            }
            Box(Modifier.fillMaxWidth().height(640.dp)) {
                EmojiKeyboardHost(state, true, {}, store = store,
                    ime = WindowInsets(0, 0, 0, 0), target = WindowInsets(0, 0, 0, 0),
                    nav = WindowInsets(0, 0, 0, 0)) {
                    Box(Modifier.fillMaxSize().chatInputLift().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().height(60.dp).testTag("cache-composer"))
                    }
                }
            }
        } }
        val before = compose.onNodeWithTag("cache-composer").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.toggle() }
        compose.onNodeWithTag("emoji-keyboard").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag("cache-composer").fetchSemanticsNode().boundsInRoot)
        release.complete(Unit)
        compose.waitUntil(5000) { store.state.value.loaded }
        compose.onNodeWithTag("emoji-keyboard").assertIsDisplayed()
        val after = compose.onNodeWithTag("cache-composer").fetchSemanticsNode().boundsInRoot
        assertEquals(before.bottom - heightPixels, after.bottom, .5f)
        compose.runOnIdle { }
        assertEquals(after, compose.onNodeWithTag("cache-composer").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun actualBackDispatchClosesEmojiAndNextFocusUsesSystemKeyboard() {
        val state = EmojiInputState()
        compose.setContent { CocoTheme {
            var input by remember { mutableStateOf("Keep draft") }
            var focused by remember { mutableStateOf(false) }
            val focus = androidx.compose.ui.platform.LocalFocusManager.current
            EmojiKeyboardHost(state, focused, { focus.clearFocus() }) {
                Box(Modifier.fillMaxSize().chatInputLift().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                    ComposerBar(input = input, submitting = false, keyboardVisible = focused,
                        onInputChange = { input = it }, onFocusChanged = { focused = it },
                        onHideKeyboard = { focus.clearFocus() }, isRecording = false,
                        onAttach = {}, onMicToggle = {}, onSend = {}, emojiInput = state)
                }
            }
        } }
        val editor = compose.onNode(hasSetTextAction())
        editor.performClick()
        compose.onNodeWithTag("composer-emoji").performClick()
        compose.onNodeWithTag("emoji-keyboard").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("emoji-keyboard").assertDoesNotExist()
        editor.assertIsNotFocused().assertTextEquals("Keep draft").performClick()
        compose.runOnIdle { assertEquals(EmojiInputMode.System, state.mode) }
    }

    @Test fun backspaceDeletesWholeFlagsSkinToneZwjAndCombiningGraphemes() {
        val state = EmojiInputState()
        var draft = ""
        compose.setContent { CocoTheme {
            var input by remember { mutableStateOf("") }
            var focused by remember { mutableStateOf(false) }
            val focus = androidx.compose.ui.platform.LocalFocusManager.current
            EmojiKeyboardHost(state, focused, { focus.clearFocus() }) {
                Box(Modifier.fillMaxSize().chatInputLift().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                    ComposerBar(input = input, submitting = false, keyboardVisible = focused,
                        onInputChange = { input = it; draft = it }, onFocusChanged = { focused = it },
                        onHideKeyboard = { focus.clearFocus() }, isRecording = false,
                        onAttach = {}, onMicToggle = {}, onSend = {}, emojiInput = state)
                }
            }
        } }
        val editor = compose.onNode(hasSetTextAction())
        editor.performClick()
        compose.onNodeWithTag("composer-emoji").performClick()
        compose.waitUntil(5000) { state.connection != null }
        for (grapheme in listOf("🇺🇦", "👩🏽‍💻", "e\u0301", "🏳️‍🌈", "👨‍👩‍👧‍👦")) {
            val text = "prefix $grapheme"
            editor.performTextReplacement(text)
            editor.performTextInputSelection(TextRange(text.length))
            compose.onNodeWithTag("emoji-keyboard-backspace").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.runOnIdle { assertEquals("Grapheme $grapheme", "prefix ", draft) }
        }
        editor.performTextReplacement("prefix 🇺🇦 tail")
        editor.performTextInputSelection(TextRange(7, 11))
        compose.onNodeWithTag("emoji-keyboard-backspace").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("prefix  tail", draft) }
    }
}
