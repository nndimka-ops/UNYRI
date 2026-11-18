package com.messenger.app.ui.security

import java.io.File
import kotlin.test.*

/** Confirms framework-only sinks keep the shared filter, without filtering typing. */
class ObscuredTouchWiringTest {
    private fun source(path: String): String = listOf(File("src/main/java/com/messenger/app/$path"), File("app/src/main/java/com/messenger/app/$path")).first { it.isFile }.readText()
    @Test fun sensitiveTargetsUseTheSharedEventFilter() {
        for (path in listOf("ui/call/CallScreens.kt", "ui/devices/VerificationScreen.kt", "conference/ConferenceTrustDialog.kt",
            "ui/settings/SettingsButtons.kt", "ui/settings/HistoryKeySettingsScreen.kt", "ui/settings/HistoryTransferScreen.kt",
            "ui/auth/AccountRecoveryPanel.kt", "ui/admin/AdminPanel.kt", "ui/admin/AdminComponents.kt", "ui/admin/AdminSecurityScreen.kt",
            "ui/auth/LoginScreen.kt", "ui/auth/RegisterScreen.kt", "ui/chats/ChatScreen.kt", "ui/chats/ChatsScreen.kt",
            "ui/chats/ChatActionMenu.kt", "ui/chats/ContactChatMenu.kt", "ui/settings/BlockedUsersScreen.kt",
            "ui/chats/AttachmentGallery.kt", "ui/settings/NotificationSettingsContent.kt", "ui/settings/LocalConnectionSettings.kt")) {
            assertTrue(source(path).contains(".rejectObscuredTouches()"), path)
        }
        assertTrue(source("ui/call/VideoCallScreen.kt").contains("sensitive = true"))
    }
    @Test fun nativeEventFlagsAreObservedBeforeClickHandlingWithoutWindowWideFilter() {
        val helper = source("ui/security/ObscuredTouches.kt")
        assertTrue(helper.contains("motionEventSpy")); assertTrue(helper.contains("PointerEventPass.Initial"))
        assertTrue(helper.contains("event.changes.forEach { it.consume() }"))
        assertFalse(helper.contains("pointerInteropFilter")) // A false DOWN would miss later obscured events.
        for (path in listOf("MainActivity.kt", "MessengerApp.kt")) {
            assertFalse(source(path).contains("rejectObscuredTouches"), path)
            assertFalse(source(path).contains("filterTouchesWhenObscured"), path)
        }
    }
}
