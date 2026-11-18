package com.messenger.app.data.call

import kotlin.test.*

class CallOfferLifetimeTest {
    @Test fun `media negotiation has an answer timer and fixed lifetime without an outage`() {
        val policy = CallRecoveryPolicy()
        policy.offerRequested(1_000, restart = false)
        policy.offerSent(1_000)
        assertNull(policy.startedAt, "healthy media must not start an outage")
        assertEquals(CallRecoveryPolicy.Action.WAIT, policy.next(5_999))
        assertEquals(CallRecoveryPolicy.Action.RESEND_OFFER, policy.next(6_000))
        policy.offerSent(6_000)
        assertEquals(CallRecoveryPolicy.Action.WAIT, policy.next(10_999))
        assertEquals(CallRecoveryPolicy.Action.RESEND_OFFER, policy.next(11_000))
        policy.offerSent(30_000)
        assertEquals(CallRecoveryPolicy.Action.ABANDON_OFFER, policy.next(31_000), "resends do not extend the lifetime")
        policy.offerResolved(31_000)
        assertEquals(CallRecoveryPolicy.Action.IDLE, policy.next(60_000))
    }

    @Test fun `a later outage has its own budget after an older media offer expires`() {
        val policy = CallRecoveryPolicy(peerResolvesGlare = true)
        policy.offerRequested(0, restart = false)
        policy.offerSent(0)
        policy.mediaDisconnected(20_000)
        assertEquals(CallRecoveryPolicy.Action.ABANDON_OFFER, policy.next(30_000))
        policy.offerResolved(30_000)
        assertEquals(CallRecoveryPolicy.Action.RESTART, policy.next(30_000))
        assertEquals(CallRecoveryPolicy.Action.FAIL, policy.next(50_000))
    }

    @Test fun `a pending offer cannot extend the outage budget`() {
        val policy = CallRecoveryPolicy(peerResolvesGlare = true)
        policy.mediaDisconnected(0)
        policy.offerRequested(20_000, restart = true)
        policy.offerSent(29_000)
        assertEquals(CallRecoveryPolicy.Action.FAIL, policy.next(30_000))
    }
}
