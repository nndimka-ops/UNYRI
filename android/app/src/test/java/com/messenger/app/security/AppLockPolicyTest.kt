package com.messenger.app.security
import kotlin.test.*
class AppLockPolicyTest {
    @Test fun timeoutCountsOnlyBackgroundTime() {
        assertFalse(AppLockPolicy.shouldLock(true, null, 9_000_000, 60_000))
        assertFalse(AppLockPolicy.shouldLock(true, 1_000, 60_999, 60_000))
        assertTrue(AppLockPolicy.shouldLock(true, 1_000, 61_000, 60_000))
        assertTrue(AppLockPolicy.shouldLock(true, 1_000, 1_000, 0))
        assertFalse(AppLockPolicy.shouldLock(false, 1_000, 1_000_000, 0))
    }
    @Test fun callbackCannotCrossAccountOrGeneration() {
        assertFalse(AppLockPolicy.accepts("origin|a|1", "origin|b|2", 7, 7))
        assertFalse(AppLockPolicy.accepts("origin|a|1", "origin|a|1", 6, 7))
        assertFalse(AppLockPolicy.accepts(null, null, 7, 7))
        assertTrue(AppLockPolicy.accepts("origin|a|1", "origin|a|1", 7, 7))
    }
    @Test fun backgroundSuccessCannotUnlockAndOnlyMatchingCredentialReturnIsFresh() {
        assertFalse(AppLockPolicy.acceptsForegroundProof(false, 1, 1, false))
        assertFalse(AppLockPolicy.acceptsForegroundProof(false, 1, 2, true))
        assertFalse(AppLockPolicy.acceptsForegroundProof(true, 1, 2, false))
        assertTrue(AppLockPolicy.acceptsForegroundProof(true, 1, 2, true))
        assertFalse(AppLockPolicy.acceptsForegroundProof(true, 1, 3, true))
        // Even after a credential activity exceeds the timer, only a fresh
        // native result delivered in foreground may unlock the locked state.
        assertTrue(AppLockPolicy.shouldLock(true,0,300_001,300_000))
        assertTrue(AppLockPolicy.acceptsForegroundProof(true, 1, 2, true))
    }

    @Test fun viewedCursorCannotCrossALiveAccountBeforeUiOwnerRebinds() {
        assertTrue(AppLockPolicy.permitsViewedCursor("server|a|1","server|a|1",false))
        assertFalse(AppLockPolicy.permitsViewedCursor("server|a|1","server|b|2",false))
        assertFalse(AppLockPolicy.permitsViewedCursor("server|a|1","server|a|2",false))
        assertFalse(AppLockPolicy.permitsViewedCursor("one|a|1","two|a|1",false))
        assertFalse(AppLockPolicy.permitsViewedCursor(null,null,false))
        assertFalse(AppLockPolicy.permitsViewedCursor("server|a|1","server|a|1",true))
    }

    @Test fun deviceGlobalLockProtectsEverySignedInOwnerButNeverShieldsLogin() {
        assertTrue(AppLockPolicy.protectsOwner(true,"server|a|1"))
        assertTrue(AppLockPolicy.protectsOwner(true,"other-server|b|2"))
        assertFalse(AppLockPolicy.protectsOwner(true,null))
        assertFalse(AppLockPolicy.shouldLock(AppLockPolicy.protectsOwner(true,null),0,999999,0))
        assertFalse(AppLockPolicy.protectsOwner(false,"server|a|1"))
    }

}
