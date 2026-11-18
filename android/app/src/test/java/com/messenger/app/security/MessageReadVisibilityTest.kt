package com.messenger.app.security
import kotlin.test.*
class MessageReadVisibilityTest {
    @Test fun backgroundAndLockAreCheckedLiveAndOldActivityCannotClearNewOwner() {
        val gate=MessageReadVisibility(); val old=Any(); val current=Any()
        var foreground=true; var locked=false
        assertFalse(gate.allowsViewedCursor())
        gate.bind(old) { foreground && !locked }; assertTrue(gate.allowsViewedCursor())
        locked=true; assertFalse(gate.allowsViewedCursor())
        locked=false; foreground=false; assertFalse(gate.allowsViewedCursor())
        gate.bind(current) { true }; gate.release(old); assertTrue(gate.allowsViewedCursor())
        gate.release(current); assertFalse(gate.allowsViewedCursor())
    }
}
