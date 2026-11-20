package com.messenger.app.ui.profile

import org.junit.Test
import kotlin.test.assertEquals

class ProfileEditorIdentityTest {
    @Test fun publicNumberUsesTheSameNarrowNoBreakGroupingAsOtherIdentitySurfaces() {
        assertEquals("604\u202f743\u202f993", profileEditorIdentityLabel(false, "private_login", "604743993"))
        assertEquals("123\u202f456\u202f789", profileEditorIdentityLabel(false, "private_login", "123456789"))
    }

    @Test fun shortAliasesAndAbsentNumbersAreNotRewritten() {
        assertEquals("42", profileEditorIdentityLabel(false, "private_login", "42"))
        assertEquals("", profileEditorIdentityLabel(false, "private_login", null))
    }

    @Test fun legacyEditorRetainsItsUsernameLabel() {
        assertEquals("@Legacy.User", profileEditorIdentityLabel(true, "Legacy.User", "604743993"))
    }
}
