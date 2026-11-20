package com.messenger.app.conference

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.AuthUser
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.ui.theme.CocoTheme
import com.messenger.app.ui.GraphiteDrawer
import com.messenger.app.ui.RootDestination
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production Compose views/controller, synthetic tile STATE only.
 * No Room/track/camera/microphone/connect call, no account or network mutation.
 * These tests validate layout/navigation, never thirty physical video decoders.
 */
@RunWith(AndroidJUnit4::class)
class ConferencePresentationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val requests = AtomicInteger()

    @Test fun focusDoesNotRecreateTheGridNativeView() {
        var focused by mutableStateOf(false)
        val factories = AtomicInteger()
        val disposed = AtomicInteger()
        compose.setContent {
            CocoTheme {
                ConferenceVideoStage(focused, grid = {
                    DisposableEffect(Unit) { onDispose { disposed.incrementAndGet() } }
                    androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                        factories.incrementAndGet(); android.view.View(context)
                    }, modifier = Modifier.fillMaxSize())
                }, focus = { Text("Focused participant") })
            }
        }
        compose.waitForIdle()
        repeat(5) {
            compose.runOnIdle { focused = true }
            compose.onNodeWithText("Focused participant").assertIsDisplayed()
            compose.runOnIdle { focused = false }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals(1, factories.get()); assertEquals(0, disposed.get()) }
    }

    @Test fun participantSheetAndFocusRetainOnlyTheCurrentVideoPage() {
        val controller = fixture()
        show(controller)
        val visibleField = ConferenceController::class.java.getDeclaredField("visible").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") fun demand(): Set<String> = (visibleField.get(controller) as Set<String>).toSet()
        compose.waitUntil(5_000) { demand().isNotEmpty() }
        var before = emptySet<String>()
        compose.runOnIdle { before = demand(); assertTrue(before.size <= 6) }
        compose.onNodeWithTag("conference_participants").performClick()
        compose.onNodeWithTag("conference_participant_directory").assertIsDisplayed()
        compose.runOnIdle { assertEquals(before, demand()) }
        val target = before.sorted().first()
        compose.onNode(hasText("Fixture " + target.removePrefix("remote-")) and
            hasAnyAncestor(hasTestTag("conference_participant_directory"))).performClick()
        compose.onNodeWithTag("conference_back_to_grid").assertIsDisplayed()
        compose.runOnIdle { assertEquals(before, demand()) }
        compose.onNodeWithTag("conference_back_to_grid").performClick()
        compose.runOnIdle { assertEquals(before, demand()) }
    }

    @Test fun outgoingInvitationStaysNarrowWithoutInlineShareAtBothTextSizes() {
        var scale by mutableStateOf(1f)
        compose.setContent { CocoTheme {
            val density = LocalDensity.current
            val colors = com.messenger.app.ui.theme.LocalCocoColors.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.primaryText) {
                    Box(Modifier.padding(16.dp)) {
                        androidx.compose.material3.Surface(color = colors.surfaceVariant, contentColor = colors.primaryText) {
                            ConferenceOutgoingInvitationCard("Evening room", "Room invitation")
                        }
                    }
                }
            }
        } }
        val cardHeight = compose.onNodeWithTag("conference_outgoing_invitation").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Outgoing invitation must stay compact", cardHeight / compose.activity.resources.displayMetrics.density <= 72.5f)
        val cardWidth = compose.onNodeWithTag("conference_outgoing_invitation").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Invitation must not stretch across the chat", cardWidth / compose.activity.resources.displayMetrics.density <= 184.5f)
        compose.onNodeWithTag("conference_invitation_share").assertDoesNotExist()
        compose.onNodeWithText("Room invitation").assertIsDisplayed()
        screenshot("conference-outgoing-invitation-compact.png")
        compose.runOnIdle { scale = 2f }
        compose.onNodeWithTag("conference_invitation_share").assertDoesNotExist()
        compose.onNodeWithText("Evening room").assertIsDisplayed()
        compose.onNodeWithText("Room invitation").assertIsDisplayed()
        screenshot("conference-outgoing-invitation-large-text.png")
    }

    @Test fun incomingInvitationUsesFullWidthTextAndASeparateButtonRowAtEveryTextSize() {
        var scale by mutableStateOf(1f)
        var error by mutableStateOf<String?>(null)
        var enabled by mutableStateOf(true)
        var joined = 0
        compose.setContent { CocoTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Box(Modifier.width(280.dp).padding(12.dp)) {
                    ConferenceIncomingInvitationCard("Вечерняя комната", "Приглашение в комнату", error, enabled) { joined++ }
                }
            }
        } }
        val density = compose.activity.resources.displayMetrics.density
        val card = compose.onNodeWithTag("invite-card")
        assertEquals(256f, card.fetchSemanticsNode().boundsInRoot.width / density, .5f)
        val join = compose.onNodeWithTag("invite-action")
        val target = join.fetchSemanticsNode().boundsInRoot
        assertTrue(target.height / density >= 47.5f && target.width / density >= 47.5f)
        fun assertFullWidthRows() {
            val bounds = card.fetchSemanticsNode().boundsInRoot
            val subtitle = compose.onNodeWithTag("invite-subtitle", useUnmergedTree = true)
            val subtitleBounds = subtitle.fetchSemanticsNode().boundsInRoot
            val actionBounds = join.fetchSemanticsNode().boundsInRoot
            assertEquals(bounds.left, subtitleBounds.left, .5f)
            assertEquals(bounds.right, subtitleBounds.right, .5f)
            assertEquals(bounds.left, actionBounds.left, .5f)
            assertEquals(bounds.right, actionBounds.right, .5f)
            assertTrue(actionBounds.top >= subtitleBounds.bottom)
            for (tag in listOf("invite-title", "invite-subtitle")) {
                compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    assertTrue(read(layouts))
                    assertTrue(layouts.isNotEmpty())
                    assertFalse("Invitation text must wrap without clipping", layouts.any { it.hasVisualOverflow })
                }
            }
        }
        assertFullWidthRows()
        join.performClick()
        compose.runOnIdle { assertEquals(1, joined) }
        screenshot("conference-incoming-invitation-full-width.png")
        compose.runOnIdle { scale = 2f; error = "Не удалось войти. Повторите попытку." }
        join.assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Вечерняя комната").assertIsDisplayed()
        compose.onNodeWithText("Не удалось войти. Повторите попытку.").assertIsDisplayed()
        assertFullWidthRows()
        screenshot("conference-incoming-invitation-large-text.png")
        compose.runOnIdle { enabled = false }
        join.assertIsNotEnabled()
    }

    @After fun stopFixtureScopes() {
        scopes.forEach { it.cancel() }
        assertEquals("Presentation must never start a network admission", 0, requests.get())
    }

    @Test fun thirtyParticipantsAreReachableByPagesSwipeAndDirectoryPin() {
        val controller = fixture()
        show(controller)
        compose.onNodeWithText("1 / 5").assertIsDisplayed()
        repeat(4) { compose.onNodeWithTag("conference_page_next").performClick() }
        compose.onNodeWithText("5 / 5").assertIsDisplayed()
        compose.onNodeWithText("Fixture 29").assertIsDisplayed()
        screenshot("conference-v2-portrait-last-page.png")
        compose.onNodeWithTag("conference_grid").performTouchInput { swipeRight() }
        compose.onNodeWithText("4 / 5").assertIsDisplayed()

        compose.onNodeWithTag("conference_participants").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Fixture 29")
        // The query editor intentionally contains the same text; click the
        // actual directory result rather than weakening to an arbitrary match.
        compose.onNode(hasText("Fixture 29") and
            hasAnyAncestor(hasTestTag("conference_participant_directory"))).performClick()
        compose.onNodeWithText("К сетке участников").assertIsDisplayed()
        compose.onNodeWithText("Fixture 29").assertIsDisplayed()
        compose.runOnIdle { assertEquals("remote-29", controller.state.value.pinnedId) }
        screenshot("conference-v2-portrait-pinned.png")
        compose.onNodeWithTag("conference_back_to_grid").performClick()
        compose.onNodeWithText("5 / 5").assertIsDisplayed()
    }

    @Test fun ownCameraSharesTheGridWithoutOverlapAndMinimizeRetainsPageAndPin() {
        val controller = fixture(ownCamera = true)
        show(controller)
        val own = compose.onNodeWithTag("conference_tile_fixture-self").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val firstRemote = compose.onNodeWithTag("conference_tile_remote-1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Own tile must be an independent grid cell", own.right <= firstRemote.left + .5f || own.bottom <= firstRemote.top + .5f)
        compose.onNodeWithTag("conference_own_preview").assertDoesNotExist()
        repeat(2) { compose.onNodeWithTag("conference_page_next").performClick() }
        compose.onNodeWithText("3 / 5").assertIsDisplayed()
        val before = compose.runOnIdle { controller.state.value }
        compose.onNodeWithTag("conference_minimize").performClick()
        compose.onNodeWithTag("conference_return_strip").assertIsDisplayed()
        compose.onNodeWithTag("fixture_app_body").assertIsDisplayed()
        screenshot("conference-v6-minimized.png")
        compose.onNodeWithTag("conference_return_strip").performClick()
        compose.onNodeWithText("3 / 5").assertIsDisplayed()
        compose.runOnIdle { assertEquals(before.pageAnchor, controller.state.value.pageAnchor) }
        compose.onNodeWithText("Fixture 13").performClick()
        val pinned = compose.onNodeWithTag("conference_tile_remote-13").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val ownStrip = compose.onNodeWithTag("conference_own_pinned_tile").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Pinned preview must be below, never above participant video", ownStrip.top >= pinned.bottom - .5f)
        compose.onNodeWithTag("conference_minimize").performClick()
        compose.onNodeWithTag("conference_return_strip").performClick()
        compose.onNodeWithText("К сетке участников").assertIsDisplayed()
        compose.runOnIdle { assertEquals("remote-13", controller.state.value.pinnedId) }
    }

    @Test fun singleCameraOffUsesIdleStageAndCameraOnReturnsToTheSameGridCell() {
        val controller = fixture()
        val state = fixtureState(controller)
        state.value = state.value.copy(title = "Evening room", tiles = emptyList(), camera = false)
        show(controller)
        compose.onNodeWithTag("conference_alone_stage").assertIsDisplayed()
        compose.onNodeWithText("Вы в комнате").assertIsDisplayed()
        compose.onNodeWithTag("conference_tile_fixture-self").assertDoesNotExist()
        compose.onNodeWithTag("conference_alone_invite").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Выйти").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Камера и микрофон выключены").assertIsDisplayed()
        screenshot("conference-rooms-single-camera-off.png")
        compose.runOnIdle { state.value = state.value.copy(microphone = true, own = state.value.own!!.copy(muted = false)) }
        compose.onNodeWithText("Микрофон включён · камера выключена").assertIsDisplayed()
        compose.onNodeWithText("Камера и микрофон выключены").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(camera = true, microphone = false, own = state.value.own!!.copy(cameraEnabled = true, muted = true)) }
        compose.onNodeWithTag("conference_alone_stage").assertDoesNotExist()
        compose.onNodeWithTag("conference_tile_fixture-self").assertIsDisplayed()
        compose.runOnIdle { assertNull(controller.room); assertFalse(controller.captureReservation.value) }
        screenshot("conference-rooms-single-camera-on-grid.png")
    }

    @Test fun singleCameraOffKeepsExitVisibleInShortLandscapeViewport() {
        val controller = fixture()
        val state = fixtureState(controller)
        state.value = state.value.copy(title = "A room with a deliberately long name", tiles = emptyList())
        show(controller, width = 780, landscape = true, height = 280)
        compose.onNodeWithTag("conference_alone_stage").assertIsDisplayed()
        val dock = compose.onNodeWithTag("conference_control_dock").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val exit = compose.onNodeWithContentDescription("Выйти").assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
        assertTrue(exit.top >= dock.top && exit.bottom <= dock.bottom + .5f)
        screenshot("conference-rooms-single-short-landscape.png")
    }

    @Test fun idleInviteButtonDeliversExactlyOneTapWithoutStartingCapture() {
        var invitations = 0
        compose.setContent { CocoTheme {
            ConferenceAloneStage(connecting = false, reconnecting = false, microphone = false, canInvite = true, enabled = true,
                onInvite = { invitations++ }, modifier = Modifier.width(380.dp).height(420.dp))
        } }
        compose.onNodeWithTag("conference_alone_invite").assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, invitations); assertEquals(0, requests.get()) }
        screenshot("conference-rooms-idle-invite-action.png")
    }

    @Test fun participantLocalControlsDoNotAffectOthersAndCameraOffDoesNotMoveOwnTile() {
        val controller = fixture()
        show(controller)
        compose.onNodeWithTag("conference_tile_fixture-self").assertIsDisplayed()
        val before = compose.runOnIdle { visibleDemand(controller).toSet() }
        compose.onNodeWithTag("conference_tile_remote-1").performTouchInput { longClick() }
        compose.onNodeWithTag("conference_participant_local_audio").assertIsDisplayed().performClick()
        compose.onNodeWithTag("conference_participant_local_video").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val selected = controller.state.value.tiles.first { it.id == "remote-1" }
            assertTrue(selected.audioMutedForMe); assertTrue(selected.videoHiddenForMe)
            assertTrue(controller.state.value.tiles.filter { it.id != "remote-1" }.none { it.audioMutedForMe || it.videoHiddenForMe })
            assertTrue("Local changes must not start own capture", !controller.state.value.microphone && !controller.state.value.camera)
            assertEquals("Participant actions must not stop the other displayed cameras", before, visibleDemand(controller))
        }
        compose.onNodeWithTag("conference_participant_global_audio").assertDoesNotExist()
        compose.onNodeWithTag("conference_participant_remove").assertDoesNotExist()
        compose.onNodeWithTag("conference_participant_local_audio").performClick()
        compose.onNodeWithTag("conference_participant_local_video").performClick()
        compose.runOnIdle {
            assertTrue(controller.state.value.tiles.none { it.audioMutedForMe || it.videoHiddenForMe })
        }
        screenshot("conference-v6-local-actions.png")
    }

    @Test fun dockLabelIsPartOfTheSameTouchableButton() {
        val controller = fixture()
        show(controller)
        compose.onNodeWithContentDescription("Ещё").performTouchInput {
            // Bottom of the target is the caption below its 44dp circle.
            click(androidx.compose.ui.geometry.Offset(center.x, height - 2f))
        }
        compose.onNodeWithTag("conference_layout_settings").assertIsDisplayed()
    }

    @Test fun blockedMicrophoneLockExplainsRestrictionWithoutPermissionOrCapture() {
        val controller = fixture()
        val state = fixtureState(controller)
        state.value = state.value.copy(microphoneBlockedByHost = true)
        show(controller)
        val reason = "Микрофон отключён создателем комнаты. Нажмите, чтобы узнать подробнее."
        val button = compose.onNodeWithContentDescription(reason).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("conference_microphone_host_lock", useUnmergedTree = true).assertIsDisplayed()
        val bounds = button.fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(bounds.width / density >= 43.5f && bounds.height / density >= 43.5f)
        screenshot("conference-v7-microphone-lock.png")
        // The actual production controller handles the info tap before any Room,
        // permission launcher or capture-control coroutine can be entered.
        button.performTouchInput { click(androidx.compose.ui.geometry.Offset(center.x, height - 2f)) }
        // A separate platform Dialog uses its Activity-window locale, which the
        // synthetic RU in-composition configuration does not replace.
        val explanation = compose.activity.getString(com.messenger.app.R.string.conference_error_microphone_host)
        try { awaitDisplayed(explanation) }
        finally { screenshot("conference-v7-microphone-info-probe.png") }
        compose.onNodeWithText(explanation).assertIsDisplayed()
        compose.runOnIdle {
            assertNull(controller.room)
            assertFalse(controller.captureReservation.value)
            assertFalse(state.value.microphone)
            assertFalse(state.value.controlsBusy)
            assertEquals(com.messenger.app.R.string.conference_error_microphone_host, state.value.error)
        }
        screenshot("conference-v7-microphone-info.png")
        compose.onNodeWithText("OK").performClick()
        compose.runOnIdle { state.value = state.value.copy(microphoneBlockedByHost = false) }
        compose.onNodeWithContentDescription("Микрофон").assertIsEnabled()
        compose.onNodeWithTag("conference_microphone_host_lock", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertFalse("Permission restoration must not enable capture", state.value.microphone) }
    }

    @Test fun actualRemovedDialogExplainsCreatorActionAndClosedRoomRemainsDistinct() {
        val controller = fixture()
        val state = fixtureState(controller)
        state.value = state.value.copy(error = ConferenceErrorPresentation.message(
            com.messenger.app.data.api.AuthApiException.Api("gone", "You were removed from this conference.", 410), false))
        show(controller)
        val removed = compose.activity.getString(com.messenger.app.R.string.conference_error_removed)
        val closed = compose.activity.getString(com.messenger.app.R.string.conference_error_closed)
        assertNotEquals(removed, closed)
        try { awaitDisplayed(removed) }
        finally { screenshot("conference-v7-removed-probe.png") }
        compose.onNodeWithText(removed).assertIsDisplayed()
        compose.onNodeWithText(closed).assertDoesNotExist()
        screenshot("conference-v7-removed-explanation.png")
        compose.onNodeWithText("OK").performClick()
        compose.runOnIdle { state.value = state.value.copy(error = ConferenceErrorPresentation.message(ConferenceClosed(), true)) }
        awaitDisplayed(closed)
        compose.onNodeWithText(closed).assertIsDisplayed()
        compose.onNodeWithText(removed).assertDoesNotExist()
        compose.onNodeWithText("OK").performClick()
        compose.runOnIdle { assertNull(controller.room); assertFalse(controller.captureReservation.value) }
    }

    @Test fun catalogGuestInviteTapExplainsCapabilityWithoutOpeningEmptyShareOrCapture() {
        val controller = fixture()
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
        ConferenceController::class.java.getDeclaredField("admission").apply { isAccessible = true }.set(controller,
            ConferenceAdmission.Catalog(com.messenger.app.data.api.ServerEndpoint.current(), controller.state.value.roomId, UUID.randomUUID().toString(), key))
        show(controller)
        compose.runOnIdle { assertFalse(controller.canShareInvitation); assertNull(controller.inviteLink()); assertNull(controller.invitationCard()) }
        compose.onNodeWithContentDescription("Позвать").performClick()
        val explanation = compose.activity.getString(com.messenger.app.R.string.conference_invite_requires_original)
        awaitDisplayed(explanation)
        compose.onNodeWithText(explanation).assertIsDisplayed()
        compose.onNodeWithText("Выберите собеседников.", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertNull(controller.room); assertFalse(controller.captureReservation.value); assertEquals(0, requests.get()) }
        screenshot("conference-directory-guest-invite-info.png")
        compose.onNodeWithText("OK").performClick()
    }

    @Test fun narrowLargeTextKeepsSixActionsTouchableAndReturnStripDoesNotOverlapBody() {
        val controller = fixture()
        show(controller, width = 320, fontScale = 2f)
        val descriptions = listOf("Микрофон", "Камера", "Звук", "Позвать", "Ещё", "Выйти")
        val buttons = descriptions.map { compose.onNodeWithContentDescription(it).assertIsDisplayed().fetchSemanticsNode() }
        val density = compose.activity.resources.displayMetrics.density
        buttons.forEach { node ->
            assertTrue("Keep touch width", node.boundsInRoot.width / density >= 43.5f)
            assertTrue("Keep touch height", node.boundsInRoot.height / density >= 43.5f)
        }
        for (left in buttons.indices) for (right in left + 1 until buttons.size) {
            val a = buttons[left].boundsInRoot
            val b = buttons[right].boundsInRoot
            assertFalse("Controls must not overlap", a.left < b.right - .5f && b.left < a.right - .5f && a.top < b.bottom - .5f && b.top < a.bottom - .5f)
        }
        screenshot("conference-v2-large-text.png")
        compose.onNodeWithTag("conference_minimize").performClick()
        val strip = compose.onNodeWithTag("conference_return_strip").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("fixture_app_body").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The next screen must start below the measured strip", body.top >= strip.bottom - .5f)
        screenshot("conference-v2-large-text-minimized.png")
    }

    @Test fun customFiveScrollsToAllParticipantsAndFitAllRetainsExplicitChoice() {
        val controller = fixture()
        show(controller)
        compose.onNodeWithContentDescription("Ещё").performClick()
        compose.onNodeWithTag("conference_layout_settings").performClick()
        compose.onNodeWithTag("conference_visible_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        compose.onNodeWithText("На экране: 5").assertIsDisplayed()
        compose.onNodeWithTag("conference_layout_done").performClick()
        compose.runOnIdle {
            assertEquals(ConferenceGridMode.SCROLL, controller.state.value.gridMode)
            assertEquals(5, controller.state.value.visibleParticipantCount)
        }
        compose.onNodeWithTag("conference_tile_fixture-self").assertIsDisplayed()
        compose.onNodeWithTag("conference_tile_remote-4").assertIsDisplayed()
        compose.onNodeWithTag("conference_tile_remote-5").assertIsNotDisplayed()
        screenshot("conference-v3-five-visible.png")
        compose.onNodeWithTag("conference_grid").performScrollToIndex(28)
        compose.onNodeWithText("Fixture 29").assertIsDisplayed()
        val anchor = compose.runOnIdle { controller.state.value.pageAnchor }
        compose.onNodeWithTag("conference_minimize").performClick()
        compose.onNodeWithTag("conference_return_strip").performClick()
        compose.runOnIdle {
            assertEquals(5, controller.state.value.visibleParticipantCount)
            assertEquals(ConferenceGridMode.SCROLL, controller.state.value.gridMode)
            assertEquals(anchor, controller.state.value.pageAnchor)
        }
        compose.onNodeWithText("Fixture 29").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ещё").performClick()
        compose.onNodeWithTag("conference_layout_settings").performClick()
        compose.onNodeWithTag("conference_fit_all").performClick()
        compose.onNodeWithTag("conference_layout_done").performClick()
        compose.runOnIdle { assertEquals(ConferenceGridMode.FIT, controller.state.value.gridMode) }
        (1..29).forEach { compose.onNodeWithTag("conference_tile_remote-$it").assertIsDisplayed() }
        screenshot("conference-v3-fit-29.png")
        compose.onNodeWithContentDescription("Выйти").assertIsDisplayed()
    }

    @Test fun roomsDestinationUsesExistingSidebarRowsWithoutReplacingCalls() {
        val opened = AtomicInteger()
        compose.setContent {
            CocoTheme {
                GraphiteDrawer(width = 280.dp, userId = null, displayName = "Fixture", username = "fixture", avatarVersion = 0, hasAvatar = false,
                    selected = RootDestination.CHATS, unread = 0, showCalls = true, showSaved = true,
                    onClose = {}, onEdit = {}, onSearch = {}, onDestination = {}, onSaved = {}, onServer = {}, onDevices = {}, onHelp = {}, onReport = {}, onLogout = {},
                    avatar = { size, _, _ -> Spacer(Modifier.size(size)) }, onRooms = { opened.incrementAndGet() })
            }
        }
        compose.onNodeWithTag("saved-sidebar-item-calls").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("saved-sidebar-item-rooms").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened.get()) }
        screenshot("conference-v3-sidebar-rooms.png")
    }

    @Test fun olderBackendDoesNotExposeUnsupportedHostSettings() {
        val controller = fixture()
        show(controller)
        compose.onNodeWithContentDescription("Ещё").performClick()
        compose.onNodeWithTag("conference_layout_settings").assertIsDisplayed()
        compose.onNodeWithTag("conference_host_settings").assertDoesNotExist()
    }

    @Test fun roomSheetsRetainTheBoundedPageAndClosingDoesNotRestartIt() {
        val controller = fixture()
        show(controller)
        compose.runOnIdle { assertEquals(5, visibleDemand(controller).size); assertFalse("Local tile is never a remote subscription", "fixture-self" in visibleDemand(controller)) }
        compose.onNodeWithContentDescription("Ещё").performClick()
        compose.runOnIdle { assertEquals(setOf("remote-1", "remote-2", "remote-3", "remote-4", "remote-5"), visibleDemand(controller)) }
        compose.onNodeWithTag("conference_layout_settings").performClick()
        compose.runOnIdle { assertEquals(setOf("remote-1", "remote-2", "remote-3", "remote-4", "remote-5"), visibleDemand(controller)) }
        compose.onNodeWithTag("conference_visible_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        compose.runOnIdle { assertEquals(setOf("remote-1", "remote-2", "remote-3", "remote-4"), visibleDemand(controller)) }
        compose.onNodeWithTag("conference_layout_done").performClick()
        compose.runOnIdle { assertEquals(setOf("remote-1", "remote-2", "remote-3", "remote-4"), visibleDemand(controller)) }
    }

    @Test fun oldPresentationCannotMoveCurrentAnchorOrResumeHiddenVideos() {
        val controller = fixture()
        val old = controller.beginPresentation()
        controller.anchor(old, "remote-5")
        assertEquals("remote-5", controller.state.value.pageAnchor)
        val current = controller.beginPresentation()
        controller.anchor(old, "remote-29")
        controller.present(old, setOf("remote-29"))
        assertEquals("remote-5", controller.state.value.pageAnchor)
        assertTrue(visibleDemand(controller).isEmpty())
        controller.anchor(current, "remote-10")
        assertEquals("remote-10", controller.state.value.pageAnchor)
        controller.minimize(true)
        controller.anchor(current, "remote-29")
        controller.present(current, setOf("remote-29"))
        assertEquals("remote-10", controller.state.value.pageAnchor)
        assertTrue(visibleDemand(controller).isEmpty())
    }

    @Test fun replacingPublicationRendererActuallyReplacesTheAttachedAndroidView() {
        // Exact native View-binding function used by ConferenceVideoTile.
        // Plain Views stand in for two SDK renderers: no camera, Room or JNI.
        val first = android.widget.TextView(compose.activity).apply { text = "Renderer A" }
        val second = android.widget.TextView(compose.activity).apply { text = "Renderer B" }
        var current by mutableStateOf<android.view.View>(first)
        compose.setContent { Box(Modifier.size(200.dp)) { ConferenceRendererView(current) } }
        compose.runOnIdle {
            assertNotNull(first.parent); assertNull(second.parent)
            current = second
        }
        compose.runOnIdle { assertNull(first.parent); assertNotNull(second.parent) }
        compose.runOnIdle { current = first }
        compose.runOnIdle { assertNotNull(first.parent); assertNull(second.parent) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun visibleDemand(controller: ConferenceController): Set<String> = ConferenceController::class.java.getDeclaredField("visible")
        .apply { isAccessible = true }.get(controller) as Set<String>

    @Composable private fun FixtureContent(controller: ConferenceController, width: Int, fontScale: Float, landscape: Boolean = false, height: Int? = null) {
        val original = LocalConfiguration.current
        val base = LocalContext.current
        val density = LocalDensity.current
        val configuration = remember(original, width, fontScale, landscape) {
            Configuration(original).apply {
                screenWidthDp = width; orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                this.fontScale = fontScale; setLocale(Locale("ru"))
            }
        }
        val localized = remember(base, configuration) { base.createConfigurationContext(configuration) }
        CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides localized,
            LocalActivityResultRegistryOwner provides compose.activity,
            LocalDensity provides Density(density.density, fontScale)) {
            CocoTheme {
                Box(Modifier.width(width.dp).then(if (height == null) Modifier.fillMaxHeight() else Modifier.height(height.dp))) {
                    val state by controller.state.collectAsState()
                    if (state.minimized) Column(Modifier.fillMaxSize()) {
                        ConferenceReturnStrip(controller, state)
                        Text("Fixture app body", Modifier.testTag("fixture_app_body"))
                    } else CompactConferenceRoom(controller, state)
                    state.error?.let { ConferenceErrorDialog(it, onDismiss = controller::dismissError) }
                }
            }
        }
    }

    private fun show(controller: ConferenceController, width: Int = 380, fontScale: Float = 1f, landscape: Boolean = false, height: Int? = null) {
        compose.setContent { FixtureContent(controller, width, fontScale, landscape, height) }
    }

    /** Native Dialog owns another Window; wait for its actual visible layout,
     * not merely existence of the composition's text semantics. */
    private fun awaitDisplayed(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { compose.onNodeWithText(text).assertIsDisplayed(); true }.getOrDefault(false)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fixtureState(controller: ConferenceController): MutableStateFlow<ConferenceState> =
        ConferenceController::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(controller) as MutableStateFlow<ConferenceState>

    @Suppress("UNCHECKED_CAST")
    private fun fixture(ownCamera: Boolean = false): ConferenceController {
        val auth = FixtureAuth()
        val http = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            throw AssertionError("Synthetic UI must never call a server")
        }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also(scopes::add)
        val controller = ConferenceController(compose.activity, ConferenceApi(http, Json { ignoreUnknownKeys = true }, auth), auth, scope) { false }
        // Explicitly own this synthetic active session, matching the production
        // presentation fence without creating a server admission or media Room.
        ConferenceController::class.java.getDeclaredField("ownerEpoch").apply { isAccessible = true }.set(controller, 1)
        ConferenceController::class.java.getDeclaredField("ownerOrigin").apply { isAccessible = true }.set(controller, com.messenger.app.data.api.ServerEndpoint.current())
        val flow = ConferenceController::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(controller) as MutableStateFlow<ConferenceState>
        flow.value = ConferenceState(active = true, title = "Fixture 30", host = true, roomId = UUID.randomUUID().toString(),
            camera = ownCamera, previewX = .8f, previewY = .15f,
            own = ConferenceTile("fixture-self", "Вы", null, true, false, ownCamera),
            tiles = (1..29).map { ConferenceTile("remote-$it", "Fixture $it", null, it % 2 == 0, false, true) })
        return controller
    }

    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private class FixtureAuth : AuthRepository {
        override val authState = MutableStateFlow<AuthSessionState>(AuthSessionState.LoggedIn(AuthUser("fixture-owner", "fixture", "Fixture")))
        override suspend fun register(username: String, password: String, displayName: String) = Result.failure<Unit>(UnsupportedOperationException())
        override suspend fun login(username: String, password: String) = Result.failure<Unit>(UnsupportedOperationException())
        override suspend fun logout() { authState.value = AuthSessionState.LoggedOut }
        override suspend fun currentAccessToken(): String? = null
        override fun currentDeviceId() = "fixture-device"
        override fun currentSessionEpoch() = 1
        override suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T? = if (epochAtStart == 1) block() else null
        override suspend fun clearRoom(block: suspend () -> Unit) { block() }
    }
}
