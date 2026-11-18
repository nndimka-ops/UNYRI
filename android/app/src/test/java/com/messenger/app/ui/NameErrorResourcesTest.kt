package com.messenger.app.ui

import com.messenger.app.R
import org.junit.Assert.*
import org.junit.Test

class NameErrorResourcesTest {
    @Test fun stableCodesChooseLocalizedResourcesRegardlessOfServerWording() {
        for ((code, resource) in mapOf(
            "display_name_link" to R.string.profile_name_link,
            "display_name_leading_at" to R.string.profile_name_leading_at,
            "username_reserved" to R.string.profile_username_reserved,
            "username_link" to R.string.profile_username_link,
            "validation_error" to R.string.profile_validation_error,
        )) assertEquals(resource, nameErrorResource(code))
        assertNull(nameErrorResource("unknown_code"))
    }
}
