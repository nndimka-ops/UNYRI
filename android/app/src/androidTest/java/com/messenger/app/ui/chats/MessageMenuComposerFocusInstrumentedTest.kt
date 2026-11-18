package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.messenger.app.R
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.ui.theme.CocoTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real menu and composer; fake actions and keyboard recorder avoid account/network dependencies. */
class MessageMenuComposerFocusInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun replyRestoresFocusAndKeepsTheDraft() = checkHandoff(Action.Reply, initiallyFocused = true)
    @Test fun editRestoresFocusOnTheSelectedText() = checkHandoff(Action.Edit, initiallyFocused = true)
    @Test fun replyAlsoFocusesAnInitiallyUnfocusedInput() = checkHandoff(Action.Reply, initiallyFocused = false)
    @Test fun cancellingTheMenuDoesNotRefocusTheInput() = checkHandoff(Action.Cancel, initiallyFocused = true)

    private enum class Action { Reply, Edit, Cancel }

    private fun checkHandoff(action: Action, initiallyFocused: Boolean) {
        val open = mutableStateOf(false)
        val keyboardShows = AtomicInteger()
        var shownWhileMenuOpen = false
        var hostFocusedAtShow = false
        var replies = 0
        var edits = 0
        val draft = "An unfinished draft"
        val fixture = Message("focus-client", "focus-server", "focus-chat", "me", "Selected message text",
            1, "2026-11-13T10:00:00Z", MessageStatus.SENT)
        val keyboard = object : SoftwareKeyboardController {
            override fun hide() = Unit
            override fun show() {
                shownWhileMenuOpen = open.value
                hostFocusedAtShow = compose.activity.window.decorView.hasWindowFocus()
                keyboardShows.incrementAndGet()
            }
        }
        compose.setContent { CocoTheme {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                val focus = rememberMessageMenuComposerFocus("focus-fixture", if (open.value) "fixture-message" else null)
                var input by remember { mutableStateOf(draft) }
                var editing by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    Button(onClick = { open.value = true }, modifier = Modifier.testTag("open-focus-menu")) { Text("Open menu") }
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        ComposerBar(editing = editing, keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {},
                            menuFocus = focus, input = input, submitting = false, onInputChange = { input = it },
                            isRecording = false, onAttach = {}, onMicToggle = {}, onSend = {})
                    }
                }
                if (open.value) MessageActionsSheet(Rect(0f, 120f, 300f, 220f), null, fixture,
                    isOwnMessage = true, hasAttachment = false, isPinned = false,
                    onDismiss = { open.value = false },
                    onReply = { focus.choose({ replies++ }, { open.value = false }) },
                    onEdit = { focus.choose({ edits++; editing = true; input = fixture.text }, { open.value = false }) },
                    onSelect = {}, onPin = {}, onRemind = {}, onCopy = {}, onForward = {}, onSave = {},
                    onDeleteForMe = {}, onDeleteForEveryone = {}, onReact = {})
            }
        } }
        if (initiallyFocused) compose.onNode(hasSetTextAction()).performClick().assertIsFocused()
        compose.onNodeWithTag("open-focus-menu").performClick()
        compose.onNodeWithTag("message-menu-group").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle { keyboardShows.set(0) }

        when (action) {
            Action.Reply -> compose.onNodeWithText(compose.activity.getString(R.string.reply)).performScrollTo().performClick()
            Action.Edit -> compose.onNodeWithText(compose.activity.getString(R.string.message_menu_edit)).performScrollTo().performClick()
            Action.Cancel -> compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        }
        compose.onNodeWithTag("message-menu-group").assertDoesNotExist()
        if (action == Action.Cancel) {
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            compose.runOnIdle { assertEquals(0, keyboardShows.get()); assertEquals(0, replies + edits) }
            return
        }
        compose.waitUntil(5_000) { keyboardShows.get() > 0 }
        val expected = if (action == Action.Edit) fixture.text else draft
        compose.onNode(hasSetTextAction()).assertIsFocused().assertTextEquals(expected).performTextInput(" continued")
        compose.onNode(hasSetTextAction()).assertTextEquals(expected + " continued")
        compose.runOnIdle {
            assertEquals(1, keyboardShows.get())
            assertFalse(shownWhileMenuOpen)
            assertTrue(hostFocusedAtShow)
            assertEquals(if (action == Action.Reply) 1 else 0, replies)
            assertEquals(if (action == Action.Edit) 1 else 0, edits)
        }
    }
}
