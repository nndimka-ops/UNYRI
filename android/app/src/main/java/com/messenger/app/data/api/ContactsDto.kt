package com.messenger.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ContactEntryDto(val user: PublicUserDto, val source: String, @SerialName("added_at") val addedAt: String)

@Serializable
data class ContactsPageDto(val items: List<ContactEntryDto>, @SerialName("next_cursor") val nextCursor: String? = null)

@Serializable
data class ContactStatusDto(@SerialName("user_id") val userId: String, @SerialName("is_contact") val isContact: Boolean)

@Serializable
data class ContactChangeDto(
    @SerialName("user_id") val userId: String,
    @SerialName("is_contact") val isContact: Boolean,
    val changed: Boolean,
    val source: String? = null,
    @SerialName("added_at") val addedAt: String? = null,
)

interface ContactsRemote {
    suspend fun contacts(accessToken: String, cursor: String?, origin: String): ContactsPageDto
    suspend fun contactStatus(accessToken: String, userId: String, origin: String): ContactStatusDto
    suspend fun setContact(accessToken: String, userId: String, contact: Boolean, origin: String): ContactChangeDto
}
