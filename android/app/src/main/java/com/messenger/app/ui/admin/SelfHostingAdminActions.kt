package com.messenger.app.ui.admin

import java.util.UUID
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class InviteRow(
    val id: String,
    val status: String,
    val uses: Int,
    @SerialName("max_uses") val maxUses: Int,
    @SerialName("expires_at") val expiresAt: String,
    val creator: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null,
)
@Serializable
internal data class InvitePage(val items: List<InviteRow>, @SerialName("next_before") val nextBefore: String? = null)
@Serializable
internal class CreatedInvite(val id: String, val code: String, @SerialName("max_uses") val maxUses: Int,
    val uses: Int, @SerialName("expires_at") val expiresAt: String) {
    override fun toString() = "CreatedInvite(redacted)"
}
@Serializable
internal data class CreateInviteBody(@SerialName("max_uses") val uses: Int, @SerialName("expires_in_seconds") val seconds: Long)
@Serializable
internal data class RegistrationMode(@SerialName("registration_mode") val mode: String,
    @SerialName("registration_open") val open: Boolean = true)

/** The role fence is applied to reads and writes, independent of the visible navigation. */
internal class SelfHostingAdminActions(private val role: String, private val transport: AdminTransport) {
    private fun administrator() { check(AdminNavigation.isAdministrator(role)) }
    private fun owner() { check(AdminNavigation.isOwner(role)) }
    suspend fun invites(before: String? = null): InvitePage {
        administrator()
        val path = "invites" + (before?.let { "?before=${UUID.fromString(it)}" } ?: "")
        return transport.get<InvitePage>(path).also {
            require(it.items.size <= 100)
            require(it.items.map { row -> row.id }.distinct().size == it.items.size)
            it.items.forEach { row ->
                UUID.fromString(row.id); require(row.uses >= 0 && row.maxUses in 1..10000)
                require(row.expiresAt.length <= 64); java.time.Instant.parse(row.expiresAt)
                require(row.creator == null || row.creator.length <= 128)
            }
            it.nextBefore?.let(UUID::fromString)
        }
    }
    suspend fun create(uses: Int, days: Int): CreatedInvite {
        administrator(); require(uses in 1..10000 && days in 1..365)
        return transport.post<CreateInviteBody, CreatedInvite>("invites", CreateInviteBody(uses, days * 86400L)).also {
            UUID.fromString(it.id)
            require(it.code.length in 1..256 && it.code.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "._-" })
            require(it.maxUses in 1..10000 && it.uses == 0 && it.expiresAt.length <= 64)
            java.time.Instant.parse(it.expiresAt)
        }
    }
    suspend fun revoke(id: String) { administrator(); check(transport.postOk("invites/${UUID.fromString(id)}/revoke", AdminEmptyBody)) }
    suspend fun registration(): RegistrationMode {
        owner()
        return transport.get<RegistrationMode>("registration").also { require(it.mode in listOf("open", "invite_only")) }
    }
    suspend fun setMode(mode: String) {
        owner(); require(mode in listOf("open", "invite_only"))
        check(transport.postOk("registration", RegistrationMode(mode)))
    }
    suspend fun selfCheck(): JsonObject { owner(); return transport.get("self-check") }
}
