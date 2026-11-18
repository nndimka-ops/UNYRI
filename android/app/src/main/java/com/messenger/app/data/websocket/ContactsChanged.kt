package com.messenger.app.data.websocket

import com.messenger.app.data.profile.ContactsStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ContactsChangeHint(val userId: String, val isContact: Boolean)

fun parseContactsChanged(frame: JsonObject): ContactsChangeHint? = runCatching {
    if (frame["type"]?.jsonPrimitive?.contentOrNull != "contacts.changed") return null
    val id = frame["user_id"]?.jsonPrimitive?.contentOrNull?.let(ContactsStore::canonicalUuid) ?: return null
    val value = frame["is_contact"]?.jsonPrimitive ?: return null
    if (value.isString) return null
    val member = value.booleanOrNull ?: return null
    ContactsChangeHint(id, member)
}.getOrNull()
