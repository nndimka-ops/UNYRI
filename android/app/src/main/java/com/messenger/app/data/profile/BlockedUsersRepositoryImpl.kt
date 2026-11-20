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

/** Server pages are presentation hints; only explicit user actions change local decisions. */
class BlockedUsersRepositoryImpl(
    private val api: BlockedUsersRemote,
    private val auth: AuthRepository,
    private val projectKnownPeer: suspend (String, Boolean, Int) -> Boolean,
    private val origin: () -> String = { ServerEndpoint.current() },
    private val localSecurity: com.messenger.app.data.secure.LocalSecurityDecisions? = null,
    private val identity: com.messenger.app.data.identity.IdentityRepository? = null,
) : BlockedUsersRepository {
    override fun ownerKey(): String? = (auth.authState.value as? AuthSessionState.LoggedIn)?.user?.let {
        origin().trimEnd('/') + "|" + it.id + "|" + auth.currentSessionEpoch()
    }

    override suspend fun list(query: String, cursor: String?): Result<BlockedAccountsPage> = authenticated { token, self, _ ->
        val publicOnly = if (identity == null) false else try {
            identity.refresh()
            !identity.legacy.value
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { true } // Unknown identity capability must not expose private-login search.
        val page = try { api.blockedUsers(token, if (publicOnly) "" else query.take(128), cursor) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (localSecurity == null) throw error else com.messenger.app.data.api.BlockedUsersPageDto(emptyList()) }
        val remote = page.items.filter { it.user.id != self && localSecurity?.blockDecision(it.user.id) != false && (!publicOnly ||
                com.messenger.app.domain.model.DisplayNameSanitizer.peer(it.user.displayName).contains(query.trim(), true)) }
            .map { BlockedAccount(it.user.toDomain(), it.blockedAt) }
        val local = if (cursor == null) localSecurity?.blockedUsers().orEmpty().filter { id ->
            id != self && remote.none { it.user.id == id } && (query.isBlank() || id.contains(query, ignoreCase = true))
        }.map { BlockedAccount(PublicUser(it, "", it, null), "") } else emptyList()
        BlockedAccountsPage(remote + local, page.nextCursor)
    }

    override suspend fun people(query: String, offset: Long): Result<BlockableAccountsPage> = authenticated { token, self, _ ->
        identity?.search(query, offset)?.let { result ->
            return@authenticated BlockableAccountsPage(result.items.filter { it.id != self }, null)
        }
        val page = api.directory(token, query.take(128), 50, offset.coerceAtLeast(0))
        BlockableAccountsPage(page.items.filter { it.id != self }.map { it.toDomain() }, page.nextOffset)
    }

    override suspend fun setBlocked(userId: String, blocked: Boolean): Result<Unit> = authenticated { token, self, epoch ->
        require(userId != self) { "Cannot block yourself" }
        val id = java.util.UUID.fromString(userId).toString()
        val requestOwner = ownerKey()
        check(projectKnownPeer(id, blocked, epoch)) { "Session changed" }
        api.setUserBlocked(token, id, blocked)
        // The callback takes the ORIGINAL epoch. It may only update rows already
        // present on this device; it must not fetch chats/history or create a chat.
        check(requestOwner == ownerKey() && epoch == auth.currentSessionEpoch()) { "Session changed" }
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

    private fun PublicUserDto.toDomain() = PublicUser(id, "", displayName, avatarUrl, bio, profileVersion, avatarVersion, hasAvatar, publicNumber, publicUsername)
}
