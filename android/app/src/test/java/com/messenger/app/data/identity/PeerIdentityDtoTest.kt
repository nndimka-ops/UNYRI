package com.messenger.app.data.identity

import com.messenger.app.data.api.*
import kotlinx.serialization.json.*
import kotlin.test.*

class PeerIdentityDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun publicPeerFieldsAreOptionalAndPreservedInEverySharedEnvelope() {
        for (fields in listOf("", ",\"public_username\":\"alice\",\"unyri_number\":\"123456789\"")) {
            val peer = """{"id":"peer","username":"","display_name":"Alice"$fields}"""
            fun verify(user: PublicUserDto) {
                assertEquals("", user.username)
                assertEquals(if (fields.isEmpty()) null else "alice", user.publicUsername)
                assertEquals(if (fields.isEmpty()) null else "123456789", user.publicNumber)
            }
            verify(json.decodeFromString<PublicUserDto>(peer))
            verify(json.decodeFromString<ChatSummaryDto>("""{"chat_id":"chat","type":"direct","created_at":"now","other_user":$peer}""").otherUser!!)
            verify(json.decodeFromString<CallDto>("""{"id":"call","caller_user_id":"owner","callee_user_id":"peer","media_kind":"audio","state":"ended","created_at":"now","peer":$peer}""").peer!!)
            verify(json.decodeFromString<ContactEntryDto>("""{"user":$peer,"source":"manual","added_at":"now"}""").user)
            verify(json.decodeFromString<BlockedUserDto>("""{"user":$peer,"blocked_at":"now"}""").user)
            verify(json.decodeFromString<SearchResponseDto>("""{"items":[$peer]}""").items.single())
        }
    }

    @Test fun oldPeerPayloadStillDecodesButLogin2PresentationHidesItsCachedLogin() {
        val user = json.decodeFromString<PublicUserDto>("""{"id":"peer","username":"private_login","display_name":"Alice"}""")
        assertEquals("", PeerIdentityPolicy.handle(user.publicUsername, user.publicNumber, user.username))
        assertEquals("", PeerIdentityPolicy.handle(user.publicUsername, user.publicNumber, user.username, true))
        assertEquals("Alice", user.visibleName)
        assertEquals(com.messenger.app.domain.model.DisplayNameSanitizer.unknownUser, user.copy(displayName = "").visibleName)
    }
}
