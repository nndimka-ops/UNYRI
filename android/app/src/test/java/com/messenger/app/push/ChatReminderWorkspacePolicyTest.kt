package com.messenger.app.push

import org.junit.Assert.*
import org.junit.Test

class ChatReminderWorkspacePolicyTest {
    private val first = "6f9619ff-8b86-d011-b42d-00c04fc964ff"
    private val second = "6f9619ff-8b86-d011-b42d-00c04fc964fe"

    @Test fun inactiveWorkspaceAlarmsAreRejectedEvenWhenTheUserIsTheSame() {
        assertTrue(ChatReminderWorkspacePolicy.accepts(first, first))
        assertFalse(ChatReminderWorkspacePolicy.accepts(first, second))
        assertFalse(ChatReminderWorkspacePolicy.accepts(first, "legacy"))
    }

    @Test fun preWorkspaceAlarmsBelongOnlyToTheOriginalWorkspace() {
        assertTrue(ChatReminderWorkspacePolicy.accepts(null, "legacy"))
        assertTrue(ChatReminderWorkspacePolicy.accepts("legacy", "legacy"))
        assertFalse(ChatReminderWorkspacePolicy.accepts(null, first))
        assertFalse(ChatReminderWorkspacePolicy.accepts("legacy", first))
    }

    @Test fun pendingIntentAndNotificationIdentityIncludeWorkspace() {
        assertNotEquals(ChatReminderWorkspacePolicy.uri(first, "same-owner", "same-chat", "same-message"),
            ChatReminderWorkspacePolicy.uri(second, "same-owner", "same-chat", "same-message"))
        assertNotEquals(ChatReminderWorkspacePolicy.notificationTag(first, "same-owner", "same-chat", "same-message"),
            ChatReminderWorkspacePolicy.notificationTag(second, "same-owner", "same-chat", "same-message"))
    }
}
