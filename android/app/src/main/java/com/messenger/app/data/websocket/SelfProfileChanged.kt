package com.messenger.app.data.websocket

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal data class SelfProfileChanged(val userId: String, val version: Long)

/** Hints carry no profile fields; the authenticated self endpoint remains authoritative. */
internal fun parseSelfProfileChanged(frame: JsonObject): SelfProfileChanged? {
    val id = (frame["user_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
    if (!id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) return null
    val version = (frame["profile_version"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
    return if (version >= 0) SelfProfileChanged(id, version) else null
}
