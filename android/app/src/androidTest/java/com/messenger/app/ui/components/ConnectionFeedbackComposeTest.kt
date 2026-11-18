package com.messenger.app.ui.components

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.messenger.app.ui.chats.ChatMotionSettings
import com.messenger.app.ui.chats.ChatNavigationButton
import com.messenger.app.ui.chats.LocalChatMotionSettings
import com.messenger.app.ui.chats.TypingMotionView
import com.messenger.app.ui.theme.CocoBrandHeader
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.chats.ContactChatMenu
import com.messenger.app.ui.chats.ContactChatAction
import com.messenger.app.ui.chats.ChatListModalCoverage
import com.messenger.app.ui.theme.CocoIcons
import androidx.compose.ui.graphics.Color
import com.messenger.app.R
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConnectionFeedbackComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun actualContactDialogStopsUnderlyingHeaderAndRestartsItOnDismiss() {
        val menu = mutableStateOf(false)
        val confirmation = mutableStateOf(false)
        val polls = AtomicInteger()
        compose.setContent { CocoTheme {
            CompositionLocalProvider(LocalChatMotionSettings provides ChatMotionSettings(active = true)) {
                Column {
                    Button(onClick = { menu.value = true }) { Text("Open contact menu") }
                    ChatConnectionHeaderContent(HeaderConnectionStatus.ONLINE, connected = true, busy = false,
                        showPing = true, visible = ChatListModalCoverage(contactMenu = menu.value,
                            deleteConfirmation = confirmation.value).headerVisible, onRetry = {}) { polls.incrementAndGet(); 96L }
                }
                if (menu.value) ContactChatMenu("Contact", {}, emptyList(), emptyList(), listOf(
                    ContactChatAction("Delete fixture", CocoIcons.Delete, Color.Red) { confirmation.value = true; menu.value = false }),
                    enabled = true, canAct = { true }, error = null, onDismiss = { menu.value = false })
                if (confirmation.value) AlertDialog(onDismissRequest = { confirmation.value = false },
                    title = { Text("Delete confirmation") }, confirmButton = {},
                    dismissButton = { TextButton(onClick = { confirmation.value = false }) { Text("Cancel deletion") } })
            }
        } }
        fun find(view: View): ConnectionUnderlineView? {
            if (view is ConnectionUnderlineView) return view
            if (view is ViewGroup) repeat(view.childCount) { find(view.getChildAt(it))?.let { found -> return found } }
            return null
        }
        compose.waitUntil { polls.get() == 1 }
        compose.onNodeWithText("Open contact menu").performClick()
        compose.runOnIdle {
            val strip = requireNotNull(find(compose.activity.window.decorView))
            assertTrue("Dialog keeps the underlying native view visible", strip.isShown)
            assertEquals(View.INVISIBLE, strip.getChildAt(1).visibility)
            assertEquals(1, polls.get())
        }
        compose.onNodeWithText("Delete fixture").performClick()
        compose.onNodeWithText("Delete confirmation").assertIsDisplayed()
        compose.runOnIdle {
            val strip = requireNotNull(find(compose.activity.window.decorView))
            assertTrue(strip.isShown)
            assertEquals(View.INVISIBLE, strip.getChildAt(1).visibility)
            assertEquals(1, polls.get())
        }
        compose.onNodeWithText("Cancel deletion").performClick()
        compose.waitUntil { polls.get() == 2 }
        compose.runOnIdle {
            val strip = requireNotNull(find(compose.activity.window.decorView))
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) assertEquals(View.VISIBLE, strip.getChildAt(1).visibility)
        }
    }

    @Test fun hiddenAndCalmStripsStopAndResumeWithoutReconfiguration() {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        // Children: 0 = base gradient, 1 = sweep. Only Sweep animates; Still and hidden views do no work.
        val mode = mutableStateOf(ConnectionStripMotion.Sweep)
        lateinit var strip: ConnectionUnderlineView
        compose.setContent {
            AndroidView(factory = { ConnectionUnderlineView(it).also { view -> strip = view } },
                update = { it.configure(0xFF32C9E9.toInt(), false, mode.value) }, modifier = Modifier.size(30.dp, 2.dp))
        }
        compose.runOnIdle {
            assertEquals(View.VISIBLE, strip.getChildAt(1).visibility)
            strip.visibility = View.INVISIBLE
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(View.INVISIBLE, strip.getChildAt(1).visibility)
            strip.visibility = View.VISIBLE
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(View.VISIBLE, strip.getChildAt(1).visibility)
            mode.value = ConnectionStripMotion.Still
        }
        compose.runOnIdle {
            assertEquals(View.INVISIBLE, strip.getChildAt(1).visibility)
            mode.value = ConnectionStripMotion.Sweep
        }
        compose.runOnIdle { assertEquals(View.VISIBLE, strip.getChildAt(1).visibility) }
    }

    @Test fun jumpHasOneActionAndReportsFullCountWhenBadgeIsCapped() {
        var calls = 0
        compose.setContent { CocoTheme {
            CompositionLocalProvider(LocalChatMotionSettings provides ChatMotionSettings(active = true, durationScale = 0f)) {
                ChatNavigationButton(count = 120, onClick = { calls++ })
            }
        } }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNode(hasClickAction()).assertContentDescriptionContains("120", substring = true).performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun hiddenTypingIndicatorsCancelAndReturnToStillEndpoints() {
        lateinit var typing: TypingMotionView
        compose.setContent {
            AndroidView(factory = { TypingMotionView(it, false).also { view -> typing = view } },
                update = { it.configure(0xFFB0B0B0.toInt(), false, 1f) }, modifier = Modifier.size(18.dp, 12.dp))
        }
        compose.runOnIdle { typing.visibility = View.INVISIBLE }
        compose.waitForIdle()
        compose.runOnIdle {
            repeat(typing.childCount) { index ->
                val dot = typing.getChildAt(index)
                assertEquals(0f, dot.translationY)
                assertEquals(1f, dot.scaleX)
                assertFalse(dot.hasTransientState())
            }
        }
    }

    @Test fun decorativeBrandDoesNotAdvertiseADeadButton() {
        compose.setContent { CocoTheme { CocoBrandHeader() } }
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }
}
