package com.messenger.app.data.websocket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class SelfProfileChangedTest {
    private val id = "7f2da07c-8b45-4aba-bdc1-5b52eb9f1099"
    private fun parse(body: String) = runCatching { parseSelfProfileChanged(Json.parseToJsonElement(body).jsonObject) }.getOrNull()

    @Test fun validHintCarriesOnlyIdentityAndMonotonicVersion() {
        assertEquals(SelfProfileChanged(id, 0), parse("""{"user_id":"$id","profile_version":0}"""))
        assertEquals(SelfProfileChanged(id, Long.MAX_VALUE), parse("""{"user_id":"$id","profile_version":9223372036854775807}"""))
    }

    @Test fun malformedAndStringVersionsAreIgnored() {
        for (value in listOf("-1", "1.5", "\"3\"", "null", "true", "{}", "[]", "9223372036854775808")) {
            assertNull(parse("""{"user_id":"$id","profile_version":$value}"""), value)
        }
        assertNull(parse("""{"user_id":"$id"}"""))
    }

    @Test fun nonCanonicalOrMissingUserIdentityIsIgnored() {
        for (value in listOf("0-0-0-0-0", id.uppercase(), "", "foreign"))
            assertNull(parse("""{"user_id":"$value","profile_version":3}"""))
        assertNull(parse("""{"profile_version":3}"""))
    }
}
