package com.messenger.app.conference

import kotlinx.serialization.json.*
import kotlin.test.*

class ConferencePeerIdentityDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun oldAndNewConferencePeersDecodeWithoutRequiringPublicFields() {
        for (fields in listOf("", ",\"public_username\":\"alice\",\"unyri_number\":\"123456789\"")) {
            val member = json.decodeFromString<ConferenceMember>("""{"user_id":"peer","device_id":"device","display_name":"Alice"$fields}""")
            val room = json.decodeFromString<ConferenceDto>("""{"id":"room","title":"Room","owner_user_id":"peer","max_participants":30,"state":"active"$fields}""")
            val directory = json.decodeFromString<DirectoryRoom>("""{"id":"room","title":"Room","owner_user_id":"peer","owner_display_name":"Alice","participant_count":1,"max_participants":30,"access_mode":"open","created_at":"now","expires_at":"later"$fields}""")
            val request = json.decodeFromString<ConferenceJoinRequest>("""{"request_id":"request","conference_id":"room","requester_user_id":"peer","requester_device_id":"device","display_name":"Alice","username":"","status":"pending","expires_at":"later"$fields}""")
            val expectedName = if (fields.isEmpty()) null else "alice"
            val expectedNumber = if (fields.isEmpty()) null else "123456789"
            assertEquals(listOf(expectedName, expectedName, expectedName, expectedName), listOf(member.publicUsername, room.publicUsername, directory.publicUsername, request.publicUsername))
            assertEquals(listOf(expectedNumber, expectedNumber, expectedNumber, expectedNumber), listOf(member.publicNumber, room.publicNumber, directory.publicNumber, request.publicNumber))
        }
    }
}
