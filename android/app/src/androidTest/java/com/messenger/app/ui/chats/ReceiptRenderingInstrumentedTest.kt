package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.messenger.app.domain.model.MessageStatus
import org.junit.Rule
import org.junit.Test

/** Renderer contracts only; no accounts, transport, or real messages are involved. */
class ReceiptRenderingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun eachStatusExposesItsLocalizedLabelInsteadOfGlyphText() {
        val status = mutableStateOf(MessageStatus.PENDING)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalChatMotionSettings provides ChatMotionSettings()) {
                    MessageReceipt(status.value, "message")
                }
            }
        }
        for (next in MessageStatus.entries) {
            compose.runOnIdle { status.value = next }
            compose.onNodeWithContentDescription(compose.activity.getString(MessageReceiptPolicy.label(next))).assertExists()
            compose.onNodeWithText(MessageReceiptPolicy.glyph(next)).assertDoesNotExist()
        }
    }

    @Test fun inlinePlaceholderIsNotSpokenAndStatusLabelUpdates() {
        val status = mutableStateOf(MessageStatus.DELIVERED)
        val body = "Hello 12:34  "
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalChatMotionSettings provides ChatMotionSettings()) {
                    InlineMessageReceiptText(AnnotatedString(body + ReceiptInkPolicy.doubleTick), body.length,
                        "message", status.value, MaterialTheme.typography.bodyMedium, Color.Black)
                }
            }
        }
        compose.onNodeWithText(body.trimEnd()).assertExists()
        compose.onNodeWithText(body + ReceiptInkPolicy.doubleTick).assertDoesNotExist()
        for (next in listOf(MessageStatus.DELIVERED, MessageStatus.READ)) {
            compose.runOnIdle { status.value = next }
            compose.onNodeWithContentDescription(compose.activity.getString(MessageReceiptPolicy.label(next))).assertExists()
        }
    }
}
