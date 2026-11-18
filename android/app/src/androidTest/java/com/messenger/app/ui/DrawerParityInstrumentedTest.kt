package com.messenger.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.theme.SavedSidebarStyle
import com.messenger.app.ui.theme.SidebarAppearanceSettings
import com.messenger.app.ui.theme.LocalResolvedSidebarStyle
import com.messenger.app.ui.theme.resolved
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.material3.*
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Device-free fixtures for phase 2: no account, network, animation or avatar decoder needed. */
class DrawerParityInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun shortcutPreferenceControlsHeaderActionInBothLayoutsWithoutAnExtraRow() {
        val shortcut = mutableStateOf(true)
        val centered = mutableStateOf(false)
        var edits = 0
        var qr = 0
        compose.setContent { CocoTheme {
            val style = SidebarAppearanceSettings(profileShowEdit = shortcut.value,
                profileLayout = if (centered.value) "center" else "compact").resolved
            CompositionLocalProvider(LocalResolvedSidebarStyle provides style, LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(181.dp, 320.dp)) {
                    SavedSidebarProfile("Boris QA", "boris_qa", style.metrics(402f), false,
                        { edits++ }, {}, { qr++ }) { size, fill, _ -> Box(Modifier.size(size).background(fill)) }
                }
            }
        } }
        for (center in listOf(false, true)) {
            compose.runOnIdle { centered.value = center; shortcut.value = true }
            compose.onNodeWithTag("saved-sidebar-profile-edit").performClick()
            compose.runOnIdle { shortcut.value = false }
            compose.onNodeWithTag("saved-sidebar-profile-edit").assertDoesNotExist()
            val identity = compose.onNodeWithTag("saved-sidebar-profile-identity").fetchSemanticsNode()
            assertFalse(identity.config.contains(SemanticsActions.OnClick))
            compose.onNodeWithTag("saved-sidebar-qr").performClick()
            compose.onNodeWithTag("saved-sidebar-profile-link").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(2, edits); assertEquals(2, qr) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun doneReturnsToClosedDrawerAfterQrPresentation() {
        lateinit var drawer: DrawerState
        compose.setContent { CocoTheme {
            drawer = rememberDrawerState(DrawerValue.Open)
            val rootScope = rememberCoroutineScope()
            var show by remember { mutableStateOf(false) }
            ModalNavigationDrawer(drawerState = drawer, drawerContent = { ModalDrawerSheet {
                TextButton(onClick = { rootScope.launch { closeDrawerThenShowQr({ drawer.snapTo(DrawerValue.Closed) }, { show = true }) } },
                    modifier = Modifier.testTag("open-profile-qr")) { Text("QR") }
            } }) { Box(Modifier.fillMaxSize()) }
            if (show) ProfileQrSheet("Boris QA", "boris_qa", {}, { show = false })
        } }
        compose.onNodeWithTag("open-profile-qr").performClick()
        compose.onNodeWithTag("profile-qr-sheet").assertExists()
        compose.runOnIdle { assertTrue(drawer.isClosed) }
        compose.onNodeWithTag("profile-qr-done").performClick()
        compose.onNodeWithTag("profile-qr-sheet").assertDoesNotExist()
        compose.runOnIdle { assertTrue(drawer.isClosed) }
    }

    @Test fun qrReplacesCloseAndProfileLinkWithoutCoveringLongNameAtTwoWidths() {
        val width = mutableStateOf(181.dp)
        var qr = 0
        var edits = 0
        compose.setContent { CocoTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.7f)) {
                val metrics = SavedSidebarStyle.metrics(width.value.value / .45f)
                Box(Modifier.requiredWidth(width.value).height(420.dp)) {
                    SavedSidebarProfile("A very long profile name", "long_username_123456", metrics, false,
                        { edits++ }, { error("QR must replace Close") }, { qr++ }) { size, fill, _ ->
                        Box(Modifier.size(size).background(fill))
                    }
                }
            }
        } }
        for (panel in listOf(181.dp, 156.dp)) {
            compose.runOnIdle { width.value = panel }
            val qrBounds = compose.onNodeWithTag("saved-sidebar-qr").assertHasClickAction().fetchSemanticsNode().boundsInRoot
            val nameBounds = compose.onNodeWithTag("saved-sidebar-name", true).fetchSemanticsNode().boundsInRoot
            assertTrue("QR target must clear the large-text name", nameBounds.top >= qrBounds.bottom)
            compose.onNodeWithTag("saved-sidebar-qr").performClick()
            compose.onNodeWithTag("saved-sidebar-profile-edit").performClick()
            compose.onNodeWithTag("saved-sidebar-close").assertDoesNotExist()
            compose.onNodeWithTag("saved-sidebar-profile-link").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(2, qr); assertEquals(2, edits) }
    }

    @Test fun selectionWrapsContentAndTransferCounterUpdatesWithoutChangingTheAction() {
        val count = mutableStateOf(0)
        var opens = 0
        val item = SavedSidebarStyle.Item("transfers", "Transfers", "arrows", 0xFFFFFFFF, true, 0, "main")
        compose.setContent { CocoTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredWidth(280.dp)) {
                    SavedSidebarRow(item, SavedSidebarStyle.metrics(660f), true, count.value) { opens++ }
                }
            }
        } }
        compose.onNodeWithTag("saved-sidebar-item-transfers").assertIsSelected().performClick()
        val row = compose.onNodeWithTag("saved-sidebar-item-transfers").fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithTag("saved-sidebar-chip-transfers", true).fetchSemanticsNode().boundsInRoot
        assertTrue("Selected chip must leave the trailing row clear", chip.width < row.width)
        compose.onNodeWithTag("saved-sidebar-transfer-badge", true).assertDoesNotExist()
        compose.runOnIdle { count.value = 4 }
        compose.onNodeWithTag("saved-sidebar-transfer-badge", true).assertTextEquals("4")
        compose.onNodeWithTag("saved-sidebar-item-transfers").performClick()
        compose.runOnIdle { assertEquals(2, opens); count.value = 0 }
        compose.onNodeWithTag("saved-sidebar-transfer-badge", true).assertDoesNotExist()
    }

    @Test fun statusMenuHasThreeRealChoicesAndClosesAfterSelection() {
        val status = mutableStateOf(DrawerPresence.ONLINE)
        val busy = mutableStateOf(false)
        var choices = 0
        compose.setContent { CocoTheme {
            DrawerPresenceControl(status.value, busy.value) { status.value = it; choices++ }
        } }
        compose.onNodeWithTag("drawer-status").performClick()
        compose.onNodeWithTag("drawer-status-ONLINE").assertIsSelected()
        compose.onNodeWithTag("drawer-status-INVISIBLE").performClick()
        compose.onNodeWithTag("drawer-status-menu").assertDoesNotExist()
        compose.onNodeWithTag("drawer-status").performClick()
        compose.onNodeWithTag("drawer-status-INVISIBLE").assertIsSelected()
        compose.onNodeWithTag("drawer-status-DO_NOT_DISTURB").performClick()
        compose.runOnIdle { assertEquals(2, choices); busy.value = true }
        compose.onNodeWithTag("drawer-status").assertIsNotEnabled()
    }

    @Test fun largeTextDrawerCanScrollAllTheWayToSignOut() {
        var exits = 0
        compose.setContent { CocoTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                Box(Modifier.requiredSize(181.dp, 460.dp)) {
                    GraphiteDrawer(181.dp, null, "Long profile name", "long_username", 0, false,
                        RootDestination.CHATS, 0, true, true, {}, {}, {}, {}, {}, {}, {}, {}, {}, { exits++ },
                        onQR = {}, profileControls = { DrawerPresenceControl(DrawerPresence.ONLINE, false) {} },
                        avatar = { size, fill, _ -> Box(Modifier.size(size).background(fill)) })
                }
            }
        } }
        compose.onNodeWithTag("saved-sidebar-logout").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("saved-sidebar-version", true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, exits) }
    }
}
