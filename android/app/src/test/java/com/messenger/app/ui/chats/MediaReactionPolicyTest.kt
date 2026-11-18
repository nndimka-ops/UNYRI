package com.messenger.app.ui.chats
import kotlin.test.*
class MediaReactionPolicyTest {
    @Test fun myVotesOnAnyDeviceNeverLookIncoming() {
        assertFalse(MediaReactionPolicy.incoming(0, false, 1, true))
        assertFalse(MediaReactionPolicy.incoming(2, true, 1, false))
        assertTrue(MediaReactionPolicy.incoming(1, true, 2, true))
    }
    @Test fun initialLoadSyncCoversAndInactiveRowsSuppressIncomingEffects() {
        assertFalse(MediaReactionPolicy.mayAnimate(false, true, true, false, 999, 2000))
        assertFalse(MediaReactionPolicy.mayAnimate(false, true, true, false, 2000, 1499))
        assertFalse(MediaReactionPolicy.mayAnimate(false, false, true, false, 2000, 2000))
        assertFalse(MediaReactionPolicy.mayAnimate(false, true, false, false, 2000, 2000))
        assertFalse(MediaReactionPolicy.mayAnimate(true, true, true, true, 2000, 2000))
        assertTrue(MediaReactionPolicy.mayAnimate(false, true, true, false, 1000, 1500))
        assertTrue(MediaReactionPolicy.mayAnimate(true, true, true, false, 0, 0))
    }
    @Test fun noMoreThanThreeFiniteEffectsAreAdmitted() {
        val admission = MediaReactionAdmission(); val tokens = List(4) { Any() }
        tokens.take(3).forEach { assertTrue(admission.enter(it)) }; assertFalse(admission.enter(tokens[3]))
        admission.leave(tokens[0]); assertTrue(admission.enter(tokens[3]))
    }
}
