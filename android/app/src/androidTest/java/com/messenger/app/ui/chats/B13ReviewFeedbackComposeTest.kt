package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.messenger.app.domain.model.Message
import com.messenger.app.domain.model.MessageStatus
import com.messenger.app.domain.model.ReactionSummary
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.messenger.app.ui.theme.CocoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class B13ReviewFeedbackComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun normalTapExplainsHoldWhileTalkBackRetainsLockedRecordingAndDraftHidesHint() {
        val draft = mutableStateOf("")
        val accessible = mutableStateOf(false)
        var locked = 0
        compose.setContent { CocoTheme {
            CompositionLocalProvider(LocalChatMotionSettings provides ChatMotionSettings(
                active = true, durationScale = 0f, touchExploration = accessible.value)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    ComposerBar(keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {},
                        input = draft.value, submitting = false, onInputChange = { draft.value = it },
                        isRecording = false, onAttach = {}, onMicToggle = { locked++ }, onSend = {})
                }
            }
        } }
        val originalBounds = compose.onNodeWithTag("composer-input-row").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("composer-record").performTouchInput { click() }
        compose.onNodeWithTag("voice-hold-hint").assertIsDisplayed()
        assertEquals(originalBounds, compose.onNodeWithTag("composer-input-row").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertEquals(0, locked); draft.value = "draft" }
        compose.onNodeWithTag("voice-hold-hint").assertDoesNotExist()
        compose.runOnIdle { draft.value = ""; accessible.value = true }
        compose.onNodeWithTag("composer-record").performClick()
        compose.runOnIdle { assertEquals(1, locked) }
        compose.onNodeWithTag("voice-hold-hint").assertDoesNotExist()
    }

    @Test fun keyboardAndSemanticRecordingWorkWithoutTouchExploration() {
        var locked = 0
        val enabled = mutableStateOf(true)
        lateinit var inputMode: InputModeManager
        compose.setContent { CocoTheme {
            val manager = LocalInputModeManager.current
            SideEffect { inputMode = manager }
            CompositionLocalProvider(LocalChatMotionSettings provides ChatMotionSettings(active = true, durationScale = 0f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    ComposerBar(keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {}, input = "",
                        submitting = false, onInputChange = {}, isRecording = false, voiceActionEnabled = enabled.value,
                        onAttach = {}, onMicToggle = { locked++ }, onSend = {})
                }
            }
        } }
        val record = compose.onNodeWithTag("composer-record")
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        record.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        record.assertIsFocused()
        for (key in listOf(Key.Enter, Key.Spacebar, Key.NumPadEnter, Key.DirectionCenter)) {
            record.performKeyInput { pressKey(key) }
        }
        compose.runOnIdle { assertEquals(4, locked) }
        record.performClick()
        compose.runOnIdle { assertEquals(5, locked); enabled.value = false }
        record.assertIsNotEnabled()
        record.performSemanticsAction(SemanticsActions.OnClick) { assertFalse(it()) }
        compose.runOnIdle { assertEquals(5, locked) }
        compose.onNodeWithTag("voice-hold-hint").assertDoesNotExist()
    }

    @Test fun albumMemberRemovalUsesThatMembersOwnedReaction() {
        val events = mutableListOf<String>()
        val members = listOf(
            Message("a", "sa", "chat", "peer", "", 1, "2026-11-13T00:00:00Z", MessageStatus.READ,
                albumId = "album", reactions = listOf(ReactionSummary("👍", 1, true))),
            Message("b", "sb", "chat", "peer", "", 2, "2026-11-13T00:00:00Z", MessageStatus.READ,
                albumId = "album", reactions = listOf(ReactionSummary("❤️", 1, true))))
        compose.setContent { CocoTheme {
            CompositionLocalProvider(LocalChatMotionSettings provides ChatMotionSettings(active = true, durationScale = 0f)) {
                CapsuleChatAlbum(members, outgoing = false, onReaction = { member, emoji ->
                    dispatchReactionToggle(member.reactions, emoji, { events += "add" }, { events += "remove:${member.clientMessageId}" },
                        { events += "intent" }, { events += "toggle:${member.clientMessageId}" })
                }) { Box(Modifier.fillMaxSize()) }
            }
        } }
        for (emoji in listOf("👍", "❤️")) compose.onNode(hasText(emoji) and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(listOf("remove:a", "toggle:a", "remove:b", "toggle:b"), events) }
    }

    @Test fun messageMenuRemovalUsesSharedFeedbackAndDoesNotExpectAdd() {
        val events = mutableListOf<String>()
        val open = mutableStateOf(true)
        val message = Message("menu", "sm", "chat", "me", "Menu fixture", 1, "2026-11-13T00:00:00Z", MessageStatus.READ,
            reactions = listOf(ReactionSummary("👍", 1, true)))
        compose.setContent { CocoTheme {
            if (open.value) MessageActionsSheet(Rect(0f, 150f, 320f, 450f), null, message,
                isOwnMessage = true, hasAttachment = false, isPinned = false, onDismiss = { open.value = false },
                onReply = {}, onSelect = {}, onPin = {}, onRemind = {}, onCopy = {}, onEdit = {}, onForward = {}, onSave = {},
                onDeleteForMe = {}, onDeleteForEveryone = {}, onReact = { emoji ->
                    dispatchReactionToggle(message.reactions, emoji, { events += "add" }, { events += "remove" },
                        { events += "intent" }, { events += "toggle" })
                })
        } }
        compose.onNodeWithContentDescription(compose.activity.getString(com.messenger.app.R.string.react_with, "👍"))
            .assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(listOf("remove", "toggle"), events); assertFalse(open.value) }
    }

    @Test fun holdReleaseCannotAlsoShowTapGuidanceOrStartLockedRecording() {
        var starts = 0; var releases = 0; var locked = 0
        compose.setContent { CocoTheme {
            CompositionLocalProvider(LocalChatMotionSettings provides ChatMotionSettings(active = true, durationScale = 0f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    ComposerBar(keyboardVisible = false, onFocusChanged = {}, onHideKeyboard = {}, input = "",
                        submitting = false, onInputChange = {}, isRecording = false, onAttach = {},
                        onVoiceHold = { starts++ }, onVoiceRelease = { releases++ }, onMicToggle = { locked++ }, onSend = {})
                }
            }
        } }
        compose.onNodeWithTag("composer-record").performTouchInput { longClick() }
        compose.onNodeWithTag("composer-record").performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(1, releases); assertEquals(0, locked) }
        compose.onNodeWithTag("voice-hold-hint").assertDoesNotExist()
    }

    @Test fun jumpExitRetainsButtonUntilFinishedAndCalmModesSettleImmediately() {
        compose.mainClock.autoAdvance = false
        val visible = mutableStateOf(true)
        val power = mutableStateOf(false)
        compose.setContent { CocoTheme {
            CompositionLocalProvider(LocalChatMotionSettings provides ChatMotionSettings(active = true, lowPower = power.value)) {
                NewMessagesPill(1, visible.value) {}
            }
        } }
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(90)
        compose.onNode(hasClickAction()).assertExists()
        compose.mainClock.advanceTimeBy(150)
        compose.onNode(hasClickAction()).assertDoesNotExist()
        compose.runOnIdle { power.value = true; visible.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(hasClickAction()).assertExists()
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(hasClickAction()).assertDoesNotExist()
    }
}
