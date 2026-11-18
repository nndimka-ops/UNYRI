package com.messenger.app.data.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.messenger.app.BuildConfig
import com.messenger.app.domain.model.AuthSessionState
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Serializable
data class SavedLocalAccount(
    val key: String,
    val origin: String,
    val username: String,
    val displayName: String,
    val unreadCount: Int = 0,
    val userId: String = "",
)

/** The original workspace keeps its paths, so upgrading never moves a session.
 * New workspaces have separate encrypted preferences, Room databases and files.
 * The selected key is captured for the entire process: a background operation
 * can never start using the next account's storage halfway through a switch. */
class AccountWorkspaces internal constructor(
    private val preferences: SharedPreferences,
    val avatarStore: com.messenger.app.data.avatar.AccountAvatarStore,
    private val context: Context? = null,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("local-accounts", Context.MODE_PRIVATE),
        com.messenger.app.data.avatar.AccountAvatarStore(File(
            AccountWorkspaceContext.installationDirectory(context), "account-avatars")), context,
    )
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    val activeKey: String = preferences.getString("selected", null)
        ?.takeIf(AccountWorkspaceLayout::validKey) ?: AccountWorkspaceLayout.LEGACY

    /** Storage delegate, also used by Activities. Never attach it as an Application's base. */
    fun wrap(base: Context): Context = AccountWorkspaceContext(base, activeKey)

    @Synchronized
    fun accounts(): List<SavedLocalAccount> = runCatching {
        Json.decodeFromString(ListSerializer(SavedLocalAccount.serializer()), preferences.getString("accounts", null) ?: "[]")
            .filter { AccountWorkspaceLayout.validKey(it.key) }
            .distinctBy { it.key }
    }.getOrDefault(emptyList())

    @Synchronized
    fun record(state: AuthSessionState, origin: String) {
        if (state is AuthSessionState.Unknown) return
        val previous = accounts()
        val next = previous.filterNot { it.key == activeKey }.toMutableList()
        if (state is AuthSessionState.LoggedIn) {
            next.add(SavedLocalAccount(activeKey, origin, state.user.username, state.user.displayName,
                previous.firstOrNull { it.key == activeKey }?.unreadCount ?: 0, state.user.id))
        } else {
            eraseActiveAvatar()
        }
        if (next == previous) return
        check(preferences.edit().putString("accounts", Json.encodeToString(ListSerializer(SavedLocalAccount.serializer()), next)).commit())
        revision.value++
    }

    /** The last local account also sweeps orphaned thumbnails from interrupted removals. */
    @Synchronized
    fun eraseActiveAvatar() {
        if (accounts().any { it.key != activeKey }) avatarStore.purge(activeKey) else avatarStore.purgeAll()
    }

    @Synchronized
    fun select(key: String) {
        require(key == activeKey || accounts().any { it.key == key })
        check(preferences.edit().putString("selected", key).commit())
    }

    @Synchronized
    fun add(): String {
        check(accounts().size < 2) { "Account limit reached" }
        val key = UUID.randomUUID().toString()
        // An empty workspace always starts at the public default. The sign-in
        // screen can choose another server without touching a saved account.
        AccountWorkspaceContext(requireNotNull(context), key).getSharedPreferences("server-endpoint", Context.MODE_PRIVATE)
            .edit().putString("origin", BuildConfig.PUBLIC_DEFAULT_API_ORIGIN).commit().also { check(it) }
        check(preferences.edit().putString("selected", key).commit())
        return key
    }

    @Synchronized
    fun updateUnread(count: Int) {
        val previous = accounts()
        val next = previous.map { if (it.key == activeKey) it.copy(unreadCount = count.coerceAtLeast(0)) else it }
        if (previous != next) check(preferences.edit().putString("accounts", Json.encodeToString(ListSerializer(SavedLocalAccount.serializer()), next)).commit())
    }

    /** Forget an inactive sign-in; its workspace cannot be selected through the registry again. */
    @Synchronized
    fun removeInactive(key: String) {
        require(key != activeKey && AccountWorkspaceLayout.validKey(key))
        avatarStore.purge(key)
        val next = accounts().filterNot { it.key == key }
        check(preferences.edit().putString("accounts", Json.encodeToString(ListSerializer(SavedLocalAccount.serializer()), next)).commit())
        revision.value++
    }
}

internal object AccountWorkspaceLayout {
    const val LEGACY = "legacy"
    private val keyPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val sharedPreferences = setOf(
        "local-accounts", "local_security_decisions", "installation-identity", "native-app-lock", "coco_language", "coco_theme", "client-config-v1", "coco-drawer",
    )

    fun validKey(key: String): Boolean = key == LEGACY || keyPattern.matches(key)
    fun preferenceName(key: String, name: String): String =
        if (name in sharedPreferences || key == LEGACY) name else "account_${key}_$name"
    // AndroidX may already supply a no-backup database's absolute path.
    fun databaseName(key: String, name: String): String =
        if (key == LEGACY || name.startsWith('/') || File(name).isAbsolute) name else "account_${key}_$name"

    fun directory(key: String, root: File): File = if (key == LEGACY) root else File(root, "accounts/$key")
}

internal class AccountWorkspaceContext(base: Context, private val key: String) : ContextWrapper(base) {
    init { require(AccountWorkspaceLayout.validKey(key)) }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences(AccountWorkspaceLayout.preferenceName(key, name), mode)

    override fun getDatabasePath(name: String): File =
        baseContext.getDatabasePath(AccountWorkspaceLayout.databaseName(key, name))

    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        baseContext.openOrCreateDatabase(AccountWorkspaceLayout.databaseName(key, name), mode, factory)

    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        baseContext.openOrCreateDatabase(AccountWorkspaceLayout.databaseName(key, name), mode, factory, errorHandler)

    override fun deleteDatabase(name: String): Boolean = baseContext.deleteDatabase(AccountWorkspaceLayout.databaseName(key, name))
    override fun getFilesDir(): File = scoped(baseContext.filesDir)
    override fun getNoBackupFilesDir(): File = scoped(baseContext.noBackupFilesDir)
    override fun getCacheDir(): File = scoped(baseContext.cacheDir)
    override fun getCodeCacheDir(): File = scoped(baseContext.codeCacheDir)

    private fun scoped(root: File): File = AccountWorkspaceLayout.directory(key, root).also {
        if (key != AccountWorkspaceLayout.LEGACY) check(it.isDirectory || it.mkdirs())
    }

    companion object {
        /** Installation protection belongs to the device, across all accounts. */
        fun installationDirectory(context: Context): File {
            var base = context
            while (base is ContextWrapper) base = base.baseContext
            return base.noBackupFilesDir
        }
    }
}
