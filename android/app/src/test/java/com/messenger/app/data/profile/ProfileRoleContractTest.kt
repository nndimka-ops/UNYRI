package com.messenger.app.data.profile

import com.messenger.app.data.api.UserProfileDto
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProfileRoleContractTest {
    @Test fun ownerProfileRepliesPreserveServerRoleAndOldServersRemainCompatible() {
        // GET /users/me, PATCH /users/me, avatar commit/removal all share this owner DTO.
        val profile = """{"id":"me","username":"me","display_name":"UNYRI Support","created_at":"","updated_at":"","admin_role":"moderator"}"""
        assertEquals("moderator", Json.decodeFromString<UserProfileDto>(profile).adminRole)
        assertNull(Json.decodeFromString<UserProfileDto>(profile.replace(",\"admin_role\":\"moderator\"", "")).adminRole)
        assertNull(Json.decodeFromString<UserProfileDto>(profile.replace("\"moderator\"", "null")).adminRole)
    }
}
