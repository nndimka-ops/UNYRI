package com.messenger.app.ui.chats

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.messenger.app.MessengerApp
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.domain.model.AuthSessionState
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal data class EmojiOwner(val workspace: String, val origin: String, val userId: String, val epoch: Int) {
    // Epoch invalidates live callbacks, but does not discard this account's saved history.
    val storageKey: String get() {
        val identity = listOf(workspace, origin.trimEnd('/'), userId).joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

internal data class EmojiSaved(val recents: List<String> = emptyList(), val keyboardHeightDp: Float? = null)
internal data class EmojiRecentsSnapshot(val saved: EmojiSaved = EmojiSaved(),
    val initialRecents: List<String>? = null, val loaded: Boolean = false)

internal interface EmojiRecentsStorage {
    suspend fun read(owner: EmojiOwner): EmojiSaved
    suspend fun write(owner: EmojiOwner, saved: EmojiSaved)
}

/** No preference handle, disk read, JSON parsing or flush is obtained on the caller's thread. */
internal class EmojiPreferencesStorage(private val context: Context) : EmojiRecentsStorage {
    override suspend fun read(owner: EmojiOwner): EmojiSaved = withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences("emoji-input-v2-${owner.storageKey}", Context.MODE_PRIVATE)
        val recents = runCatching { Json.decodeFromString<List<String>>(preferences.getString("recents", null) ?: "[]") }
            .getOrDefault(emptyList()).filter(::validRecentEmoji).distinct().take(32)
        EmojiSaved(recents, preferences.getFloat("keyboard-height", 0f).takeIf(::validKeyboardHeight))
    }
    override suspend fun write(owner: EmojiOwner, saved: EmojiSaved) = withContext(Dispatchers.IO) {
        val editor = context.getSharedPreferences("emoji-input-v2-${owner.storageKey}", Context.MODE_PRIVATE).edit()
            .putString("recents", Json.encodeToString(saved.recents))
        saved.keyboardHeightDp?.let { editor.putFloat("keyboard-height", it) }
        // Await the write here rather than leaving lifecycle apply() work for the main thread.
        check(editor.commit()) { "Could not save emoji preferences" }
    }
}

/** Serial actor orders cold load, selections and height updates without losing early input. */
internal class EmojiRecentsStore(
    private val owner: EmojiOwner?, private val storage: EmojiRecentsStorage?, scope: CoroutineScope,
    private val isCurrent: () -> Boolean = { true }, dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private sealed interface Update {
        data class Use(val emoji: String) : Update
        data class Height(val dp: Float) : Update
    }
    private val updates = Channel<Update>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(EmojiRecentsSnapshot())
    val state: StateFlow<EmojiRecentsSnapshot> = mutable.asStateFlow()
    private val lifecycleLock = Any()
    @Volatile private var closed = false
    private val job = scope.launch(dispatcher) {
        val loaded = try { if (owner != null) storage?.read(owner) ?: EmojiSaved() else EmojiSaved() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { EmojiSaved() }
        if (!current()) return@launch
        var saved = loaded.copy(recents = loaded.recents.filter(::validRecentEmoji).distinct().take(32),
            keyboardHeightDp = loaded.keyboardHeightDp?.takeIf(::validKeyboardHeight))
        if (!publish(EmojiRecentsSnapshot(saved, saved.recents, loaded = true))) return@launch
        for (update in updates) {
            if (!current()) break
            val next = when (update) {
                is Update.Use -> saved.copy(recents = rememberEmoji(saved.recents, update.emoji))
                is Update.Height -> saved.copy(keyboardHeightDp = update.dp)
            }
            if (next == saved) continue
            saved = next
            if (!publish(mutable.value.copy(saved = saved))) break
            if (owner != null && current()) try { storage?.write(owner, saved) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Keep usable in-memory recents; retry on the next edit. */ }
        }
    }
    private fun current() = !closed && isCurrent()
    private fun publish(snapshot: EmojiRecentsSnapshot): Boolean = synchronized(lifecycleLock) {
        if (!current()) false else { mutable.value = snapshot; true }
    }
    val acceptsCurrentOwner: Boolean get() = current()
    fun use(emoji: String) { if (current() && validRecentEmoji(emoji)) updates.trySend(Update.Use(emoji)) }
    fun rememberHeight(dp: Float) {
        if (current() && validKeyboardHeight(dp) && state.value.saved.keyboardHeightDp != dp) updates.trySend(Update.Height(dp))
    }
    fun close() {
        // Serialize only in-memory publication, never preference I/O. A native disk read
        // finishing after cancellation must not republish an invalidated session.
        synchronized(lifecycleLock) { closed = true; mutable.value = EmojiRecentsSnapshot() }
        job.cancel(); updates.close()
    }
}

/** Session changes invalidate observable state as well as any retained old callback. */
internal class EmojiRecentsRegistry(owners: Flow<EmojiOwner?>, private val currentOwner: () -> EmojiOwner?,
    private val storage: EmojiRecentsStorage, private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private fun create(owner: EmojiOwner?) = EmojiRecentsStore(owner, storage, scope,
        isCurrent = { owner == currentOwner() }, dispatcher = dispatcher)
    private val mutable = MutableStateFlow(create(null))
    val stores: StateFlow<EmojiRecentsStore> = mutable.asStateFlow()
    init {
        scope.launch {
            owners.distinctUntilChanged().collect { owner ->
                mutable.value.close()
                mutable.value = create(owner)
            }
        }
    }
}

private object AppEmojiRecents {
    private var app: MessengerApp? = null
    private var registry: EmojiRecentsRegistry? = null
    @Synchronized fun get(application: MessengerApp): EmojiRecentsRegistry {
        if (app === application) return checkNotNull(registry)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fun owner(): EmojiOwner? = (application.authRepository.authState.value as? AuthSessionState.LoggedIn)?.let {
            EmojiOwner(application.accountWorkspaces.activeKey, ServerEndpoint.current(), it.user.id,
                application.authRepository.currentSessionEpoch())
        }
        return EmojiRecentsRegistry(application.authRepository.authState.map { owner() }, ::owner,
            EmojiPreferencesStorage(application), scope).also { app = application; registry = it }
    }
}

@Composable
internal fun rememberEmojiRecentsStore(): EmojiRecentsStore {
    val app = LocalContext.current.applicationContext as? MessengerApp
    val scope = rememberCoroutineScope()
    if (app == null) return remember(scope) { EmojiRecentsStore(null, null, scope) }
    val registry = remember(app) { AppEmojiRecents.get(app) }
    val active = registry.stores.collectAsState().value
    // Authentication can change before its collector gets the next main-thread turn.
    // Never render even one frame from that previous owner in the meantime.
    return if (active.acceptsCurrentOwner) active
        else remember(scope) { EmojiRecentsStore(null, null, scope) }
}

private fun validRecentEmoji(emoji: String): Boolean = emoji.isNotBlank() && emoji.length <= 32 &&
    emoji.none { it.isWhitespace() || it.isISOControl() }
private fun validKeyboardHeight(dp: Float): Boolean = dp.isFinite() && dp > 0f
internal fun rememberEmoji(recents: List<String>, emoji: String): List<String> =
    if (!validRecentEmoji(emoji)) recents else (listOf(emoji) + recents.filter { it != emoji }).distinct().take(32)
