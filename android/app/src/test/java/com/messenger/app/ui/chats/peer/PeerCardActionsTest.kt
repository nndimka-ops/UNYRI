package com.messenger.app.ui.chats.peer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerCardActionsTest {
    @Test fun `existing offline chat remains readable but network actions are disabled`() {
        val actions = peerCardActions(false, false, false, true, true, true, false)
        assertTrue(actions.message)
        assertFalse(actions.call)
        assertFalse(actions.changeContact)
        assertTrue(actions.confirmRemoval)
    }
    @Test fun `blocked or unavailable profile cannot expose QR or start calls`() {
        val blocked = peerCardActions(false, true, true, true, true, true, false)
        assertFalse(blocked.message)
        assertFalse(blocked.call)
        assertFalse(blocked.qr)
        assertFalse(peerCardActions(false, false, true, false, true, false, false).qr)
    }
    @Test fun `removal confirms while adding and unknown membership cannot remove`() {
        val adding = peerCardActions(false, false, true, true, true, false, false)
        assertTrue(adding.changeContact)
        assertFalse(adding.confirmRemoval)
        assertFalse(peerCardActions(false, false, true, true, false, false, false).changeContact)
        assertFalse(peerCardActions(false, false, true, true, true, true, true).changeContact)
    }
    @Test fun `self card has no call contact or verification actions`() {
        val actions = peerCardActions(true, false, true, true, true, false, false)
        assertFalse(actions.message)
        assertFalse(actions.call)
        assertFalse(actions.changeContact)
        assertFalse(actions.verify)
    }
}
