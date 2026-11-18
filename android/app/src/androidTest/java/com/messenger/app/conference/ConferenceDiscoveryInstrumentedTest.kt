package com.messenger.app.conference

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.ui.theme.CocoTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production editor/card views; no crypto/server/Room fixtures replace runtime behavior. */
@RunWith(AndroidJUnit4::class)
class ConferenceDiscoveryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scopes = mutableListOf<kotlinx.coroutines.CoroutineScope>()
    @org.junit.After fun releaseScopes() { scopes.forEach { it.coroutineContext[kotlinx.coroutines.Job]?.cancel() } }

    @Test fun blockedCreationExplainsWhyWithoutReplacingTheCurrentRoom() {
        val controller = hostController()
        val field = ConferenceController::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(controller) as kotlinx.coroutines.flow.MutableStateFlow<ConferenceState>
        val before = ConferenceState(active = true, roomId = "kept-room", title = "Keep this conversation", minimized = true)
        state.value = before
        controller.create(ConferenceCreateOptions("Do not create this room", 2))
        assertEquals(before.copy(error = com.messenger.app.R.string.media_busy_room), state.value)
        assertNull(controller.room)
        compose.setContent { CocoTheme { ConferenceErrorDialog(state.value.error!!) { controller.dismissError() } } }
        compose.onNodeWithText(compose.activity.getString(com.messenger.app.R.string.media_busy_room)).assertIsDisplayed()
        capture("conference-blocked-action-explanation.png")
    }

    @Test fun capacitySliderSelectsAnIntegerWithoutOpeningANumericEditor() {
        var result: ConferenceCreateOptions? = null
        compose.setContent { CocoTheme { ConferenceRoomEditor("Создать комнату", true, {}, onCreate = { result = it }) } }
        compose.onNodeWithTag("conference_room_title").performTextInput("Slider room")
        val slider = compose.onNodeWithTag("conference_room_capacity").performScrollTo()
        slider.assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.EditableText))
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(2f) }
        slider.assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(2f, 2f..30f, 27))
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(30f) }
        slider.assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(30f, 2f..30f, 27))
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(7f) }
        slider.assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(7f, 2f..30f, 27))
        compose.onNodeWithTag("conference_room_apply").performClick()
        compose.runOnIdle { assertEquals(7, result!!.maxParticipants) }
        capture("conference-directory-capacity-slider.png")
    }

    @Test fun createKeepsVisibilityIndependentAndPreservesPasswordOnlyAfterExplicitApply() {
        var result: ConferenceCreateOptions? = null
        compose.setContent { CocoTheme { ConferenceRoomEditor("Создать комнату", true, {}, onCreate = { result = it }) } }
        compose.onNodeWithTag("conference_room_title").performTextInput("Native room")
        for (access in ConferenceAccess.entries) compose.onNodeWithText(accessLabel(access)).assertExists()
        compose.onNodeWithTag("conference_room_listed").performScrollTo()
        compose.onNodeWithText("Комната скрыта из списка").assertIsDisplayed()
        compose.onNodeWithText("Только для тех, у кого есть приглашение").assertDoesNotExist()
        compose.onNodeWithTag("conference_room_listed").performClick()
        compose.onNodeWithText("Комнату можно найти в списке").assertIsDisplayed()
        compose.onNodeWithText("По паролю").performScrollTo().performClick()
        compose.onNodeWithTag("conference_room_password").performScrollTo().assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password)).performTextInput("  native-secret  ")
        assertEquals(androidx.compose.ui.text.input.KeyboardType.Password, conferencePasswordKeyboardOptions.keyboardType)
        assertEquals(androidx.compose.ui.text.input.KeyboardCapitalization.None, conferencePasswordKeyboardOptions.capitalization)
        assertEquals(false, conferencePasswordKeyboardOptions.autoCorrectEnabled)
        assertEquals(androidx.compose.ui.text.input.ImeAction.Done, conferencePasswordKeyboardOptions.imeAction)
        compose.runOnIdle { assertNull(result) }
        compose.onNodeWithTag("conference_room_apply").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNotNull(result); assertTrue(result!!.listed); assertEquals(ConferenceAccess.PASSWORD, result!!.access); assertEquals("  native-secret  ", result!!.password) }
        capture("conference-directory-create-password.png")
    }
    @Test fun hostNeverPrefillsPasswordAndKeepsExistingPasswordUntilExplicitReplacement() {
        var saved: ConferenceSettingsEdit? = null
        val state = ConferenceState(title = "Protected", roomId = "fixture-room", description = "Current description", listed = true, accessMode = "invite_password", settingsRevision = 12)
        compose.setContent { CocoTheme { ConferenceRoomEditor("Настройки комнаты", true, {}, initial = state, onSave = { saved = it }) } }
        compose.onNodeWithTag("conference_room_password").assertDoesNotExist()
        compose.onNodeWithTag("conference_room_description").performScrollTo().performTextInput(" more")
        compose.onNodeWithTag("conference_host_save").performClick()
        compose.runOnIdle { assertEquals("keep", saved!!.passwordAction); assertNull(saved!!.password); assertEquals(12L, saved!!.expectedRevision); assertEquals(ConferenceAccess.INVITE_PASSWORD, saved!!.access) }
        compose.onNodeWithText("Открытая").performScrollTo().performClick()
        compose.onNodeWithTag("conference_host_save").performClick()
        compose.runOnIdle { assertEquals("remove", saved!!.passwordAction); assertNull(saved!!.password); assertEquals(ConferenceAccess.OPEN, saved!!.access) }
        capture("conference-directory-host-password-keep-remove.png")
    }
    @Test fun compactLargeTextEditorKeepsApplyCancelVisibleAndUnknownAccessFailClosed() {
        val state = ConferenceState(title = "Room", roomId = "fixture-room", accessMode = "future_unknown", settingsRevision = 2)
        var saved: ConferenceSettingsEdit? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) { CocoTheme {
                Box(Modifier.width(320.dp).fillMaxHeight()) { ConferenceRoomEditor("Настройки комнаты", true, {}, initial = state, onSave = { saved = it }) }
            } }
        }
        compose.onNodeWithTag("conference_host_save").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Отмена").assertIsDisplayed()
        compose.onNodeWithText("По приглашению").performScrollTo().performClick()
        compose.onNodeWithTag("conference_host_save").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(ConferenceAccess.INVITE, saved!!.access) }
        capture("conference-directory-host-large-text.png")
    }
    @Test fun remoteSettingsRevisionNeverOverwritesUnsavedFieldsAndReloadIsExplicit() {
        var current by mutableStateOf(ConferenceState(title = "Original", roomId = "fixture-room", accessMode = "open", settingsRevision = 1))
        compose.setContent { CocoTheme { ConferenceRoomEditor("Настройки комнаты", true, {}, initial = current, onSave = {}) } }
        compose.onNodeWithTag("conference_host_title").performTextReplacement("My unsaved title")
        compose.runOnIdle { current = current.copy(title = "Server title", settingsRevision = 2) }
        compose.onNodeWithTag("conference_host_title").assertTextContains("My unsaved title")
        compose.onNodeWithTag("conference_host_save").assertIsNotEnabled()
        compose.onNodeWithTag("conference_room_reload_settings").performScrollTo().performClick()
        compose.onNodeWithTag("conference_host_title").assertTextContains("Server title")
        compose.onNodeWithTag("conference_room_reload_settings").assertDoesNotExist()
        capture("conference-directory-explicit-revision-reload.png")
    }
    @Test fun actualHostEditorSharesActivityWindowAndKeepsBothActionsInsideVisibleBounds() {
        configureRealWindow()
        val controller = hostController()
        val state = ConferenceState(title = "Native host", roomId = "11111111-1111-4111-8111-111111111111", host = true, roomAccessAvailable = true, accessMode = "open", settingsRevision = 1)
        compose.setContent { CocoTheme { ConferenceHostEditor(controller, state) {} } }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("conference_host_editor").assertIsDisplayed()
        assertFooterAboveVisibleWindow()
        capture("conference-directory-v2-owner-window-safe-footer.png")
    }
    @Test fun actualHostEditorKeepsApplyAndCancelAboveSettledPasswordKeyboard() {
        configureRealWindow()
        val controller = hostController()
        val state = ConferenceState(title = "Native host", roomId = "11111111-1111-4111-8111-111111111111", host = true, roomAccessAvailable = true, accessMode = "open", settingsRevision = 1)
        var closed by mutableStateOf(false)
        compose.setContent { CocoTheme { if (!closed) ConferenceHostEditor(controller, state) { closed = true } else Text("Editor closed") } }
        compose.onNodeWithText("По паролю").performScrollTo().performClick()
        compose.onNodeWithTag("conference_room_password").performScrollTo().performClick().performTextInput("native-password")
        var previousHeight = -1; var unchangedSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(8_000) {
            var height = 0
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                if (insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) height = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (height != previousHeight) { previousHeight = height; unchangedSince = now }
            height > 0 && now - unchangedSince >= 300
        }
        compose.waitForIdle()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertFooterAboveVisibleWindow()
        capture("conference-directory-v2-owner-settled-password-ime.png")
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Editor closed").assertIsDisplayed()
    }
    private fun configureRealWindow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Match the real MainActivity's edge-to-edge + manifest adjustResize policy.
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    private fun assertFooterAboveVisibleWindow() {
        val visible = android.graphics.Rect(); val location = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.window.decorView.getWindowVisibleDisplayFrame(visible)
            compose.activity.window.decorView.getLocationOnScreen(location)
        }
        for (node in listOf(compose.onNodeWithTag("conference_host_save"), compose.onNodeWithText("Отмена"))) {
            val bounds = node.assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            assertTrue("The entire action must remain above navigation and IME", bounds.bottom + location[1] <= visible.bottom + 1f)
            assertTrue("The action needs a complete 44dp touch target", bounds.height / compose.activity.resources.displayMetrics.density >= 43.5f)
        }
    }
    private fun hostController(): ConferenceController {
        val auth = object : com.messenger.app.domain.repository.AuthRepository {
            override val authState = kotlinx.coroutines.flow.MutableStateFlow<com.messenger.app.domain.model.AuthSessionState>(com.messenger.app.domain.model.AuthSessionState.LoggedOut)
            override suspend fun register(username: String, password: String, displayName: String) = Result.success(Unit)
            override suspend fun login(username: String, password: String) = Result.success(Unit)
            override suspend fun logout() {}
            override suspend fun currentAccessToken(): String? = error("Window fixtures cannot authenticate")
            override fun currentDeviceId(): String? = null
            override fun currentSessionEpoch() = 1
            override suspend fun <T> withSessionWrite(epochAtStart: Int, block: suspend () -> T): T? = null
            override suspend fun clearRoom(block: suspend () -> Unit) {}
        }
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { error("Window fixture must not call a server") }.build()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate).also(scopes::add)
        return ConferenceController(compose.activity, ConferenceApi(client, kotlinx.serialization.json.Json, auth), auth, scope) { false }
    }
    @Test fun invitationOnlyCardShowsCreatorCapacityExpiryAndReasonWithoutJoining() {
        var taps = 0
        compose.setContent { CocoTheme { Column(Modifier.width(360.dp)) {
            ConferenceRoomCard("Evening", "Private access", "Creator", 4, 30, "invite", false, "2027-01-15T08:01:00Z", onClick = { taps++ }, action = { Text("Нужно приглашение") })
        } } }
        compose.onNodeWithText("Creator · 4 / 30").assertIsDisplayed()
        compose.onNodeWithText("Нужно приглашение").assertIsDisplayed()
        compose.onNodeWithText("По приглашению").assertIsDisplayed()
        compose.onNode(hasText("автоотключение", substring = true)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, taps) }
        capture("conference-directory-invitation-only-card.png")
    }
    @Test fun ownedRoomRejoinWaitsForCleanupAndNeverTurnsEndIntoJoin() {
        val room = ConferenceDto("11111111-1111-4111-8111-111111111111", "Evening", "owner", 30, "active", expiresAt = "2027-01-15T08:01:00Z")
        var leaving by mutableStateOf(true)
        var hasKey by mutableStateOf(true)
        var opened = 0; var ended = 0
        compose.setContent { CocoTheme { Column(Modifier.width(360.dp)) {
            ConferenceOwnedRoomCard(room, current = false, savedKey = hasKey, slotBusy = leaving, onOpen = { opened++ }, onEnd = { ended++ })
        } } }
        compose.onNodeWithTag("conference_owned_join_${room.id}").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, opened); leaving = false }
        compose.onNodeWithTag("conference_owned_join_${room.id}").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(0, ended) }
        compose.onNodeWithTag("conference_owned_menu_${room.id}").performClick()
        compose.onNodeWithText("Завершить для всех").performClick()
        compose.runOnIdle { assertEquals(1, ended); assertEquals(1, opened); hasKey = false }
        compose.onNodeWithTag("conference_owned_join_${room.id}").assertIsDisplayed().assertTextContains("По приглашению")
        compose.onNodeWithText("На этом устройстве нет ключа комнаты. Для входа нужно прежнее приглашение.").assertIsDisplayed()
        capture("conference-rooms-owner-rejoin-and-explicit-end.png")
    }
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
