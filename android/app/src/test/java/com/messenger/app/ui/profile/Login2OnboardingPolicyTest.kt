package com.messenger.app.ui.profile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B-049 (owner 19 Nov): no recovery gate or notice right after sign-up; upgraded accounts see one notice. */
class Login2OnboardingPolicyTest {
    @Test fun aNewRegistrationNeverShowsAGateOrNotice() {
        assertFalse(Login2OnboardingPolicy.showsUpgradeNotice(signupPending = true, hasIdentity = true, acknowledged = false))
        assertFalse(Login2OnboardingPolicy.showsUpgradeNotice(signupPending = true, hasIdentity = false, acknowledged = false))
    }

    @Test fun anAccountFromBeforeThePrivateLoginSeesTheNoticeOnce() {
        assertTrue(Login2OnboardingPolicy.showsUpgradeNotice(signupPending = false, hasIdentity = true, acknowledged = false))
        assertFalse(Login2OnboardingPolicy.showsUpgradeNotice(signupPending = false, hasIdentity = true, acknowledged = true))
        assertFalse(Login2OnboardingPolicy.showsUpgradeNotice(signupPending = false, hasIdentity = false, acknowledged = false))
    }
}
