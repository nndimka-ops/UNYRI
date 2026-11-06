package com.messenger.app.domain.repository

import com.messenger.app.domain.model.PublicUser

data class BlockedAccount(val user: PublicUser, val blockedAt: String)
data class BlockedAccountsPage(val items: List<BlockedAccount>, val nextCursor: String?)
data class BlockableAccountsPage(val items: List<PublicUser>, val nextOffset: Long?)

/** Account security preferences, separate from chat/message discovery and history. */
interface BlockedUsersRepository {
    fun ownerKey(): String?
    suspend fun list(query: String, cursor: String?): Result<BlockedAccountsPage>
    suspend fun people(query: String, offset: Long): Result<BlockableAccountsPage>
    suspend fun setBlocked(userId: String, blocked: Boolean): Result<Unit>
}
