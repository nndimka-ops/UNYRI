package com.messenger.app.data.profile

import com.messenger.app.data.api.BlockedUsersRemote
import com.messenger.app.data.api.PublicUserDto
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.repository.AuthRepository
import com.messenger.app.domain.repository.BlockableAccountsPage
import com.messenger.app.domain.repository.BlockedAccount
import com.messenger.app.domain.repository.BlockedAccountsPage
import com.messenger.app.domain.repository.BlockedUsersRepository
import kotlinx.coroutines.CancellationException

/** No disk/list cache: leaving the account never keeps a directory of blocked people. */
class BlockedUsersRepositoryImpl(
    private val api: BlockedUsersRemote,
    private val auth: AuthRepository,
    private val projectKnownPeer: suspend (String, Boolean, Int) -> Boolean,
    private val origin: () -> String = { ServerEndpoint.current() },
) : BlockedUsersRepository {
    override fun ownerKey(): String? = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.let {
        origin().trimEnd('/') + "|" + it.id + "|" + auth.currentSessionEpoch()
    }

    override suspend fun list(query: String, cursor: String?): Result<BlockedAccountsPage> = authenticated { token, self, _ ->
        val page = api.blockedUsers(token, query.take(128), cursor)
        BlockedAccountsPage(page.items.filter { it.user.id != self }.map { BlockedAccount(it.user.toDomain(), it.blockedAt) }, page.nextCursor)
    }

    override suspend fun people(query: String, offset: Long): Result<BlockableAccountsPage> = authenticated { token, self, _ ->
        val page = api.directory(token, query.take(128), 50, offset.coerceAtLeast(0))
        BlockableAccountsPage(page.items.filter { it.id != self }.map { it.toDomain() }, page.nextOffset)
    }

    override suspend fun setBlocked(userId: String, blocked: Boolean): Result<Unit> = authenticated { token, self, epoch ->
        require(userId != self) { "Cannot block yourself" }
        val id = java.util.UUID.fromString(userId).toString()
        val requestOwner = ownerKey()
        api.setUserBlocked(token, id, blocked)
        // The callback takes the ORIGINAL epoch. It may only update rows already
        // present on this device; it must not fetch chats/history or create a chat.
        check(requestOwner == ownerKey() && epoch == auth.currentSessionEpoch()) { "Session changed" }
        check(projectKnownPeer(id, blocked, epoch)) { "Session changed" }
    }

    private suspend fun <T> authenticated(block: suspend (String, String, Int) -> T): Result<T> {
        return try {
            val owner = checkNotNull(ownerKey()) { "Not authenticated" }
            val epoch = auth.currentSessionEpoch()
            val self = (auth.authState.value as AuthSessionState.LoggedIn).user.id
            val token = checkNotNull(auth.currentAccessToken()) { "Not authenticated" }
            check(owner == ownerKey()) { "Session changed" }
            val value = block(token, self, epoch)
            check(owner == ownerKey()) { "Session changed" }
            Result.success(value)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { Result.failure(failure) }
    }

    private fun PublicUserDto.toDomain() = PublicUser(id, username, displayName, avatarUrl, bio, profileVersion, avatarVersion, hasAvatar)
}
