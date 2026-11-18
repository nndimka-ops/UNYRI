package com.messenger.app.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.theme.LocalCocoColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ContactChatMenuInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun archiveAndRestoreUseContextualLabelsWithoutReplacingTheirHandlers() {
        val archived = mutableStateOf(false)
        var calls = 0
        compose.setContent { CocoTheme {
            val colors = LocalCocoColors.current
            ContactChatMenu("Аня QA", avatar = { Text("А") }, quick = emptyList(),
                rows = listOf(ContactChatAction("Legacy archive title",
                    if (archived.value) CocoIcons.RestoreChat else CocoIcons.Archive, colors.primaryText) {
                    calls++; archived.value = !archived.value
                }), danger = emptyList(), enabled = true, canAct = { true }, error = null, onDismiss = {})
        } }
        compose.onNodeWithText("Legacy archive title").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_menu_archive)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_menu_restore)).performClick()
        compose.runOnIdle { assertEquals(2, calls); assertEquals(false, archived.value) }
    }

    @Test fun busyAndStaleOwnerCannotDispatchAndErrorsRemainVisible() {
        val enabled = mutableStateOf(false)
        var currentOwner = false
        var calls = 0
        compose.setContent { CocoTheme {
            val colors = LocalCocoColors.current
            ContactChatMenu("Аня QA", avatar = { Text("А") },
                quick = listOf(ContactChatAction("Open", CocoIcons.Chat, colors.primaryText) { calls++ }),
                rows = listOf(ContactChatAction("Report", CocoIcons.ReportFlag, colors.primaryText) { calls++ }),
                danger = listOf(ContactChatAction("Block", CocoIcons.Security, colors.danger) { calls++ }),
                enabled = enabled.value, canAct = { currentOwner }, error = "Request failed", onDismiss = {})
        } }
        compose.onNodeWithText("Open").assertIsNotEnabled()
        compose.onNodeWithText("Request failed").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText("Open").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, calls); currentOwner = true }
        compose.onNodeWithText("Open").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    /** Populated S10 fixture also exercises the exact contextual Copy label and ordered rows. */
    @Test fun contactFixtureHasTheObservedOrderAndCopyDispatchesOnce() {
        var copies = 0
        compose.setContent { CocoTheme {
            val colors = LocalCocoColors.current
            fun action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) = ContactChatAction(label, icon, colors.primaryText) {}
            ContactChatMenu("Аня QA", avatar = { Text("А") },
                quick = listOf(action("Open", CocoIcons.Chat), action("Pin", CocoIcons.Pin), action("Mute", CocoIcons.MutedBell)),
                rows = listOf(action("Archive", CocoIcons.Archive), ContactChatAction("Legacy copy label", CocoIcons.Copy, colors.primaryText) { copies++ }, action("Report", CocoIcons.ReportFlag)),
                danger = listOf(ContactChatAction("Block", CocoIcons.Security, colors.danger) {}, ContactChatAction("Delete", CocoIcons.Delete, colors.danger) {}),
                enabled = true, canAct = { true }, error = null, onDismiss = {})
        } }
        val copy = compose.activity.getString(R.string.chat_menu_copy_name)
        val archive = compose.activity.getString(R.string.chat_menu_archive)
        compose.onNodeWithText("Legacy copy label").assertDoesNotExist()
        val nodes = listOf("Open", "Pin", "Mute", archive, copy, "Report", "Block", "Delete")
        val top = nodes.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(top.sorted(), top)
        compose.onNodeWithText(copy).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, copies) }
    }
}
