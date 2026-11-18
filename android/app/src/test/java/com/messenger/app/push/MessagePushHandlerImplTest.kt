package com.messenger.app.push

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P0 regression (notifications completely broken): a stale activeChatId left
 *  behind after the user backgrounds the app must NOT suppress the background
 *  system notification. */
class MessagePushHandlerImplTest {
    @Test fun `unbound message is rejected while logged out`() {
        assertFalse(MessagePushHandlerImpl.isForCurrentUser(null, null))
    }


    @Test
    fun `foreground with the same chat visible is suppressed`() {
        assertFalse(MessagePushHandlerImpl.shouldNotify(isForeground = true, activeChatId = "chat-1", eventChatId = "chat-1"))
    }

    @Test
    fun `background with a stale activeChatId still notifies`() {
        // The P0 bug: Home from an open chat leaves activeChatId set, but the
        // app is backgrounded -> the system notification MUST still appear.
        assertTrue(MessagePushHandlerImpl.shouldNotify(isForeground = false, activeChatId = "chat-1", eventChatId = "chat-1"))
    }

    @Test
    fun `background with no active chat notifies`() {
        assertTrue(MessagePushHandlerImpl.shouldNotify(isForeground = false, activeChatId = null, eventChatId = "chat-1"))
    }

    @Test
    fun `foreground with a different chat uses only the in-app banner`() {
        assertFalse(MessagePushHandlerImpl.shouldNotify(isForeground = true, activeChatId = "chat-2", eventChatId = "chat-1"))
    }

    // --- P0 account-switch push privacy (delayed old-account FCM) ---

    @Test
    fun `a push bound to the current account is allowed`() {
        assertTrue(MessagePushHandlerImpl.isForCurrentUser(eventUserId = "user-A", currentUserId = "user-A"))
    }

    @Test
    fun `a delayed push bound to a previous account is dropped`() {
        assertFalse(MessagePushHandlerImpl.isForCurrentUser(eventUserId = "user-A", currentUserId = "user-B"))
    }

    @Test
    fun `a push bound to a previous account arriving while logged out is dropped`() {
        assertFalse(MessagePushHandlerImpl.isForCurrentUser(eventUserId = "user-A", currentUserId = null))
    }

    @Test
    fun `an unbound push (ws path or legacy) is always considered for the current session`() {
        assertTrue(MessagePushHandlerImpl.isForCurrentUser(eventUserId = null, currentUserId = "user-B"))
    }
}
